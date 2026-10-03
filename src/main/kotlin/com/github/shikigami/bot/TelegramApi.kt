package com.github.shikigami.bot

import com.github.shikigami.model.FileRef
import eu.vendeli.tgbot.TelegramBot
import eu.vendeli.tgbot.api.media.getFile
import eu.vendeli.tgbot.api.message.editText
import eu.vendeli.tgbot.api.message.message
import eu.vendeli.tgbot.types.common.ReplyParameters
import eu.vendeli.tgbot.types.component.ParseMode
import eu.vendeli.tgbot.types.component.onFailure
import eu.vendeli.tgbot.types.msg.Message
import io.github.kamiiroawase.markdownv2.MarkdownV2
import kotlinx.coroutines.CancellationException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.Base64

object TelegramApi {
    private val log: Logger = LoggerFactory.getLogger(TelegramApi::class.java)

    internal const val MAX_MESSAGE_LENGTH = 4096

    suspend fun replyMarkdownChunked(
        bot: TelegramBot,
        chatId: Long,
        content: String,
        placeholderMessageId: Long,
        replyParams: ReplyParameters? = null,
    ) {
        val chunks = MarkdownV2.renderChunked(content)

        if (chunks.isEmpty()) {
            editMessageWithRetry(
                bot = bot,
                chatId = chatId,
                content = MarkdownV2.escape(content),
                messageId = placeholderMessageId,
                fallbackContent = content,
            )
            return
        }

        editMessageWithRetry(
            bot = bot,
            chatId = chatId,
            content = chunks.first(),
            messageId = placeholderMessageId,
            fallbackContent = content,
        )

        sendChunks(bot = bot, chatId = chatId, chunks = chunks.drop(1), replyParams = replyParams)
    }

    suspend fun sendMarkdownChunked(
        bot: TelegramBot,
        chatId: Long,
        content: String,
        replyParams: ReplyParameters? = null,
    ) {
        val chunks = MarkdownV2.renderChunked(content)

        if (chunks.isEmpty()) {
            sendMarkdownWithRetry(
                bot = bot,
                chatId = chatId,
                text = MarkdownV2.escape(content),
                replyParams = replyParams,
                fallbackContent = content,
            )
            return
        }

        sendChunks(bot = bot, chatId = chatId, chunks = chunks, replyParams = replyParams)
    }

    private suspend fun sendChunks(
        bot: TelegramBot,
        chatId: Long,
        chunks: List<String>,
        replyParams: ReplyParameters? = null,
    ) {
        chunks.forEach { chunk ->
            sendMarkdownWithRetry(
                bot = bot,
                chatId = chatId,
                text = chunk,
                replyParams = replyParams,
                fallbackContent = MarkdownV2.toPlainText(chunk),
            )
        }
    }

    private suspend fun sendMarkdownWithRetry(
        bot: TelegramBot,
        chatId: Long,
        text: String,
        replyParams: ReplyParameters? = null,
        fallbackContent: String = text,
        attempt: Int = 0,
    ) {
        val action =
            message(text).apply {
                options {
                    parseMode = ParseMode.MarkdownV2
                    if (replyParams != null) {
                        replyParameters = replyParams
                    }
                }
            }

        val sent =
            try {
                action.sendReturning(chatId, bot).onFailure { failure ->
                    log.warn(
                        "发送 Markdown 消息失败（第 {} 次尝试，chatId={}）：{} {}",
                        attempt + 1,
                        chatId,
                        failure.errorCode,
                        failure.description,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("发送 Markdown 消息异常（第 {} 次尝试，chatId={}）", attempt + 1, chatId, e)
                null
            }

        if (sent == null && attempt < 3) {
            sendMarkdownWithRetry(
                bot = bot,
                chatId = chatId,
                text = text,
                replyParams = replyParams,
                fallbackContent = fallbackContent,
                attempt = attempt + 1,
            )
        } else if (sent == null) {
            log.warn("Markdown 消息连续 {} 次发送失败，回退为纯文本（chatId={}）", attempt + 1, chatId)
            sendMessageWithRetry(bot = bot, chatId = chatId, text = fallbackContent, replyParams = replyParams)
        }
    }

    suspend fun sendMessageWithRetry(
        bot: TelegramBot,
        chatId: Long,
        text: String,
        callback: ((Message?) -> Unit)? = null,
        replyParams: ReplyParameters? = null,
        attempt: Int = 0,
    ) {
        val truncated = truncate(text, MAX_MESSAGE_LENGTH)

        val action =
            message(truncated).apply {
                if (replyParams != null) {
                    options { replyParameters = replyParams }
                }
            }

        val sent =
            try {
                action.sendReturning(chatId, bot).onFailure { failure ->
                    log.warn(
                        "发送消息失败（第 {} 次尝试，chatId={}）：{} {}",
                        attempt + 1,
                        chatId,
                        failure.errorCode,
                        failure.description,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("发送消息异常（第 {} 次尝试，chatId={}）", attempt + 1, chatId, e)
                null
            }

        when {
            sent != null -> {
                callback?.invoke(sent)
            }

            attempt < 3 -> {
                sendMessageWithRetry(bot, chatId, text, callback, replyParams, attempt + 1)
            }

            else -> {
                log.error("发送消息连续 {} 次失败，已放弃（chatId={}）", attempt + 1, chatId)
                callback?.invoke(null)
            }
        }
    }

    suspend fun editMessageWithRetry(
        bot: TelegramBot,
        chatId: Long,
        content: String,
        messageId: Long,
        fallbackContent: String,
        attempt: Int = 0,
    ) {
        val edited =
            try {
                editText(messageId) { content }
                    .options { parseMode = ParseMode.MarkdownV2 }
                    .sendReturning(chatId, bot)
                    .onFailure { failure ->
                        log.warn(
                            "编辑消息失败（第 {} 次尝试，chatId={}，messageId={}）：{} {}",
                            attempt + 1,
                            chatId,
                            messageId,
                            failure.errorCode,
                            failure.description,
                        )
                    } != null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("编辑消息异常（第 {} 次尝试，chatId={}，messageId={}）", attempt + 1, chatId, messageId, e)
                false
            }

        when {
            edited -> {
                Unit
            }

            attempt < 3 -> {
                editMessageWithRetry(bot, chatId, content, messageId, fallbackContent, attempt + 1)
            }

            else -> {
                log.warn("MarkdownV2 编辑消息连续失败，回退为纯文本（chatId={}，messageId={}）", chatId, messageId)

                try {
                    editText(messageId) { truncate(fallbackContent, MAX_MESSAGE_LENGTH) }
                        .sendReturning(chatId, bot)
                        .onFailure { failure ->
                            log.error(
                                "纯文本回退发送失败（chatId={}，messageId={}）：{} {}",
                                chatId,
                                messageId,
                                failure.errorCode,
                                failure.description,
                            )
                        }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error("纯文本回退发送异常（chatId={}，messageId={}）", chatId, messageId, e)
                }
            }
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

    // Telegram API 不返回文件 MIME（photo 不一定是 jpeg，静态贴纸可能是 webp 或 png），
    // 因此以下载内容的文件头为准，识别失败时回退到 FileRef 中的提示值
    internal fun sniffImageMime(bytes: ByteArray): String? =
        when {
            bytes.matches(0, 0xFF, 0xD8, 0xFF) -> "jpeg"

            bytes.matches(0, 0x89, 0x50, 0x4E, 0x47) -> "png"

            bytes.matches(0, 0x47, 0x49, 0x46) -> "gif"

            bytes.matches(8, 0x57, 0x45, 0x42, 0x50) -> "webp"

            // "WEBP" 位于 RIFF 头（8 字节）之后
            else -> null
        }

    private fun ByteArray.matches(
        offset: Int,
        vararg magic: Int,
    ): Boolean {
        if (size < offset + magic.size) {
            return false
        }

        return magic.indices.all { this[offset + it] == magic[it].toByte() }
    }

    suspend fun getFileDataUrl(
        bot: TelegramBot,
        file: FileRef,
        maxAttempts: Int = 3,
        attempt: Int = 0,
    ): String? {
        if (attempt < maxAttempts) {
            return try {
                val remote =
                    getFile(file.fileId).sendReturning(bot).onFailure { failure ->
                        log.warn(
                            "获取文件信息失败（第 {} 次尝试，fileId={}）：{} {}",
                            attempt + 1,
                            file.fileId,
                            failure.errorCode,
                            failure.description,
                        )
                    }
                val bytes = remote?.let { bot.getFileContent(it) }

                if (bytes != null) {
                    "data:image/${sniffImageMime(bytes) ?: file.mimeType};base64," +
                        Base64.getEncoder().encodeToString(bytes)
                } else {
                    log.warn("获取文件内容失败（第 {} 次尝试，fileId={}）", attempt + 1, file.fileId)
                    getFileDataUrl(bot, file, maxAttempts, attempt + 1)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("下载文件异常（第 {} 次尝试，fileId={}）", attempt + 1, file.fileId, e)
                getFileDataUrl(bot, file, maxAttempts, attempt + 1)
            }
        }

        log.error("下载文件连续 {} 次失败，已放弃（fileId={}）", maxAttempts, file.fileId)
        return null
    }
}
