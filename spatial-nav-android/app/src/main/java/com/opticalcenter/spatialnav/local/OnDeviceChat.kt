package com.opticalcenter.spatialnav.local

/** A tool call proposed by the on-device model. */
data class LocalToolCall(val name: String, val arguments: Map<String, Any?>)

/** One model turn: text and/or tool calls. */
data class ModelTurn(val text: String, val toolCalls: List<LocalToolCall>)

/** A tool's output to send back to the model: tool name and JSON result. */
data class LocalToolResult(val name: String, val json: String)

/**
 * A stateful chat with an on-device model that keeps conversation history.
 * Implementations run inference off the main thread.
 */
interface OnDeviceChat : AutoCloseable {
    /** Short description of the engine actually in use, e.g. "Gemma 4 E2B · GPU". */
    val engineLabel: String?

    /** Load the model if needed (slow: several seconds). Safe to call repeatedly. */
    suspend fun warmUp()

    suspend fun sendUser(text: String): ModelTurn

    suspend fun sendToolResults(results: List<LocalToolResult>): ModelTurn

    /** Drop the conversation history (the model stays loaded). */
    fun reset()
}
