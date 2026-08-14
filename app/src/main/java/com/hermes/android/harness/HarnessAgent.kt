package com.hermes.android.harness

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Harness agent loop.
 *
 * One [run] = send history+tools to an OpenAI-compatible endpoint, execute
 * any tool_calls locally via [HarnessTools], append tool results, repeat —
 * up to [HarnessConfig.maxIterations] times — until the model answers with
 * plain text.
 */
@Singleton
class HarnessAgent @Inject constructor(
    private val tools: HarnessTools,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /** Per-step events the UI can render live. */
    sealed class Event {
        data class ToolRunning(val name: String, val args: String) : Event()
        data class ToolResult(val name: String, val output: String) : Event()
        data class Final(val text: String) : Event()
        data class Error(val message: String) : Event()
    }

    /**
     * Run the loop synchronously (call from a background coroutine).
     * Emits [Event]s through [onEvent] and returns the final answer text.
     */
    fun run(
        config: HarnessConfig,
        userText: String,
        history: List<HarnessMessage>,
        onEvent: (Event) -> Unit,
    ): String {
        val messages = mutableListOf<HarnessMessage>()
        messages.add(HarnessMessage("system", config.systemPrompt))
        messages.addAll(history)
        messages.add(HarnessMessage("user", userText))

        repeat(config.maxIterations) {
            val response = chatWithTools(config, messages)
                ?: run {
                    onEvent(Event.Error("Provider request failed"))
                    return "Harness error: provider request failed"
                }

            val (content, toolCalls) = response
            messages.add(
                HarnessMessage(
                    role = "assistant",
                    content = content,
                    toolCalls = toolCalls,
                ),
            )

            if (toolCalls.isEmpty()) {
                onEvent(Event.Final(content))
                return content
            }

            // Execute each requested tool locally and feed results back.
            toolCalls.forEach { call ->
                onEvent(Event.ToolRunning(call.name, call.argumentsJson))
                val result = tools.execute(call)
                onEvent(Event.ToolResult(result.name, result.output.take(500)))
                messages.add(
                    HarnessMessage(
                        role = "tool",
                        content = result.output,
                        toolCallId = result.callId,
                        name = result.name,
                    ),
                )
            }
        }
        onEvent(Event.Error("Reached max iterations (${config.maxIterations})"))
        return "Harness stopped after ${config.maxIterations} iterations without a final answer."
    }

    /**
     * One non-streaming chat/completions call with the Harness tools
     * attached. Returns (content, toolCalls) or null on hard failure.
     */
    private fun chatWithTools(
        config: HarnessConfig,
        messages: List<HarnessMessage>,
    ): Pair<String, List<ToolCallRequest>>? = runCatching {
        val body = buildJsonObject {
            put("model", config.model)
            put("temperature", config.temperature)
            put("stream", false)
            put(
                "messages",
                buildJsonArray {
                    messages.forEach { m ->
                        add(buildJsonObject {
                            put("role", m.role)
                            put("content", m.content)
                            m.toolCallId?.let { put("tool_call_id", it) }
                            m.name?.let { if (m.role == "tool") put("name", it) }
                            if (m.toolCalls.isNotEmpty()) {
                                put(
                                    "tool_calls",
                                    buildJsonArray {
                                        m.toolCalls.forEach { tc ->
                                            add(buildJsonObject {
                                                put("id", tc.id)
                                                put("type", "function")
                                                put("function", buildJsonObject {
                                                    put("name", tc.name)
                                                    put("arguments", tc.argumentsJson)
                                                })
                                            })
                                        }
                                    },
                                )
                            }
                        })
                    }
                },
            )
            put(
                "tools",
                buildJsonArray {
                    tools.toolDefinitions.forEach { t ->
                        add(buildJsonObject {
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", t.name)
                                put("description", t.description)
                                put("parameters", json.parseToJsonElement(t.parametersJson))
                            })
                        })
                    }
                },
            )
        }.toString()

        val request = Request.Builder()
            .url("${config.baseUrl.trimEnd('/')}/chat/completions")
            .header("Authorization", "Bearer ${config.apiKey}")
            .post(body.toRequestBody(JSON_MEDIA))
            .build()

        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return@runCatching null
            val root = json.parseToJsonElement(resp.body!!.string()).jsonObject
            val choice = (root["choices"] as? JsonArray)?.getOrNull(0)?.jsonObject
            val message = choice?.get("message")?.jsonObject ?: return@runCatching null
            val content = (message["content"] as? JsonPrimitive)?.content ?: ""
            val calls = (message["tool_calls"] as? JsonArray)?.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val fn = o["function"]?.jsonObject ?: return@mapNotNull null
                ToolCallRequest(
                    id = (o["id"] as? JsonPrimitive)?.content ?: UUID.randomUUID().toString(),
                    name = (fn["name"] as? JsonPrimitive)?.content ?: return@mapNotNull null,
                    argumentsJson = (fn["arguments"] as? JsonPrimitive)?.content ?: "{}",
                )
            } ?: emptyList()
            content to calls
        }
    }.getOrNull()

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}