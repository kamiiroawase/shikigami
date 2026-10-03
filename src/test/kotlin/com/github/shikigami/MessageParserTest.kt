package com.github.shikigami

import com.github.shikigami.bot.MessageParser
import com.github.shikigami.model.BotConfig
import com.github.shikigami.model.FileRef
import eu.vendeli.tgbot.types.User
import eu.vendeli.tgbot.types.chat.Chat
import eu.vendeli.tgbot.types.chat.ChatType
import eu.vendeli.tgbot.types.media.PhotoSize
import eu.vendeli.tgbot.types.media.Sticker
import eu.vendeli.tgbot.types.media.StickerType
import eu.vendeli.tgbot.types.msg.EntityType
import eu.vendeli.tgbot.types.msg.Message
import eu.vendeli.tgbot.types.msg.MessageEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class MessageParserTest {
    private companion object {
        const val BOT_USERNAME = "mybot"
        const val WHITELISTED_GROUP_ID = -1001L
        const val PRIVATE_CHAT_ID = 42L
    }

    private fun config(allowedChatIds: List<Long> = listOf(WHITELISTED_GROUP_ID)) =
        BotConfig(
            adminChatId = 1L,
            adminMessageText = "report",
            allowedChatIds = allowedChatIds,
            commandStartText = "start",
            errorEmptyText = "empty",
            errorFileText = "file",
            errorMessageText = "error",
            errorUnknownText = "unknown",
            mmjPrompt = "prompt",
            placeholderText = "placeholder",
            proxy = null,
            rateLimitText = "rate",
            token = "token",
            username = BOT_USERNAME,
        )

    private fun user(
        id: Long = 100L,
        isBot: Boolean = false,
        username: String? = null,
    ) = User(id = id, isBot = isBot, firstName = "User", username = username)

    private fun chatOf(
        id: Long = PRIVATE_CHAT_ID,
        type: ChatType = ChatType.Private,
    ) = Chat(id = id, type = type)

    private fun commandEntity(
        offset: Int = 0,
        length: Int,
    ) = MessageEntity(type = EntityType.BotCommand, offset = offset, length = length)

    private fun photo(fileId: String) = PhotoSize(fileId = fileId, fileUniqueId = fileId, width = 100, height = 100)

    private fun sticker(
        isAnimated: Boolean = false,
        isVideo: Boolean = false,
    ) = Sticker(
        fileId = "sticker-file",
        fileUniqueId = "sticker-unique",
        type = StickerType.Regular,
        width = 512,
        height = 512,
        isAnimated = isAnimated,
        isVideo = isVideo,
    )

    private fun message(
        chat: Chat = chatOf(),
        from: User? = user(),
        text: String? = null,
        entities: List<MessageEntity>? = null,
        caption: String? = null,
        captionEntities: List<MessageEntity>? = null,
        photo: List<PhotoSize>? = null,
        sticker: Sticker? = null,
        replyToMessage: Message? = null,
        senderChat: Chat? = null,
    ) = Message(
        messageId = 1L,
        date = Instant.fromEpochSeconds(0),
        chat = chat,
        from = from,
        senderChat = senderChat,
        text = text,
        entities = entities,
        caption = caption,
        captionEntities = captionEntities,
        photo = photo,
        sticker = sticker,
        replyToMessage = replyToMessage,
    )

    private fun commandMessage(
        command: String,
        arguments: String = "",
        chat: Chat = chatOf(),
        from: User? = user(),
        replyToMessage: Message? = null,
    ) = message(
        chat = chat,
        from = from,
        text = "$command$arguments",
        entities = listOf(commandEntity(length = command.length)),
        replyToMessage = replyToMessage,
    )

    @Test
    fun parsesCommandWithArgumentsInPrivateChat() {
        val parsed = MessageParser.parse(commandMessage("/native", " 你好"), config())!!

        assertEquals("native", parsed.command)
        assertEquals(" 你好", parsed.text)
        assertEquals(PRIVATE_CHAT_ID, parsed.chat.id)
        assertEquals(emptyList(), parsed.files)
        assertEquals(emptyList(), parsed.replyToFiles)
        assertNull(parsed.replyToMessageText)
        assertFalse(parsed.replyToBotSelf)
    }

    @Test
    fun parsesBareCommandWithNullText() {
        val parsed = MessageParser.parse(commandMessage("/start"), config())!!

        assertEquals("start", parsed.command)
        assertNull(parsed.text)
    }

    @Test
    fun returnsNullWhenFromIsMissing() {
        assertNull(MessageParser.parse(commandMessage("/native", from = null), config()))
    }

    @Test
    fun returnsNullWithoutTextOrCaption() {
        assertNull(MessageParser.parse(message(text = null, entities = null), config()))
    }

    @Test
    fun returnsNullWithoutCommandEntity() {
        val plain =
            message(
                text = "hello there",
                entities = listOf(MessageEntity(type = EntityType.Mention, offset = 0, length = 5)),
            )

        assertNull(MessageParser.parse(plain, config()))
    }

    @Test
    fun returnsNullWhenCommandIsNotAtMessageStart() {
        val mentionOnly =
            message(
                text = "你好 /native 写首诗",
                entities = listOf(commandEntity(offset = 3, length = "/native".length)),
            )

        assertNull(MessageParser.parse(mentionOnly, config()))
    }

    @Test
    fun returnsNullWhenCommandIsNotAtCaptionStart() {
        val photoMessage =
            message(
                caption = "看图 /native",
                captionEntities = listOf(commandEntity(offset = 3, length = "/native".length)),
                photo = listOf(photo("photo-file")),
            )

        assertNull(MessageParser.parse(photoMessage, config()))
    }

    @Test
    fun ignoresCommandTargetingOtherBot() {
        assertNull(MessageParser.parse(commandMessage("/native@otherbot"), config()))
    }

    @Test
    fun parsesCommandTargetingSelfBot() {
        val parsed = MessageParser.parse(commandMessage("/native@$BOT_USERNAME"), config())!!

        assertEquals("native", parsed.command)
    }

    @Test
    fun botUsernameMatchIsCaseInsensitive() {
        val parsed = MessageParser.parse(commandMessage("/native@MyBot"), config())!!

        assertEquals("native", parsed.command)
    }

    @Test
    fun parsesCommandInWhitelistedGroup() {
        val groupChat = chatOf(id = WHITELISTED_GROUP_ID, type = ChatType.Group)

        assertEquals("native", MessageParser.parse(commandMessage("/native", chat = groupChat), config())!!.command)
    }

    @Test
    fun parsesCommandInWhitelistedSupergroup() {
        val supergroupChat = chatOf(id = WHITELISTED_GROUP_ID, type = ChatType.Supergroup)

        assertEquals("native", MessageParser.parse(commandMessage("/native", chat = supergroupChat), config())!!.command)
    }

    @Test
    fun parsesInPrivateChatOutsideWhitelist() {
        val strangerChat = chatOf(id = 777L)

        assertEquals("native", MessageParser.parse(commandMessage("/native", chat = strangerChat), config())!!.command)
    }

    @Test
    fun returnsNullForGroupOutsideWhitelist() {
        val groupChat = chatOf(id = -9999L, type = ChatType.Group)

        assertNull(MessageParser.parse(commandMessage("/native", chat = groupChat), config()))
    }

    @Test
    fun returnsNullForSupergroupOutsideWhitelist() {
        val supergroupChat = chatOf(id = -9999L, type = ChatType.Supergroup)

        assertNull(MessageParser.parse(commandMessage("/native", chat = supergroupChat), config()))
    }

    @Test
    fun returnsNullForChannelOutsideWhitelist() {
        val channelChat = chatOf(id = -9999L, type = ChatType.Channel)

        assertNull(MessageParser.parse(commandMessage("/native", chat = channelChat), config()))
    }

    @Test
    fun returnsNullForBotSenderInPrivateChat() {
        assertNull(MessageParser.parse(commandMessage("/native", from = user(isBot = true)), config()))
    }

    @Test
    fun returnsNullForBotSenderInGroupWithoutSenderChat() {
        val groupChat = chatOf(id = WHITELISTED_GROUP_ID, type = ChatType.Group)

        assertNull(MessageParser.parse(commandMessage("/native", chat = groupChat, from = user(isBot = true)), config()))
    }

    @Test
    fun parsesAnonymousGroupAdminMessage() {
        val groupChat = chatOf(id = WHITELISTED_GROUP_ID, type = ChatType.Group)
        val anonymousAdmin =
            message(
                chat = groupChat,
                from = user(id = 1087968824L, isBot = true),
                text = "/native hi",
                entities = listOf(commandEntity(length = "/native".length)),
                senderChat = groupChat,
            )

        assertEquals("native", MessageParser.parse(anonymousAdmin, config())!!.command)
    }

    @Test
    fun returnsNullWhenSenderChatDiffersFromChat() {
        val groupChat = chatOf(id = WHITELISTED_GROUP_ID, type = ChatType.Group)
        val onBehalfOfChannel =
            message(
                chat = groupChat,
                from = user(isBot = true),
                text = "/native hi",
                entities = listOf(commandEntity(length = "/native".length)),
                senderChat = chatOf(id = -2002L, type = ChatType.Channel),
            )

        assertNull(MessageParser.parse(onBehalfOfChannel, config()))
    }

    @Test
    fun parsesPhotoWithCaptionCommand() {
        val photoMessage =
            message(
                caption = "/native2 这张图",
                captionEntities = listOf(commandEntity(length = "/native2".length)),
                photo = listOf(photo("photo-small"), photo("photo-large")),
            )

        val parsed = MessageParser.parse(photoMessage, config())!!

        assertEquals("native2", parsed.command)
        assertEquals(" 这张图", parsed.text)
        assertEquals(listOf(FileRef("jpeg", "photo-large")), parsed.files)
    }

    @Test
    fun extractsReplyToMessageText() {
        val reply = message(text = "前情提要")
        val parsed = MessageParser.parse(commandMessage("/native", " 继续", replyToMessage = reply), config())!!

        assertEquals("前情提要", parsed.replyToMessageText)
        assertFalse(parsed.replyToBotSelf)
    }

    @Test
    fun detectsReplyToBotSelfByUsername() {
        val reply = message(from = user(username = BOT_USERNAME), text = "bot said")

        val parsed = MessageParser.parse(commandMessage("/native", replyToMessage = reply), config())!!

        assertTrue(parsed.replyToBotSelf)
    }

    @Test
    fun detectsReplyToBotSelfCaseInsensitively() {
        val reply = message(from = user(username = "MyBot"), text = "bot said")

        val parsed = MessageParser.parse(commandMessage("/native", replyToMessage = reply), config())!!

        assertTrue(parsed.replyToBotSelf)
    }

    @Test
    fun stripsCommandFromReplyText() {
        val reply =
            message(
                text = "/native 写诗",
                entities = listOf(commandEntity(length = "/native".length)),
            )

        val parsed = MessageParser.parse(commandMessage("/native", replyToMessage = reply), config())!!

        assertEquals(" 写诗", parsed.replyToMessageText)
    }

    @Test
    fun keepsReplyTextWhenReplyOnlyMentionsCommand() {
        val reply =
            message(
                text = "看看 /native 这个",
                entities = listOf(commandEntity(offset = 3, length = "/native".length)),
            )

        val parsed = MessageParser.parse(commandMessage("/native", replyToMessage = reply), config())!!

        assertEquals("看看 /native 这个", parsed.replyToMessageText)
    }

    @Test
    fun replyTextIsNullWhenReplyContainsOnlyCommand() {
        val reply =
            message(
                text = "/native",
                entities = listOf(commandEntity(length = "/native".length)),
            )

        val parsed = MessageParser.parse(commandMessage("/native", replyToMessage = reply), config())!!

        assertNull(parsed.replyToMessageText)
    }

    @Test
    fun extractsLargestPhotoFromReply() {
        val reply = message(photo = listOf(photo("reply-small"), photo("reply-large")))

        val parsed = MessageParser.parse(commandMessage("/native", replyToMessage = reply), config())!!

        assertEquals(listOf(FileRef("jpeg", "reply-large")), parsed.replyToFiles)
    }

    @Test
    fun extractsStaticStickerFromReply() {
        val reply = message(sticker = sticker(isAnimated = false, isVideo = false))

        val parsed = MessageParser.parse(commandMessage("/native", replyToMessage = reply), config())!!

        assertEquals(listOf(FileRef("webp", "sticker-file")), parsed.replyToFiles)
    }

    @Test
    fun ignoresAnimatedStickerInReply() {
        val reply = message(sticker = sticker(isAnimated = true))

        val parsed = MessageParser.parse(commandMessage("/native", replyToMessage = reply), config())!!

        assertEquals(emptyList(), parsed.replyToFiles)
    }

    @Test
    fun ignoresVideoStickerInReply() {
        val reply = message(sticker = sticker(isVideo = true))

        val parsed = MessageParser.parse(commandMessage("/native", replyToMessage = reply), config())!!

        assertEquals(emptyList(), parsed.replyToFiles)
    }

    @Test
    fun prefersReplyPhotoOverSticker() {
        val reply = message(photo = listOf(photo("reply-photo")), sticker = sticker())

        val parsed = MessageParser.parse(commandMessage("/native", replyToMessage = reply), config())!!

        assertEquals(listOf(FileRef("jpeg", "reply-photo")), parsed.replyToFiles)
    }
}
