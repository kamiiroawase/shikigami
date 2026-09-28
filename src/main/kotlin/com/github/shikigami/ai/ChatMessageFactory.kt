package com.github.shikigami.ai

import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.chat.ImagePart
import com.aallam.openai.api.chat.TextPart
import com.github.shikigami.model.ParsedMessage

object ChatMessageFactory {
    fun build(
        currentImageBase64: String?,
        replyImageBase64: String?,
        message: ParsedMessage,
        systemPrompt: String?,
    ): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()

        if (!systemPrompt.isNullOrBlank()) {
            messages.add(ChatMessage(role = ChatRole.System, content = systemPrompt))
        }

        if (replyImageBase64 != null || !message.replyToMessageText.isNullOrBlank()) {
            val role = if (message.replyToBotSelf) ChatRole.Assistant else ChatRole.User

            messages.add(
                if (replyImageBase64 != null) {
                    ChatMessage(
                        role = role,
                        content =
                            buildList {
                                add(ImagePart(replyImageBase64))
                                if (!message.replyToMessageText.isNullOrBlank()) {
                                    add(TextPart(message.replyToMessageText))
                                }
                            },
                    )
                } else {
                    ChatMessage(role = role, content = message.replyToMessageText)
                },
            )
        }

        val currentMessage =
            if (currentImageBase64 != null) {
                ChatMessage(
                    role = ChatRole.User,
                    content =
                        buildList {
                            add(ImagePart(currentImageBase64))
                            if (!message.text.isNullOrBlank()) {
                                add(TextPart(message.text))
                            }
                        },
                )
            } else {
                message.text
                    ?.takeIf { it.isNotBlank() }
                    ?.let { ChatMessage(role = ChatRole.User, content = it) }
            }

        currentMessage?.let(messages::add)

        return messages
    }
}
