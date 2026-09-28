package com.github.shikigami.bot

import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.CustomBlock
import org.commonmark.node.CustomNode
import org.commonmark.node.Document
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

object MarkdownV2 {
    private const val MAX_MESSAGE_LENGTH = 4096

    private val SPECIAL_CHARS = "_*[]()~`>#+-=|{}.!\\".toSet()

    private val HTML_TAG = Regex("""<(\/?)([a-zA-Z][a-zA-Z0-9]*)([^>]*?)(\/?)>""")

    private val HTML_HREF = Regex("""href\s*=\s*(?:"([^"]*)"|'([^']*)')""")

    private val parser =
        Parser
            .builder()
            .extensions(listOf(TablesExtension.create(), StrikethroughExtension.create()))
            .build()

    fun render(content: String): String {
        val converted = convert(content)
        if (converted.length <= MAX_MESSAGE_LENGTH) {
            return converted
        }

        var low = 0
        var high = content.length
        val budget = MAX_MESSAGE_LENGTH - 1

        while (low < high) {
            var mid = (low + high + 1) / 2

            if (mid < content.length && Character.isHighSurrogate(content[mid - 1])) {
                mid--
            }

            if (convert(content.take(mid)).length <= budget) {
                low = mid
            } else {
                high = mid - 1
            }
        }

        if (low <= 0) {
            return TelegramApi.truncate(converted)
        }

        return convert(content.take(low)) + "…"
    }

    private fun convert(markdown: String): String {
        val visitor = Visitor()
        parser.parse(markdown).accept(visitor)
        return visitor.output().trim()
    }

    private fun escape(text: String): String =
        buildString(text.length + 16) {
            for (char in text) {
                if (char in SPECIAL_CHARS) {
                    append('\\')
                }
                append(char)
            }
        }

    private fun escapeCode(text: String): String = text.replace("\\", "\\\\").replace("`", "\\`")

    private fun escapeUrl(url: String): String = url.replace("\\", "\\\\").replace(")", "\\)")

    private class Visitor : AbstractVisitor() {
        private val sb = StringBuilder()

        private val openLinkUrls = ArrayDeque<String>()

        fun output(): String =
            if (openLinkUrls.isEmpty()) {
                sb.toString()
            } else {
                sb.toString().trimEnd() + openLinkUrls.joinToString("") { "](${escapeUrl(it)})" }
            }

        override fun visit(text: Text) {
            sb.append(escape(text.literal))
        }

        override fun visit(document: Document) {
            visitChildren(document)
        }

        override fun visit(paragraph: Paragraph) {
            visitChildren(paragraph)
            sb.append("\n\n")
        }

        override fun visit(heading: Heading) {
            sb.append(escape("#".repeat(heading.level))).append(' ')
            visitChildren(heading)
            sb.append("\n\n")
        }

        override fun visit(blockQuote: BlockQuote) {
            val body = renderChild(blockQuote)
            sb.append(
                body.lines().joinToString("\n") { if (it.isEmpty()) ">" else "> $it" },
            )
            sb.append("\n\n")
        }

        override fun visit(bulletList: BulletList) {
            renderList(bulletList) { "• " }
        }

        override fun visit(orderedList: OrderedList) {
            var number = orderedList.markerStartNumber ?: 1
            renderList(orderedList) { "${number++}\\. " }
        }

        override fun visit(thematicBreak: ThematicBreak) {
            sb.append(escape("———")).append("\n\n")
        }

        override fun visit(fencedCodeBlock: FencedCodeBlock) {
            val language =
                fencedCodeBlock.info
                    .orEmpty()
                    .trim()
                    .filter { it.isLetterOrDigit() || it == '-' || it == '+' }
                    .take(32)
            sb.append("```").append(language).append('\n')
            sb.append(escapeCode(fencedCodeBlock.literal.trimEnd('\n')))
            sb.append("\n```\n\n")
        }

        override fun visit(indentedCodeBlock: IndentedCodeBlock) {
            sb.append("```\n")
            sb.append(escapeCode(indentedCodeBlock.literal.trimEnd('\n')))
            sb.append("\n```\n\n")
        }

        override fun visit(code: Code) {
            sb.append('`').append(escapeCode(code.literal)).append('`')
        }

        override fun visit(emphasis: Emphasis) {
            sb.append('_')
            visitChildren(emphasis)
            sb.append('_')
        }

        override fun visit(strongEmphasis: StrongEmphasis) {
            sb.append('*')
            visitChildren(strongEmphasis)
            sb.append('*')
        }

        override fun visit(customNode: CustomNode) {
            when (customNode) {
                is Strikethrough -> {
                    sb.append('~')
                    visitChildren(customNode)
                    sb.append('~')
                }

                else -> {
                    super.visit(customNode)
                }
            }
        }

        override fun visit(customBlock: CustomBlock) {
            if (customBlock is TableBlock) {
                visitTable(customBlock)
            } else {
                super.visit(customBlock)
            }
        }

        private fun visitTable(table: TableBlock) {
            val lines = renderTableLines(table)
            if (lines.isEmpty()) {
                return
            }

            sb.append("```\n")
            sb.append(escapeCode(lines.joinToString("\n")))
            sb.append("\n```\n\n")
        }

        override fun visit(link: Link) {
            sb.append('[')
            visitChildren(link)
            sb.append("](").append(escapeUrl(link.destination)).append(')')
        }

        override fun visit(image: Image) {
            sb.append('[')
            visitChildren(image)
            sb.append("](").append(escapeUrl(image.destination)).append(')')
        }

        override fun visit(softLineBreak: SoftLineBreak) {
            sb.append('\n')
        }

        override fun visit(hardLineBreak: HardLineBreak) {
            sb.append('\n')
        }

        override fun visit(htmlBlock: HtmlBlock) {
            val literal = htmlBlock.literal.trim()
            if (literal.isEmpty() || literal.startsWith("<!--") ||
                literal.startsWith("<!") || literal.startsWith("<?")
            ) {
                return
            }

            sb.append("```\n").append(escapeCode(literal)).append("\n```\n\n")
        }

        override fun visit(htmlInline: HtmlInline) {
            sb.append(renderHtmlInline(htmlInline.literal))
        }

        private fun renderHtmlInline(literal: String): String {
            val tag = literal.trim()
            if (tag.startsWith("<!--") || tag.startsWith("<!") || tag.startsWith("<?")) {
                return ""
            }

            val match = HTML_TAG.matchEntire(tag) ?: return escape(tag)
            val closing = match.groupValues[1].isNotEmpty()
            val attrs = match.groupValues[3]

            return when (match.groupValues[2].lowercase()) {
                "br" -> {
                    "\n"
                }

                "b", "strong" -> {
                    "*"
                }

                "i", "em" -> {
                    "_"
                }

                "s", "del", "strike" -> {
                    "~"
                }

                "u", "ins" -> {
                    "__"
                }

                "code", "kbd", "samp", "tt" -> {
                    "`"
                }

                "a" -> {
                    if (closing) {
                        openLinkUrls.removeLastOrNull()?.let { "](${escapeUrl(it)})" } ?: ""
                    } else {
                        HTML_HREF
                            .find(attrs)
                            ?.groupValues
                            ?.lastOrNull { it.isNotEmpty() }
                            ?.let {
                                openLinkUrls.addLast(it)
                                "["
                            } ?: ""
                    }
                }

                else -> {
                    escape(tag)
                }
            }
        }

        private fun renderList(
            list: Node,
            marker: () -> String,
        ) {
            var item = list.firstChild
            while (item != null) {
                if (item is ListItem) {
                    val body = renderChild(item)
                    val prefix = marker()
                    val indent = " ".repeat(prefix.length)
                    val lines = body.lines()

                    sb.append(prefix).append(lines.firstOrNull().orEmpty())
                    for (i in 1 until lines.size) {
                        sb.append('\n')
                        if (lines[i].isNotEmpty()) {
                            sb.append(indent)
                        }
                        sb.append(lines[i])
                    }
                    sb.append('\n')
                }
                item = item.next
            }
            sb.append('\n')
        }

        private fun renderChild(node: Node): String {
            val visitor = Visitor()
            visitor.visitChildren(node)
            return visitor.output().trimEnd('\n')
        }

        private fun renderTableLines(table: TableBlock): List<String> {
            val headRows = mutableListOf<List<TableCell>>()
            val bodyRows = mutableListOf<List<TableCell>>()

            var block = table.firstChild
            while (block != null) {
                when (block) {
                    is TableHead -> headRows += block.tableRows()
                    is TableBody -> bodyRows += block.tableRows()
                }
                block = block.next
            }

            val rows = headRows + bodyRows
            val columnCount = rows.maxOfOrNull { it.size } ?: return emptyList()

            val texts =
                rows.map { row ->
                    (0 until columnCount).map { index ->
                        row.getOrNull(index)?.let { cellText(it) }.orEmpty()
                    }
                }
            val alignments =
                (0 until columnCount).map { index ->
                    headRows.firstOrNull()?.getOrNull(index)?.alignment
                }
            val widths =
                (0 until columnCount).map { index ->
                    maxOf(texts.maxOfOrNull { displayWidth(it[index]) } ?: 0, 3)
                }

            return buildList {
                add(tableLine(texts.first(), widths, alignments))
                add(widths.joinToString(" | ", "| ", " |") { "-".repeat(it) })
                texts.drop(1).forEach { add(tableLine(it, widths, alignments)) }
            }
        }

        private fun tableLine(
            cells: List<String>,
            widths: List<Int>,
            alignments: List<TableCell.Alignment?>,
        ): String =
            cells
                .mapIndexed { index, cell -> padCell(cell, widths[index], alignments[index]) }
                .joinToString(" | ", "| ", " |")

        private fun padCell(
            text: String,
            width: Int,
            alignment: TableCell.Alignment?,
        ): String {
            val padding = width - displayWidth(text)
            if (padding <= 0) {
                return text
            }

            return when (alignment) {
                TableCell.Alignment.RIGHT -> {
                    " ".repeat(padding) + text
                }

                TableCell.Alignment.CENTER -> {
                    val left = padding / 2
                    " ".repeat(left) + text + " ".repeat(padding - left)
                }

                else -> {
                    text + " ".repeat(padding)
                }
            }
        }

        private fun cellText(cell: TableCell): String {
            val visitor = PlainTextVisitor()
            cell.accept(visitor)
            return visitor.output().trim()
        }
    }

    private fun Node.tableRows(): List<List<TableCell>> {
        val rows = mutableListOf<List<TableCell>>()
        var row = firstChild
        while (row != null) {
            if (row is TableRow) {
                val cells = mutableListOf<TableCell>()
                var cell = row.firstChild
                while (cell != null) {
                    if (cell is TableCell) cells += cell
                    cell = cell.next
                }
                rows += cells
            }
            row = row.next
        }
        return rows
    }

    private class PlainTextVisitor : AbstractVisitor() {
        private val sb = StringBuilder()

        fun output(): String = sb.toString()

        override fun visit(text: Text) {
            sb.append(text.literal)
        }

        override fun visit(code: Code) {
            sb.append(code.literal)
        }

        override fun visit(htmlInline: HtmlInline) {
            if (!htmlInline.literal.trimStart().startsWith("<!--")) {
                sb.append(htmlInline.literal)
            }
        }

        override fun visit(softLineBreak: SoftLineBreak) {
            sb.append(' ')
        }

        override fun visit(hardLineBreak: HardLineBreak) {
            sb.append(' ')
        }
    }

    private fun displayWidth(text: String): Int {
        var width = 0
        var index = 0
        while (index < text.length) {
            width +=
                if (Character.isHighSurrogate(text[index])) {
                    index++
                    2
                } else if (isWideChar(text[index])) {
                    2
                } else {
                    1
                }
            index++
        }
        return width
    }

    private fun isWideChar(c: Char): Boolean =
        c in '\u1100'..'\u115F' ||
            c in '\u2E80'..'\u303E' ||
            c in '\u3041'..'\u33FF' ||
            c in '\u3400'..'\u4DBF' ||
            c in '\u4E00'..'\u9FFF' ||
            c in '\uA000'..'\uA4CF' ||
            c in '\uAC00'..'\uD7A3' ||
            c in '\uF900'..'\uFAFF' ||
            c in '\uFE30'..'\uFE4F' ||
            c in '\uFF00'..'\uFF60' ||
            c in '\uFFE0'..'\uFFE6'
}
