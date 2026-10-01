package com.feldman.scholix.api.platforms

import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/** A disposed UI must finish its HTTP call before another UI creates an SMS challenge. */
internal val inbarSmsLoginMutex = Mutex()

/** Delayed/duplicate messages must not discard the current, still-valid challenge. */
internal suspend fun verifyInbarSmsCandidates(
    codes: ReceiveChannel<String>,
    timeoutMs: Long,
    verify: suspend (String) -> Unit
) {
    val completed = withTimeoutOrNull(timeoutMs) {
        val attempted = mutableSetOf<String>()
        while (attempted.size < 3) {
            val code = codes.receive()
            if (!attempted.add(code)) continue
            try {
                verify(code)
                return@withTimeoutOrNull true
            } catch (_: InbarSmsCodeRejected) {
                // Retain the fresh form from the rejected response, without sending another SMS.
            }
        }
        throw InbarSmsCodeRejected()
    }
    if (completed != true) throw IOException("SMS verification timed out. Please try signing in again.")
}
