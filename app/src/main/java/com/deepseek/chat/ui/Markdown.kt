package com.deepseek.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

sealed interface MdBlock {
    data class Paragraph(val raw: String) : MdBlock
    data class Heading(val level: Int, val raw: String) : MdBlock
    data class Code(val language: String, val code: String) : MdBlock
    data class ListItem(val marker: String, val raw: String) : MdBlock
    data class Quote(val raw: String) : MdBlock
    object Rule : MdBlock
}

private val BULLET = Regex("^([-*+])\\s+(.*)$")
private val ORDERED = Regex("^(\\d{1,2})[.)]\\s+(.*)$")
private val RULE = Regex("^([-*_])\\1{2,}$")

private val INLINE = Regex(
    "\\*\\*([^*\\n]+?)\\*\\*" +
        "|__([^_\\n]+?)__" +
        "|~~([^~\\n]+?)~~" +
        "|`([^`\\n]+?)`" +
        "|\\[([^\\]\\n]+?)\\]\\(([^)\\s]+?)\\)" +
        "|\\*([^*\\n]+?)\\*" +
        "|(https?://[^\\s<)）]+)"
)

/** 把 Markdown 源码切成块级结构；不支持表格，会退化成段落。 */
fun parseMarkdown(source: String): List<MdBlock> {
    val text = source.replace("\r\n", "\n").replace('\r', '\n')
    val lines = text.split("\n")
    val blocks = ArrayList<MdBlock>()
    val paragraph = StringBuilder()

    fun flushParagraph() {
        if (paragraph.isNotBlank()) {
            blocks.add(MdBlock.Paragraph(paragraph.toString().trim()))
            paragraph.setLength(0)
        }
    }

    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        val trimmed = line.trim()

        when {
            trimmed.startsWith("```") -> {
                flushParagraph()
                val language = trimmed.removePrefix("```").trim()
                val code = StringBuilder()
                index++
                while (index < lines.size && !lines[index].trim().startsWith("```")) {
                    if (code.isNotEmpty()) code.append('\n')
                    code.append(lines[index])
                    index++
                }
                blocks.add(MdBlock.Code(language, code.toString()))
            }

            trimmed.isEmpty() -> flushParagraph()

            RULE.matches(trimmed) -> {
                flushParagraph()
                blocks.add(MdBlock.Rule)
            }

            trimmed.startsWith("#") -> {
                flushParagraph()
                var level = 0
                while (level < trimmed.length && level < 6 && trimmed[level] == '#') level++
                val content = trimmed.substring(level).trim()
                if (content.isEmpty()) {
                    paragraph.append(trimmed)
                } else {
                    blocks.add(MdBlock.Heading(level, content))
                }
            }

            trimmed.startsWith(">") -> {
                flushParagraph()
                blocks.add(MdBlock.Quote(trimmed.removePrefix(">").trim()))
            }

            BULLET.matches(trimmed) -> {
                flushParagraph()
                val match = BULLET.find(trimmed)
                if (match != null) {
                    blocks.add(MdBlock.ListItem("•", match.groupValues[2]))
                }
            }

            ORDERED.matches(trimmed) -> {
                flushParagraph()
                val match = ORDERED.find(trimmed)
                if (match != null) {
                    blocks.add(MdBlock.ListItem(match.groupValues[1] + ".", match.groupValues[2]))
                }
            }

            else -> {
                if (paragraph.isNotEmpty()) paragraph.append('\n')
                paragraph.append(trimmed)
            }
        }
        index++
    }
    flushParagraph()
    return blocks
}

private fun buildInline(
    raw: String,
    accent: Color,
    codeBackground: Color,
    codeColor: Color,
    mono: FontFamily
): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    for (match in INLINE.findAll(raw)) {
        if (match.range.first > cursor) {
            append(raw.substring(cursor, match.range.first))
        }
        val groups = match.groupValues
        when {
            groups[1].isNotEmpty() -> {
                pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold))
                append(groups[1])
                pop()
            }
            groups[2].isNotEmpty() -> {
                pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold))
                append(groups[2])
                pop()
            }
            groups[3].isNotEmpty() -> {
                pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                append(groups[3])
                pop()
            }
            groups[4].isNotEmpty() -> {
                pushStyle(SpanStyle(fontFamily = mono, background = codeBackground, color = codeColor))
                append(groups[4])
                pop()
            }
            groups[5].isNotEmpty() -> {
                pushStyle(SpanStyle(color = accent, textDecoration = TextDecoration.Underline))
                append(groups[5])
                pop()
            }
            groups[7].isNotEmpty() -> {
                pushStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic))
                append(groups[7])
                pop()
            }
            groups[8].isNotEmpty() -> {
                pushStyle(SpanStyle(color = accent, textDecoration = TextDecoration.Underline))
                append(groups[8])
                pop()
            }
            else -> append(match.value)
        }
        cursor = match.range.last + 1
    }
    if (cursor < raw.length) append(raw.substring(cursor))
}

@Composable
fun inlineText(raw: String): AnnotatedString {
    val accent = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    val codeColor = MaterialTheme.colorScheme.onSurfaceVariant
    return remember(raw, accent, codeBackground, codeColor) {
        buildInline(raw, accent, codeBackground, codeColor, FontFamily.Monospace)
    }
}

@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge
) {
    val blocks = remember(text) { parseMarkdown(text) }
    val onSurface = MaterialTheme.colorScheme.onSurface
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (block in blocks) {
            when (block) {
                is MdBlock.Paragraph -> Text(
                    text = inlineText(block.raw),
                    style = style,
                    color = onSurface
                )

                is MdBlock.Heading -> Text(
                    text = inlineText(block.raw),
                    style = style.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = when (block.level) {
                            1 -> 22.sp
                            2 -> 20.sp
                            3 -> 18.sp
                            else -> 16.sp
                        }
                    ),
                    color = onSurface
                )

                is MdBlock.Code -> CodeBlock(block.language, block.code)

                is MdBlock.ListItem -> Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = block.marker,
                        style = style,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(20.dp)
                    )
                    Text(
                        text = inlineText(block.raw),
                        style = style,
                        color = onSurface,
                        modifier = Modifier.weight(1f)
                    )
                }

                is MdBlock.Quote -> Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = inlineText(block.raw),
                        style = style.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }

                MdBlock.Rule -> HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun CodeBlock(language: String, code: String) {
    val clipboard = LocalClipboardManager.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = language.ifBlank { "代码" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = { clipboard.setText(AnnotatedString(code)) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = "复制代码",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Text(
                text = code,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 20.sp
                ),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
            )
        }
    }
}
