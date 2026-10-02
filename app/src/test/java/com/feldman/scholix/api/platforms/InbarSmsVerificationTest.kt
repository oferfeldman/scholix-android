package com.feldman.scholix.api.platforms

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class InbarSmsVerificationTest {
    @Test fun delayedCodeThenCurrentCodeCompletesWithoutAnotherSmsRequest() = runBlocking<Unit> {
        val codes = Channel<String>(Channel.BUFFERED)
        codes.send("11111")
        codes.send("22222")
        val verified = mutableListOf<String>()
        verifyInbarSmsCandidates(codes, 1000) { code ->
            verified += code
            if (code == "11111") throw InbarSmsCodeRejected()
        }
        assertEquals(listOf("11111", "22222"), verified)
        codes.close()
    }

    @Test fun duplicateRejectedMessageIsNotSubmittedAgain() = runBlocking<Unit> {
        val codes = Channel<String>(Channel.BUFFERED)
        listOf("11111", "11111", "22222").forEach { codes.send(it) }
        val verified = mutableListOf<String>()
        verifyInbarSmsCandidates(codes, 1000) { code ->
            verified += code
            if (code == "11111") throw InbarSmsCodeRejected()
        }
        assertEquals(listOf("11111", "22222"), verified)
        codes.close()
    }

    @Test fun networkFailureDoesNotConsumeAnotherCode() {
        val codes = Channel<String>(Channel.BUFFERED)
        codes.trySend("11111")
        codes.trySend("22222")
        var attempts = 0
        assertThrows(IOException::class.java) {
            runBlocking {
                verifyInbarSmsCandidates(codes, 1000) { attempts++; throw IOException("server unavailable") }
            }
        }
        assertEquals(1, attempts)
        assertEquals("22222", codes.tryReceive().getOrNull())
        codes.close()
    }

    @Test fun rejectedCandidatesAreBounded() {
        val codes = Channel<String>(Channel.BUFFERED)
        listOf("11111", "22222", "33333", "44444").forEach { codes.trySend(it) }
        var attempts = 0
        assertThrows(InbarSmsCodeRejected::class.java) {
            runBlocking {
                verifyInbarSmsCandidates(codes, 1000) { attempts++; throw InbarSmsCodeRejected() }
            }
        }
        assertEquals(3, attempts)
        assertEquals("44444", codes.tryReceive().getOrNull())
        codes.close()
    }

    @Test fun waitingForCurrentCodeTimesOutAndCanBeCancelled() = runBlocking<Unit> {
        val codes = Channel<String>(Channel.BUFFERED)
        assertThrows(IOException::class.java) {
            runBlocking { verifyInbarSmsCandidates(codes, 10) { fail("No code arrived") } }
        }
        val waiting = async { verifyInbarSmsCandidates(codes, 1000) { fail("No code arrived") } }
        yield()
        waiting.cancelAndJoin()
        assertTrue(waiting.isCancelled)
        codes.close()
    }
}
