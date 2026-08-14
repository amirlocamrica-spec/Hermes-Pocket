package com.hermes.android.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Renders a unified-diff (or any patch-like) text with the classic
 * +green / −red / @@blue line coloring, the way a code review UI does.
 *
 * The whole block is one [Text] (not one Text per line) — diffs from real
 * tool calls can be thousands of lines, and per-line composables made the
 * message list jank while the agent was editing files. A single annotated
 * string scrolls horizontally as one unit.
 *
 * Lines longer than a sensible cap are hard-truncated per line so one
 * minified-JS blob can't blow up layout; the full text stays reachable via
 * the regular copy action on the tool card.
 */
@Composable
internal fun DiffView(text: String, modifier: Modifier = Modifier) {
    val annotated = remember(text) { colorDiff(text) }
    Text(
        text = annotated,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .horizontalScroll(rememberScrollState()),
    )
}

/** True when [text] looks like a unified diff worth special rendering. */
internal fun looksLikeDiff(text: String): Boolean {
    // Cheap heuristic: a diff hunk header, or both an added and a removed
    // line. Pure prose never starts a line with +++ while also having one
    // starting with ---.
    var plus = false
    var minus = false
    var hunk = false
    text.lineSequence().forEach { line ->
        when {
            line.startsWith("+++") -> plus = true
            line.startsWith("---") -> minus = true
            line.startsWith("@@") -> hunk = true
            line.startsWith("+") -> plus = true
            line.startsWith("-") -> minus = true
        }
    }
    return hunk || (plus && minus)
}

private const val MAX_LINES = 400
private const val MAX_LINE_CHARS = 500

private fun colorDiff(text: String): AnnotatedString {
    val added = SpanStyle(color = Color(0xFF2E7D32), background = Color(0x224CAF50))
    val removed = SpanStyle(color = Color(0xFFC62828), background = Color(0x22F44336))
    val hunk = SpanStyle(color = Color(0xFF1565C0))
    val header = SpanStyle(color = Color(0xFF6A1B9A))

    return buildAnnotatedString {
        var lineNo = 0
        for (raw in text.lineSequence()) {
            if (lineNo >= MAX_LINES) {
                append("\n… (diff truncated)")
                break
            }
            val line = if (raw.length > MAX_LINE_CHARS) raw.take(MAX_LINE_CHARS) + "…" else raw
            val start = length
            append(line)
            when {
                line.startsWith("+++") || line.startsWith("---") -> addStyle(header, start, length)
                line.startsWith("@@") -> addStyle(hunk, start, length)
                line.startsWith("+") -> addStyle(added, start, length)
                line.startsWith("-") -> addStyle(removed, start, length)
            }
            append("\n")
            lineNo++
        }
    }
}