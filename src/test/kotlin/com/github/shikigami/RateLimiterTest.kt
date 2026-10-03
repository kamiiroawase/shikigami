package com.github.shikigami

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RateLimiterTest {
    @Test
    fun allowsUpToMaxCountPerKey() {
        val limiter = RateLimiter(maxCount = 2)

        assertTrue(limiter.allow("user"))
        assertTrue(limiter.allow("user"))
        assertFalse(limiter.allow("user"))
    }

    @Test
    fun keysAreIndependent() {
        val limiter = RateLimiter(maxCount = 1)

        assertTrue(limiter.allow("a"))
        assertFalse(limiter.allow("a"))
        assertTrue(limiter.allow("b"))
    }

    @Test
    fun resetsCountAfterWindowExpires() {
        var now = 0L
        val limiter = RateLimiter(maxCount = 2) { now }

        assertTrue(limiter.allow("user"))
        assertTrue(limiter.allow("user"))
        assertFalse(limiter.allow("user"))

        now += TimeUnit.MILLISECONDS.toNanos(60_000)

        assertTrue(limiter.allow("user"))
        assertTrue(limiter.allow("user"))
        assertFalse(limiter.allow("user"))
    }

    @Test
    fun doesNotResetBeforeWindowExpires() {
        var now = 0L
        val limiter = RateLimiter(maxCount = 1) { now }

        assertTrue(limiter.allow("user"))

        now += TimeUnit.MILLISECONDS.toNanos(59_999)
        assertFalse(limiter.allow("user"))

        now += TimeUnit.MILLISECONDS.toNanos(1)
        assertTrue(limiter.allow("user"))
    }
}
