package com.opticalcenter.spatialnav.local

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class ModelStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val payload = ByteArray(10 * 1024 * 1024) { (it % 251).toByte() }

    @Before
    fun start() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun stop() = server.shutdown()

    private fun spec() = LocalModelSpec(
        id = "test",
        name = "Test model",
        fileName = "test.litertlm",
        url = server.url("/model.litertlm").toString(),
        approxBytes = payload.size.toLong(),
        summary = "",
    )

    private fun store(free: Long = Long.MAX_VALUE) = ModelStore(tmp.root, freeBytes = { free })

    @Test
    fun downloadsAndBecomesReady() = runBlocking {
        server.enqueue(MockResponse().setBody(Buffer().write(payload)))
        val store = store()
        store.download(spec())
        val ready = store.statusOf(spec()) as ModelStatus.Ready
        assertEquals(payload.size.toLong(), ready.bytes)
        assertArrayEquals(payload, ready.file.readBytes())
        assertFalse("no leftover partial file", tmp.root.resolve("test.litertlm.part").exists())
    }

    @Test
    fun resumesWithRangeRequest() = runBlocking {
        val half = payload.size / 2
        tmp.root.resolve("test.litertlm.part").writeBytes(payload.copyOfRange(0, half))
        server.enqueue(
            MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes $half-${payload.size - 1}/${payload.size}")
                .setBody(Buffer().write(payload.copyOfRange(half, payload.size))),
        )
        val store = store()
        assertTrue(store.statusOf(spec()) is ModelStatus.Paused)
        store.download(spec())
        assertEquals("bytes=$half-", server.takeRequest().getHeader("Range"))
        assertArrayEquals(payload, (store.statusOf(spec()) as ModelStatus.Ready).file.readBytes())
    }

    @Test
    fun serverIgnoringRangeRestartsFromScratch() = runBlocking {
        tmp.root.resolve("test.litertlm.part").writeBytes(ByteArray(1000) { 9 })
        server.enqueue(MockResponse().setBody(Buffer().write(payload))) // 200, full body
        val store = store()
        store.download(spec())
        assertArrayEquals(payload, (store.statusOf(spec()) as ModelStatus.Ready).file.readBytes())
    }

    @Test
    fun truncatedDownloadIsPausedNotReady() {
        val store = store()
        server.enqueue(
            MockResponse().setBody(Buffer().write(payload.copyOfRange(0, 1000)))
                .setHeader("Content-Length", payload.size.toString())
                .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )
        assertThrows(IOException::class.java) { runBlocking { store.download(spec()) } }
        assertTrue(store.statusOf(spec()).toString(), store.statusOf(spec()) is ModelStatus.Paused)
        assertFalse(store.file(spec()).exists())
    }

    @Test
    fun httpErrorIsReported() {
        server.enqueue(MockResponse().setResponseCode(404))
        val store = store()
        val e = assertThrows(IOException::class.java) { runBlocking { store.download(spec()) } }
        assertEquals("Download failed (HTTP 404).", e.message)
    }

    @Test
    fun refusesWhenStorageIsShort() {
        val store = store(free = 1_000)
        val e = assertThrows(IOException::class.java) { runBlocking { store.download(spec()) } }
        assertTrue(e.message!!, e.message!!.startsWith("Not enough storage"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun importAndDelete() = runBlocking {
        val store = store()
        store.import(spec(), payload.inputStream(), payload.size.toLong())
        assertTrue(store.statusOf(spec()) is ModelStatus.Ready)
        store.delete(spec())
        assertEquals(ModelStatus.NotDownloaded, store.statusOf(spec()))
        assertFalse(store.file(spec()).exists())
    }

    @Test
    fun catalogPointsAtUngatedLiteRtModels() {
        for (spec in LocalModelSpec.CATALOG) {
            assertTrue(spec.url, spec.url.startsWith("https://huggingface.co/litert-community/"))
            assertTrue(spec.url.endsWith("/resolve/main/${spec.fileName}"))
        }
    }
}
