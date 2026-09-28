package com.github.shikigami

import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatRole
import com.github.shikigami.ai.ChatMessageFactory
import com.github.shikigami.bot.MarkdownV2
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

    private val commandStartLimiter = RateLimiter(maxCount = 1)
    private val commandOpenaiLimiter = RateLimiter(maxCount = 10)

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

        when (val command = Config.commandMatches[parsed.command] ?: return) {
            is Config.BotCommand.Start -> {
                if (!commandStartLimiter.allow(from)) {
                    return
                }

                launch {
                    TelegramApi.sendMessageWithRetry(
                        bot = bot,
                        chatId = parsed.chat.id,
                        relayText = config.commandStartRelayText,
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

                if (!commandOpenaiLimiter.allow(from)) {
                    launch {
                        TelegramApi.sendMessageWithRetry(
                            bot = bot,
                            chatId = parsed.chat.id,
                            relayText = config.rateLimitRelayText,
                            replyParams = ReplyParameters(parsed.messageId),
                        )
                    }

                    return
                }

                runOpenaiCompletion(
                    bot = bot,
                    parsed = parsed,
                    command = command,
                    config = config,
                )
            }
        }
    }

    private fun runOpenaiCompletion(
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
                    relayText = buildAdminReport(parsed, config),
                )
            }
        }

        val resultMessageDeferred = CompletableDeferred<Message?>()

        launch {
            try {
                TelegramApi.sendMessageWithRetry(
                    bot = bot,
                    chatId = parsed.chat.id,
                    relayText = config.placeHolderRelayText,
                    replyParams = ReplyParameters(parsed.messageId),
                    callback = resultMessageDeferred::complete,
                )
            } finally {
                resultMessageDeferred.complete(null)
            }
        }

        launch {
            val content =
                try {
                    val currentImage =
                        parsed.files.lastOrNull()?.let {
                            async { TelegramApi.getFileBase64(bot = bot, file = it) }
                        }
                    val replyImage =
                        parsed.replyToFiles.lastOrNull()?.let {
                            async { TelegramApi.getFileBase64(bot = bot, file = it) }
                        }

                    val openAiMessages =
                        ChatMessageFactory.build(
                            currentImageBase64 = currentImage?.await(),
                            replyImageBase64 = replyImage?.await(),
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
                                } ?: config.errorEmptyRelayText
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            e.message?.let {
                                config.errorMessageRelayText + it
                            } ?: config.errorUnknownRelayText
                        }
                    } else {
                        config.errorFileRelayText
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    config.errorFileRelayText
                }

            resultMessageDeferred.await()?.let {
                TelegramApi.editMessageWithRetry(
                    bot = bot,
                    chatId = it.chat.id,
                    messageId = it.messageId,
                    content = MarkdownV2.render(content),
                    fallbackContent = content,
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

        return config.adminMessageRelayText +
            "\n${parsed.from.firstName}" +
            lastName +
            username +
            parsed.originText
    }
}
