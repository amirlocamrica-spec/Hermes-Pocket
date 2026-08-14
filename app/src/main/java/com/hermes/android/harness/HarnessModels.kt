package com.hermes.android.harness

import kotlinx.serialization.Serializable

/**
 * Harness — an on-device agent loop (Triad "Harness mode" port).
 *
 * Instead of a Python agent on the phone, the Harness runs a tool-using
 * loop against any OpenAI-compatible LLM: the model returns `tool_calls`,
 * the app executes them locally (calculator, clock, notes, HTTP fetch) and
 * feeds results back until the model produces a final text answer.
 */

/** One tool the Harness exposes to the model (OpenAI function-calling schema). */
@Serializable
data class HarnessTool(
    val name: String,
    val description: String,
    /** JSON Schema of the parameters, as a raw JSON string. */
    val parametersJson: String,
)

/** A tool call requested by the model. */
data class ToolCallRequest(
    val id: String,
    val name: String,
    val argumentsJson: String,
)

/** Result of executing one tool call. */
data class ToolCallResult(
    val callId: String,
    val name: String,
    val output: String,
)

/** One message inside the Harness loop (OpenAI chat shape). */
data class HarnessMessage(
    val role: String, // system | user | assistant | tool
    val content: String,
    val toolCalls: List<ToolCallRequest> = emptyList(),
    val toolCallId: String? = null,
    val name: String? = null,
)

/** Settings for a Harness run. */
data class HarnessConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val maxIterations: Int = 8,
    val temperature: Float = 0.2f,
) {
    val isComplete: Boolean get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()

    companion object {
        const val DEFAULT_SYSTEM_PROMPT =
            "You are Harness, an on-device agent inside Hermes Pocket. " +
                "Use the provided tools whenever they help. Be concise and direct."
    }
}