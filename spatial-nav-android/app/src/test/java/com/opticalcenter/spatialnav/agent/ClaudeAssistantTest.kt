package com.opticalcenter.spatialnav.agent

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.opticalcenter.spatialnav.navigation.NavigationManager
import com.opticalcenter.spatialnav.navigation.Place
import com.opticalcenter.spatialnav.spatial.MockPois
import com.opticalcenter.spatialnav.spatial.UserPose
import com.opticalcenter.spatialnav.testing.FakeMapData
import com.opticalcenter.spatialnav.testing.Fixtures
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Drives [ClaudeAssistant] against a local mock of the Messages API, checking
 * the request shape, the tool loop, and history handling. No real API calls.
 */
class ClaudeAssistantTest {
    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var assistant: ClaudeAssistant
    private val mapper = ObjectMapper()

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val telemetry = TelemetrySnapshot(UserPose(Fixtures.START.lat, Fixtures.START.lon, 90.0), 5f, hasFix = true)
        val data = FakeMapData(places = MockPois.around(Fixtures.START).map { Place(it.id, it.name, it.category, null, it.location) })
        val manager = NavigationManager(data, { telemetry }, scope, speak = {})
        val tools = NavigationTools({ telemetry }, data, manager)
        val client = AnthropicOkHttpClient.builder()
            .apiKey("test-key")
            .baseUrl(server.url("/").toString().trimEnd('/'))
            .maxRetries(0)
            .build()
        assistant = ClaudeAssistant(tools, client)
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    private fun message(stopReason: String, content: String) = MockResponse()
        .setHeader("content-type", "application/json")
        .setBody(
            """{"id":"msg_${System.nanoTime()}","type":"message","role":"assistant","model":"claude-opus-5-5",
               "content":$content,"stop_reason":"$stopReason","stop_sequence":null,
               "usage":{"input_tokens":100,"output_tokens":20}}""",
        )

    private fun toolUse(id: String, name: String, input: String) =
        message("tool_use", """[{"type":"tool_use","id":"$id","name":"$name","input":$input}]""")

    private fun text(t: String) = message("end_turn", """[{"type":"text","text":"$t"}]""")

    private fun nextRequest(): JsonNode {
        val req = server.takeRequest()
        assertEquals("/v1/messages", req.path)
        return mapper.readTree(req.body.readUtf8()).also { it.put("_beta", req.getHeader("anthropic-beta")) }
    }

    private fun JsonNode.put(key: String, value: String?) = (this as com.fasterxml.jackson.databind.node.ObjectNode).put(key, value)

    @Test
    fun toolLoop_runsToolAndReturnsFinalText() = runBlocking {
        server.enqueue(toolUse("toolu_1", "get_surrounding_pois", """{"direction":"left"}"""))
        server.enqueue(text("On your left: Blue Door Coffee, then Corner Pharmacy."))

        val result = assistant.respond("What's on my left?")

        assertEquals("On your left: Blue Door Coffee, then Corner Pharmacy.", result.text)
        val step = result.trace.single()
        assertEquals("get_surrounding_pois", step.tool)
        assertEquals(mapOf("direction" to "left"), step.input)
        assertEquals(
            listOf("Blue Door Coffee", "Corner Pharmacy", "General Hospital"),
            (step.output as ToolResult.Surroundings).pois.map { it.place.name },
        )

        val first = nextRequest()
        assertEquals("claude-opus-5-5", first["model"].asText())
        assertEquals("low", first["output_config"]["effort"].asText())
        assertEquals("default", first["fallbacks"].asText())
        assertEquals("server-side-fallback-2026-07-01", first["_beta"].asText())
        assertFalse("thinking can't be disabled on this model", first.has("thinking"))
        assertEquals(NavigationTools.DEFINITIONS.map { it.name }, first["tools"].map { it["name"].asText() })
        assertEquals(listOf("direction"), first["tools"][2]["input_schema"]["required"].map { it.asText() })
        assertTrue(first["system"].asText().contains("spoken aloud"))
        assertEquals("What's on my left?", first["messages"][0]["content"].asText())

        val second = nextRequest()
        val msgs = second["messages"]
        assertEquals(3, msgs.size())
        assertEquals("assistant", msgs[1]["role"].asText())
        assertEquals("tool_use", msgs[1]["content"][0]["type"].asText())
        val toolResult = msgs[2]["content"][0]
        assertEquals("tool_result", toolResult["type"].asText())
        assertEquals("toolu_1", toolResult["tool_use_id"].asText())
        assertFalse(toolResult["is_error"].asBoolean())
        val payload = mapper.readTree(toolResult["content"].asText())
        assertEquals("Blue Door Coffee", payload["pois"][0]["name"].asText())
        assertEquals("left", payload["pois"][0]["relative_direction"].asText())
    }

    @Test
    fun historyCarriesAcrossUtterances() = runBlocking {
        server.enqueue(toolUse("toolu_1", "search_places", """{"query":"library","nearby_only":true}"""))
        server.enqueue(text("The closest is the Public Library, about 340 meters to your right."))
        server.enqueue(text("Sure."))
        assistant.respond("Find a library")
        assistant.respond("Take me to the second one")

        repeat(2) { nextRequest() }
        val third = nextRequest()["messages"]
        // user, assistant(tool_use), user(tool_result), assistant(text), user — all replayed append-only.
        assertEquals(listOf("user", "assistant", "user", "assistant", "user"), third.map { it["role"].asText() })
        assertEquals("Take me to the second one", third[4]["content"].asText())
    }

    @Test
    fun toolErrorsAreFlagged() = runBlocking {
        server.enqueue(toolUse("toolu_1", "get_route_status", "{}"))
        server.enqueue(text("You're not navigating right now."))
        assistant.respond("How long left?")
        nextRequest()
        val toolResult = nextRequest()["messages"][2]["content"][0]
        assertTrue(toolResult["is_error"].asBoolean())
        assertEquals("there is no active navigation.", mapper.readTree(toolResult["content"].asText())["error"].asText())
    }

    @Test
    fun refusalClearsHistory() = runBlocking {
        server.enqueue(message("refusal", "[]"))
        server.enqueue(text("Hi."))
        assertEquals("Sorry, I can't help with that one.", assistant.respond("something declined").text)
        assistant.respond("hello")
        nextRequest()
        assertEquals(1, nextRequest()["messages"].size())
    }

    @Test
    fun badKeyGivesActionableMessage() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(401).setHeader("content-type", "application/json")
                .setBody("""{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""),
        )
        val result = assistant.respond("hello")
        assertTrue(result.text, "API key was rejected" in result.text)
    }
}
