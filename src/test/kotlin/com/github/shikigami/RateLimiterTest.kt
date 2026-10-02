package com.github.shikigami

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
}
