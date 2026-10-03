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
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

object App : CoroutineScope {
    private val log: Logger = LoggerFactory.getLogger(App::class.java)

    private val appJob = SupervisorJob()

    override val coroutineContext =
        Dispatchers.IO + appJob +
            CoroutineExceptionHandler { _, throwable ->
                log.error("协程中未捕获的异常", throwable)
            }

    private val shutdownGracePeriod = 30.seconds

    private val shutdownRequested = AtomicBoolean(false)

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

        // JVM 在所有 shutdown hook 返回后即退出，因此等待在途任务必须在 hook 内完成
        Runtime.getRuntime().addShutdownHook(
            Thread(
                {
                    log.info("收到停机信号，停止接收新更新")
                    shutdownRequested.set(true)
                    bot.update.stopListener()

                    val drained = runBlocking { awaitPendingWork(appJob, shutdownGracePeriod) }

                    if (drained) {
                        log.info("进行中的任务已全部完成，停机完毕")
                    } else {
                        log.warn("等待进行中的任务超时（{}），强制取消剩余任务", shutdownGracePeriod)
                        appJob.cancel()
                    }
                },
                "graceful-shutdown",
            ),
        )

        runBlocking {
            bot.setFunctionality {
                onUpdate(UpdateType.MESSAGE) {
                    val raw = (update as? MessageUpdate)?.message ?: return@onUpdate

                    handleMessage(bot, raw, config)
                }
            }

            val retryAfter = 5

            while (!shutdownRequested.get()) {
                try {
                    bot.handleUpdates()
                    log.info("更新监听已停止，退出主循环")
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error("处理 Telegram 更新失败，$retryAfter 秒后重试", e)
                    delay(retryAfter.seconds)
                }
            }
        }
    }

    internal fun handleMessage(
        bot: TelegramBot,
        raw: Message,
        config: BotConfig,
        commands: Map<String, Config.BotCommand> = Config.commands,
    ) {
        val parsed = MessageParser.parse(raw, config) ?: return

        val from = parsed.from.id.toString()

        when (val command = commands[parsed.command] ?: return) {
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

    internal fun handleCompletion(
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
                            log.error("LLM 调用失败（model={}）", command.model.id, e)
                            e.message?.let {
                                config.errorMessageText + it
                            } ?: config.errorUnknownText
                        }
                    } else {
                        config.errorFileText
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error("生成补全内容失败", e)
                    config.errorFileText
                }

            val placeholder = placeholderMessage.await()

            if (placeholder == null) {
                log.warn("占位消息发送失败，降级为直接发送回复（chatId={}）", parsed.chat.id)
                TelegramApi.sendMarkdownChunked(
                    bot = bot,
                    chatId = parsed.chat.id,
                    content = content,
                    replyParams = ReplyParameters(parsed.messageId),
                )
            } else {
                TelegramApi.replyMarkdownChunked(
                    bot = bot,
                    chatId = placeholder.chat.id,
                    content = content,
                    placeholderMessageId = placeholder.messageId,
                    replyParams = ReplyParameters(parsed.messageId),
                )
            }
        }
    }

    internal fun buildAdminReport(
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

internal suspend fun awaitPendingWork(
    parentJob: Job,
    gracePeriod: Duration,
): Boolean =
    withTimeoutOrNull(gracePeriod) {
        parentJob.children.toList().joinAll()
    } != null
