package com.hermes.android.xkiro

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Simple REST client for api.xkiro.com (OpenAI-compatible chat completions).
 * No streaming for now — keeps it minimal.
 */
class XKiroClient(
    private val apiKey: String,
    private val baseUrl: String = "https://api.xkiro.com/v1",
    private val client: OkHttpClient = defaultClient(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun sendMessage(
        messages: List<ChatMessage>,
        model: String = DEFAULT_MODEL,
    ): String = withContext(Dispatchers.IO) {
        val messagesJson = messages.joinToString(",") { msg ->
            """{"role":"${msg.role}","content":"${msg.content.replace("\"", "\\\"").replace("\n", "\\n")}"""
        }

        val body = """
            {
                "model": "$model",
                "messages": [$messagesJson],
                "stream": false
            }
        """.trimIndent()

        val request = Request.Builder()
            .url("$baseUrl/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw XKiroException("API error ${response.code}: ${response.body?.string()}")
        }

        val bodyText = response.body?.string() ?: throw XKiroException("Empty response")
        val parsed = json.decodeFromString<ChatResponse>(bodyText)
        parsed.choices.firstOrNull()?.message?.content
            ?: throw XKiroException("No reply from API")
    }

    suspend fun listModels(): List<String> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/models")
            .addHeader("Authorization", "Bearer $apiKey")
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return@withContext emptyList()

        val bodyText = response.body?.string() ?: return@withContext emptyList()
        val parsed = json.decodeFromString<ModelsResponse>(bodyText)
        parsed.data.map { it.id }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        const val DEFAULT_MODEL = "gpt-4o-mini"

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

data class ChatMessage(
    val role: String, // "user" or "assistant" or "system"
    val content: String,
)

class XKiroException(message: String) : Exception(message)

@Serializable
private data class ChatResponse(
    val choices: List<Choice>,
)

@Serializable
private data class Choice(
    val message: RespMessage,
)

@Serializable
private data class RespMessage(
    val content: String,
)

@Serializable
private data class ModelsResponse(
    val data: List<ModelEntry>,
)

@Serializable
private data class ModelEntry(
    val id: String,
)
