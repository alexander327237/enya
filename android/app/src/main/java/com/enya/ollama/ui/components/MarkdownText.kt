package com.enya.ollama.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A small, dependency-free renderer for the subset of Markdown that model output typically
 * uses: fenced code blocks, bullet lists, **bold** and `inline code`.
 */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { parseBlocks(text) }
    Column(modifier = modifier) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Code -> Text(
                    text = block.text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    color = LocalContentColor.current,
                    modifier = Modifier
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(10.dp)
                )
                is MdBlock.Bullet -> Text(
                    text = buildAnnotatedString {
                        append("•  ")
                        append(inlineStyled(block.text))
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 2.dp)
                )
                is MdBlock.Paragraph -> Text(
                    text = inlineStyled(block.text),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }
}

private sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock
    data class Code(val text: String) : MdBlock
    data class Bullet(val text: String) : MdBlock
}

private fun parseBlocks(raw: String): List<MdBlock> {
    val lines = raw.split("\n")
    val blocks = mutableListOf<MdBlock>()
    val paragraph = StringBuilder()

    fun flush() {
        if (paragraph.isNotEmpty()) {
            blocks.add(MdBlock.Paragraph(paragraph.toString().trim()))
            paragraph.clear()
        }
    }

    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()
        when {
            trimmed.startsWith("```") -> {
                flush()
                val code = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    code.add(lines[i])
                    i++
                }
                blocks.add(MdBlock.Code(code.joinToString("\n")))
            }
            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                flush()
                blocks.add(MdBlock.Bullet(trimmed.drop(2)))
            }
            line.isBlank() -> flush()
            else -> {
                if (paragraph.isNotEmpty()) paragraph.append("\n")
                paragraph.append(line)
            }
        }
        i++
    }
    flush()
    return blocks
}

private val inlineTokenRegex = Regex("\\*\\*(.+?)\\*\\*|`(.+?)`")

private fun inlineStyled(raw: String): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    for (match in inlineTokenRegex.findAll(raw)) {
        if (match.range.first > cursor) append(raw.substring(cursor, match.range.first))
        val bold = match.groups[1]?.value
        val code = match.groups[2]?.value
        when {
            bold != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(bold) }
            code != null -> withStyle(
                SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            ) { append(code) }
        }
        cursor = match.range.last + 1
    }
    if (cursor < raw.length) append(raw.substring(cursor))
}
