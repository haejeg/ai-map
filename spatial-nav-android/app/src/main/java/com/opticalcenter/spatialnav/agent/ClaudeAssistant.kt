package com.opticalcenter.spatialnav.agent

import com.anthropic.client.AnthropicClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolResultBlockParam
import com.fasterxml.jackson.core.type.TypeReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Voice assistant backed by Claude with tool use. Conversation history is kept
 * across utterances (so "take me to the second one" works) and is append-only;
 * when it grows too long or a turn fails, it is cleared rather than edited.
 */
class ClaudeAssistant(
    private val tools: NavigationTools,
    private val client: AnthropicClient,
    private val model: String = MODEL,
) : Assistant {
    override val label = "Claude"

    private val history = mutableListOf<MessageParam>()
    private val mutex = Mutex()
    private val sdkTools: List<Tool> = NavigationTools.DEFINITIONS.map(::toSdkTool)

    override fun reset() {
        history.clear()
    }

    override suspend fun respond(utterance: String): AgentResult = mutex.withLock {
        if (history.size >= MAX_HISTORY_MESSAGES) history.clear()
        history += MessageParam.builder().role(MessageParam.Role.USER).content(utterance).build()
        val trace = mutableListOf<ToolCallTrace>()

        try {
            repeat(MAX_TOOL_ROUNDS) {
                val params = MessageCreateParams.builder()
                    .model(model)
                    .maxTokens(MAX_TOKENS)
                    .system(SYSTEM_PROMPT)
                    // Spoken, latency-sensitive replies: low effort keeps thinking short.
                    .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
                    // Retry classifier refusals on Anthropic's recommended fallback model.
                    .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                    .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                    .apply { sdkTools.forEach(::addTool) }
                    .messages(history.toList())
                    .build()

                val response = withContext(Dispatchers.IO) { client.messages().create(params) }

                when (response.stopReason().orElse(null)) {
                    StopReason.REFUSAL -> {
                        history.clear()
                        return@withLock AgentResult("Sorry, I can't help with that one.", trace)
                    }
                    StopReason.TOOL_USE -> {
                        history += response.toParam()
                        val results = response.content().mapNotNull { it.toolUse().orElse(null) }.map { use ->
                            val input: Map<String, Any?> = use._input().convert(object : TypeReference<Map<String, Any?>>() {})
                                ?: emptyMap()
                            val output = tools.execute(use.name(), input)
                            trace += ToolCallTrace(use.name(), input, output)
                            ContentBlockParam.ofToolResult(
                                ToolResultBlockParam.builder()
                                    .toolUseId(use.id())
                                    .content(ToolResultJson.toJson(output))
                                    .isError(ToolResultJson.isError(output))
                                    .build(),
                            )
                        }
                        // All results for one turn go back in a single user message.
                        history += MessageParam.builder().role(MessageParam.Role.USER).contentOfBlockParams(results).build()
                    }
                    else -> {
                        history += response.toParam()
                        val text = response.content()
                            .mapNotNull { it.text().orElse(null)?.text() }
                            .joinToString(" ")
                            .trim()
                        return@withLock AgentResult(text.ifEmpty { "Done." }, trace)
                    }
                }
            }
            history.clear()
            AgentResult("Sorry, that took too many steps. Please try again.", trace)
        } catch (e: UnauthorizedException) {
            history.clear()
            AgentResult("The Claude API key was rejected. Check anthropic.apiKey in local.properties.", trace)
        } catch (e: RateLimitException) {
            history.clear()
            AgentResult("I'm being rate limited right now. Try again in a moment.", trace)
        } catch (e: AnthropicServiceException) {
            history.clear()
            AgentResult("Sorry, the assistant service had an error (${e.statusCode()}).", trace)
        } catch (e: com.anthropic.errors.AnthropicIoException) {
            history.clear()
            AgentResult("I can't reach the assistant service. Check your connection.", trace)
        }
    }

    private fun toSdkTool(def: ToolDefinition): Tool {
        @Suppress("UNCHECKED_CAST")
        val properties = def.inputSchema["properties"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val required = def.inputSchema["required"] as List<String>
        return Tool.builder()
            .name(def.name)
            .description(def.description)
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(
                        Tool.InputSchema.Properties.builder()
                            .apply { properties.forEach { (k, v) -> putAdditionalProperty(k, JsonValue.from(v)) } }
                            .build(),
                    )
                    .required(required)
                    .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                    .build(),
            )
            .build()
    }

    companion object {
        const val MODEL = "claude-opus-5-5"
        private const val MAX_TOKENS = 16_000L
        private const val MAX_TOOL_ROUNDS = 6
        private const val MAX_HISTORY_MESSAGES = 40

        val SYSTEM_PROMPT = """
            You are the voice assistant inside a driving navigation app, like a co-pilot. Everything you write is
            spoken aloud by text-to-speech to someone who may be driving.

            How to answer:
            - Keep replies to one or two short sentences. No markdown, lists, emoji or URLs.
            - Round numbers the way a person would say them ("about 12 minutes", "2.5 kilometers"). Use metric units.
            - Use the tools for anything about location, places, routes, traffic or road conditions. Never guess these.
            - "Left", "right", "ahead" and "behind" are relative to the direction the user is facing; get_surrounding_pois
              handles that.
            - When the user names a generic destination ("a gas station", "coffee"), search nearby and pick the closest
              sensible result; mention its name when you start navigation. Ask only if results are truly ambiguous.
            - After starting navigation, confirm the destination, the travel time and any significant traffic delay.
            - Mention road conditions such as ice, fog or flooding whenever they affect the route.
            - If a tool returns an error, say briefly what went wrong and what the user can do.
        """.trimIndent()
    }
}
