package com.github.shikigami.bot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MarkdownV2Test {
    private val maxMessageLength = 4096

    @Test
    fun shortContentRenderedWithoutTruncation() {
        assertEquals("*hello* world", MarkdownV2.render("**hello** world"))
    }

    @Test
    fun longContentTruncatedWithinLimit() {
        val content = "第一段\n\n" + "很长的内容".repeat(2000)
        val result = MarkdownV2.render(content, maxMessageLength)
        assertTrue(result.length <= maxMessageLength)
        assertTrue(result.endsWith("…"))
        assertTrue(result.startsWith("第一段"))
    }

    @Test
    fun longParagraphTruncatesWholeLines() {
        val content = "第一段\n\n" + (1..2000).joinToString("\n") { "第${it}行" }
        val result = MarkdownV2.render(content, maxMessageLength)
        assertTrue(result.length <= maxMessageLength)
        assertTrue(result.endsWith("…"))
        assertTrue(result.contains("第1行"))
        assertTrue(!result.contains("第2000行"))
    }

    @Test
    fun longCodeBlockStaysClosed() {
        val code = (1..1000).joinToString("\n") { "line $it" }
        val content = "前文\n\n```\n$code\n```"
        val result = MarkdownV2.render(content, maxMessageLength)
        assertTrue(result.length <= maxMessageLength)
        assertTrue(result.endsWith("…"))
        assertEquals(0, Regex("```").findAll(result).count() % 2)
        assertTrue(result.contains("line 1"))
        assertTrue(!result.contains("line 1000"))
    }

    @Test
    fun longListKeepsWholeItems() {
        val content = (1..2000).joinToString("\n") { "- item $it" }
        val result = MarkdownV2.render(content, maxMessageLength)
        assertTrue(result.length <= maxMessageLength)
        assertTrue(result.endsWith("…"))
        assertTrue(result.contains("• item 1"))
        assertTrue(!result.contains("item 2000"))
        result.lines().dropLast(1).forEach { line ->
            assertTrue(line.startsWith("• "), "line is not a whole item: $line")
        }
    }

    @Test
    fun longBlockQuoteTruncatesWithPrefix() {
        val content = "> " + (1..2000).joinToString("\n") { "引用段落$it" }
        val result = MarkdownV2.render(content, maxMessageLength)
        assertTrue(result.length <= maxMessageLength)
        assertTrue(result.endsWith("…"))
        assertTrue(result.contains("引用段落1"))
        result.lines().dropLast(1).forEach { line ->
            assertTrue(line.startsWith("> "), "quote prefix lost: $line")
        }
    }

    @Test
    fun longTableTruncatesAsClosedCodeBlock() {
        val header = "| 列1 | 列2 |\n| --- | --- |"
        val rows = (1..1000).joinToString("\n") { "| 单元$it | 内容$it |" }
        val result = MarkdownV2.render("$header\n$rows")
        assertTrue(result.length <= maxMessageLength)
        assertEquals(0, Regex("```").findAll(result).count() % 2)
    }

    @Test
    fun overlongSingleLineFallsBackToEscapedPlainText() {
        val content = "特殊*字符_内容" + "字".repeat(5000)
        val result = MarkdownV2.render(content, maxMessageLength)
        assertTrue(result.length <= maxMessageLength)
        assertTrue(result.endsWith("…"))
        val plain = removeEscapes(result.dropLast(1))
        assertEquals(content.take(plain.length), plain)
    }

    @Test
    fun htmlBoldAcrossLinesStaysBalanced() {
        val content = "前缀<b>加粗开始\n" + "长".repeat(5000) + "\n第二行</b>结尾"
        val result = MarkdownV2.render(content, maxMessageLength)
        assertTrue(result.length <= maxMessageLength)
        assertTrue(result.endsWith("…"))
        val body = removeEscapes(result.dropLast(1))
        assertEquals(0, body.count { it == '*' } % 2)
    }

    @Test
    fun customSmallLimitTruncatesWithinLimit() {
        val content = "**加粗** 开头\n\n" + "内容".repeat(500) + "\n\n```\ncode line\n```"
        val result = MarkdownV2.render(content, 200)
        assertTrue(result.length <= 200)
        assertTrue(result.endsWith("…"))
        assertTrue(result.startsWith("*加粗* 开头"))
    }

    @Test
    fun customLimitKeepsCodeBlockClosed() {
        val code = (1..100).joinToString("\n") { "line $it" }
        val result = MarkdownV2.render("```\n$code\n```", 300)
        assertTrue(result.length <= 300)
        assertEquals(0, Regex("```").findAll(result).count() % 2)
    }

    private fun removeEscapes(text: String): String =
        buildString {
            var index = 0
            while (index < text.length) {
                if (text[index] == '\\' && index + 1 < text.length) {
                    append(text[index + 1])
                    index += 2
                } else {
                    append(text[index])
                    index++
                }
            }
        }
}
