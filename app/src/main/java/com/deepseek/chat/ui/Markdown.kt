package com.deepseek.chat.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.takeOrElse

sealed interface MdBlock {
    data class Paragraph(val raw: String) : MdBlock
    data class Heading(val level: Int, val raw: String) : MdBlock
    data class Code(val language: String, val code: String) : MdBlock
    data class ListItem(val marker: String, val raw: String, val level: Int = 0) : MdBlock
    data class Quote(val raw: String) : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
    /** 块级 LaTeX，渲染成居中的公式图；失败时回退到代码块 */
    data class Math(val latex: String) : MdBlock
    object Rule : MdBlock
}

private val BULLET = Regex("^([-*+])\\s+(.*)$")
private val ORDERED = Regex("^(\\d{1,2})[.)]\\s+(.*)$")
private val RULE = Regex("^([-*_])\\1{2,}$")
private val HEADING = Regex("^(#{1,6})(?:\\s+(.*))?$")
private val TABLE_CELL = Regex("^:?-{3,}:?$")

private val INLINE = Regex(
    // 粗斜体要放在粗体前面，否则 ***x*** 会被拆成 *斜体* 和多余星号
    "\\*\\*\\*([^*\\n]+?)\\*\\*\\*" +
        "|\\*\\*([^*\\n]+?)\\*\\*" +
        "|__([^_\\n]+?)__" +
        "|~~([^~\\n]+?)~~" +
        "|`([^`\\n]+?)`" +
        "|\\[([^\\]\\n]+?)\\]\\(([^)\\s]+?)\\)" +
        "|\\*([^*\\n]+?)\\*" +
        "|(https?://[^\\s<)）]+)" +
        // 行内 LaTeX：\( ... \)、$$ ... $$、$ ... $，以及裸露的 \命令{...}
        """|\\\(([^\n]+?)\\\)""" +
        """|\$\$([^$]+?)\$\$""" +
        """|\$(?!\$)([^$\n]+?)\$""" +
        """|\\([a-zA-Z]+(?:\{[^{}]*\})*)"""
)

private fun splitTableRow(line: String): List<String> =
    line.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

private fun isTableSeparator(line: String): Boolean {
    val trimmed = line.trim()
    if (!trimmed.contains('|')) return false
    val cells = splitTableRow(trimmed)
    return cells.isNotEmpty() && cells.all { TABLE_CELL.matches(it) }
}

private fun isTableStart(lines: List<String>, index: Int): Boolean =
    index + 1 < lines.size &&
        lines[index].contains('|') &&
        isTableSeparator(lines[index + 1])

private fun normalizeRow(cells: List<String>, columns: Int): List<String> =
    List(columns) { index -> cells.getOrElse(index) { "" } }

/** 把 Markdown 源码切成块级结构；支持段落/标题/代码块/列表（含嵌套）/引用/表格/分隔线。 */
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
        val mathBlock = parseMathBlock(lines, index)

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

            // 块级 LaTeX：渲染成居中的公式图，失败时渲染器里回退成代码块
            mathBlock != null -> {
                flushParagraph()
                blocks.add(MdBlock.Math(mathBlock.latex))
                index = mathBlock.endIndex
            }

            isTableStart(lines, index) -> {
                flushParagraph()
                val header = splitTableRow(trimmed)
                val rows = ArrayList<List<String>>()
                var rowIndex = index + 2
                while (rowIndex < lines.size) {
                    val rowLine = lines[rowIndex].trim()
                    if (rowLine.isEmpty() || !rowLine.contains('|') || isTableSeparator(rowLine)) break
                    rows.add(normalizeRow(splitTableRow(rowLine), header.size))
                    rowIndex++
                }
                blocks.add(MdBlock.Table(normalizeRow(header, header.size), rows))
                index = rowIndex - 1
            }

            RULE.matches(trimmed) -> {
                flushParagraph()
                blocks.add(MdBlock.Rule)
            }

            HEADING.matches(trimmed) -> {
                flushParagraph()
                val match = HEADING.find(trimmed)
                val level = match?.groupValues?.get(1)?.length ?: 1
                val content = match?.groupValues?.get(2)?.trim().orEmpty()
                if (content.isEmpty()) {
                    paragraph.append(trimmed)
                } else {
                    blocks.add(MdBlock.Heading(level, content))
                }
            }

            trimmed.startsWith(">") -> {
                flushParagraph()
                val quote = StringBuilder()
                while (index < lines.size && lines[index].trim().startsWith(">")) {
                    var content = lines[index].trim()
                    while (content.startsWith(">")) {
                        content = content.removePrefix(">").trim()
                    }
                    if (quote.isNotEmpty()) quote.append('\n')
                    quote.append(content)
                    index++
                }
                blocks.add(MdBlock.Quote(quote.toString().trim()))
                index--
            }

            BULLET.matches(trimmed) -> {
                flushParagraph()
                val match = BULLET.find(trimmed)
                if (match != null) {
                    blocks.add(MdBlock.ListItem("•", match.groupValues[2], listLevel(line)))
                }
            }

            ORDERED.matches(trimmed) -> {
                flushParagraph()
                val match = ORDERED.find(trimmed)
                if (match != null) {
                    blocks.add(MdBlock.ListItem(match.groupValues[1] + ".", match.groupValues[2], listLevel(line)))
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

/** 按行首缩进推算列表层级（2 空格一级，最多 3 级） */
private fun listLevel(line: String): Int {
    val indent = line.length - line.trimStart().length
    return (indent / 2).coerceIn(0, 3)
}

// ------------------------------------------------------------------ LaTeX 兜底

private class MathBlock(val latex: String, val endIndex: Int)

private val BEGIN_ENV = Regex("""\\begin\{([^}]*)\}""")

/**
 * 识别块级 LaTeX：$$...$$、\[...\]、\begin{env}...\end{env}。
 * 返回公式源码（不含定界符）与结束行号；不是公式块时返回 null。
 */
private fun parseMathBlock(lines: List<String>, start: Int): MathBlock? {
    val first = lines[start].trim()
    // \begin{env} ... \end{env}：整段保留（JLaTeXMath 需要环境名才能排版矩阵）
    if (first.startsWith("\\begin{")) {
        val env = BEGIN_ENV.find(first)?.groupValues?.get(1).orEmpty()
        if (env.isEmpty()) return null
        val close = "\\end{$env}"
        val sb = StringBuilder()
        var i = start
        while (i < lines.size) {
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(lines[i].trimEnd())
            if (lines[i].contains(close)) return MathBlock(sb.toString().trim(), i)
            i++
        }
        return MathBlock(sb.toString().trim(), lines.size - 1)
    }

    val open: String
    val close: String
    when {
        first.startsWith("$$") -> {
            open = "$$"
            close = "$$"
        }
        first.startsWith("\\[") -> {
            open = "\\["
            close = "\\]"
        }
        else -> return null
    }

    val sb = StringBuilder()
    val remainder = first.removePrefix(open)
    if (remainder.contains(close)) {
        val latex = remainder.substringBefore(close).trim()
        return if (latex.isBlank()) null else MathBlock(latex, start)
    }
    if (remainder.isNotBlank()) sb.append(remainder)

    var i = start + 1
    while (i < lines.size) {
        val current = lines[i]
        if (current.contains(close)) {
            val before = current.substringBefore(close)
            if (before.isNotBlank()) {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(before)
            }
            val latex = sb.toString().trim()
            return if (latex.isBlank()) null else MathBlock(latex, i)
        }
        if (sb.isNotEmpty()) sb.append('\n')
        sb.append(current)
        i++
    }

    // 没有闭合（流式截断）：剩下的都按公式处理
    val latex = sb.toString().trim()
    return if (latex.isBlank()) null else MathBlock(latex, lines.size - 1)
}

private val FRAC = Regex("""\\(?:d|t)?frac\{([^{}]*)\}\{([^{}]*)\}""")
private val SQRT = Regex("""\\sqrt\{([^{}]*)\}""")
private val TEXT_COMMAND = Regex("""\\(?:text|mathrm|mathbf|mathit|operatorname|overline|vec|hat|bar|tilde)\{([^{}]*)\}""")
private val THIN_SPACE = Regex("""\\[,;:! ]""")
private val COMMAND = Regex("""\\([a-zA-Z]+)""")
private val SCRIPT = Regex("""([\^_])\{([^{}]*)\}""")
private val START_ENV = Regex("""\\begin\{[^}]*\}""")
private val END_ENV = Regex("""\\end\{[^}]*\}""")
private val DOUBLE_BACKSLASH = Regex("""\\\\(\s*\[[^\]]*\])?""")

private val LATEX_COMMANDS = mapOf(
    "times" to "×", "div" to "÷", "cdot" to "·", "pm" to "±", "mp" to "∓",
    "approx" to "≈", "neq" to "≠", "ne" to "≠", "le" to "≤", "leq" to "≤",
    "ge" to "≥", "geq" to "≥", "ll" to "≪", "gg" to "≫",
    "sum" to "∑", "prod" to "∏", "int" to "∫", "infty" to "∞",
    "pi" to "π", "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ",
    "theta" to "θ", "lambda" to "λ", "mu" to "μ", "sigma" to "σ", "phi" to "φ",
    "omega" to "ω", "partial" to "∂", "nabla" to "∇",
    "rightarrow" to "→", "to" to "→", "leftarrow" to "←", "Rightarrow" to "⇒",
    "ldots" to "…", "cdots" to "⋯", "quad" to " ", "qquad" to "  ",
    "left" to "", "right" to "", "displaystyle" to "", "textstyle" to "",
    "sin" to "sin", "cos" to "cos", "tan" to "tan", "log" to "log", "ln" to "ln", "det" to "det"
)

/** 行内 LaTeX → 普通文本：去掉 \left/\right 等命令，\frac{a}{b} → a/b，\text{..} 保留内容 */
fun latexToPlain(source: String): String {
    var text = source
    text = FRAC.replace(text) { match -> "${match.groupValues[1]}/${match.groupValues[2]}" }
    text = SQRT.replace(text) { match -> "√(${match.groupValues[1]})" }
    text = TEXT_COMMAND.replace(text) { match -> match.groupValues[1] }
    text = THIN_SPACE.replace(text, " ")
    text = COMMAND.replace(text) { match -> LATEX_COMMANDS[match.groupValues[1]] ?: match.groupValues[1] }
    text = SCRIPT.replace(text) { match -> match.groupValues[1] + match.groupValues[2] }
    text = text.replace("{", "").replace("}", "")
    return text.trim()
}

/**
 * 块级 LaTeX 内容 → 等宽代码块文本：
 * 去掉 \begin/\end，\\\\ 变成真实换行，& 变成两个空格，其它命令走 latexToPlain。
 */
fun latexBlockToCode(source: String): String {
    var text = source.replace("\r\n", "\n")
    text = START_ENV.replace(text, "\n")
    text = END_ENV.replace(text, "\n")
    text = DOUBLE_BACKSLASH.replace(text, "\n")
    // 列分隔：去掉 & 两侧原有空格，统一成两个空格，等宽字体下大致对齐
    text = text.replace(Regex("""\s*&\s*"""), "  ")
    val rows = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    return rows.joinToString("\n") { latexToPlain(it).trim() }
}

private fun buildInline(
    raw: String,
    accent: Color,
    codeBackground: Color,
    codeColor: Color,
    mono: FontFamily,
    /** 行内公式渲染器：返回占位符 id，返回 null 表示渲染失败、走纯文本兜底 */
    mathResolver: ((String) -> String?)? = null
): AnnotatedString = buildAnnotatedString {
    fun appendMath(latex: String) {
        val id = mathResolver?.invoke(latex)
        val fallback = latexToPlain(latex)
        if (id != null) appendInlineContent(id, fallback) else append(fallback)
    }

    var cursor = 0
    for (match in INLINE.findAll(raw)) {
        if (match.range.first > cursor) {
            append(raw.substring(cursor, match.range.first))
        }
        val groups = match.groupValues
        when {
            groups[1].isNotEmpty() -> {
                pushStyle(
                    SpanStyle(
                        fontWeight = FontWeight.SemiBold,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                    )
                )
                append(groups[1])
                pop()
            }
            groups[2].isNotEmpty() -> {
                pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold))
                append(groups[2])
                pop()
            }
            groups[3].isNotEmpty() -> {
                pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold))
                append(groups[3])
                pop()
            }
            groups[4].isNotEmpty() -> {
                pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                append(groups[4])
                pop()
            }
            groups[5].isNotEmpty() -> {
                pushStyle(SpanStyle(fontFamily = mono, background = codeBackground, color = codeColor))
                append(groups[5])
                pop()
            }
            groups[6].isNotEmpty() -> {
                pushStyle(SpanStyle(color = accent, textDecoration = TextDecoration.Underline))
                append(groups[6])
                pop()
            }
            groups[8].isNotEmpty() -> {
                pushStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic))
                append(groups[8])
                pop()
            }
            groups[9].isNotEmpty() -> {
                pushStyle(SpanStyle(color = accent, textDecoration = TextDecoration.Underline))
                append(groups[9])
                pop()
            }
            // \( ... \)：渲染成行内公式图，失败回退纯文本
            groups[10].isNotEmpty() -> appendMath(groups[10])
            // $$ ... $$（段落中间出现的块级写法）
            groups[11].isNotEmpty() -> appendMath(groups[11])
            // $ ... $：像公式才处理，货币（$5 and $10）原样保留
            groups[12].isNotEmpty() -> {
                val content = groups[12]
                val looksLikeMath = content.contains('\\') || content.contains('^') ||
                    content.contains('_') || content.contains('=')
                if (looksLikeMath) appendMath(content) else append(match.value)
            }
            // 裸露的 \text{...}、\frac{a}{b}、\alpha 等也转成可读文本
            groups[13].isNotEmpty() -> append(latexToPlain(match.value))
            else -> append(match.value)
        }
        cursor = match.range.last + 1
    }
    if (cursor < raw.length) append(raw.substring(cursor))
}

private class InlineRendered(
    val text: AnnotatedString,
    val inlineContent: Map<String, InlineTextContent>
)

@Composable
private fun renderInline(raw: String, style: TextStyle): InlineRendered {
    val context = LocalContext.current
    val density = LocalDensity.current
    val accent = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    val codeColor = MaterialTheme.colorScheme.onSurfaceVariant
    val mathColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val fontSize = style.fontSize.takeOrElse { 16.sp }
    val sizePx = with(density) { fontSize.toPx() }
    return remember(raw, accent, codeBackground, codeColor, mathColor, sizePx) {
        val contents = LinkedHashMap<String, InlineTextContent>()
        var counter = 0
        val text = buildInline(raw, accent, codeBackground, codeColor, FontFamily.Monospace) { latex ->
            val bitmap = MathRenderer.render(context, latex, sizePx, mathColor)
                ?: return@buildInline null
            val id = "math${counter++}"
            val width = with(density) { bitmap.width.toSp() }
            val height = with(density) { bitmap.height.toSp() }
            contents[id] = InlineTextContent(
                Placeholder(width, height, PlaceholderVerticalAlign.TextCenter)
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = latex,
                    modifier = Modifier.fillMaxSize()
                )
            }
            id
        }
        InlineRendered(text, contents)
    }
}

@Composable
private fun MdInlineText(
    raw: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip
) {
    val rendered = renderInline(raw, style)
    Text(
        text = rendered.text,
        style = style,
        color = color,
        modifier = modifier,
        maxLines = maxLines,
        overflow = overflow,
        inlineContent = rendered.inlineContent
    )
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
                is MdBlock.Paragraph -> MdInlineText(
                    raw = block.raw,
                    style = style,
                    color = onSurface
                )

                is MdBlock.Heading -> MdInlineText(
                    raw = block.raw,
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

                is MdBlock.Math -> MathBlockView(block.latex)

                is MdBlock.ListItem -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 嵌套列表按层级缩进
                        .padding(start = (block.level * 16).dp)
                ) {
                    Text(
                        text = block.marker,
                        style = style,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(20.dp)
                    )
                    MdInlineText(
                        raw = block.raw,
                        style = style,
                        color = onSurface,
                        modifier = Modifier.weight(1f)
                    )
                }

                is MdBlock.Table -> TableBlock(block)

                is MdBlock.Quote -> Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    MdInlineText(
                        raw = block.raw,
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

/** 块级公式：居中显示；渲染失败回退到等宽代码块，不能空白 */
@Composable
private fun MathBlockView(latex: String) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val color = MaterialTheme.colorScheme.onSurface.toArgb()
    val sizePx = with(density) { 18.sp.toPx() }
    val bitmap = remember(latex, sizePx, color) {
        MathRenderer.render(context, latex, sizePx, color)
    }
    if (bitmap == null) {
        CodeBlock("math", latexBlockToCode(latex))
        return
    }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        val width = with(density) { bitmap.width.toDp() }
        val height = with(density) { bitmap.height.toDp() }
        val scale = if (width > maxWidth) maxWidth.value / width.value else 1f
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = latex,
            modifier = Modifier
                .width(width * scale)
                .height(height * scale)
        )
    }
}

@Composable
private fun TableBlock(table: MdBlock.Table) {
    val border = MaterialTheme.colorScheme.outlineVariant
    val headerBackground = MaterialTheme.colorScheme.surfaceVariant
    val columns = table.header.size.coerceAtLeast(1)
    // 列数多时改成横向滚动 + 固定列宽：否则每列被挤成几十 dp，长单词会一个字一行
    val wide = columns >= 5
    val cellWidth = 116.dp
    val totalWidth = cellWidth * columns
    val horizontalScroll = rememberScrollState()
    Surface(
        color = Color.Transparent,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, border),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = if (wide) {
                Modifier.horizontalScroll(horizontalScroll)
            } else {
                Modifier.fillMaxWidth()
            }
        ) {
            TableRow(
                cells = table.header,
                header = true,
                headerBackground = headerBackground,
                columns = columns,
                wide = wide,
                cellWidth = cellWidth,
                totalWidth = totalWidth
            )
            for (row in table.rows) {
                HorizontalDivider(color = border)
                TableRow(
                    cells = row,
                    header = false,
                    headerBackground = headerBackground,
                    columns = columns,
                    wide = wide,
                    cellWidth = cellWidth,
                    totalWidth = totalWidth
                )
            }
        }
    }
}

@Composable
private fun TableRow(
    cells: List<String>,
    header: Boolean,
    headerBackground: Color,
    columns: Int,
    wide: Boolean,
    cellWidth: Dp,
    totalWidth: Dp
) {
    Row(
        modifier = (if (wide) Modifier.width(totalWidth) else Modifier.fillMaxWidth())
            .background(if (header) headerBackground else Color.Transparent)
    ) {
        for (index in 0 until columns) {
            MdInlineText(
                raw = cells.getOrElse(index) { "" },
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal
                ),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = (if (wide) Modifier.width(cellWidth) else Modifier.weight(1f))
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            )
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
