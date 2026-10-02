package com.github.shikigami.bot

import com.github.shikigami.model.BotConfig
import com.github.shikigami.model.FileRef
import com.github.shikigami.model.ParsedMessage
import eu.vendeli.tgbot.types.chat.ChatType
import eu.vendeli.tgbot.types.msg.EntityType
import eu.vendeli.tgbot.types.msg.Message

object MessageParser {
    private val GROUP_TYPES = listOf(ChatType.Group, ChatType.Supergroup)

    fun parse(
        message: Message,
        config: BotConfig,
    ): ParsedMessage? {
        val from = message.from ?: return null
        val text = message.text ?: message.caption ?: return null

        val commandEntity =
            message.entities?.firstOrNull { it.type == EntityType.BotCommand }
                ?: message.captionEntities?.firstOrNull { it.type == EntityType.BotCommand }
                ?: return null

        if (from.isBot) {
            if (message.chat.type !in GROUP_TYPES) return null

            val senderChat = message.senderChat ?: return null
            if (senderChat.id != message.chat.id) return null
        }

        val commandText =
            text.substring(
                commandEntity.offset,
                commandEntity.offset + commandEntity.length,
            )

        commandText.split("@").getOrNull(1)?.let { target ->
            if (!target.equals(config.username, ignoreCase = true)) return null
        }

        if (message.chat.id !in config.allowedChatIds && message.chat.type != ChatType.Private) {
            return null
        }

        var replyToMessageText =
            message.replyToMessage
                ?.let { it.text ?: it.caption ?: "" }

        val replyToCommandEntity =
            message.replyToMessage?.entities?.firstOrNull { it.type == EntityType.BotCommand }
                ?: message.replyToMessage?.captionEntities?.firstOrNull { it.type == EntityType.BotCommand }

        if (replyToCommandEntity != null) {
            replyToMessageText =
                replyToMessageText
                    ?.drop(replyToCommandEntity.offset + replyToCommandEntity.length)
                    ?.ifEmpty { null }
        }

        return ParsedMessage(
            from = from,
            text =
                text
                    .drop(commandEntity.offset + commandEntity.length)
                    .ifEmpty { null },
            command = commandText.removePrefix("/").substringBefore("@"),
            originalText = text,
            chat = message.chat,
            messageId = message.messageId,
            replyToMessageText = replyToMessageText,
            replyToBotSelf =
                message.replyToMessage
                    ?.from
                    ?.username
                    ?.equals(config.username, ignoreCase = true) == true,
            files =
                message.photo
                    ?.lastOrNull()
                    ?.let { listOf(FileRef("jpeg", it.fileId)) }
                    ?: message.sticker
                        ?.takeIf { !it.isAnimated && !it.isVideo }
                        ?.let { listOf(FileRef("webp", it.fileId)) }
                    ?: emptyList(),
            replyToFiles =
                message.replyToMessage
                    ?.photo
                    ?.lastOrNull()
                    ?.let { listOf(FileRef("jpeg", it.fileId)) }
                    ?: message.replyToMessage
                        ?.sticker
                        ?.takeIf { !it.isAnimated && !it.isVideo }
                        ?.let { listOf(FileRef("webp", it.fileId)) }
                    ?: emptyList(),
        )
    }
}
