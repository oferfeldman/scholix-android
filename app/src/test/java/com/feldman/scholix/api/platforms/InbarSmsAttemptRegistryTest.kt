package com.feldman.scholix.api.platforms

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class InbarSmsAttemptRegistryTest {
    @Test fun pageCancellationAndRetryStillVerifyTheOriginalSms() = runTest {
        val codes = Channel<String>(Channel.BUFFERED)
        var requests = 0
        val attempts = InbarSmsAttemptRegistry<Deferred<String>>(completed = { it.isCompleted })
        fun signIn() = attempts.getOrCreate("synthetic-account") {
            backgroundScope.async { requests++; codes.receive() }
        }
        val first = signIn()
        val page = async { first.await() }
        runCurrent()
        page.cancelAndJoin()
        assertTrue(first.isActive)
        // The code arrives while no page is listening, and is kept by the shared task.
        codes.send("01234")
        assertSame(first, signIn())
        assertEquals("01234", signIn().await())
        assertEquals(1, requests)
        codes.cancel()
    }

    @Test fun anActiveChallengeIsJoinedEvenAfterItsRequestCooldown() = runTest {
        var now = 0L
        val codes = Channel<String>()
        val attempts = InbarSmsAttemptRegistry<Deferred<String>>(completed = { it.isCompleted }, nowMs = { now })
        val first = attempts.getOrCreate("synthetic-account") { backgroundScope.async { codes.receive() } }
        runCurrent()
        now = 100_000
        assertSame(first, attempts.getOrCreate("synthetic-account") { fail("Must resume the active challenge"); first })
        codes.send("01234")
        assertEquals("01234", first.await())
    }

    @Test fun aFailedRequestKeepsItsActualErrorDuringCooldown() = runTest {
        var now = 0L
        val attempts = InbarSmsAttemptRegistry<Deferred<Unit>>(completed = { it.isCompleted }, nowMs = { now })
        val error = IOException("Inbar temporarily refused the request")
        val failed = CompletableDeferred<Unit>().apply { completeExceptionally(error) }
        attempts.getOrCreate("synthetic-account") { failed }
        now = 30_000
        val retry = attempts.getOrCreate("synthetic-account") { fail("Must not request another SMS"); failed }
        assertSame(failed, retry)
        try { retry.await(); fail("Expected the original failure") } catch (actual: IOException) { assertEquals(error.message, actual.message) }
        now = 90_000
        assertNull(attempts.find("synthetic-account"))
    }

    @Test fun aLocallyBlockedAttemptDoesNotExtendTheCooldown() {
        val attempts = InbarSmsAttemptRegistry<Deferred<Unit>>(completed = { it.isCompleted }, retainCompleted = { false })
        val rejected = CompletableDeferred<Unit>().apply { completeExceptionally(IOException("Wait briefly")) }
        attempts.getOrCreate("synthetic-account") { rejected }
        assertNull(attempts.find("synthetic-account"))
    }
}
