package com.example.caramelai.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

data class RoleplayColors(
    val dialogue: Color,
    val action: Color,
    val bold: Color,
    val normal: Color,
)

/**
 * Formats roleplay markdown-ish text:
 * - *action* → italic + action color
 * - **emphasis** → bold
 * - "dialogue" / “dialogue” → dialogue color
 *
 * Nested markers work (*a **b** c*, **"hi"*, quotes with *actions*).
 * Unclosed markers are shown as literal `*` so a stray asterisk cannot
 * swallow the rest of the message (including manual edits).
 */
object RoleplayFormatter {
    fun format(text: String, colors: RoleplayColors): AnnotatedString {
        if (text.isEmpty()) return AnnotatedString("")
        return buildAnnotatedString {
            appendFormatted(text, colors, baseColor = colors.normal)
        }
    }

    private fun AnnotatedString.Builder.appendFormatted(
        text: String,
        colors: RoleplayColors,
        baseColor: Color,
    ) {
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("**", i) -> {
                    val end = indexOfClosingDoubleAsterisk(text, i + 2)
                    if (end >= 0) {
                        withStyle(
                            SpanStyle(
                                fontWeight = FontWeight.Bold,
                                color = colors.bold,
                            ),
                        ) {
                            appendFormatted(
                                text.substring(i + 2, end),
                                colors,
                                baseColor = colors.bold,
                            )
                        }
                        i = end + 2
                    } else {
                        // Orphan "**" / leading "*": keep literal, don't eat the rest.
                        withStyle(SpanStyle(color = baseColor)) { append("*") }
                        i += 1
                    }
                }

                text[i] == '*' -> {
                    val end = indexOfClosingSingleAsterisk(text, i + 1)
                    if (end >= 0) {
                        withStyle(
                            SpanStyle(
                                fontStyle = FontStyle.Italic,
                                color = colors.action,
                            ),
                        ) {
                            appendFormatted(
                                text.substring(i + 1, end),
                                colors,
                                baseColor = colors.action,
                            )
                        }
                        i = end + 1
                    } else {
                        withStyle(SpanStyle(color = baseColor)) { append("*") }
                        i += 1
                    }
                }

                text[i] == '"' || text[i] == '“' || text[i] == '”' -> {
                    val open = text[i]
                    val close = when (open) {
                        '“' -> '”'
                        '”' -> '”'
                        else -> '"'
                    }
                    val end = findClosingQuote(text, i + 1, close, open)
                    if (end >= 0) {
                        withStyle(SpanStyle(color = colors.dialogue)) {
                            append(text[i])
                            appendFormatted(
                                text.substring(i + 1, end),
                                colors,
                                baseColor = colors.dialogue,
                            )
                            append(text[end])
                        }
                        i = end + 1
                    } else {
                        withStyle(SpanStyle(color = baseColor)) { append(text[i]) }
                        i += 1
                    }
                }

                else -> {
                    val next = nextSpecialIndex(text, i + 1)
                    withStyle(SpanStyle(color = baseColor)) {
                        append(text.substring(i, next))
                    }
                    i = next
                }
            }
        }
    }

    /** Closing `**` that is not the start of a longer run ambiguity — first match. */
    private fun indexOfClosingDoubleAsterisk(text: String, from: Int): Int {
        var i = from
        while (i < text.length - 1) {
            if (text[i] == '*' && text[i + 1] == '*') return i
            i++
        }
        return -1
    }

    /**
     * Nearest single `*` that is not part of a `**` run.
     * Skips asterisk runs (`**`, `***`, …) so bold delimiters are not treated
     * as italic closers, without requiring the bold region to be well-formed.
     */
    private fun indexOfClosingSingleAsterisk(text: String, from: Int): Int {
        var i = from
        while (i < text.length) {
            if (text[i] == '*') {
                val prevIsStar = i > 0 && text[i - 1] == '*'
                val nextIsStar = i + 1 < text.length && text[i + 1] == '*'
                if (!prevIsStar && !nextIsStar) return i
                while (i < text.length && text[i] == '*') i++
                continue
            }
            i++
        }
        return -1
    }

    private fun findClosingQuote(
        text: String,
        from: Int,
        close: Char,
        open: Char,
    ): Int {
        val end = text.indexOf(close, from).takeIf { it >= 0 }
        if (end != null) return end
        if (open == '"' ) {
            return text.indexOf('”', from).takeIf { it >= 0 } ?: -1
        }
        return -1
    }

    private fun nextSpecialIndex(text: String, from: Int): Int {
        var i = from
        while (i < text.length) {
            val c = text[i]
            if (c == '*' || c == '"' || c == '“' || c == '”') return i
            i++
        }
        return text.length
    }
}
