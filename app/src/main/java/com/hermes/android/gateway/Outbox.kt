package com.hermes.android.gateway

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Durable send queue for user intent that must survive a dead connection
 * and a process kill.
 *
 * Phase 1 v3: Room is not yet in the project, so this is backed by a simple
 * JSON file in the app's filesDir instead of a Room DAO. The file is written
 * atomically (tmp + rename) so a kill mid-write cannot corrupt the queue.
 * When Phase 2 introduces Room, the storage can migrate to an OutboxDao
 * without touching the rest of this class's API.
 *
 * Scope is deliberately narrow: ONLY methods where silently losing the call
 * would lose user work (prompt.submit and the interactive responses).
 * Everything else — reads, listings, terminal resize — is pointless to
 * replay later and is left to fail fast.
 */
@Singleton
class Outbox @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val json: Json,
) {
    private val file: File
        get() = File(context.filesDir, "hermes_outbox.json")

    private val _pendingCount = MutableStateFlow(0)
    val pendingCount: Flow<Int> = _pendingCount

    @Serializable
    data class Entry(
        val id: Long,
        val sessionId: String?,
        val method: String,
        val paramsJson: String,
        val createdAtMs: Long,
        val attempts: Int = 0,
        val lastError: String? = null,
    )

    private val lock = Any()

    init {
        _pendingCount.value = readAll().size
    }

    suspend fun enqueue(
        method: String,
        params: Map<String, JsonElement>,
        sessionId: String?,
    ): Long {
        val entries = readAll()
        val id = (entries.maxOfOrNull { it.id } ?: 0L) + 1
        val entry = Entry(
            id = id,
            sessionId = sessionId,
            method = method,
            paramsJson = json.encodeToString(
                JsonObject.serializer(),
                JsonObject(params),
            ),
            createdAtMs = System.currentTimeMillis(),
        )
        writeAll(entries + entry)
        _pendingCount.value = entries.size + 1
        Timber.i("[Outbox] queued $method (id=$id)")
        return id
    }

    suspend fun pending(): List<Entry> = readAll().sortedBy { it.createdAtMs }

    suspend fun markSent(id: Long) {
        val entries = readAll().filterNot { it.id == id }
        writeAll(entries)
        _pendingCount.value = entries.size
    }

    suspend fun markFailed(id: Long, error: String?) {
        val entries = readAll().map { entry ->
            if (entry.id == id) {
                entry.copy(attempts = entry.attempts + 1, lastError = error)
            } else entry
        }.filterNot { it.attempts >= MAX_ATTEMPTS }
        writeAll(entries)
        _pendingCount.value = entries.size
    }

    fun decodeParams(entry: Entry): Map<String, JsonElement> =
        json.decodeFromString(JsonObject.serializer(), entry.paramsJson).toMap()

    private fun readAll(): List<Entry> = synchronized(lock) {
        if (!file.exists()) return emptyList()
        try {
            val text = file.readText()
            if (text.isBlank()) return emptyList()
            json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(Entry.serializer()),
                text,
            )
        } catch (e: Exception) {
            Timber.e(e, "[Outbox] corrupt queue file — starting fresh")
            file.delete()
            emptyList()
        }
    }

    private fun writeAll(entries: List<Entry>) = synchronized(lock) {
        try {
            val tmp = File(context.filesDir, "hermes_outbox.json.tmp")
            tmp.writeText(
                json.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(Entry.serializer()),
                    entries,
                )
            )
            if (!tmp.renameTo(file)) {
                // renameTo can fail on some FS if target exists — delete then retry
                file.delete()
                tmp.renameTo(file)
            }
        } catch (e: Exception) {
            Timber.e(e, "[Outbox] failed to persist queue (${entries.size} entries)")
        }
    }

    companion object {
        const val MAX_ATTEMPTS = 5

        /** Only these survive a disconnect; everything else fails fast. */
        val QUEUEABLE_METHODS = setOf(
            GatewayMethods.PROMPT_SUBMIT,
            GatewayMethods.APPROVAL_RESPOND,
            GatewayMethods.CLARIFY_RESPOND,
            GatewayMethods.SECRET_RESPOND,
            GatewayMethods.SESSION_STEER,
        )
    }
}