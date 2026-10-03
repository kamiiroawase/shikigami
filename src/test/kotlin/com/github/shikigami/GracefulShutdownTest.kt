package com.github.shikigami

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class GracefulShutdownTest {
    @Test
    fun awaitPendingWorkReturnsTrueWithoutPendingWork() =
        runBlocking {
            assertTrue(awaitPendingWork(SupervisorJob(), 1.seconds))
        }

    @Test
    fun awaitPendingWorkWaitsForChildrenToFinish() =
        runBlocking {
            val parent = SupervisorJob()
            CoroutineScope(parent).launch { delay(50) }

            assertTrue(awaitPendingWork(parent, 5.seconds))
        }

    @Test
    fun awaitPendingWorkTimesOutWhenChildIsStuck() =
        runBlocking {
            val parent = SupervisorJob()
            val stuck = CoroutineScope(parent).launch { delay(60_000) }

            assertFalse(awaitPendingWork(parent, 100.milliseconds))

            stuck.cancel()
        }
}
