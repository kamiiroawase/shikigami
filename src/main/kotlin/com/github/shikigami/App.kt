package com.github.shikigami

import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatRole
import com.github.shikigami.ai.ChatMessageFactory
import com.github.shikigami.bot.MessageParser
import com.github.shikigami.bot.TelegramApi
import com.github.shikigami.model.BotConfig
import com.github.shikigami.model.ParsedMessage
import eu.vendeli.tgbot.TelegramBot
import eu.vendeli.tgbot.types.common.ReplyParameters
import eu.vendeli.tgbot.types.component.MessageUpdate
import eu.vendeli.tgbot.types.component.UpdateType
import eu.vendeli.tgbot.types.msg.Message
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.Proxy
import kotlin.time.Duration.Companion.milliseconds

object App : CoroutineScope {
    override val coroutineContext = Dispatchers.IO + SupervisorJob()

    private val startLimiter = RateLimiter(maxCount = 1)
    private val completionLimiter = RateLimiter(maxCount = 10)

    @JvmStatic
    fun main(args: Array<String>) {
        val config = Config.botConfig

        val bot =
            TelegramBot(token = config.token) {
                httpClient {
                    proxy =
                        config.proxy?.let {
                            Proxy(Proxy.Type.HTTP, InetSocketAddress(it.hostname, it.port))
                        }
                    connectTimeoutMillis = 10_000L
                    socketTimeoutMillis = 35_000L
                    requestTimeoutMillis = 60_000L
                }
            }

        runBlocking {
            bot.setFunctionality {
                onUpdate(UpdateType.MESSAGE) {
                    val raw = (update as? MessageUpdate)?.message ?: return@onUpdate

                    handleMessage(bot, raw, config)
                }
            }

            while (true) {
                try {
                    bot.handleUpdates()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    delay(5000.milliseconds)
                }
            }
        }
    }

    private fun handleMessage(
        bot: TelegramBot,
        raw: Message,
        config: BotConfig,
    ) {
        val parsed = MessageParser.parse(raw, config) ?: return

        val from = parsed.from.id.toString()

        when (val command = Config.commands[parsed.command] ?: return) {
            is Config.BotCommand.Start -> {
                if (!startLimiter.allow(from)) {
                    return
                }

                launch {
                    TelegramApi.sendMessageWithRetry(
                        bot = bot,
                        chatId = parsed.chat.id,
                        text = config.commandStartText,
                        replyParams = ReplyParameters(parsed.messageId),
                    )
                }
            }

            is Config.BotCommand.Completion -> {
                if (parsed.text.isNullOrBlank() &&
                    parsed.files.isEmpty() &&
                    parsed.replyToFiles.isEmpty() &&
                    parsed.replyToMessageText.isNullOrBlank()
                ) {
                    return
                }

                if (!completionLimiter.allow(from)) {
                    launch {
                        TelegramApi.sendMessageWithRetry(
                            bot = bot,
                            chatId = parsed.chat.id,
                            text = config.rateLimitText,
                            replyParams = ReplyParameters(parsed.messageId),
                        )
                    }

                    return
                }

                handleCompletion(
                    bot = bot,
                    parsed = parsed,
                    command = command,
                    config = config,
                )
            }
        }
    }

    private fun handleCompletion(
        bot: TelegramBot,
        parsed: ParsedMessage,
        command: Config.BotCommand.Completion,
        config: BotConfig,
    ) {
        if (parsed.chat.id !in config.allowedChatIds) {
            launch {
                TelegramApi.sendMessageWithRetry(
                    bot = bot,
                    chatId = config.adminChatId,
                    text = buildAdminReport(parsed, config),
                )
            }
        }

        val placeholderMessage = CompletableDeferred<Message?>()

        launch {
            try {
                TelegramApi.sendMessageWithRetry(
                    bot = bot,
                    chatId = parsed.chat.id,
                    text = config.placeholderText,
                    replyParams = ReplyParameters(parsed.messageId),
                    callback = placeholderMessage::complete,
                )
            } finally {
                placeholderMessage.complete(null)
            }
        }

        launch {
            val content =
                try {
                    val currentImage =
                        parsed.files.lastOrNull()?.let {
                            async { TelegramApi.getFileDataUrl(bot = bot, file = it) }
                        }
                    val replyImage =
                        parsed.replyToFiles.lastOrNull()?.let {
                            async { TelegramApi.getFileDataUrl(bot = bot, file = it) }
                        }

                    val openAiMessages =
                        ChatMessageFactory.build(
                            currentImageDataUrl = currentImage?.await(),
                            replyImageDataUrl = replyImage?.await(),
                            message = parsed,
                            systemPrompt = if (command.mmj) config.mmjPrompt else null,
                        )

                    if (openAiMessages.any { it.role != ChatRole.System }) {
                        try {
                            command.client
                                .chatCompletion(
                                    ChatCompletionRequest(
                                        messages = openAiMessages,
                                        model = command.model,
                                    ),
                                ).choices
                                .firstOrNull()
                                ?.message
                                ?.content
                                ?.let {
                                    it.ifBlank {
                                        null
                                    }
                                } ?: config.errorEmptyText
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            e.message?.let {
                                config.errorMessageText + it
                            } ?: config.errorUnknownText
                        }
                    } else {
                        config.errorFileText
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    config.errorFileText
                }

            placeholderMessage.await()?.let {
                TelegramApi.replyMarkdownChunked(
                    bot = bot,
                    chatId = it.chat.id,
                    content = content,
                    placeholderMessageId = it.messageId,
                    replyParams = ReplyParameters(parsed.messageId),
                )
            }
        }
    }

    private fun buildAdminReport(
        parsed: ParsedMessage,
        config: BotConfig,
    ): String {
        val lastName =
            parsed.from.lastName
                ?.takeIf { it.isNotBlank() }
                ?.let { " $it" }
                ?: ""
        val username =
            parsed.from.username
                ?.takeIf { it.isNotBlank() }
                ?.let { "（@$it）：\n" }
                ?: "：\n"

        return config.adminMessageText +
            "\n${parsed.from.firstName}" +
            lastName +
            username +
            parsed.originalText
    }
}
