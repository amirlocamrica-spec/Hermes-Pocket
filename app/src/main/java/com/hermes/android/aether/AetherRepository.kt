package com.hermes.android.aether

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists Aether conversations as JSON files in the app-private
 * `aether/` directory — one file per conversation. Simple, portable,
 * and matches how the rest of the app keeps local state.
 */
@Singleton
class AetherRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val dir = File(context.filesDir, "aether").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    suspend fun listConversations(): List<AetherConversationSummary> = withContext(Dispatchers.IO) {
        dir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { f ->
                runCatching { json.decodeFromString<AetherConversation>(f.readText()) }.getOrNull()
            }
            ?.sortedByDescending { it.updatedAt }
            ?.map { AetherConversationSummary(it.id, it.title, it.updatedAt, it.messages.size) }
            ?: emptyList()
    }

    suspend fun load(id: String): AetherConversation? = withContext(Dispatchers.IO) {
        val f = File(dir, "$id.json")
        if (!f.exists()) return@withContext null
        runCatching { json.decodeFromString<AetherConversation>(f.readText()) }.getOrNull()
    }

    suspend fun save(conv: AetherConversation): Unit = withContext(Dispatchers.IO) {
        File(dir, "${conv.id}.json").writeText(json.encodeToString(conv))
    }

    suspend fun delete(id: String): Unit = withContext(Dispatchers.IO) {
        File(dir, "$id.json").delete()
    }

    suspend fun newConversation(): AetherConversation {
        val now = System.currentTimeMillis()
        return AetherConversation(
            id = UUID.randomUUID().toString(),
            title = "New chat",
            createdAt = now,
            updatedAt = now,
        )
    }

    /** Derive a short title from the first user message. */
    fun autoTitle(conv: AetherConversation): String {
        val firstUser = conv.messages.firstOrNull { it.role == "user" }?.content ?: return conv.title
        val oneLine = firstUser.lineSequence().firstOrNull().orEmpty().trim()
        return if (oneLine.length <= 40) oneLine else oneLine.take(37) + "…"
    }

    /** Export the conversation as Markdown (Triad-style exporter). */
    fun exportMarkdown(conv: AetherConversation): String = buildString {
        appendLine("# ${conv.title}")
        appendLine()
        conv.messages.forEach { m ->
            val who = when (m.role) {
                "user" -> "**User**"
                "assistant" -> "**Assistant**"
                else -> "**System**"
            }
            appendLine("$who:")
            appendLine()
            appendLine(m.content)
            appendLine()
            appendLine("---")
            appendLine()
        }
    }
}