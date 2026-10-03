package com.github.shikigami

import com.github.shikigami.bot.TelegramApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TelegramApiTest {
    @Test
    fun unescapesMarkdownV2SpecialCharacters() {
        assertEquals(
            "a.b (c) [d] *e* f! 你好~世界~ #h +i -j =k |l {m} n> o` p",
            TelegramApi.unescapeMarkdownV2(
                """a\.b \(c\) \[d\] \*e\* f\! 你好\~世界\~ \#h \+i \-j \=k \|l \{m\} n\> o\` p""",
            ),
        )
    }

    @Test
    fun keepsBackslashesBeforeNonSpecialCharacters() {
        assertEquals("""a\b c\d""", TelegramApi.unescapeMarkdownV2("""a\b c\d"""))
    }

    @Test
    fun unescapesEscapedBackslash() {
        assertEquals("""a\b""", TelegramApi.unescapeMarkdownV2("""a\\b"""))
    }

    @Test
    fun leavesPlainTextUnchanged() {
        assertEquals("普通文本没有转义", TelegramApi.unescapeMarkdownV2("普通文本没有转义"))
    }

    @Test
    fun truncateReturnsShortTextUnchanged() {
        assertEquals("hello", TelegramApi.truncate("hello", 4096))
    }

    @Test
    fun truncateAppendsEllipsisAtLimit() {
        val truncated = TelegramApi.truncate("a".repeat(5000), 4096)

        assertEquals(4096, truncated.length)
        assertEquals("…", truncated.takeLast(1))
        assertTrue(truncated.dropLast(1).all { it == 'a' })
    }

    @Test
    fun truncateAvoidsSplittingSurrogatePair() {
        val text = "a".repeat(4094) + "😀" + "b".repeat(100)
        val truncated = TelegramApi.truncate(text, 4096)

        assertEquals(4095, truncated.length)
        assertEquals("a…", truncated.takeLast(2))
    }

    @Test
    fun truncateKeepsSurrogatePairIntactWhenBoundaryIsSafe() {
        val text = "😀" + "a".repeat(5000)
        val truncated = TelegramApi.truncate(text, 4096)

        assertEquals(4096, truncated.length)
        assertEquals("a…", truncated.takeLast(2))
    }
}
