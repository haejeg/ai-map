package com.opticalcenter.spatialnav.settings

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.NotFoundException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.opticalcenter.spatialnav.agent.ClaudeAssistant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Checks a key against its service without spending meaningful quota. */
fun interface KeyVerifier {
    suspend fun verify(service: ApiService, key: String): Verification
}

/**
 * - TomTom: one reverse-geocode request (a single, cheap Search API call).
 * - Claude: GET /v1/models/{model}, which authenticates the key and confirms
 *   access to the assistant's model without generating any tokens.
 * Base URLs are overridable for tests.
 */
class NetworkKeyVerifier(
    private val tomTomBaseUrl: String = "https://api.tomtom.com",
    private val anthropicBaseUrl: String? = null,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
) : KeyVerifier {
    override suspend fun verify(service: ApiService, key: String): Verification = withContext(Dispatchers.IO) {
        service.formatProblem(key)?.let { return@withContext Verification.Invalid(it) }
        when (service) {
            ApiService.TOMTOM -> verifyTomTom(key)
            ApiService.ANTHROPIC -> verifyAnthropic(key)
        }
    }

    private fun verifyTomTom(key: String): Verification {
        val url = "$tomTomBaseUrl/search/2/reverseGeocode/51.500700,-0.124600.json".toHttpUrl().newBuilder()
            .addQueryParameter("key", key)
            .build()
        return try {
            http.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                when (response.code) {
                    in 200..299 -> Verification.Valid
                    401, 403 -> Verification.Invalid("TomTom rejected this key. Check it was copied fully and that Search is enabled for it.")
                    429 -> Verification.Unreachable("TomTom rate limit reached; the key may still be fine. Try again later.")
                    else -> Verification.Unreachable("TomTom returned HTTP ${response.code}. Try again later.")
                }
            }
        } catch (e: IOException) {
            Verification.Unreachable("Couldn't reach TomTom. Check your connection.")
        }
    }

    private fun verifyAnthropic(key: String): Verification {
        val client = AnthropicOkHttpClient.builder()
            .apiKey(key)
            .maxRetries(0)
            .apply { anthropicBaseUrl?.let { baseUrl(it) } }
            .build()
        return try {
            client.models().retrieve(ClaudeAssistant.MODEL)
            Verification.Valid
        } catch (e: UnauthorizedException) {
            Verification.Invalid("Anthropic rejected this key. It may be mistyped, revoked or from another workspace.")
        } catch (e: PermissionDeniedException) {
            Verification.Invalid("This key doesn't have permission to use the Claude API.")
        } catch (e: NotFoundException) {
            Verification.Invalid("This key works but can't access ${ClaudeAssistant.MODEL}.")
        } catch (e: RateLimitException) {
            Verification.Unreachable("Anthropic rate limit reached; the key may still be fine. Try again later.")
        } catch (e: AnthropicServiceException) {
            Verification.Unreachable("Anthropic returned an error (${e.statusCode()}). Try again later.")
        } catch (e: AnthropicIoException) {
            Verification.Unreachable("Couldn't reach Anthropic. Check your connection.")
        } finally {
            client.close()
        }
    }
}
