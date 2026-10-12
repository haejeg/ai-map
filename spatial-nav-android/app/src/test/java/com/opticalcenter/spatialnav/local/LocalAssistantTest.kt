package com.opticalcenter.spatialnav.local

import com.fasterxml.jackson.databind.ObjectMapper
import com.opticalcenter.spatialnav.agent.Agent
import com.opticalcenter.spatialnav.agent.MockLlm
import com.opticalcenter.spatialnav.agent.NavigationTools
import com.opticalcenter.spatialnav.agent.TelemetrySnapshot
import com.opticalcenter.spatialnav.agent.ToolResult
import com.opticalcenter.spatialnav.navigation.NavigationManager
import com.opticalcenter.spatialnav.navigation.Place
import com.opticalcenter.spatialnav.spatial.MockPois
import com.opticalcenter.spatialnav.spatial.UserPose
import com.opticalcenter.spatialnav.testing.FakeMapData
import com.opticalcenter.spatialnav.testing.Fixtures
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Scripted stand-in for the on-device model. */
private class FakeChat(private val script: MutableList<ModelTurn>) : OnDeviceChat {
    val userMessages = mutableListOf<String>()
    val toolResults = mutableListOf<List<LocalToolResult>>()
    var resets = 0
    var failNext: Exception? = null
    override val engineLabel = "Fake · CPU"

    override suspend fun warmUp() = Unit

    private fun next(): ModelTurn {
        failNext?.let { failNext = null; throw it }
        return script.removeAt(0)
    }

    override suspend fun sendUser(text: String): ModelTurn {
        userMessages += text
        return next()
    }

    override suspend fun sendToolResults(results: List<LocalToolResult>): ModelTurn {
        toolResults += results
        return next()
    }

    override fun reset() {
        resets++
    }

    override fun close() = Unit
}

class LocalAssistantTest {
    private val mapper = ObjectMapper()

    private fun kotlinx.coroutines.test.TestScope.setup(script: List<ModelTurn>): Pair<LocalAssistant, FakeChat> {
        val telemetry = TelemetrySnapshot(UserPose(Fixtures.START.lat, Fixtures.START.lon, 90.0), 5f, hasFix = true)
        val data = FakeMapData(places = MockPois.around(Fixtures.START).map { Place(it.id, it.name, it.category, null, it.location) })
        val tools = NavigationTools({ telemetry }, data, NavigationManager(data, { telemetry }, backgroundScope, {}))
        val chat = FakeChat(script.toMutableList())
        return LocalAssistant(chat, tools, fallback = Agent(tools), modelName = "Gemma 4 E2B") to chat
    }

    private fun call(name: String, vararg args: Pair<String, Any?>) = ModelTurn("", listOf(LocalToolCall(name, mapOf(*args))))

    @Test
    fun runsToolsAndSpeaksTheModelsReply() = runTest {
        val (assistant, chat) = setup(
            listOf(
                call("get_surrounding_pois", "direction" to "left"),
                ModelTurn("On your left: **Blue Door Coffee**, about 120 meters away.", emptyList()),
            ),
        )
        val result = assistant.respond("what's on my left")

        assertEquals("On your left: Blue Door Coffee, about 120 meters away.", result.text) // markdown stripped
        val step = result.trace.single()
        assertEquals("get_surrounding_pois", step.tool)
        assertEquals(
            listOf("Blue Door Coffee", "Corner Pharmacy", "General Hospital"),
            (step.output as ToolResult.Surroundings).pois.map { it.place.name },
        )
        // The model received the tool result as JSON under the tool's name.
        val sent = chat.toolResults.single().single()
        assertEquals("get_surrounding_pois", sent.name)
        assertEquals("Blue Door Coffee", mapper.readTree(sent.json)["pois"][0]["name"].asText())
    }

    @Test
    fun emptyReplyAfterToolFallsBackToTemplate() = runTest {
        val (assistant, _) = setup(listOf(call("get_surrounding_pois", "direction" to "left"), ModelTurn("  ", emptyList())))
        val result = assistant.respond("left?")
        assertTrue(result.text, result.text.startsWith("To your left, I found 3 places: Blue Door Coffee"))
    }

    @Test
    fun plainAnswerWithoutTools() = runTest {
        val (assistant, _) = setup(listOf(ModelTurn("Hi! Ask me about places, routes or traffic.", emptyList())))
        val result = assistant.respond("hello")
        assertEquals("Hi! Ask me about places, routes or traffic.", result.text)
        assertTrue(result.trace.isEmpty())
    }

    @Test
    fun multipleToolCallsInOneTurnAreAllExecuted() = runTest {
        val (assistant, chat) = setup(
            listOf(
                ModelTurn(
                    "",
                    listOf(
                        LocalToolCall("get_current_location", emptyMap()),
                        LocalToolCall("get_surrounding_pois", mapOf("direction" to "ahead")),
                    ),
                ),
                ModelTurn("You're facing east near Baker Street; the bus stop is just ahead.", emptyList()),
            ),
        )
        val result = assistant.respond("where am I and what's ahead")
        assertEquals(listOf("get_current_location", "get_surrounding_pois"), result.trace.map { it.tool })
        assertEquals(2, chat.toolResults.single().size)
    }

    @Test
    fun unknownToolIsReportedBackToTheModelNotExecuted() = runTest {
        val (assistant, chat) = setup(listOf(call("launch_rocket"), ModelTurn("I can't do that.", emptyList())))
        val result = assistant.respond("launch a rocket")
        assertTrue(result.trace.single().output is ToolResult.Error)
        assertTrue(chat.toolResults.single().single().json.contains("unknown tool launch_rocket"))
        assertEquals("I can't do that.", result.text)
    }

    @Test
    fun modelFailureFallsBackToTheBasicAssistant() = runTest {
        val (assistant, chat) = setup(emptyList())
        chat.failNext = IllegalStateException("out of memory")
        val result = assistant.respond("What is to my left?")
        assertTrue(result.text, result.text.startsWith("To your left, I found 3 places"))
        assertEquals("get_surrounding_pois", result.trace.single().tool)
        assertEquals("conversation is reset after a failure", 1, chat.resets)
    }

    @Test
    fun runawayToolLoopIsCapped() = runTest {
        val loop = List(10) { call("get_current_location") }
        val (assistant, _) = setup(loop)
        val result = assistant.respond("where am I")
        assertEquals(4, result.trace.size)
        assertTrue(result.text, result.text.startsWith("You're near 221B Baker Street"))
    }

    @Test
    fun conversationIsPeriodicallyReset() = runTest {
        val (assistant, chat) = setup(List(9) { ModelTurn("ok", emptyList()) })
        repeat(9) { assistant.respond("hi") }
        assertEquals(1, chat.resets)
    }

    @Test
    fun labelShowsEngineInUse() = runTest {
        val (assistant, _) = setup(emptyList())
        assertEquals("Fake · CPU", assistant.label)
    }

    @Test
    fun spokenTextCleanup() {
        assertEquals("Turn left. Then right.", SpokenText.clean("- Turn **left**.\n- Then _right_."))
        assertEquals("1 km to go", SpokenText.clean("1. 1 km to go"))
        assertEquals(MockLlm.HELP_TEXT.isNotEmpty(), true)
    }
}
