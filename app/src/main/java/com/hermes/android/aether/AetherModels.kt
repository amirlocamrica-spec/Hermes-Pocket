package com.hermes.android.aether

import kotlinx.serialization.Serializable

/**
 * Aether — direct LLM chat without a Hermes server.
 *
 * Models ported from Triad AI's providers.js (OpenAI-compatible chat
 * completions, Anthropic Messages, Google Gemini generateContent).
 * Every provider preset below mirrors an entry in the upstream list so
 * behavior matches what Triad users expect, plus a free-form "custom"
 * entry for any OpenAI-compatible endpoint.
 */

/** Wire protocol spoken by a provider preset. */
enum class AetherProtocol { OPENAI, ANTHROPIC, GEMINI }

/** A built-in provider preset (Triad providers.js PROVIDERS list). */
data class ProviderPreset(
    val id: String,
    val name: String,
    val proto: AetherProtocol,
    val base: String,
    val hint: String,
    val keyHint: String = "API key",
)

val AETHER_PROVIDER_PRESETS = listOf(
    ProviderPreset("openai", "OpenAI", AetherProtocol.OPENAI, "https://api.openai.com/v1", "Chat Completions"),
    ProviderPreset("anthropic", "Anthropic", AetherProtocol.ANTHROPIC, "https://api.anthropic.com/v1", "Claude Messages"),
    ProviderPreset("gemini", "Google Gemini", AetherProtocol.GEMINI, "https://generativelanguage.googleapis.com/v1beta", "Generative Language"),
    ProviderPreset("openrouter", "OpenRouter", AetherProtocol.OPENAI, "https://openrouter.ai/api/v1", "Hundreds of models"),
    ProviderPreset("groq", "Groq", AetherProtocol.OPENAI, "https://api.groq.com/openai/v1", "Very fast inference"),
    ProviderPreset("deepseek", "DeepSeek", AetherProtocol.OPENAI, "https://api.deepseek.com/v1", ""),
    ProviderPreset("together", "Together AI", AetherProtocol.OPENAI, "https://api.together.xyz/v1", ""),
    ProviderPreset("mistral", "Mistral", AetherProtocol.OPENAI, "https://api.mistral.ai/v1", ""),
    ProviderPreset("xai", "xAI Grok", AetherProtocol.OPENAI, "https://api.x.ai/v1", ""),
    ProviderPreset("nous", "Nous Research", AetherProtocol.OPENAI, "https://inference-api.nousresearch.com/v1", "Hermes models"),
    ProviderPreset("ollama", "Ollama (local)", AetherProtocol.OPENAI, "http://127.0.0.1:11434/v1", "On device / LAN"),
    ProviderPreset("lmstudio", "LM Studio (local)", AetherProtocol.OPENAI, "http://127.0.0.1:1234/v1", ""),
    ProviderPreset("custom", "Custom", AetherProtocol.OPENAI, "", "Any OpenAI-compatible endpoint"),
)

/**
 * User-configured Aether connection. Persisted by [AetherSettings].
 * The API key is stored in SharedPreferences — the same trade-off the
 * Hermes server token already uses (RemoteServerSettings); the app's
 * biometric lock gates access to the whole UI.
 */
@Serializable
data class AetherConfig(
    val providerId: String = "custom",
    val protocol: String = AetherProtocol.OPENAI.name,
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val systemPrompt: String = "",
    val temperature: Float = 0.7f,
    val maxTokens: Int = 4096,
    val stream: Boolean = true,
) {
    val proto: AetherProtocol
        get() = runCatching { AetherProtocol.valueOf(protocol) }.getOrDefault(AetherProtocol.OPENAI)

    val isComplete: Boolean
        get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()
}

/** One message of an Aether conversation. */
@Serializable
data class AetherMessage(
    val id: String,
    val role: String, // "user" | "assistant" | "system"
    val content: String,
    val timestamp: Long,
)

/** A persisted Aether conversation. */
@Serializable
data class AetherConversation(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: List<AetherMessage> = emptyList(),
)

/** Metadata for the conversation list (no messages). */
data class AetherConversationSummary(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val messageCount: Int,
)