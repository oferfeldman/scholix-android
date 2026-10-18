package com.feldman.scholix.api.platforms

import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/** A disposed UI must finish its HTTP call before another UI creates an SMS challenge. */
internal val inbarSmsLoginMutex = Mutex()
internal const val INBAR_SMS_WAIT_TIMEOUT_MS = 180_000L

/** Delayed/duplicate messages must not discard the current, still-valid challenge. */
internal suspend fun verifyInbarSmsCandidates(
    codes: ReceiveChannel<String>,
    timeoutMs: Long,
    verify: suspend (String) -> Unit
) {
    val startedAt = System.nanoTime()
    val attempted = mutableSetOf<String>()
    while (attempted.size < 3) {
        val remainingMs = timeoutMs - (System.nanoTime() - startedAt) / 1_000_000
        val code = withTimeoutOrNull(remainingMs) { codes.receive() }
        if (code == null) {
            if (attempted.isNotEmpty()) throw InbarSmsCodeRejected()
            throw IOException("No SMS code was received within three minutes. Please try signing in again.")
        }
        if (!attempted.add(code)) continue
        try {
            // The delivery deadline must not cancel verification/grade loading after a code arrives.
            verify(code)
            return
        } catch (_: InbarSmsCodeRejected) {
            // Retain the fresh form from the rejected response, without sending another SMS.
        }
    }
    throw InbarSmsCodeRejected()
}
