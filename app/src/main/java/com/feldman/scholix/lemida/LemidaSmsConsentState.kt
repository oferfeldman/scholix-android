package com.feldman.scholix.lemida

/** Listener timing only; renewing consent never requests a verification SMS. */
internal class LemidaSmsConsentState {
    private var generation = 0
    private var requestStarted: Long? = null
    private var listeningStarted: Long? = null
    private var pending = false
    private var challengePrepared = false

    fun start(now: Long): Int {
        generation++
        requestStarted = now
        listeningStarted = null
        pending = true
        return generation
    }

    fun complete(request: Int, successful: Boolean, now: Long) {
        if (request != generation || !pending || !startupWithinDeadline(now)) return
        pending = false
        // Count from the request, conservatively allowing for slow Play services startup.
        listeningStarted = if (successful) requestStarted else null
    }

    fun ready(now: Long): Boolean {
        if (pending && !startupWithinDeadline(now)) pending = false
        return !pending
    }

    /** Reserve the full three-minute MFA window inside Google's five-minute listener. */
    fun prepareChallenge(now: Long): Boolean {
        if (challengePrepared) return false
        challengePrepared = true
        val started = listeningStarted ?: return true
        return now < started || now - started >= 120_000
    }

    private fun startupWithinDeadline(now: Long): Boolean {
        val started = requestStarted ?: return false
        return now >= started && now - started < 5_000
    }
}
