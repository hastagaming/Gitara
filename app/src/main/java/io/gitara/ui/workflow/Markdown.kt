package io.gitara.ui.workflow

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private sealed class MdBlock {
    class Heading(val level: Int, val text: String) : MdBlock()
    class Paragraph(val text: String) : MdBlock()
    class Item(val marker: String, val indent: Int, val text: String) : MdBlock()
    class Code(val text: String) : MdBlock()
    class Quote(val text: String) : MdBlock()
    class Table(val rows: List<String>) : MdBlock()
    object Rule : MdBlock()
}

private val BULLET = Regex("^(\\s*)([-*+]|\\d+[.)])\\s+(.*)$")
private val HEADING = Regex("^(#{1,6})\\s+(.*?)\\s*#*\\s*$")
private val RULE = Regex("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$")
private val TABLE_SEPARATOR = Regex("^\\|?[\\s:|-]+\\|?$")
private val INLINE = Regex(
    "`([^`]+)`" +
        "|\\*\\*(.+?)\\*\\*" +
        "|(?<![A-Za-z0-9*])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![A-Za-z0-9*])" +
        "|(?<![A-Za-z0-9])_(.+?)_(?![A-Za-z0-9])" +
        "|!?\\[([^\\]]*)\\]\\(([^)]*)\\)"
)

private fun clean(markdown: String): String =
    markdown
        .replace("\r\n", "\n")
        .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("</?[A-Za-z][^>]*>"), "")

private fun parseBlocks(markdown: String): List<MdBlock> {
    val blocks = ArrayList<MdBlock>()
    val lines = clean(markdown).split('\n')
    val paragraph = StringBuilder()
    fun flush() {
        if (paragraph.isNotBlank()) blocks.add(MdBlock.Paragraph(paragraph.toString().trim()))
        paragraph.setLength(0)
    }
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()
        val heading = HEADING.matchEntire(trimmed)
        val bullet = BULLET.matchEntire(line)
        when {
            trimmed.startsWith("```") -> {
                flush()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    code.append(lines[i]).append('\n')
                    i++
                }
                blocks.add(MdBlock.Code(code.toString().trimEnd('\n')))
            }
            trimmed.isEmpty() -> flush()
            heading != null -> {
                flush()
                blocks.add(MdBlock.Heading(heading.groupValues[1].length, heading.groupValues[2]))
            }
            RULE.matches(trimmed) -> {
                flush()
                blocks.add(MdBlock.Rule)
            }
            trimmed.startsWith("|") -> {
                flush()
                val rows = ArrayList<String>()
                while (i < lines.size && lines[i].trim().startsWith("|")) {
                    val row = lines[i].trim()
                    if (!TABLE_SEPARATOR.matches(row)) {
                        rows.add(row.trim('|').split('|').joinToString("  |  ") { it.trim() })
                    }
                    i++
                }
                i--
                blocks.add(MdBlock.Table(rows))
            }
            trimmed.startsWith(">") -> {
                flush()
                blocks.add(MdBlock.Quote(trimmed.removePrefix(">").trim()))
            }
            bullet != null -> {
                flush()
                val marker = bullet.groupValues[2]
                blocks.add(
                    MdBlock.Item(
                        marker = if (marker[0].isDigit()) marker else "•",
                        indent = bullet.groupValues[1].length / 2,
                        text = bullet.groupValues[3]
                    )
                )
            }
            else -> {
                if (paragraph.isNotEmpty()) paragraph.append(' ')
                paragraph.append(trimmed)
            }
        }
        i++
    }
    flush()
    return blocks
}

private fun inline(text: String, codeBackground: Color, linkColor: Color): AnnotatedString =
    buildAnnotatedString {
        var last = 0
        for (match in INLINE.findAll(text)) {
            append(text.substring(last, match.range.first))
            val g = match.groupValues
            when {
                g[1].isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) { append(g[1]) }
                g[2].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(g[2]) }
                g[3].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[3]) }
                g[4].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[4]) }
                match.value.startsWith("!") -> append("[" + g[5].ifEmpty { "image" } + "]")
                else -> withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                    append(g[5].ifEmpty { g[6] })
                }
            }
            last = match.range.last + 1
        }
        append(text.substring(last))
    }

@Composable
private fun CodeBlock(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = text,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            softWrap = false,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(10.dp)
        )
    }
}

@Composable
internal fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val blocks = remember(markdown) { parseBlocks(markdown) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> Text(
                    inline(block.text, scheme.surfaceContainerHigh, scheme.primary),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        3 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                    fontWeight = FontWeight.Bold
                )
                is MdBlock.Paragraph -> Text(
                    inline(block.text, scheme.surfaceContainerHigh, scheme.primary),
                    style = MaterialTheme.typography.bodyMedium
                )
                is MdBlock.Item -> Row(Modifier.padding(start = (block.indent * 16).dp)) {
                    Text(block.marker + "  ", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        inline(block.text, scheme.surfaceContainerHigh, scheme.primary),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                }
                is MdBlock.Quote -> Text(
                    inline(block.text, scheme.surfaceContainerHigh, scheme.primary),
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = FontStyle.Italic,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp)
                )
                is MdBlock.Code -> CodeBlock(block.text)
                is MdBlock.Table -> CodeBlock(block.rows.joinToString("\n"))
                MdBlock.Rule -> HorizontalDivider()
            }
        }
    }
}