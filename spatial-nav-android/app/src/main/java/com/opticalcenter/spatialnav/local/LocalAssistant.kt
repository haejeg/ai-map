package com.opticalcenter.spatialnav.local

import com.opticalcenter.spatialnav.agent.AgentResult
import com.opticalcenter.spatialnav.agent.Assistant
import com.opticalcenter.spatialnav.agent.MockLlm
import com.opticalcenter.spatialnav.agent.NavigationTools
import com.opticalcenter.spatialnav.agent.ToolCallTrace
import com.opticalcenter.spatialnav.agent.ToolResult
import com.opticalcenter.spatialnav.agent.ToolResultJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Voice assistant running entirely on the phone. The model picks tools and
 * phrases the reply; because small models are less reliable than cloud ones,
 * every failure mode degrades to something useful:
 *  - empty or unusable reply after a tool ran → the templated reply for that result
 *  - model error → the rule-based offline assistant answers instead
 */
class LocalAssistant(
    private val chat: OnDeviceChat,
    private val tools: NavigationTools,
    private val fallback: Assistant,
    private val modelName: String,
    /** Diagnostics sink (tag-less); the app routes it to Logcat. */
    private val log: (String) -> Unit = {},
) : Assistant, AutoCloseable {
    override val label: String get() = chat.engineLabel ?: "$modelName (on-device)"

    private val mutex = Mutex()
    private var turns = 0

    override fun reset() {
        chat.reset()
        turns = 0
    }

    suspend fun warmUp() = chat.warmUp()

    override suspend fun respond(utterance: String): AgentResult = mutex.withLock {
        // Small context window: start a fresh conversation every so often.
        if (turns >= MAX_TURNS_PER_CONVERSATION) reset()
        turns++

        val trace = mutableListOf<ToolCallTrace>()
        try {
            var turn = chat.sendUser(utterance)
            log("model turn: tools=${turn.toolCalls} text=${turn.text.take(200)}")
            repeat(MAX_TOOL_ROUNDS) {
                if (turn.toolCalls.isEmpty()) return@withLock finish(turn.text, trace)
                val results = turn.toolCalls.map { call ->
                    val known = NavigationTools.DEFINITIONS.any { it.name == call.name }
                    val output = if (known) {
                        tools.execute(call.name, call.arguments)
                    } else {
                        ToolResult.Error("unknown tool ${call.name}.")
                    }
                    trace += ToolCallTrace(call.name, call.arguments, output)
                    LocalToolResult(call.name, ToolResultJson.toJson(output))
                }
                turn = chat.sendToolResults(results)
                log("model turn: tools=${turn.toolCalls} text=${turn.text.take(200)}")
            }
            finish(turn.text, trace)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The model failed (load error, out of memory, malformed output): don't leave the user hanging.
            log("model failed, using fallback: $e")
            reset()
            val backup = fallback.respond(utterance)
            backup.copy(trace = trace + backup.trace)
        }
    }

    private fun finish(rawText: String, trace: List<ToolCallTrace>): AgentResult {
        val text = SpokenText.clean(rawText)
        if (text.isNotEmpty()) {
            log("answered by model")
            return AgentResult(text, trace)
        }
        log("empty model reply, using template")
        // The model ran a tool but didn't say anything usable: phrase the last result ourselves.
        val last = trace.lastOrNull()?.output
        return AgentResult(last?.let(MockLlm::composeReply) ?: MockLlm.HELP_TEXT, trace)
    }

    override fun close() = chat.close()

    companion object {
        private const val MAX_TOOL_ROUNDS = 4
        private const val MAX_TURNS_PER_CONVERSATION = 8

        /** Shorter than the cloud prompt: small models follow brief, concrete instructions best. */
        val SYSTEM_PROMPT = """
            You are the voice assistant in a car navigation app. Your replies are read aloud to a driver.
            Rules:
            - Answer in one or two short sentences. Plain text only: no lists, markdown or emoji.
            - Always use a tool for location, places, directions, routes, traffic or road conditions. Never make these up.
            - "left", "right", "ahead", "behind" questions: call get_surrounding_pois with that direction.
            - "take me to", "navigate to", "directions to": call reroute_navigation with the destination.
            - For a generic place like "a gas station", call search_places with nearby_only true first.
            - Use metric units and round numbers the way people speak.
        """.trimIndent()
    }
}

/** Makes model output safe to speak: strips markdown and whitespace noise. */
object SpokenText {
    private val MARKDOWN = Regex("""[*_`#>]+""")
    private val BULLET = Regex("""(?m)^\s*(?:[-•]|\d+[.)])\s+""")
    private val SPACES = Regex("""\s+""")

    fun clean(text: String): String = text
        .replace(BULLET, "")
        .replace(MARKDOWN, "")
        .replace(SPACES, " ")
        .trim()
}
