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

        val FAILURE_BODY = """{"ok":false,"error_code":400,"description":"Bad Request: test failure"}"""
    }

    private fun okMessage(messageId: Long = 555L) =
        MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody("""{"ok":true,"result":{"message_id":$messageId,"date":1,"chat":{"id":$CHAT_ID,"type":"private"}}}""")

    private fun failure() =
        MockResponse()
            .setResponseCode(400)
            .setHeader("Content-Type", "application/json")
            .setBody(FAILURE_BODY)

    private fun serverError() =
        MockResponse()
            .setResponseCode(500)
            .setHeader("Content-Type", "application/json")
            .setBody("""{"ok":false,"error_code":500,"description":"Internal Server Error"}""")

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
                // 库内 5xx 重试默认退避 3/6/9s，关闭以免外层重试测试真实睡眠
                httpClient { maxRequestRetry = 0 }
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
    fun editMessageWithRetryFallsBackToPlainTextImmediatelyOnBadRequest() =
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
            assertEquals(2, bodies.size)
            assertEquals(1, bodies.count { it.contains("MarkdownV2") })
            assertTrue(bodies.last().contains("hello world"))
        }

    @Test
    fun editMessageWithRetryRetriesTransientErrorsBeforeFallback() =
        runBlocking {
            server.dispatcher =
                dispatchByBody { body ->
                    if (body.contains("MarkdownV2")) serverError() else okMessage()
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
    fun sendMarkdownChunkedChainsRepliesToBotPreviousMessage() =
        runBlocking {
            val content = "word ".repeat(6000)
            val chunks = MarkdownV2.renderChunked(content)
            assertTrue(chunks.size > 1)

            var nextMessageId = 555L
            val dispatcher = RecordingDispatcher { _, _ -> okMessage(nextMessageId++) }
            server.dispatcher = dispatcher

            TelegramApi.sendMarkdownChunked(
                bot = bot,
                chatId = CHAT_ID,
                content = content,
                replyParams = TelegramApi.replyParams(111L),
            )

            drainRequests(chunks.size)
            val bodies = dispatcher.recorded.map { it.second }
            assertTrue(bodies.first().contains("\"message_id\":111"))
            bodies.drop(1).forEachIndexed { index, body ->
                assertTrue(body.contains("\"message_id\":${555L + index}"), "第 ${index + 2} 段应回复上一段消息")
            }
        }

    @Test
    fun replyMarkdownChunkedChainsSentChunksToBotPreviousMessage() =
        runBlocking {
            val content = "word ".repeat(6000)
            val chunks = MarkdownV2.renderChunked(content)
            assertTrue(chunks.size > 1)

            var nextMessageId = 555L
            val dispatcher =
                RecordingDispatcher { path, _ ->
                    if (path.endsWith("/editMessageText")) okMessage(900L) else okMessage(nextMessageId++)
                }
            server.dispatcher = dispatcher

            TelegramApi.replyMarkdownChunked(
                bot = bot,
                chatId = CHAT_ID,
                content = content,
                placeholderMessageId = 900L,
                replyParams = TelegramApi.replyParams(111L),
            )

            drainRequests(chunks.size)
            assertTrue(
                dispatcher.recorded
                    .first()
                    .first
                    .endsWith("/editMessageText"),
            )
            val bodies = dispatcher.recorded.map { it.second }
            assertTrue(bodies.first().contains("\"message_id\":900"))
            assertTrue(bodies[1].contains("\"message_id\":111"))
            bodies.drop(2).forEachIndexed { index, body ->
                assertTrue(body.contains("\"message_id\":${555L + index}"), "第 ${index + 3} 段应回复上一段消息")
            }
        }

    @Test
    fun sendMarkdownChunkedKeepsChainAtLastSuccessfulMessageWhenChunkFails() =
        runBlocking {
            val content = "word ".repeat(6000)
            val chunks = MarkdownV2.renderChunked(content)
            assertTrue(chunks.size >= 3)

            var nextMessageId = 555L
            var requestIndex = 0
            val dispatcher =
                RecordingDispatcher { _, _ ->
                    val index = requestIndex++
                    // 请求 1-8：第 2 段的 4 次 Markdown 发送 + 4 次纯文本回退全部失败，其余请求成功
                    if (index in 1..8) failure() else okMessage(nextMessageId++)
                }
            server.dispatcher = dispatcher

            TelegramApi.sendMarkdownChunked(
                bot = bot,
                chatId = CHAT_ID,
                content = content,
                replyParams = TelegramApi.replyParams(111L),
            )

            val totalRequests = 1 + 8 + (chunks.size - 2)
            drainRequests(totalRequests)
            assertEquals(totalRequests, server.requestCount)
            val bodies = dispatcher.recorded.map { it.second }
            // 第 2 段放弃后，第 3 段应回复第 1 段（message_id 555），第 4 段回复第 3 段（message_id 556）
            assertTrue(bodies[9].contains("\"message_id\":555"))
            assertTrue(bodies[10].contains("\"message_id\":556"))
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
