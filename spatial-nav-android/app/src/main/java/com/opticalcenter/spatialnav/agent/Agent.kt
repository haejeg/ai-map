package com.opticalcenter.spatialnav.agent

/** A voice assistant: one utterance in, one spoken reply (plus the tool calls it made) out. */
interface Assistant {
    /** Short label for the UI, e.g. which model is answering. */
    val label: String
    suspend fun respond(utterance: String): AgentResult
    /** Forget the conversation so far. */
    fun reset() = Unit
}

data class ToolCallTrace(val tool: String, val input: Map<String, Any?>, val output: ToolResult)

data class AgentResult(val text: String, val trace: List<ToolCallTrace>)

enum class Role { USER, ASSISTANT }

sealed interface ContentBlock {
    data class Text(val text: String) : ContentBlock
    data class ToolUse(val id: String, val name: String, val input: Map<String, String>) : ContentBlock
    data class ToolResultBlock(val toolUseId: String, val toolName: String, val result: ToolResult) : ContentBlock
}

data class Message(val role: Role, val content: List<ContentBlock>) {
    companion object {
        fun user(text: String) = Message(Role.USER, listOf(ContentBlock.Text(text)))
    }
}

/** Minimal chat-model contract used by the offline [MockLlm]. */
fun interface LlmClient {
    suspend fun complete(messages: List<Message>, tools: List<ToolDefinition>): Message
}

/**
 * Offline assistant: the [MockLlm] loop. Each utterance is handled on its own
 * (no memory between turns), which is all keyword matching can use.
 */
class Agent(
    private val tools: NavigationTools,
    private val llm: LlmClient = MockLlm(),
    private val maxSteps: Int = 4,
) : Assistant {
    override val label = "Offline assistant"

    override suspend fun respond(utterance: String): AgentResult {
        val messages = mutableListOf(Message.user(utterance))
        val trace = mutableListOf<ToolCallTrace>()

        repeat(maxSteps) {
            val reply = llm.complete(messages, NavigationTools.DEFINITIONS)
            messages += reply

            val toolUses = reply.content.filterIsInstance<ContentBlock.ToolUse>()
            if (toolUses.isEmpty()) {
                val text = reply.content.filterIsInstance<ContentBlock.Text>().joinToString(" ") { it.text }
                return AgentResult(text, trace)
            }

            val results = toolUses.map { use ->
                val output = tools.execute(use.name, use.input)
                trace += ToolCallTrace(use.name, use.input, output)
                ContentBlock.ToolResultBlock(use.id, use.name, output)
            }
            messages += Message(Role.USER, results)
        }
        return AgentResult("Sorry, I could not complete that request.", trace)
    }
}
