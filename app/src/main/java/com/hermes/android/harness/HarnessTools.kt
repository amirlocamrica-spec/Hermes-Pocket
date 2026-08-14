package com.hermes.android.harness

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Built-in Harness tools executed locally on the phone:
 *  - calculate: safe arithmetic via javax.script-free parser
 *  - current_time: device date/time
 *  - notes_save / notes_read: private app storage
 *  - http_get: fetch a URL (10s timeout, 64KB cap)
 */
@Singleton
class HarnessTools @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    val toolDefinitions: List<HarnessTool> = listOf(
        HarnessTool(
            name = "calculate",
            description = "Evaluate an arithmetic expression like '2+2*5'. Supports + - * / ( ) and decimals.",
            parametersJson = """{"type":"object","properties":{"expression":{"type":"string"}},"required":["expression"]}""",
        ),
        HarnessTool(
            name = "current_time",
            description = "Get the current date and time on the user's device.",
            parametersJson = """{"type":"object","properties":{}}""",
        ),
        HarnessTool(
            name = "notes_save",
            description = "Save a text note on the device (persists between chats).",
            parametersJson = """{"type":"object","properties":{"title":{"type":"string"},"body":{"type":"string"}},"required":["title","body"]}""",
        ),
        HarnessTool(
            name = "notes_read",
            description = "List or read saved notes.",
            parametersJson = """{"type":"object","properties":{"title":{"type":"string"}}}""",
        ),
        HarnessTool(
            name = "http_get",
            description = "Fetch a URL and return up to 64KB of its body text.",
            parametersJson = """{"type":"object","properties":{"url":{"type":"string"}},"required":["url"]}""",
        ),
    )

    /** Execute one tool call and return the textual result. Never throws. */
    fun execute(call: ToolCallRequest): ToolCallResult {
        val args = runCatching { json.parseToJsonElement(call.argumentsJson).jsonObject }
            .getOrDefault(JsonObject(emptyMap()))
        val output = try {
            when (call.name) {
                "calculate" -> calculate(args["expression"]?.jsonPrimitive?.content ?: "")
                "current_time" -> currentTime()
                "notes_save" -> notesSave(
                    args["title"]?.jsonPrimitive?.content ?: "untitled",
                    args["body"]?.jsonPrimitive?.content ?: "",
                )
                "notes_read" -> notesRead(args["title"]?.jsonPrimitive?.content)
                "http_get" -> httpGet(args["url"]?.jsonPrimitive?.content ?: "")
                else -> "Unknown tool: ${call.name}"
            }
        } catch (e: Exception) {
            "Tool error: ${e.message}"
        }
        return ToolCallResult(call.id, call.name, output)
    }

    // ── Implementations ──────────────────────────────────────────────────

    private fun calculate(expression: String): String {
        val result = ExpressionEvaluator.evaluate(expression)
            ?: return "Could not evaluate: $expression"
        return "$expression = $result"
    }

    private fun currentTime(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss zzz", Locale.US)
        return fmt.format(Date())
    }

    private fun notesDir(): File = File(context.filesDir, "harness-notes").apply { mkdirs() }

    private fun notesSave(title: String, body: String): String {
        val safe = title.replace(Regex("[^\\p{L}\\p{N} _-]"), "").ifBlank { "note" }
        File(notesDir(), "$safe.txt").writeText(body)
        return "Saved note '$safe'."
    }

    private fun notesRead(title: String?): String {
        val files = notesDir().listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
        if (title == null) {
            if (files.isEmpty()) return "No notes saved."
            return files.joinToString("\n") { "- ${it.nameWithoutExtension} (${it.length()} bytes)" }
        }
        val safe = title.replace(Regex("[^\\p{L}\\p{N} _-]"), "")
        val f = File(notesDir(), "$safe.txt")
        return if (f.exists()) f.readText() else "Note '$safe' not found."
    }

    private fun httpGet(url: String): String {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return "Only http(s) URLs allowed"
        val request = Request.Builder().url(url).get().build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return "HTTP ${resp.code}"
            val body = resp.peekBody(64 * 1024).string()
            return body.ifBlank { "(empty body)" }
        }
    }
}

/**
 * Tiny recursive-descent arithmetic evaluator (no scripting engine on
 * Android). Supports + - * / % parentheses, unary minus, decimals.
 */
internal object ExpressionEvaluator {
    fun evaluate(input: String): Double? = runCatching {
        val p = Parser(input.replace(" ", ""))
        val v = p.parseExpr()
        if (p.pos != p.src.length) throw IllegalArgumentException("trailing input")
        v
    }.getOrNull()

    private class Parser(val src: String) {
        var pos = 0

        fun parseExpr(): Double {
            var v = parseTerm()
            while (pos < src.length && (src[pos] == '+' || src[pos] == '-')) {
                val op = src[pos++]
                val r = parseTerm()
                v = if (op == '+') v + r else v - r
            }
            return v
        }

        private fun parseTerm(): Double {
            var v = parseFactor()
            while (pos < src.length && (src[pos] == '*' || src[pos] == '/' || src[pos] == '%')) {
                val op = src[pos++]
                val r = parseFactor()
                v = when (op) {
                    '*' -> v * r
                    '/' -> v / r
                    else -> v % r
                }
            }
            return v
        }

        private fun parseFactor(): Double {
            if (pos < src.length && src[pos] == '-') { pos++; return -parseFactor() }
            if (pos < src.length && src[pos] == '(') {
                pos++
                val v = parseExpr()
                if (pos < src.length && src[pos] == ')') pos++ else throw IllegalArgumentException("missing )")
                return v
            }
            val start = pos
            while (pos < src.length && (src[pos].isDigit() || src[pos] == '.')) pos++
            if (start == pos) throw IllegalArgumentException("expected number at $pos")
            return src.substring(start, pos).toDouble()
        }
    }
}