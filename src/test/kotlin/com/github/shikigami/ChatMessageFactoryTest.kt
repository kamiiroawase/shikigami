package com.github.shikigami

import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.chat.ImagePart
import com.aallam.openai.api.chat.ListContent
import com.aallam.openai.api.chat.TextPart
import com.github.shikigami.ai.ChatMessageFactory
import com.github.shikigami.model.ParsedMessage
import eu.vendeli.tgbot.types.User
import eu.vendeli.tgbot.types.chat.Chat
import eu.vendeli.tgbot.types.chat.ChatType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatMessageFactoryTest {
    private fun parsed(
        text: String?,
        replyToMessageText: String? = null,
        replyToBotSelf: Boolean = false,
    ) = ParsedMessage(
        chat = Chat(id = 1L, type = ChatType.Private),
        command = "native",
        files = emptyList(),
        from = User(id = 100L, isBot = false, firstName = "User"),
        messageId = 1L,
        originalText = text,
        replyToBotSelf = replyToBotSelf,
        replyToFiles = emptyList(),
        replyToMessageText = replyToMessageText,
        text = text,
    )

    private fun partsOf(
        index: Int,
        messages: List<ChatMessage>,
    ) = (messages[index].messageContent as ListContent).content

    @Test
    fun buildsSingleUserMessageFromText() {
        val messages = ChatMessageFactory.build(null, null, parsed("你好"), null)

        assertEquals(1, messages.size)
        assertEquals(ChatRole.User, messages[0].role)
        assertEquals("你好", messages[0].content)
    }

    @Test
    fun blankTextYieldsNoMessages() {
        val messages = ChatMessageFactory.build(null, null, parsed("   "), null)

        assertTrue(messages.isEmpty())
    }

    @Test
    fun nullTextYieldsNoMessages() {
        val messages = ChatMessageFactory.build(null, null, parsed(null), null)

        assertTrue(messages.isEmpty())
    }

    @Test
    fun prependsSystemPrompt() {
        val messages = ChatMessageFactory.build(null, null, parsed("hi"), "system prompt")

        assertEquals(2, messages.size)
        assertEquals(ChatRole.System, messages[0].role)
        assertEquals("system prompt", messages[0].content)
        assertEquals(ChatRole.User, messages[1].role)
    }

    @Test
    fun blankSystemPromptIsIgnored() {
        val messages = ChatMessageFactory.build(null, null, parsed("hi"), "   ")

        assertEquals(1, messages.size)
        assertEquals(ChatRole.User, messages[0].role)
    }

    @Test
    fun replyTextPrecedesCurrentUserMessage() {
        val messages = ChatMessageFactory.build(null, null, parsed("now", replyToMessageText = "before"), null)

        assertEquals(2, messages.size)
        assertEquals(ChatRole.User, messages[0].role)
        assertEquals("before", messages[0].content)
        assertEquals(ChatRole.User, messages[1].role)
        assertEquals("now", messages[1].content)
    }

    @Test
    fun replyToBotSelfBecomesAssistantMessage() {
        val messages =
            ChatMessageFactory.build(
                null,
                null,
                parsed("now", replyToMessageText = "bot said", replyToBotSelf = true),
                null,
            )

        assertEquals(2, messages.size)
        assertEquals(ChatRole.Assistant, messages[0].role)
        assertEquals("bot said", messages[0].content)
    }

    @Test
    fun replyImageOnlyBuildsImagePart() {
        val messages = ChatMessageFactory.build(null, "data:image/webp;base64,AAA", parsed("hi"), null)

        assertEquals(2, messages.size)
        assertEquals(ChatRole.User, messages[0].role)
        assertEquals(listOf<ImagePart>(ImagePart("data:image/webp;base64,AAA")), partsOf(0, messages))
    }

    @Test
    fun replyImageWithTextBuildsImageThenTextParts() {
        val messages =
            ChatMessageFactory.build(
                null,
                "data:image/webp;base64,BBB",
                parsed("hi", replyToMessageText = "caption"),
                null,
            )

        assertEquals(
            listOf(ImagePart("data:image/webp;base64,BBB"), TextPart("caption")),
            partsOf(0, messages),
        )
    }

    @Test
    fun currentImageOnlyBuildsImagePart() {
        val messages = ChatMessageFactory.build("data:image/jpeg;base64,CCC", null, parsed(null), null)

        assertEquals(1, messages.size)
        assertEquals(listOf<ImagePart>(ImagePart("data:image/jpeg;base64,CCC")), partsOf(0, messages))
    }

    @Test
    fun currentImageWithTextBuildsImageThenTextParts() {
        val messages = ChatMessageFactory.build("data:image/jpeg;base64,DDD", null, parsed("hi"), null)

        assertEquals(
            listOf(ImagePart("data:image/jpeg;base64,DDD"), TextPart("hi")),
            partsOf(0, messages),
        )
    }

    @Test
    fun buildsFullConversationInOrder() {
        val messages =
            ChatMessageFactory.build(
                currentImageDataUrl = "data:current",
                replyImageDataUrl = "data:reply",
                message = parsed("hi", replyToMessageText = "before"),
                systemPrompt = "sys",
            )

        assertEquals(3, messages.size)
        assertEquals(ChatRole.System, messages[0].role)
        assertEquals("sys", messages[0].content)
        assertEquals(ChatRole.User, messages[1].role)
        assertEquals(listOf(ImagePart("data:reply"), TextPart("before")), partsOf(1, messages))
        assertEquals(listOf(ImagePart("data:current"), TextPart("hi")), partsOf(2, messages))
    }
}
