package com.opticalcenter.spatialnav.settings

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ApiKeysTest {
    private val tomTomKey = "AbCdEf0123456789AbCdEf0123456789"
    private val claudeKey = "sk-ant-api03-abcdefghijklmnop-9f2c"

    // --- Repository ----------------------------------------------------------

    @Test
    fun appKeyOverridesBuildKeyAndRemovalFallsBack() {
        val repo = ApiKeyRepository(InMemorySecretStore(), mapOf(ApiService.TOMTOM to "BuildKey0123456789abcdefgh"))
        assertEquals(KeySource.BUILD, repo.source(ApiService.TOMTOM))
        assertEquals("BuildKey0123456789abcdefgh", repo.key(ApiService.TOMTOM))

        assertNull(repo.save(ApiService.TOMTOM, "  $tomTomKey\n"))
        assertEquals(tomTomKey, repo.key(ApiService.TOMTOM)) // trimmed
        assertEquals(KeySource.APP, repo.source(ApiService.TOMTOM))

        repo.remove(ApiService.TOMTOM)
        assertEquals(KeySource.BUILD, repo.source(ApiService.TOMTOM))
        assertEquals("BuildKey0123456789abcdefgh", repo.key(ApiService.TOMTOM))
    }

    @Test
    fun noKeyMeansDemoMode() {
        val repo = ApiKeyRepository(InMemorySecretStore(), mapOf(ApiService.ANTHROPIC to ""))
        assertNull(repo.key(ApiService.ANTHROPIC))
        assertEquals(KeySource.NONE, repo.source(ApiService.ANTHROPIC))
        assertNull(repo.status.value.getValue(ApiService.ANTHROPIC).preview)
    }

    @Test
    fun formatChecksRejectObviousMistakesWithoutSaving() {
        val repo = ApiKeyRepository(InMemorySecretStore())
        assertEquals("Claude API keys start with \"sk-ant-\".", repo.save(ApiService.ANTHROPIC, "sk-proj-123"))
        assertEquals("Keys can't contain spaces or line breaks.", repo.save(ApiService.ANTHROPIC, "sk-ant-abc def"))
        assertEquals("Enter a key.", repo.save(ApiService.TOMTOM, "   "))
        assertTrue(repo.save(ApiService.TOMTOM, "short")!!.startsWith("TomTom keys"))
        assertNull(repo.key(ApiService.ANTHROPIC))
        assertEquals(0, repo.revision.value)
    }

    @Test
    fun changesBumpRevisionAndResetVerification() {
        val repo = ApiKeyRepository(InMemorySecretStore())
        repo.save(ApiService.ANTHROPIC, claudeKey)
        assertEquals(1, repo.revision.value)
        repo.setVerification(ApiService.ANTHROPIC, Verification.Valid)
        assertEquals(Verification.Valid, repo.status.value.getValue(ApiService.ANTHROPIC).verification)

        repo.save(ApiService.ANTHROPIC, claudeKey) // same key: no-op
        assertEquals(1, repo.revision.value)

        repo.save(ApiService.ANTHROPIC, "sk-ant-api03-different-key-0000")
        assertEquals(2, repo.revision.value)
        assertEquals(Verification.Unknown, repo.status.value.getValue(ApiService.ANTHROPIC).verification)

        repo.remove(ApiService.ANTHROPIC)
        repo.remove(ApiService.ANTHROPIC) // nothing left to remove
        assertEquals(3, repo.revision.value)
    }

    @Test
    fun previewsNeverRevealTheKey() {
        assertEquals("sk-ant-…9f2c", ApiKeyRepository.mask(claudeKey))
        assertEquals("AbC…6789", ApiKeyRepository.mask(tomTomKey))
        assertEquals("•••••", ApiKeyRepository.mask("abcde"))
        val repo = ApiKeyRepository(InMemorySecretStore())
        repo.save(ApiService.ANTHROPIC, claudeKey)
        val status = repo.status.value.getValue(ApiService.ANTHROPIC)
        assertEquals("sk-ant-…9f2c", status.preview)
        assertTrue(claudeKey !in status.toString())
    }

    // --- Verifier ------------------------------------------------------------

    private lateinit var server: MockWebServer
    private lateinit var verifier: NetworkKeyVerifier

    @Before
    fun startServer() {
        server = MockWebServer().apply { start() }
        val base = server.url("/").toString().trimEnd('/')
        verifier = NetworkKeyVerifier(tomTomBaseUrl = base, anthropicBaseUrl = base)
    }

    @After
    fun stopServer() = server.shutdown()

    private fun json(code: Int, body: String) =
        MockResponse().setResponseCode(code).setHeader("content-type", "application/json").setBody(body)

    @Test
    fun tomTomKeyAcceptedOrRejected() = runBlocking {
        server.enqueue(json(200, """{"addresses":[]}"""))
        assertEquals(Verification.Valid, verifier.verify(ApiService.TOMTOM, tomTomKey))
        val url = server.takeRequest().requestUrl!!
        assertTrue(url.encodedPath.startsWith("/search/2/reverseGeocode/"))
        assertEquals(tomTomKey, url.queryParameter("key"))

        server.enqueue(json(403, """{"detailedError":{"code":"Forbidden"}}"""))
        assertTrue(verifier.verify(ApiService.TOMTOM, tomTomKey) is Verification.Invalid)

        server.enqueue(json(429, "{}"))
        assertTrue(verifier.verify(ApiService.TOMTOM, tomTomKey) is Verification.Unreachable)
    }

    @Test
    fun claudeKeyCheckedAgainstModelsEndpointWithoutGenerating() = runBlocking {
        server.enqueue(
            json(200, """{"id":"claude-opus-5-5","type":"model","display_name":"Claude Opus 5.5","created_at":"2026-08-01T00:00:00Z"}"""),
        )
        assertEquals(Verification.Valid, verifier.verify(ApiService.ANTHROPIC, claudeKey))
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/models/claude-opus-5-5", request.path)
        assertEquals(claudeKey, request.getHeader("x-api-key"))

        server.enqueue(json(401, """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""))
        val rejected = verifier.verify(ApiService.ANTHROPIC, claudeKey)
        assertTrue(rejected.toString(), rejected is Verification.Invalid)

        server.enqueue(json(404, """{"type":"error","error":{"type":"not_found_error","message":"model not found"}}"""))
        val noAccess = verifier.verify(ApiService.ANTHROPIC, claudeKey) as Verification.Invalid
        assertTrue(noAccess.message, "can't access claude-opus-5-5" in noAccess.message)
    }

    @Test
    fun malformedKeysNeverHitTheNetwork() = runBlocking {
        assertTrue(verifier.verify(ApiService.ANTHROPIC, "not-a-key") is Verification.Invalid)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun unreachableServiceIsNotTreatedAsInvalid() = runBlocking {
        server.shutdown()
        assertTrue(verifier.verify(ApiService.TOMTOM, tomTomKey) is Verification.Unreachable)
        assertTrue(verifier.verify(ApiService.ANTHROPIC, claudeKey) is Verification.Unreachable)
    }
}
