package com.github.shikigami.bot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
        assertTrue(result.contains("\n第2行"), "soft line breaks were glued together")
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
    fun longOrderedListKeepsMarkerSpace() {
        val content = (1..2000).joinToString("\n") { "$it. item $it" }
        val result = MarkdownV2.render(content, maxMessageLength)
        assertTrue(result.length <= maxMessageLength)
        assertTrue(result.endsWith("…"))
        assertTrue(result.contains("1\\. item 1"), "marker space lost: $result.take(50)")
        assertTrue(!result.contains("item 2000"))
        result.lines().dropLast(1).forEach { line ->
            assertTrue(
                Regex("^\\d+\\\\\\. ").containsMatchIn(line),
                "marker space lost: $line",
            )
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

    @Test
    fun fallbackTruncationDoesNotSplitEscape() {
        val content = "```kotlin\n\\user\\home\n```"
        val result = MarkdownV2.render(content, 12)
        assertTrue(result.length <= 12)
        assertTrue(result.endsWith("…"))
        assertFalse(result.dropLast(1).endsWith("\\"), "truncation split an escape: $result")
    }

    @Test
    fun specialCharactersAreEscapedAsLiterals() {
        for (char in "_*[]()~`>#+-=|{}.!\\") {
            assertEquals("a\\${char}b", MarkdownV2.render("a${char}b"), "char: $char")
        }
    }

    @Test
    fun emphasisRendersAsUnderscores() {
        assertEquals("_italic_", MarkdownV2.render("*italic*"))
    }

    @Test
    fun strikethroughRendersAsTilde() {
        assertEquals("~gone~", MarkdownV2.render("~~gone~~"))
    }

    @Test
    fun inlineCodeEscapesBackslash() {
        assertEquals("`a\\\\b`", MarkdownV2.render("`a\\b`"))
    }

    @Test
    fun linksKeepSquareBracketSyntax() {
        assertEquals("[label](https://example.com/x)", MarkdownV2.render("[label](https://example.com/x)"))
    }

    @Test
    fun linkUrlClosingParenIsEscaped() {
        assertEquals(
            "[a](https://en.wikipedia.org/wiki/Foo_(bar\\))",
            MarkdownV2.render("[a](https://en.wikipedia.org/wiki/Foo_(bar))"),
        )
    }

    @Test
    fun imagesRenderLikeLinks() {
        assertEquals("[alt](https://example.com/i.png)", MarkdownV2.render("![alt](https://example.com/i.png)"))
    }

    @Test
    fun headingsEscapeHashesAndRenderInline() {
        assertEquals("\\#\\# Title _x_", MarkdownV2.render("## Title *x*"))
    }

    @Test
    fun thematicBreakRendersAsEmDashLine() {
        assertEquals("a\n\n———\n\nb", MarkdownV2.render("a\n\n---\n\nb"))
    }

    @Test
    fun nestedBulletListIsIndented() {
        assertEquals("• a\n\n  • b", MarkdownV2.render("- a\n  - b"))
    }

    @Test
    fun orderedListStartsFromMarkerNumber() {
        assertEquals("3\\. c\n4\\. d", MarkdownV2.render("3. c\n4. d"))
    }

    @Test
    fun fencedCodeLanguageIsSanitized() {
        assertEquals(
            "```c++v12script\ncode\n```",
            MarkdownV2.render("```c++ v1.2<script>\ncode\n```"),
        )
    }

    @Test
    fun indentedCodeRendersAsFencedBlock() {
        assertEquals("```\nindented\n```", MarkdownV2.render("    indented"))
    }

    @Test
    fun tableRendersAsAlignedCodeBlock() {
        val content = "| 左 | 中 | 右 |\n| :-- | :-: | --: |\n| a | b | c |"
        assertEquals(
            "```\n| 左  | 中  |  右 |\n| --- | --- | --- |\n| a   |  b  |   c |\n```",
            MarkdownV2.render(content),
        )
    }

    @Test
    fun htmlBlockRendersAsCodeBlock() {
        assertEquals("```\n<div>\nhello\n</div>\n```", MarkdownV2.render("<div>\nhello\n</div>"))
    }

    @Test
    fun htmlCommentBlockIsDropped() {
        assertEquals("", MarkdownV2.render("<!-- note -->"))
        assertEquals("text", MarkdownV2.render("<!-- note -->\n\ntext"))
    }

    @Test
    fun htmlInlineTagsMapToMarkdownEntities() {
        val content = "a<i>it</i>b<s>del</s>c<u>u</u>d<code>k</code>e<br>f<span>x</span>"
        assertEquals("a_it_b~del~c__u__d`k`e\nf<span\\>x</span\\>", MarkdownV2.render(content))
    }

    @Test
    fun htmlAnchorMapsToLink() {
        assertEquals("[t](https://x.com/a(b\\))", MarkdownV2.render("<a href=\"https://x.com/a(b)\">t</a>"))
    }

    @Test
    fun softLineBreakRendersAsNewline() {
        assertEquals("line1\nline2", MarkdownV2.render("line1\nline2"))
    }

    @Test
    fun blockQuoteIsPrefixed() {
        assertEquals("> quote", MarkdownV2.render("> quote"))
    }

    @Test
    fun nestedBlockQuoteDoublesPrefix() {
        assertEquals("> > deep", MarkdownV2.render("> > deep"))
    }

    @Test
    fun blockQuoteKeepsPrefixOnBlankLines() {
        assertEquals("> line1\n>\n> line2", MarkdownV2.render("> line1\n>\n> line2"))
    }

    @Test
    fun emptyContentRendersEmpty() {
        assertEquals("", MarkdownV2.render(""))
        assertEquals("", MarkdownV2.render("   \n\n"))
    }

    @Test
    fun minimalLimitRendersEllipsisOnly() {
        assertEquals("…", MarkdownV2.render("abcdef", 1))
    }

    @Test
    fun longHeadingKeepsEscapedPrefixWhenTruncated() {
        val content = "# " + "标".repeat(5000)
        val result = MarkdownV2.render(content, maxMessageLength)
        assertTrue(result.length <= maxMessageLength)
        assertTrue(result.startsWith("\\# "))
        assertTrue(result.endsWith("…"))
    }

    @Test
    fun surrogatePairIsNotSplitWhenTruncated() {
        val result = MarkdownV2.render("😀".repeat(3000), 100)
        assertTrue(result.length <= 100)
        assertTrue(result.endsWith("…"))
        assertFalse(Character.isHighSurrogate(result.dropLast(1).last()), "surrogate pair was split")
    }

    @Test
    fun partialListItemKeepsMarkerPrefix() {
        val content = "- short\n- second line item\n  continued"
        val result = MarkdownV2.render(content, 15)
        assertTrue(result.length <= 15)
        assertTrue(result.endsWith("…"))
        assertEquals("• short", result.lines().first())
        assertTrue(result.lines()[1].startsWith("• "), "marker prefix lost: $result")
    }

    @Test
    fun tinyLimitTruncatesListContent() {
        assertEquals("• it…", MarkdownV2.render("- item", 5))
    }

    @Test
    fun zeroLimitThrowsIllegalArgument() {
        assertFailsWith<IllegalArgumentException> { MarkdownV2.render("x", 0) }
    }

    @Test
    fun hardLineBreakRendersAsNewline() {
        assertEquals("a\nb", MarkdownV2.render("a  \nb"))
    }

    @Test
    fun longHtmlBlockTruncatesAsClosedCodeBlock() {
        val content = "<div>\n" + "x".repeat(5000) + "\n</div>"
        val result = MarkdownV2.render(content, maxMessageLength)
        assertTrue(result.length <= maxMessageLength)
        assertTrue(result.endsWith("…"))
        assertEquals(0, Regex("```").findAll(result).count() % 2)
    }

    @Test
    fun tableCellKeepsCodeText() {
        val content = "| a |\n| --- |\n| `x` |"
        assertEquals("```\n| a   |\n| --- |\n| x   |\n```", MarkdownV2.render(content))
    }

    @Test
    fun truncatedGroupWithInlineHtmlFallsBackToPlainText() {
        val content = "a<b>bold" + "字".repeat(5000) + "<!-- hidden -->"
        val result = MarkdownV2.render(content, maxMessageLength)
        assertTrue(result.length <= maxMessageLength)
        assertTrue(result.endsWith("…"))
        val plain = removeEscapes(result.dropLast(1))
        assertTrue(plain.startsWith("a<b>"), "html tag should remain as plain text: ${plain.take(20)}")
        assertFalse(plain.contains("hidden"), "html comment should be dropped")
    }

    @Test
    fun fallbackTruncationAvoidsSplittingSurrogatePair() {
        assertEquals("```js\n…", MarkdownV2.render("```js\n😀😀😀\n```", 8))
    }

    @Test
    fun unshrinkableBlockFallsBackToEscapedTruncation() {
        assertEquals("——…", MarkdownV2.render("---\n\nx", 3))
    }

    @Test
    fun truncatedLooseListKeepsBlankLineAndIndentation() {
        val content = "- a\n\n  b\n" + (1..2000).joinToString("\n") { "- item $it" }
        val result = MarkdownV2.render(content, maxMessageLength)
        assertTrue(result.length <= maxMessageLength)
        assertTrue(result.endsWith("…"))
        val lines = result.lines()
        assertEquals("• a", lines[0])
        assertEquals("", lines[1])
        assertEquals("  b", lines[2])
    }

    @Test
    fun quoteWithFittingHeadThenHugeBodyKeepsBoth() {
        val content = "> short\n>\n> " + (1..2000).joinToString("\n") { "引用段落$it" }
        val result = MarkdownV2.render(content, maxMessageLength)
        assertTrue(result.length <= maxMessageLength)
        assertTrue(result.endsWith("…"))
        assertTrue(result.startsWith("> short\n> "), "head quote lost: ${result.take(30)}")
    }

    @Test
    fun htmlAnchorWithoutHrefRendersTextOnly() {
        assertEquals("t", MarkdownV2.render("<a>t</a>"))
    }

    @Test
    fun tableEmojiCellCountsAsWide() {
        assertEquals(
            "```\n| 😀  |\n| --- |\n| a   |\n```",
            MarkdownV2.render("| 😀 |\n| --- |\n| a |"),
        )
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
