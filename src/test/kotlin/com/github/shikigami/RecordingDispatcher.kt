package com.github.shikigami

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.CopyOnWriteArrayList

/**
 * MockWebServer 的请求体只能读取一次：dispatcher 中读过后 takeRequest() 拿到的副本为空，
 * 因此需要按请求体路由的测试用本类在 dispatch 时记录 (path, body)。
 */
internal class RecordingDispatcher(
    private val route: (path: String, body: String) -> MockResponse,
) : Dispatcher() {
    val recorded = CopyOnWriteArrayList<Pair<String, String>>()

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path.orEmpty()
        val body = request.body.readUtf8()
        recorded += path to body
        return route(path, body)
    }
}

internal fun awaitRecorded(
    dispatcher: RecordingDispatcher,
    count: Int,
    timeoutMillis: Long = 5_000,
) {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (dispatcher.recorded.size < count && System.currentTimeMillis() < deadline) {
        Thread.sleep(20)
    }
    check(dispatcher.recorded.size >= count) {
        "预期 $count 个请求，实际只收到 ${dispatcher.recorded.size} 个"
    }
}
