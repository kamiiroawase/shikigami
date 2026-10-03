package com.github.shikigami

import com.github.shikigami.bot.TelegramApi
import com.github.shikigami.model.FileRef
import eu.vendeli.tgbot.TelegramBot
import eu.vendeli.tgbot.types.msg.Message
import io.github.kamiiroawase.markdownv2.MarkdownV2
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TelegramApiRetryTest {
    private lateinit var server: MockWebServer
    private lateinit var bot: TelegramBot

    private companion object {
        const val TOKEN = "1:test-token"
        const val CHAT_ID = 42L

        val MESSAGE_BODY =
            """{"ok":true,"result":{"message_id":555,"date":1,"chat":{"id":$CHAT_ID,"type":"private"}}}"""
        val FAILURE_BODY = """{"ok":false,"error_code":400,"description":"Bad Request: test failure"}"""
    }

    private fun okMessage() =
        MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody(MESSAGE_BODY)

    private fun failure() =
        MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody(FAILURE_BODY)

    private fun dispatchByBody(rule: (String) -> MockResponse): RecordingDispatcher = RecordingDispatcher { _, body -> rule(body) }

    private fun drainRequests(count: Int): List<RecordedRequest> =
        buildList {
            repeat(count) {
                add(server.takeRequest(5, TimeUnit.SECONDS) ?: error("预期 $count 个请求，实际只收到 $size 个"))
            }
        }

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
        bot =
            TelegramBot(token = TOKEN) {
                apiHost = "http://${server.hostName}:${server.port}"
            }
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun sendMessageWithRetryInvokesCallbackOnSuccess() =
        runBlocking {
            server.enqueue(okMessage())

            var callbackValue: Message? = null
            var callbackCount = 0
            TelegramApi.sendMessageWithRetry(
                bot = bot,
                chatId = CHAT_ID,
                text = "hi",
                callback = {
                    callbackCount++
                    callbackValue = it
                },
            )

            assertEquals(1, server.requestCount)
            assertEquals(1, callbackCount)
            assertEquals(555L, callbackValue?.messageId)
        }

    @Test
    fun sendMessageWithRetryRetriesUntilSuccess() =
        runBlocking {
            server.enqueue(failure())
            server.enqueue(failure())
            server.enqueue(okMessage())

            var callbackValue: Message? = null
            TelegramApi.sendMessageWithRetry(
                bot = bot,
                chatId = CHAT_ID,
                text = "hi",
                callback = { callbackValue = it },
            )

            assertEquals(3, server.requestCount)
            assertEquals(555L, callbackValue?.messageId)
        }

    @Test
    fun sendMessageWithRetryGivesUpAfterFourAttempts() =
        runBlocking {
            repeat(4) { server.enqueue(failure()) }

            var callbackValue: Message? = null
            TelegramApi.sendMessageWithRetry(
                bot = bot,
                chatId = CHAT_ID,
                text = "hi",
                callback = { callbackValue = it },
            )

            assertEquals(4, server.requestCount)
            assertNull(callbackValue)
        }

    @Test
    fun sendMessageWithRetryTruncatesOverlongText() =
        runBlocking {
            server.enqueue(okMessage())

            TelegramApi.sendMessageWithRetry(bot = bot, chatId = CHAT_ID, text = "a".repeat(5000))

            val body = drainRequests(1).single().body.readUtf8()
            assertFalse(body.contains("a".repeat(TelegramApi.MAX_MESSAGE_LENGTH)))
            assertTrue(body.contains("…"))
        }

    @Test
    fun sendMarkdownChunkedFallsBackToPlainTextAfterFourMarkdownFailures() =
        runBlocking {
            server.dispatcher =
                dispatchByBody { body ->
                    if (body.contains("MarkdownV2")) failure() else okMessage()
                }

            TelegramApi.sendMarkdownChunked(bot = bot, chatId = CHAT_ID, content = "hello world")

            val bodies =
                server.dispatcher
                    .let { it as RecordingDispatcher }
                    .recorded
                    .map { it.second }
            assertEquals(5, bodies.size)
            assertEquals(4, bodies.count { it.contains("MarkdownV2") })
            assertTrue(bodies.last().contains("hello world"))
        }

    @Test
    fun sendMarkdownChunkedFallsBackToDeformattedPlainText() =
        runBlocking {
            server.dispatcher =
                dispatchByBody { body ->
                    if (body.contains("MarkdownV2")) failure() else okMessage()
                }

            TelegramApi.sendMarkdownChunked(
                bot = bot,
                chatId = CHAT_ID,
                content = "**加粗** 与 [链接](https://example.com/path)",
            )

            val bodies =
                server.dispatcher
                    .let { it as RecordingDispatcher }
                    .recorded
                    .map { it.second }
            assertEquals(5, bodies.size)
            assertEquals(4, bodies.count { it.contains("MarkdownV2") })
            val fallback = bodies.last()
            assertTrue(fallback.contains("加粗 与 链接 (https://example.com/path)"))
            assertFalse(fallback.contains("*"))
            assertFalse(fallback.contains("[链接]"))
        }

    @Test
    fun editMessageWithRetryFallsBackToPlainTextAfterFourMarkdownFailures() =
        runBlocking {
            server.dispatcher =
                dispatchByBody { body ->
                    if (body.contains("MarkdownV2")) failure() else okMessage()
                }

            TelegramApi.editMessageWithRetry(
                bot = bot,
                chatId = CHAT_ID,
                content = MarkdownV2.escape("hello world"),
                messageId = 555L,
                fallbackContent = "hello world",
            )

            val bodies =
                server.dispatcher
                    .let { it as RecordingDispatcher }
                    .recorded
                    .map { it.second }
            assertEquals(5, bodies.size)
            assertEquals(4, bodies.count { it.contains("MarkdownV2") })
            assertTrue(bodies.last().contains("hello world"))
        }

    @Test
    fun sendMarkdownChunkedSendsEveryChunk() =
        runBlocking {
            val content = "word ".repeat(2000)
            val chunks = MarkdownV2.renderChunked(content)
            assertTrue(chunks.size > 1)
            repeat(chunks.size) { server.enqueue(okMessage()) }

            TelegramApi.sendMarkdownChunked(bot = bot, chatId = CHAT_ID, content = content)

            val requests = drainRequests(chunks.size)
            assertTrue(requests.all { it.path!!.endsWith("/sendMessage") })
        }

    @Test
    fun sendMarkdownChunkedSendsSingleEscapedMessageWhenNoChunks() =
        runBlocking {
            server.enqueue(okMessage())

            TelegramApi.sendMarkdownChunked(bot = bot, chatId = CHAT_ID, content = "")

            assertEquals(1, server.requestCount)
        }

    @Test
    fun replyMarkdownChunkedEditsPlaceholderThenSendsRemainingChunks() =
        runBlocking {
            val content = "word ".repeat(2000)
            val chunks = MarkdownV2.renderChunked(content)
            assertTrue(chunks.size > 1)
            repeat(chunks.size) { server.enqueue(okMessage()) }

            TelegramApi.replyMarkdownChunked(
                bot = bot,
                chatId = CHAT_ID,
                content = content,
                placeholderMessageId = 555L,
            )

            val requests = drainRequests(chunks.size)
            assertTrue(requests.first().path!!.endsWith("/editMessageText"))
            assertTrue(requests.drop(1).all { it.path!!.endsWith("/sendMessage") })
        }

    @Test
    fun replyMarkdownChunkedEscapesContentWhenNoChunks() =
        runBlocking {
            server.enqueue(okMessage())

            TelegramApi.replyMarkdownChunked(
                bot = bot,
                chatId = CHAT_ID,
                content = "",
                placeholderMessageId = 555L,
            )

            assertEquals(1, server.requestCount)
            assertTrue(drainRequests(1).single().path!!.endsWith("/editMessageText"))
        }

    @Test
    fun getFileDataUrlDownloadsFileAndBuildsDataUrl() =
        runBlocking {
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        when {
                            request.path!!.endsWith("/getFile") -> {
                                MockResponse()
                                    .setHeader("Content-Type", "application/json")
                                    .setBody(
                                        """{"ok":true,"result":{"file_id":"f","file_unique_id":"u",""" +
                                            """"file_path":"photos/img.jpg"}}""",
                                    )
                            }

                            request.path!!.endsWith("/photos/img.jpg") -> {
                                MockResponse().setBody(Buffer().write(byteArrayOf(1, 2, 3)))
                            }

                            else -> {
                                failure()
                            }
                        }
                }

            val dataUrl = TelegramApi.getFileDataUrl(bot = bot, file = FileRef("jpeg", "f"))

            assertEquals("data:image/jpeg;base64," + Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)), dataUrl)
            assertEquals(2, server.requestCount)
        }

    @Test
    fun getFileDataUrlReturnsNullAfterMaxAttempts() =
        runBlocking {
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse = failure()
                }

            val dataUrl = TelegramApi.getFileDataUrl(bot = bot, file = FileRef("jpeg", "f"))

            assertNull(dataUrl)
            assertEquals(3, server.requestCount)
        }

    @Test
    fun getFileDataUrlPrefersSniffedMimeOverHint() =
        runBlocking {
            val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00)
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        when {
                            request.path!!.endsWith("/getFile") -> {
                                MockResponse()
                                    .setHeader("Content-Type", "application/json")
                                    .setBody(
                                        """{"ok":true,"result":{"file_id":"f","file_unique_id":"u",""" +
                                            """"file_path":"stickers/sticker.png"}}""",
                                    )
                            }

                            request.path!!.endsWith("/stickers/sticker.png") -> {
                                MockResponse().setBody(Buffer().write(pngBytes))
                            }

                            else -> {
                                failure()
                            }
                        }
                }

            val dataUrl = TelegramApi.getFileDataUrl(bot = bot, file = FileRef("webp", "f"))

            assertEquals("data:image/png;base64," + Base64.getEncoder().encodeToString(pngBytes), dataUrl)
            assertEquals(2, server.requestCount)
        }
}
