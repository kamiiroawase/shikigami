package com.github.shikigami.model

import eu.vendeli.tgbot.types.User
import eu.vendeli.tgbot.types.chat.Chat

data class ParsedMessage(
    val chat: Chat,
    val command: String,
    val files: List<FileRef>,
    val from: User,
    val messageId: Long,
    val originText: String?,
    val replyToBotSelf: Boolean,
    val replyToFiles: List<FileRef>,
    val replyToMessageText: String?,
    val text: String?,
)
