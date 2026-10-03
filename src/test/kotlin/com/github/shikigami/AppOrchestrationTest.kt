package com.github.shikigami

import com.aallam.openai.api.logging.LogLevel
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.LoggingConfig
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.OpenAIConfig
import com.aallam.openai.client.OpenAIHost
import com.aallam.openai.client.RetryStrategy
import com.github.shikigami.model.BotConfig
import com.github.shikigami.model.ParsedMessage
import eu.vendeli.tgbot.TelegramBot
import eu.vendeli.tgbot.types.User
import eu.vendeli.tgbot.types.chat.Chat
import eu.vendeli.tgbot.types.chat.ChatType
import eu.vendeli.tgbot.types.msg.EntityType
import eu.vendeli.tgbot.types.msg.Message
import eu.vendeli.tgbot.types.msg.MessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class AppOrchestrationTest {
    private lateinit var server: MockWebServer
    private lateinit var bot: TelegramBot
    private lateinit var config: BotConfig
    private lateinit var commands: Map<String, Config.BotCommand>
    private lateinit var appJob: Job
    private lateinit var app: App

    private companion object {
        const val TOKEN = "1:test-token"
        const val ADMIN_CHAT_ID = 9L
        const val WHITELISTED_CHAT_ID = -100L

        val MESSAGE_BODY =
            """{"ok":true,"result":{"message_id":555,"date":1,"chat":{"id":42,"type":"private"}}}"""
        val FAILURE_BODY = """{"ok":false,"error_code":400,"description":"Bad Request: test failure"}"""
        val COMPLETION_BODY =
            """{"id":"chatcmpl-1","created":1,"model":"test-model",""" +
                """"choices":[{"index":0,"message":{"role":"assistant","content":"模型回复"},"finish_reason":"stop"}],""" +
                """"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}"""
    }

    private fun json(body: String) =
        MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody(body)

    private fun alwaysSucceedDispatcher() =
        object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                when {
                    request.path!!.endsWith("/sendMessage") -> json(MESSAGE_BODY)
                    request.path!!.endsWith("/editMessageText") -> json(MESSAGE_BODY)
                    request.path!!.endsWith("/chat/completions") -> json(COMPLETION_BODY)
                    else -> json("""{"ok":false,"error_code":404,"description":"unexpected path"}""")
                }
        }

    private fun drainRequests(count: Int): List<RecordedRequest> =
        buildList {
            repeat(count) {
                add(server.takeRequest(5, TimeUnit.SECONDS) ?: error("预期 $count 个请求，实际只收到 $size 个"))
            }
        }

    private fun user() = User(id = 1L, isBot = false, firstName = "User")

    private fun startMessage(from: User): Message =
        Message(
            messageId = 1L,
            date = kotlin.time.Instant.fromEpochSeconds(0),
            chat = Chat(id = from.id, type = ChatType.Private),
            from = from,
            text = "/start",
            entities = listOf(MessageEntity(type = EntityType.BotCommand, offset = 0, length = "/start".length)),
        )

    private fun completionMessage(
        from: User,
        chatId: Long? = null,
        prompt: String = " 你好",
    ): Message =
        Message(
            messageId = 1L,
            date = kotlin.time.Instant.fromEpochSeconds(0),
            chat = Chat(id = chatId ?: from.id, type = ChatType.Private),
            from = from,
            text = "/native$prompt",
            entities = listOf(MessageEntity(type = EntityType.BotCommand, offset = 0, length = "/native".length)),
        )

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
        server.dispatcher = alwaysSucceedDispatcher()

        bot =
            TelegramBot(token = TOKEN) {
                apiHost = "http://${server.hostName}:${server.port}"
            }

        val openAi =
            OpenAI(
                OpenAIConfig(
                    token = "test-token",
                    host = OpenAIHost(baseUrl = "http://${server.hostName}:${server.port}/v1/"),
                    logging = LoggingConfig(logLevel = LogLevel.None),
                    retry = RetryStrategy(maxRetries = 0),
                ),
            )

        commands =
            mapOf(
                "start" to Config.BotCommand.Start,
                "native" to Config.BotCommand.Completion(client = openAi, model = ModelId("test-model"), mmj = false),
            )

        config =
            BotConfig(
                adminChatId = ADMIN_CHAT_ID,
                adminMessageText = "report",
                allowedChatIds = listOf(WHITELISTED_CHAT_ID),
                commandStartText = "start text",
                errorEmptyText = "empty",
                errorFileText = "file",
                errorMessageText = "error",
                errorUnknownText = "unknown",
                mmjPrompt = "prompt",
                placeholderText = "placeholder",
                proxy = null,
                rateLimitText = "rate limited",
                token = TOKEN,
                username = "mybot",
            )

        appJob = SupervisorJob()
        app = App(Dispatchers.IO + appJob)
    }

    @AfterTest
    fun tearDown() {
        runBlocking { awaitPendingWork(appJob, 5.seconds) }
        appJob.cancel()
        server.shutdown()
    }

    @Test
    fun startCommandRepliesWithStartText() {
        app.handleMessage(bot, startMessage(user()), config, commands)

        val request = drainRequests(1).single()

        assertTrue(request.path!!.endsWith("/sendMessage"))
        assertTrue(request.body.readUtf8().contains("start text"))
    }

    @Test
    fun startCommandIgnoresSecondRequestWithinWindow() {
        val from = user()
        val message = startMessage(from)

        app.handleMessage(bot, message, config, commands)
        app.handleMessage(bot, message, config, commands)

        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertNull(server.takeRequest(500, TimeUnit.MILLISECONDS))
    }

    @Test
    fun completionCommandSendsPlaceholderThenEditsWithModelReply() {
        val whitelistedChatUser = user()
        app.handleMessage(
            bot,
            completionMessage(whitelistedChatUser, chatId = WHITELISTED_CHAT_ID),
            config,
            commands,
        )

        val requests = drainRequests(3)

        assertEquals(1, requests.count { it.path!!.endsWith("/sendMessage") })
        assertTrue(
            requests
                .single { it.path!!.endsWith("/sendMessage") }
                .body
                .readUtf8()
                .contains("placeholder"),
        )
        assertEquals(1, requests.count { it.path!!.endsWith("/chat/completions") })
        val edit = requests.first { it.path!!.endsWith("/editMessageText") }
        assertTrue(edit.body.readUtf8().contains("模型回复"))
    }

    @Test
    fun completionCommandReportsNonWhitelistedChatToAdmin() {
        app.handleMessage(bot, completionMessage(user(), chatId = 888L), config, commands)

        val requests = drainRequests(4)
        val sendBodies = requests.filter { it.path!!.endsWith("/sendMessage") }.map { it.body.readUtf8() }

        assertEquals(2, sendBodies.size)
        assertTrue(sendBodies.any { it.contains("report") && it.contains("\"chat_id\":$ADMIN_CHAT_ID") })
        assertTrue(sendBodies.any { it.contains("placeholder") })
    }

    @Test
    fun completionCommandFallsBackToDirectSendWhenPlaceholderFails() {
        val dispatcher =
            RecordingDispatcher { path, body ->
                when {
                    path.endsWith("/chat/completions") -> {
                        json(COMPLETION_BODY)
                    }

                    !path.endsWith("/sendMessage") -> {
                        json(MESSAGE_BODY)
                    }

                    body.contains("placeholder") -> {
                        json(FAILURE_BODY)
                    }

                    else -> {
                        json(MESSAGE_BODY)
                    }
                }
            }
        server.dispatcher = dispatcher

        app.handleMessage(bot, completionMessage(user(), chatId = WHITELISTED_CHAT_ID), config, commands)

        awaitRecorded(dispatcher, 6)

        val sends = dispatcher.recorded.filter { it.first.endsWith("/sendMessage") }
        assertEquals(5, sends.size)
        assertEquals(0, dispatcher.recorded.count { it.first.endsWith("/editMessageText") })
        assertTrue(sends.last().second.contains("模型回复"))
    }

    @Test
    fun completionCommandSendsRateLimitTextWhenExceeded() {
        val from = user()
        val message = completionMessage(from, chatId = WHITELISTED_CHAT_ID)

        repeat(11) { app.handleMessage(bot, message, config, commands) }

        val requests = drainRequests(31)
        val sendBodies = requests.filter { it.path!!.endsWith("/sendMessage") }.map { it.body.readUtf8() }

        assertEquals(11, sendBodies.size)
        assertEquals(10, requests.count { it.path!!.endsWith("/chat/completions") })
        assertTrue(sendBodies.any { it.contains("rate limited") })
    }

    @Test
    fun completionCommandWithoutPromptIsIgnored() {
        app.handleMessage(bot, completionMessage(user(), prompt = ""), config, commands)

        assertNull(server.takeRequest(500, TimeUnit.MILLISECONDS))
    }

    @Test
    fun llmFailureEditsErrorMessageIntoPlaceholder() {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.path!!.endsWith("/chat/completions")) {
                        MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}""")
                    } else {
                        json(MESSAGE_BODY)
                    }
            }

        app.handleMessage(bot, completionMessage(user(), chatId = WHITELISTED_CHAT_ID), config, commands)

        val requests = drainRequests(3)
        val edit = requests.first { it.path!!.endsWith("/editMessageText") }

        assertTrue(edit.body.readUtf8().contains("error"))
    }

    @Test
    fun buildAdminReportIncludesNamesAndUsername() {
        val parsed =
            ParsedMessage(
                chat = Chat(id = 1L, type = ChatType.Private),
                command = "native",
                files = emptyList(),
                from = User(id = 1L, isBot = false, firstName = "Foo", lastName = "Bar", username = "hoge"),
                messageId = 1L,
                originalText = "/native 你好",
                replyToBotSelf = false,
                replyToFiles = emptyList(),
                replyToMessageText = null,
                text = " 你好",
            )

        assertEquals("report\nFoo Bar（@hoge）：\n/native 你好", app.buildAdminReport(parsed, config))
    }

    @Test
    fun buildAdminReportOmitsBlankNames() {
        val parsed =
            ParsedMessage(
                chat = Chat(id = 1L, type = ChatType.Private),
                command = "native",
                files = emptyList(),
                from = User(id = 1L, isBot = false, firstName = "Foo", lastName = " ", username = ""),
                messageId = 1L,
                originalText = "/native 你好",
                replyToBotSelf = false,
                replyToFiles = emptyList(),
                replyToMessageText = null,
                text = " 你好",
            )

        assertEquals("report\nFoo：\n/native 你好", app.buildAdminReport(parsed, config))
    }
}
