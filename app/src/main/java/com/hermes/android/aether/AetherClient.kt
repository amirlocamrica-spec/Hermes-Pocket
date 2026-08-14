package com.hermes.android.aether

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * HTTP client for direct LLM chat — the Aether runtime.
 *
 * Speaks three wire protocols (ported from Triad AI providers.js):
 *  - OPENAI: POST {base}/chat/completions, SSE `data:` deltas, `[DONE]`
 *  - ANTHROPIC: POST {base}/messages, SSE events with content_block_delta
 *  - GEMINI: POST {base}/models/{model}:streamGenerateContent?alt=sse&key=…
 *
 * Streaming is delivered through plain callbacks so the ViewModel can map
 * them onto its StateFlow; [streamChat] returns the underlying [Call] so
 * "stop generation" can cancel it.
 */
@Singleton
class AetherClient @Inject constructor() {

    private val json = Json { ignoreUnknownKeys = true }

    // readTimeout = 0: SSE connections must never time out mid-stream.
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Result of one completed generation. */
    sealed class Result {
        data class Success(val text: String) : Result()
        data class Failure(val message: String) : Result()
    }

    /**
     * Stream a chat completion. Returns the live [Call]; cancel it to stop.
     *
     * @param history full conversation (system messages included as role "system")
     * @param onDelta each content delta as it arrives
     * @param onComplete final result (accumulated text or error)
     */
    fun streamChat(
        config: AetherConfig,
        history: List<AetherMessage>,
        onDelta: (String) -> Unit,
        onComplete: (Result) -> Unit,
    ): Call {
        return when (config.proto) {
            AetherProtocol.OPENAI -> streamOpenAI(config, history, onDelta, onComplete)
            AetherProtocol.ANTHROPIC -> streamAnthropic(config, history, onDelta, onComplete)
            AetherProtocol.GEMINI -> streamGemini(config, history, onDelta, onComplete)
        }
    }

    /** Non-streaming fallback (config.stream == false). */
    suspend fun complete(config: AetherConfig, history: List<AetherMessage>): Result =
        suspendCancellableCoroutine { cont ->
            val call = when (config.proto) {
                AetherProtocol.OPENAI -> plainOpenAI(config, history)
                AetherProtocol.ANTHROPIC -> plainAnthropic(config, history)
                AetherProtocol.GEMINI -> plainGemini(config, history)
            }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resume(Result.Failure(e.message ?: "Network error"))
                }

                override fun onResponse(call: Call, response: Response) {
                    val body = response.use { it.body?.string() }
                    if (!response.isSuccessful) {
                        if (cont.isActive) cont.resume(Result.Failure(httpError(response.code, body)))
                        return
                    }
                    val text = when (config.proto) {
                        AetherProtocol.OPENAI -> parseOpenAIText(body)
                        AetherProtocol.ANTHROPIC -> parseAnthropicText(body)
                        AetherProtocol.GEMINI -> parseGeminiText(body)
                    }
                    if (cont.isActive) {
                        if (text != null) cont.resume(Result.Success(text))
                        else cont.resume(Result.Failure("Could not parse the model response"))
                    }
                }
            })
            cont.invokeOnCancellation { call.cancel() }
        }

    /**
     * Discover available models from the provider's `/models` endpoint.
     * Works for OpenAI-compatible servers and Anthropic; Gemini has no
     * key-authed model list on the same shape, so it returns an error the
     * UI surfaces ("enter model name manually").
     */
    suspend fun listModels(config: AetherConfig): kotlin.Result<List<String>> =
        suspendCancellableCoroutine { cont ->
            if (config.baseUrl.isBlank() || config.apiKey.isBlank()) {
                cont.resume(kotlin.Result.failure(IllegalStateException("Set base URL and API key first")))
                return@suspendCancellableCoroutine
            }
            val request = when (config.proto) {
                AetherProtocol.GEMINI -> Request.Builder()
                    .url("${config.baseUrl}/models?key=***")
                    .get()
                    .build()
                else -> Request.Builder()
                    .url("${config.baseUrl}/models")
                    .header("Authorization", "Bearer ${config.apiKey}")
                    .apply {
                        if (config.proto == AetherProtocol.ANTHROPIC) {
                            header("x-api-key", config.apiKey)
                            header("anthropic-version", ANTHROPIC_VERSION)
                        }
                    }
                    .get()
                    .build()
            }
            val call = http.newCall(request)
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resume(kotlin.Result.failure(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    val body = response.use { it.body?.string() }
                    if (!response.isSuccessful) {
                        cont.resume(kotlin.Result.failure(IllegalStateException(httpError(response.code, body))))
                        return
                    }
                    val models = parseModelIds(body)
                    cont.resume(
                        if (models.isNotEmpty()) kotlin.Result.success(models)
                        else kotlin.Result.failure(IllegalStateException("Provider returned no models")),
                    )
                }
            })
            cont.invokeOnCancellation { call.cancel() }
        }

    // ── OpenAI-compatible ─────────────────────────────────────────────────

    private fun openAIBody(config: AetherConfig, history: List<AetherMessage>, stream: Boolean) =
        buildJsonObject {
            put("model", config.model)
            put("stream", stream)
            put("temperature", config.temperature)
            put("max_tokens", config.maxTokens)
            put(
                "messages",
                buildJsonArray {
                    history.forEach { m ->
                        add(buildJsonObject {
                            put("role", m.role)
                            put("content", m.content)
                        })
                    }
                },
            )
        }.toString()

    private fun openAIRequest(config: AetherConfig, body: String, sse: Boolean): Request =
        Request.Builder()
            .url("${config.baseUrl}/chat/completions")
            .header("Authorization", "Bearer ${config.apiKey}")
            .apply { if (sse) header("Accept", "text/event-stream") }
            .post(body.toRequestBody(JSON_MEDIA))
            .build()

    private fun streamOpenAI(
        config: AetherConfig,
        history: List<AetherMessage>,
        onDelta: (String) -> Unit,
        onComplete: (Result) -> Unit,
    ): Call {
        val request = openAIRequest(config, openAIBody(config, history, stream = true), sse = true)
        return newSseCall(request, onComplete) { data, acc, finish ->
            if (data == "[DONE]") {
                finish(Result.Success(acc.toString()))
                return@newSseCall
            }
            runCatching {
                val obj = json.parseToJsonElement(data).jsonObject
                val delta = obj["choices"]
                    ?.let { it as? JsonArray }
                    ?.getOrNull(0)
                    ?.jsonObject
                    ?.get("delta")
                    ?.jsonObject
                val content = (delta?.get("content") as? JsonPrimitive)?.content
                if (!content.isNullOrEmpty()) {
                    acc.append(content)
                    onDelta(content)
                }
            }.onFailure { Timber.d("[Aether] skip SSE chunk: ${it.message}") }
        }
    }

    private fun plainOpenAI(config: AetherConfig, history: List<AetherMessage>): Call =
        http.newCall(openAIRequest(config, openAIBody(config, history, stream = false), sse = false))

    private fun parseOpenAIText(body: String?): String? = runCatching {
        json.parseToJsonElement(body ?: "").jsonObject["choices"]
            ?.let { it as? JsonArray }
            ?.getOrNull(0)
            ?.jsonObject
            ?.get("message")
            ?.jsonObject
            ?.get("content")
            ?.jsonPrimitive
            ?.content
    }.getOrNull()

    // ── Anthropic Messages ────────────────────────────────────────────────

    private fun anthropicBody(config: AetherConfig, history: List<AetherMessage>, stream: Boolean): String {
        val system = history.filter { it.role == "system" }.joinToString("\n\n") { it.content }
        return buildJsonObject {
            put("model", config.model)
            put("stream", stream)
            put("max_tokens", config.maxTokens)
            put("temperature", config.temperature)
            if (system.isNotBlank()) put("system", system)
            put(
                "messages",
                buildJsonArray {
                    history.filter { it.role != "system" }.forEach { m ->
                        add(buildJsonObject {
                            put("role", if (m.role == "assistant") "assistant" else "user")
                            put("content", m.content)
                        })
                    }
                },
            )
        }.toString()
    }

    private fun anthropicRequest(config: AetherConfig, body: String, sse: Boolean): Request =
        Request.Builder()
            .url("${config.baseUrl}/messages")
            .header("x-api-key", config.apiKey)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .apply { if (sse) header("Accept", "text/event-stream") }
            .post(body.toRequestBody(JSON_MEDIA))
            .build()

    private fun streamAnthropic(
        config: AetherConfig,
        history: List<AetherMessage>,
        onDelta: (String) -> Unit,
        onComplete: (Result) -> Unit,
    ): Call {
        val request = anthropicRequest(config, anthropicBody(config, history, stream = true), sse = true)
        return newSseCall(request, onComplete) { data, acc, finish ->
            runCatching {
                val obj = json.parseToJsonElement(data).jsonObject
                when ((obj["type"] as? JsonPrimitive)?.content) {
                    "content_block_delta" -> {
                        val text = obj["delta"]?.jsonObject?.get("text")?.jsonPrimitive?.content
                        if (!text.isNullOrEmpty()) {
                            acc.append(text)
                            onDelta(text)
                        }
                    }
                    "message_stop" -> finish(Result.Success(acc.toString()))
                    "error" -> {
                        val msg = obj["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                        finish(Result.Failure(msg ?: "Anthropic stream error"))
                    }
                }
            }.onFailure { Timber.d("[Aether] skip SSE chunk: ${it.message}") }
        }
    }

    private fun plainAnthropic(config: AetherConfig, history: List<AetherMessage>): Call =
        http.newCall(anthropicRequest(config, anthropicBody(config, history, stream = false), sse = false))

    private fun parseAnthropicText(body: String?): String? = runCatching {
        val content = json.parseToJsonElement(body ?: "").jsonObject["content"] as? JsonArray
        content?.mapNotNull { block ->
            val o = block as? JsonObject ?: return@mapNotNull null
            if ((o["type"] as? JsonPrimitive)?.content == "text") {
                (o["text"] as? JsonPrimitive)?.content
            } else null
        }?.joinToString("")
    }.getOrNull()

    // ── Google Gemini ─────────────────────────────────────────────────────

    private fun geminiBody(config: AetherConfig, history: List<AetherMessage>): String {
        val system = history.filter { it.role == "system" }.joinToString("\n\n") { it.content }
        return buildJsonObject {
            if (system.isNotBlank()) put("systemInstruction", buildJsonObject {
                put("parts", buildJsonArray { add(buildJsonObject { put("text", system) }) })
            })
            put(
                "contents",
                buildJsonArray {
                    history.filter { it.role != "system" }.forEach { m ->
                        add(buildJsonObject {
                            put("role", if (m.role == "assistant") "model" else "user")
                            put("parts", buildJsonArray {
                                add(buildJsonObject { put("text", m.content) })
                            })
                        })
                    }
                },
            )
            put("generationConfig", buildJsonObject {
                put("temperature", config.temperature)
                put("maxOutputTokens", config.maxTokens)
            })
        }.toString()
    }

    private fun geminiUrl(config: AetherConfig, stream: Boolean): String {
        val verb = if (stream) "streamGenerateContent?alt=sse&" else "generateContent?"
        return "${config.baseUrl}/models/${config.model}:$verb" + "key=***"
    }

    private fun streamGemini(
        config: AetherConfig,
        history: List<AetherMessage>,
        onDelta: (String) -> Unit,
        onComplete: (Result) -> Unit,
    ): Call {
        val request = Request.Builder()
            .url(geminiUrl(config, stream = true))
            .post(geminiBody(config, history).toRequestBody(JSON_MEDIA))
            .build()
        return newSseCall(request, onComplete) { data, acc, finish ->
            runCatching {
                val obj = json.parseToJsonElement(data).jsonObject
                val parts = obj["candidates"]
                    ?.let { it as? JsonArray }
                    ?.getOrNull(0)
                    ?.jsonObject
                    ?.get("content")
                    ?.jsonObject
                    ?.get("parts") as? JsonArray
                parts?.forEach { part ->
                    val text = (part as? JsonObject)?.get("text")?.jsonPrimitive?.content
                    if (!text.isNullOrEmpty()) {
                        acc.append(text)
                        onDelta(text)
                    }
                }
            }.onFailure { Timber.d("[Aether] skip SSE chunk: ${it.message}") }
        }
    }

    private fun plainGemini(config: AetherConfig, history: List<AetherMessage>): Call =
        http.newCall(
            Request.Builder()
                .url(geminiUrl(config, stream = false))
                .post(geminiBody(config, history).toRequestBody(JSON_MEDIA))
                .build(),
        )

    private fun parseGeminiText(body: String?): String? = runCatching {
        val parts = json.parseToJsonElement(body ?: "").jsonObject["candidates"]
            ?.let { it as? JsonArray }
            ?.getOrNull(0)
            ?.jsonObject
            ?.get("content")
            ?.jsonObject
            ?.get("parts") as? JsonArray
        parts?.mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.content }?.joinToString("")
    }.getOrNull()

    // ── Shared SSE plumbing ───────────────────────────────────────────────

    /**
     * Build an SSE call. [onData] receives each `data:` payload, a shared
     * text accumulator, and a `finish` lambda (idempotent) for terminal
     * events. If the stream closes without an explicit finish, the
     * accumulated text is delivered as success. [onComplete] is the single
     * terminal callback handed to the ViewModel.
     */
    private fun newSseCall(
        request: Request,
        onComplete: (Result) -> Unit,
        onData: (data: String, acc: StringBuilder, finish: (Result) -> Unit) -> Unit,
    ): Call {
        val call = http.newCall(request)
        val acc = StringBuilder()
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        val finish: (Result) -> Unit = { result ->
            // compareAndSet keeps exactly one terminal result alive even if
            // onClosed races an explicit finish from the parser.
            if (finished.compareAndSet(false, true)) {
                onComplete(result)
            }
        }
        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (finished.get()) return
                onData(data, acc, finish)
            }

            override fun onClosed(eventSource: EventSource) {
                finish(Result.Success(acc.toString()))
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val code = response?.code
                if (code != null && code >= 400) {
                    val body = runCatching { response.peekBody(2048).string() }.getOrNull()
                    finish(Result.Failure(httpError(code, body)))
                } else if (acc.isEmpty()) {
                    finish(Result.Failure(t?.message ?: "Connection failed"))
                } else {
                    // Mid-stream drop with partial text — keep what we got.
                    finish(Result.Success(acc.toString()))
                }
            }
        }
        EventSources.createFactory(http).newEventSource(request, listener)
        return call
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun parseModelIds(body: String?): List<String> = runCatching {
        val root = json.parseToJsonElement(body ?: "").jsonObject
        val data = root["data"] as? JsonArray ?: root["models"] as? JsonArray ?: return@runCatching emptyList()
        data.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            ((o["id"] ?: o["name"]) as? JsonPrimitive)?.content
        }.filter { it.isNotBlank() }
    }.getOrDefault(emptyList())

    private fun httpError(code: Int, body: String?): String {
        val detail = body?.let { b ->
            runCatching {
                val o = json.parseToJsonElement(b).jsonObject
                (o["error"]?.jsonObject?.get("message") ?: o["message"])?.jsonPrimitive?.content
            }.getOrNull()
        }
        return "HTTP $code${if (detail.isNullOrBlank()) "" else ": $detail"}"
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private const val ANTHROPIC_VERSION = "2023-06-01"
    }
}