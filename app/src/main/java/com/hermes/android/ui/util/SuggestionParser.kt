package com.hermes.android.ui.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Parses inline quick-reply suggestions from assistant message text.
 *
 * Supports two formats:
 * 1. JSON block at end: {"suggestions": ["option1", "option2"]}
 * 2. Markdown-style list at end: lines starting with "> " or "- " (max 5)
 *
 * Returns parsed suggestions and the cleaned text (with suggestion block stripped).
 */
object SuggestionParser {

    data class ParseResult(
        val text: String,
        val suggestions: List<String>,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(rawText: String): ParseResult {
        if (rawText.isBlank()) return ParseResult(rawText, emptyList())

        // Try JSON format first
        val jsonMatch = Regex("""\{[\s]*"suggestions"[\s]*:[\s]*\[.*?\][\s]*\}""", RegexOption.DOT_MATCHES_ALL)
            .find(rawText)
        if (jsonMatch != null) {
            val jsonBlock = jsonMatch.value
            try {
                val obj = json.parseToJsonElement(jsonBlock) as? JsonObject
                val arr = obj?.get("suggestions") as? JsonArray
                val suggestions = arr?.mapNotNull {
                    (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                }?.filter { it.isNotBlank() }?.take(5) ?: emptyList()

                if (suggestions.isNotEmpty()) {
                    val cleanedText = rawText.removeRange(jsonMatch.range).trimEnd()
                    return ParseResult(cleanedText, suggestions)
                }
            } catch (_: Exception) {
                // Fall through to markdown pattern
            }
        }

        // Try markdown pattern: trailing lines starting with "> " or "- "
        val lines = rawText.lines()
        val suggestionLines = mutableListOf<String>()
        var cutoffIndex = lines.size

        // Scan backwards from end to find contiguous suggestion lines
        for (i in lines.indices.reversed()) {
            val line = lines[i].trim()
            if (line.startsWith("> ") || line.startsWith("- ")) {
                val content = line.removePrefix("> ").removePrefix("- ").trim()
                if (content.isNotBlank()) {
                    suggestionLines.add(0, content)
                    cutoffIndex = i
                }
            } else if (line.isBlank()) {
                // Allow blank lines between suggestion items
                continue
            } else {
                break
            }
        }

        if (suggestionLines.isNotEmpty()) {
            val suggestions = suggestionLines.take(5)
            val cleanedText = lines.subList(0, cutoffIndex).joinToString("\n").trimEnd()
            return ParseResult(cleanedText, suggestions)
        }

        return ParseResult(rawText, emptyList())
    }
}

</parameter>