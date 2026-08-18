package com.hermes.android.ui.component

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

/**
 * Lightweight regex-based syntax highlighter for common languages.
 * No external dependencies — keeps APK size minimal (<50KB added).
 */
object SyntaxHighlighter {

    private val kotlinKeywords = setOf(
        "fun", "val", "var", "class", "object", "interface", "return", "if", "else",
        "when", "for", "while", "do", "try", "catch", "finally", "throw", "import",
        "package", "private", "public", "internal", "protected", "override", "abstract",
        "open", "sealed", "data", "companion", "suspend", "inline", "reified",
        "null", "true", "false", "is", "as", "in", "out", "typealias", "by"
    )

    private val pythonKeywords = setOf(
        "def", "class", "return", "if", "elif", "else", "for", "while", "try",
        "except", "finally", "raise", "import", "from", "as", "with", "yield",
        "lambda", "pass", "break", "continue", "and", "or", "not", "in", "is",
        "None", "True", "False", "global", "nonlocal", "assert", "del", "async", "await"
    )

    private val bashKeywords = setOf(
        "if", "then", "else", "elif", "fi", "case", "esac", "for", "while", "until",
        "do", "done", "function", "return", "exit", "echo", "export", "source",
        "alias", "unalias", "cd", "pwd", "ls", "grep", "sed", "awk", "cat", "chmod"
    )

    private val jsonKeywords = setOf("true", "false", "null")

    private val yamlKeywords = setOf("true", "false", "null", "yes", "no", "on", "off")

    // Token colors (dark theme friendly)
    private val KEYWORD_COLOR = Color(0xFFCC7832)   // Orange
    private val STRING_COLOR = Color(0xFF6A8759)    // Green
    private val NUMBER_COLOR = Color(0xFF6897BB)    // Blue
    private val COMMENT_COLOR = Color(0xFF808080)   // Gray
    private val FUNCTION_COLOR = Color(0xFFFFC66D)  // Yellow
    private val TYPE_COLOR = Color(0xFFA9B7C6)      // Light gray

    fun highlight(code: String, language: String): AnnotatedString {
        val lang = language.lowercase().trim()
        return when (lang) {
            "kotlin", "kt" -> highlightKotlin(code)
            "python", "py" -> highlightPython(code)
            "bash", "sh", "shell" -> highlightBash(code)
            "json" -> highlightJson(code)
            "yaml", "yml" -> highlightYaml(code)
            else -> highlightGeneric(code)
        }
    }

    private fun highlightKotlin(code: String): AnnotatedString = buildAnnotatedString {
        var i = 0
        while (i < code.length) {
            when {
                // Line comment
                code.startsWith("//", i) -> {
                    val end = code.indexOf('\n', i).takeIf { it >= 0 } ?: code.length
                    withStyle(SpanStyle(color = COMMENT_COLOR)) {
                        append(code.substring(i, end))
                    }
                    i = end
                }
                // Block comment
                code.startsWith("/*", i) -> {
                    val end = code.indexOf("*/", i + 2).takeIf { it >= 0 }?.plus(2) ?: code.length
                    withStyle(SpanStyle(color = COMMENT_COLOR)) {
                        append(code.substring(i, end))
                    }
                    i = end
                }
                // String literal
                code[i] == '"' -> {
                    val end = findStringEnd(code, i + 1, '"')
                    withStyle(SpanStyle(color = STRING_COLOR)) {
                        append(code.substring(i, end))
                    }
                    i = end
                }
                // Number
                code[i].isDigit() || (code[i] == '.' && i + 1 < code.length && code[i + 1].isDigit()) -> {
                    val end = findNumberEnd(code, i)
                    withStyle(SpanStyle(color = NUMBER_COLOR)) {
                        append(code.substring(i, end))
                    }
                    i = end
                }
                // Identifier/keyword
                code[i].isLetter() || code[i] == '_' -> {
                    val end = findIdentifierEnd(code, i)
                    val word = code.substring(i, end)
                    when (word) {
                        in kotlinKeywords -> withStyle(SpanStyle(color = KEYWORD_COLOR)) { append(word) }
                        else -> append(word)
                    }
                    i = end
                }
                else -> {
                    append(code[i])
                    i++
                }
            }
        }
    }

    private fun highlightPython(code: String): AnnotatedString = buildAnnotatedString {
        var i = 0
        while (i < code.length) {
            when {
                code.startsWith("#", i) -> {
                    val end = code.indexOf('\n', i).takeIf { it >= 0 } ?: code.length
                    withStyle(SpanStyle(color = COMMENT_COLOR)) {
                        append(code.substring(i, end))
                    }
                    i = end
                }
                code[i] == '"' || code[i] == '\'' -> {
                    val quote = code[i]
                    val triple = if (i + 2 < code.length && code[i+1] == quote && code[i+2] == quote) quote.toString().repeat(3) else null
                    val end = if (triple != null) {
                        val e = code.indexOf(triple, i + 3).takeIf { it >= 0 }?.plus(3) ?: code.length
                        withStyle(SpanStyle(color = STRING_COLOR)) { append(code.substring(i, e)) }
                        e
                    } else {
                        val e = findStringEnd(code, i + 1, quote)
                        withStyle(SpanStyle(color = STRING_COLOR)) { append(code.substring(i, e)) }
                        e
                    }
                    i = end
                }
                code[i].isDigit() -> {
                    val end = findNumberEnd(code, i)
                    withStyle(SpanStyle(color = NUMBER_COLOR)) { append(code.substring(i, end)) }
                    i = end
                }
                code[i].isLetter() || code[i] == '_' -> {
                    val end = findIdentifierEnd(code, i)
                    val word = code.substring(i, end)
                    when (word) {
                        in pythonKeywords -> withStyle(SpanStyle(color = KEYWORD_COLOR)) { append(word) }
                        else -> append(word)
                    }
                    i = end
                }
                else -> { append(code[i]); i++ }
            }
        }
    }

    private fun highlightBash(code: String): AnnotatedString = buildAnnotatedString {
        var i = 0
        while (i < code.length) {
            when {
                code.startsWith("#", i) -> {
                    val end = code.indexOf('\n', i).takeIf { it >= 0 } ?: code.length
                    withStyle(SpanStyle(color = COMMENT_COLOR)) { append(code.substring(i, end)) }
                    i = end
                }
                code[i] == '"' || code[i] == '\'' -> {
                    val quote = code[i]
                    val end = findStringEnd(code, i + 1, quote)
                    withStyle(SpanStyle(color = STRING_COLOR)) { append(code.substring(i, end)) }
                    i = end
                }
                code[i] == '$' && i + 1 < code.length && (code[i+1].isLetter() || code[i+1] == '{') -> {
                    val end = if (code[i+1] == '{') {
                        code.indexOf('}', i + 2).takeIf { it >= 0 }?.plus(1) ?: code.length
                    } else {
                        findIdentifierEnd(code, i + 1)
                    }
                    withStyle(SpanStyle(color = FUNCTION_COLOR)) { append(code.substring(i, end)) }
                    i = end
                }
                code[i].isLetter() || code[i] == '_' -> {
                    val end = findIdentifierEnd(code, i)
                    val word = code.substring(i, end)
                    when (word) {
                        in bashKeywords -> withStyle(SpanStyle(color = KEYWORD_COLOR)) { append(word) }
                        else -> append(word)
                    }
                    i = end
                }
                else -> { append(code[i]); i++ }
            }
        }
    }

    private fun highlightJson(code: String): AnnotatedString = buildAnnotatedString {
        var i = 0
        while (i < code.length) {
            when {
                code[i] == '"' -> {
                    val end = findStringEnd(code, i + 1, '"')
                    val str = code.substring(i, end)
                    // Check if this is a key (followed by :)
                    val afterStr = code.substring(end).trimStart()
                    val isKey = afterStr.startsWith(":")
                    withStyle(SpanStyle(color = if (isKey) TYPE_COLOR else STRING_COLOR)) {
                        append(str)
                    }
                    i = end
                }
                code[i].isDigit() || code[i] == '-' -> {
                    val end = findNumberEnd(code, i)
                    withStyle(SpanStyle(color = NUMBER_COLOR)) { append(code.substring(i, end)) }
                    i = end
                }
                code[i].isLetter() -> {
                    val end = findIdentifierEnd(code, i)
                    val word = code.substring(i, end)
                    when (word) {
                        in jsonKeywords -> withStyle(SpanStyle(color = KEYWORD_COLOR)) { append(word) }
                        else -> append(word)
                    }
                    i = end
                }
                else -> { append(code[i]); i++ }
            }
        }
    }

    private fun highlightYaml(code: String): AnnotatedString = buildAnnotatedString {
        var i = 0
        while (i < code.length) {
            when {
                code.startsWith("#", i) -> {
                    val end = code.indexOf('\n', i).takeIf { it >= 0 } ?: code.length
                    withStyle(SpanStyle(color = COMMENT_COLOR)) { append(code.substring(i, end)) }
                    i = end
                }
                code[i] == '"' || code[i] == '\'' -> {
                    val quote = code[i]
                    val end = findStringEnd(code, i + 1, quote)
                    withStyle(SpanStyle(color = STRING_COLOR)) { append(code.substring(i, end)) }
                    i = end
                }
                code[i].isLetter() || code[i] == '_' || code[i] == '-' -> {
                    val end = findIdentifierEnd(code, i)
                    val word = code.substring(i, end)
                    // YAML keys are typically followed by :
                    val afterWord = code.substring(end).trimStart()
                    val isKey = afterWord.startsWith(":")
                    when {
                        word in yamlKeywords -> withStyle(SpanStyle(color = KEYWORD_COLOR)) { append(word) }
                        isKey -> withStyle(SpanStyle(color = TYPE_COLOR)) { append(word) }
                        else -> append(word)
                    }
                    i = end
                }
                code[i].isDigit() -> {
                    val end = findNumberEnd(code, i)
                    withStyle(SpanStyle(color = NUMBER_COLOR)) { append(code.substring(i, end)) }
                    i = end
                }
                else -> { append(code[i]); i++ }
            }
        }
    }

    private fun highlightGeneric(code: String): AnnotatedString = buildAnnotatedString {
        // Minimal generic highlighting: strings, numbers, comments
        var i = 0
        while (i < code.length) {
            when {
                code.startsWith("//", i) || code.startsWith("#", i) -> {
                    val end = code.indexOf('\n', i).takeIf { it >= 0 } ?: code.length
                    withStyle(SpanStyle(color = COMMENT_COLOR)) { append(code.substring(i, end)) }
                    i = end
                }
                code[i] == '"' || code[i] == '\'' -> {
                    val quote = code[i]
                    val end = findStringEnd(code, i + 1, quote)
                    withStyle(SpanStyle(color = STRING_COLOR)) { append(code.substring(i, end)) }
                    i = end
                }
                code[i].isDigit() -> {
                    val end = findNumberEnd(code, i)
                    withStyle(SpanStyle(color = NUMBER_COLOR)) { append(code.substring(i, end)) }
                    i = end
                }
                else -> { append(code[i]); i++ }
            }
        }
    }

    private fun findStringEnd(code: String, start: Int, quote: Char): Int {
        var i = start
        while (i < code.length) {
            if (code[i] == '\\') { i += 2; continue }
            if (code[i] == quote) return i + 1
            if (code[i] == '\n') return i
            i++
        }
        return code.length
    }

    private fun findNumberEnd(code: String, start: Int): Int {
        var i = start
        if (i < code.length && code[i] == '-') i++
        while (i < code.length && (code[i].isDigit() || code[i] == '.' || code[i] == 'x' || code[i] == 'X'
                    || (code[i] in 'a'..'f') || (code[i] in 'A'..'F'))) {
            i++
        }
        return i.coerceAtLeast(start + 1)
    }

    private fun findIdentifierEnd(code: String, start: Int): Int {
        var i = start
        while (i < code.length && (code[i].isLetterOrDigit() || code[i] == '_' || code[i] == '-')) {
            i++
        }
        return i.coerceAtLeast(start + 1)
    }
}
