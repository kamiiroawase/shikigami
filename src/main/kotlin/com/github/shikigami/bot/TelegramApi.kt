package com.github.shikigami.bot

import com.github.shikigami.model.FileRef
import eu.vendeli.tgbot.TelegramBot
import eu.vendeli.tgbot.api.media.getFile
import eu.vendeli.tgbot.api.message.editText
import eu.vendeli.tgbot.api.message.message
import eu.vendeli.tgbot.types.common.ReplyParameters
import eu.vendeli.tgbot.types.component.ParseMode
import eu.vendeli.tgbot.types.component.getOrNull
import eu.vendeli.tgbot.types.msg.Message
import kotlinx.coroutines.CancellationException
import java.util.Base64

object TelegramApi {
    internal const val MAX_MESSAGE_LENGTH = 4096

    suspend fun sendMessageWithRetry(
        bot: TelegramBot,
        chatId: Long,
        relayText: String,
        callback: ((Message?) -> Unit)? = null,
        replyParams: ReplyParameters? = null,
        times: Int = 0,
    ) {
        val text = truncate(relayText, MAX_MESSAGE_LENGTH)

        val action =
            message(text).apply {
                if (replyParams != null) {
                    options { replyParameters = replyParams }
                }
            }

        val sent = action.sendReturning(chatId, bot).getOrNull()

        when {
            sent != null -> callback?.invoke(sent)
            times < 3 -> sendMessageWithRetry(bot, chatId, text, callback, replyParams, times + 1)
            else -> callback?.invoke(null)
        }
    }

    suspend fun editMessageWithRetry(
        bot: TelegramBot,
        chatId: Long,
        content: String,
        messageId: Long,
        fallbackContent: String,
        times: Int = 0,
    ) {
        val edited =
            editText(messageId) { content }
                .options { parseMode = ParseMode.MarkdownV2 }
                .sendReturning(chatId, bot)
                .getOrNull() != null

        when {
            edited -> Unit
            times < 3 -> editMessageWithRetry(bot, chatId, content, messageId, fallbackContent, times + 1)
            else -> editText(messageId) { truncate(fallbackContent, MAX_MESSAGE_LENGTH) }.sendReturning(chatId, bot).getOrNull()
        }
    }

    internal fun truncate(
        text: String,
        maxLength: Int,
    ): String {
        if (text.length <= maxLength) {
            return text
        }

        var end = maxLength - 1
        if (end > 0 && Character.isHighSurrogate(text[end - 1])) {
            end--
        }

        return text.take(end) + "…"
    }

    suspend fun getFileBase64(
        bot: TelegramBot,
        file: FileRef,
        maxTimes: Int = 3,
        times: Int = 0,
    ): String? {
        if (times < maxTimes) {
            return try {
                val remote = getFile(file.fileId).sendReturning(bot).getOrNull()
                val bytes = remote?.let { bot.getFileContent(it) }

                if (bytes != null) {
                    "data:image/${file.mimeType};base64," +
                        Base64.getEncoder().encodeToString(bytes)
                } else {
                    getFileBase64(bot, file, maxTimes, times + 1)
                }
            } catch (_: CancellationException) {
                return null
            } catch (_: Exception) {
                getFileBase64(bot, file, maxTimes, times + 1)
            }
        }

        return null
    }
}
