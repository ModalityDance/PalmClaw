package com.palmclaw.ui

import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin

/**
 * Shared markdown renderer used by long-form help and rich text surfaces.
 */
@Composable
internal fun MarkdownText(
    markdown: String,
    textStyle: TextStyle,
    inlineCodeBackground: Color,
    quoteBackground: Color,
    codeBlockBackground: Color,
    fillMaxWidth: Boolean = true,
    contentColor: Color = MaterialTheme.colorScheme.onSurface
) {
    val context = LocalContext.current
    val plainTextColor = contentColor
    val plainTextCandidate = remember(markdown) {
        normalizeMarkdownForMobile(markdown)
    }
    val isPlainText = remember(plainTextCandidate) { isLikelyPlainText(plainTextCandidate) }
    if (isPlainText) {
        SelectionContainer {
            Text(
                text = plainTextCandidate,
                modifier = if (fillMaxWidth) Modifier.fillMaxWidth() else Modifier,
                style = textStyle,
                color = plainTextColor
            )
        }
        return
    }

    val textColor = plainTextColor.toArgb()
    val normalizedMarkdown = remember(markdown) {
        normalizeMarkdownForMobile(markdown)
    }

    val markwon = remember(context) {
        Markwon.builder(context)
            .usePlugin(TablePlugin.create(context))
            .build()
    }

    AndroidView(
        modifier = if (fillMaxWidth) Modifier.fillMaxWidth() else Modifier,
        factory = { ctx ->
            TextView(ctx).apply {
                movementMethod = LinkMovementMethod.getInstance()
                setTextIsSelectable(true)
                linksClickable = true
                isClickable = true
                includeFontPadding = false
                setPadding(0, 0, 0, 0)
                setLineSpacing(0f, 1.02f)
            }
        },
        update = { textView ->
            textView.setTextColor(textColor)
            if (textStyle.fontSize != TextUnit.Unspecified) {
                textView.textSize = textStyle.fontSize.value
            }
            if (textView.tag != normalizedMarkdown) {
                textView.tag = normalizedMarkdown
                markwon.setMarkdown(textView, normalizedMarkdown)
            }
        }
    )
}

internal fun normalizeMarkdownForMobile(markdown: String): String {
    val normalized = markdown
        .replace("\r\n", "\n")
        .replace("\\r\\n", "\n")
        .replace("\\n", "\n")
        .replace('\r', '\n')
        .replace('｜', '|')
        .replace(Regex("(?m)^(\\t+)")) { match ->
            "    ".repeat(match.value.length)
        }
    return normalizeMarkdownTables(normalized)
}

internal fun isLikelyTableLine(line: String): Boolean {
    val trimmed = line.trim()
    if (trimmed.isBlank()) return false
    val pipeCount = trimmed.count { it == '|' }
    return pipeCount >= 2
}

internal fun isTableSeparatorLine(line: String): Boolean {
    val normalized = line.trim().removePrefix("|").removeSuffix("|").replace(" ", "")
    if (normalized.isBlank()) return false
    return normalized.split("|").all { cell ->
        cell.isNotBlank() && cell.all { ch -> ch == '-' || ch == ':' }
    }
}

internal fun normalizeMarkdownTables(markdown: String): String {
    val lines = markdown.lines().toMutableList()
    for (i in lines.indices) {
        if (isLikelyTableLine(lines[i])) {
            lines[i] = lines[i].trim()
        }
    }

    val out = mutableListOf<String>()
    var i = 0
    while (i < lines.size) {
        val current = lines[i]
        if (isLikelyTableLine(current)) {
            val blockStart = i
            var blockEnd = i
            while (blockEnd + 1 < lines.size && isLikelyTableLine(lines[blockEnd + 1])) {
                blockEnd++
            }

            if (out.isNotEmpty() && out.last().isNotBlank()) {
                out.add("")
            }

            for (idx in blockStart..blockEnd) {
                out.add(lines[idx])
            }

            if (blockEnd + 1 < lines.size && lines[blockEnd + 1].isNotBlank()) {
                out.add("")
            }
            i = blockEnd + 1
            continue
        }
        out.add(current)
        i++
    }

    return out.joinToString("\n")
}

internal fun isLikelyPlainText(text: String): Boolean {
    if (text.isBlank()) return true
    val markdownSignals = listOf(
        "```",
        "`",
        "|",
        "# ",
        "##",
        "> ",
        "[",
        "](",
        "**",
        "__",
        "~~",
        "- ",
        "* ",
        "1. "
    )
    return markdownSignals.none { signal -> text.contains(signal) }
}
