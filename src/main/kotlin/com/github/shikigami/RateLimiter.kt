package com.github.shikigami

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class RateLimiter(
    private val maxCount: Int,
    private val clock: () -> Long = System::nanoTime,
) {
    private val windowNanos = TimeUnit.MILLISECONDS.toNanos(60_000)
    private val expireNanos = TimeUnit.MILLISECONDS.toNanos(60_000)

    private val windows = ConcurrentHashMap<String, FixedWindow>()

    fun allow(key: String): Boolean {
        sweep()

        var allowed = false

        windows.compute(key) { _, existing ->
            val now = clock()

            val window = existing ?: FixedWindow(maxCount, windowNanos, now)

            allowed = window.tryAcquire(now)

            window
        }

        return allowed
    }

    private fun sweep() {
        val now = clock()

        for (key in windows.keys) {
            windows.computeIfPresent(key) { _, window ->
                if (window.isExpired(now, expireNanos)) null else window
            }
        }
    }

    private class FixedWindow(
        private val maxCount: Int,
        private val windowNanos: Long,
        startNanos: Long,
    ) {
        private var count = 0

        private var lastAcquireNanos = startNanos
        private var windowStartNanos = startNanos

        fun isExpired(
            now: Long,
            expireNanos: Long,
        ): Boolean = now - lastAcquireNanos > expireNanos

        fun tryAcquire(now: Long): Boolean {
            if (now - windowStartNanos >= windowNanos) {
                windowStartNanos = now
                count = 0
            }

            return if (count < maxCount) {
                count++
                lastAcquireNanos = now
                true
            } else {
                false
            }
        }
    }
}
