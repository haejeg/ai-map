package com.opticalcenter.spatialnav.local

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import com.google.ai.edge.litertlm.tool
import com.opticalcenter.spatialnav.agent.ToolDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * [OnDeviceChat] backed by Google's LiteRT-LM runtime. Tries the GPU first and
 * falls back to the CPU. Tool calling is manual: the model proposes calls, the
 * app runs them (so results show in the UI trace) and sends results back.
 */
class LiteRtChat(
    private val modelFile: File,
    private val modelName: String,
    private val cacheDir: File,
    private val systemPrompt: String,
    private val tools: List<ToolDefinition>,
    private val preferGpu: Boolean = true,
) : OnDeviceChat {
    private val mutex = Mutex()
    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private val mapper = ObjectMapper()

    override var engineLabel: String? = null
        private set

    override suspend fun warmUp() {
        mutex.withLock { ensureConversation() }
    }

    private suspend fun ensureConversation(): Conversation = withContext(Dispatchers.IO) {
        conversation?.takeIf { it.isAlive }?.let { return@withContext it }
        val e = engine ?: loadEngine().also { engine = it }
        val config = ConversationConfig(
            systemInstruction = Contents.of(systemPrompt),
            tools = tools.map { tool(DefinitionTool(it)) },
            // Low temperature: tool choice and arguments should be predictable.
            samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.2, seed = 0),
            automaticToolCalling = false,
            // Voice replies are short; skip the model's thinking step for latency.
            thinkingConfig = ThinkingConfig(enableThinking = false),
        )
        e.createConversation(config).also { conversation = it }
    }

    private fun loadEngine(): Engine {
        cacheDir.mkdirs()
        val attempts = buildList {
            if (preferGpu) add("GPU" to Backend.GPU())
            add("CPU" to Backend.CPU())
        }
        var last: Throwable? = null
        for ((label, backend) in attempts) {
            try {
                val e = Engine(EngineConfig(modelPath = modelFile.absolutePath, backend = backend, cacheDir = cacheDir.absolutePath))
                e.initialize()
                engineLabel = "$modelName · $label"
                return e
            } catch (t: Throwable) {
                last = t
            }
        }
        throw IllegalStateException("Couldn't load $modelName: ${last?.message}", last)
    }

    override suspend fun sendUser(text: String): ModelTurn = mutex.withLock {
        val c = ensureConversation()
        withContext(Dispatchers.IO) { c.sendMessage(Message.user(text)).toTurn() }
    }

    override suspend fun sendToolResults(results: List<LocalToolResult>): ModelTurn = mutex.withLock {
        val c = ensureConversation()
        val contents = results.map { r ->
            // Send structured JSON (an object) rather than a quoted string where possible.
            val payload: Any = runCatching { mapper.readValue(r.json, object : TypeReference<Map<String, Any?>>() {}) }
                .getOrDefault(r.json)
            Content.ToolResponse(r.name, payload)
        }
        withContext(Dispatchers.IO) { c.sendMessage(Message.tool(Contents.of(contents))).toTurn() }
    }

    private fun Message.toTurn() = ModelTurn(
        text = contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text },
        toolCalls = toolCalls.map { LocalToolCall(it.name, it.arguments) },
    )

    override fun reset() {
        conversation?.close()
        conversation = null
    }

    override fun close() {
        reset()
        engine?.close()
        engine = null
    }

    /** Exposes one of our tool schemas to LiteRT-LM. Never executed: tool calling is manual. */
    private inner class DefinitionTool(private val def: ToolDefinition) : OpenApiTool {
        override fun getToolDescriptionJsonString(): String = mapper.writeValueAsString(
            mapOf("name" to def.name, "description" to def.description, "parameters" to def.inputSchema),
        )

        override fun execute(paramsJsonString: String): String = "{}"
    }
}
