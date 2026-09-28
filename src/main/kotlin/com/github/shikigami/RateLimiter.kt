package com.github.shikigami

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RateLimiter(
    private val maxCount: Int,
) {
    private val windowNanos = TimeUnit.MILLISECONDS.toNanos(60_000)
    private val expireNanos = TimeUnit.MILLISECONDS.toNanos(60_000)

    private val limiters = ConcurrentHashMap<String, FixedWindow>()

    init {
        Executors
            .newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "rate-limiter-sweeper").apply { isDaemon = true }
            }.apply {
                scheduleAtFixedRate(::sweep, expireNanos, expireNanos, TimeUnit.NANOSECONDS)
            }
    }

    fun allow(key: String): Boolean {
        var allowed = false

        limiters.compute(key) { _, existing ->
            val now = System.nanoTime()

            val window = existing ?: FixedWindow(maxCount, windowNanos, now)

            allowed = window.tryAcquire(now)

            window
        }

        return allowed
    }

    private fun sweep() {
        val now = System.nanoTime()

        for (key in limiters.keys) {
            limiters.computeIfPresent(key) { _, window ->
                if (now - window.getLastAcquireNanos() > expireNanos) null else window
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

        fun getLastAcquireNanos() = lastAcquireNanos

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
