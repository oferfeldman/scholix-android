package com.feldman.scholix.lemida

/** In-memory challenge state. Codes never enter saved state, preferences, or logs. */
internal class LemidaMfaState {
    /** Steps taken towards Microsoft's method list; a link can lead to a second list. */
    var alternativeSteps = 0
        private set
    /** No more automatic steps towards other methods (SMS is chosen at most once anyway). */
    val alternativeClicked get() = alternativeSteps >= MAX_ALTERNATIVE_STEPS
    var smsSelected = false
        private set
    var submitted = false
        private set
    private var started: Long? = null
    private var pending: String? = null
    val hasPending get() = pending != null

    fun prepareAlternative(): Boolean {
        if (alternativeClicked) return false
        alternativeSteps++
        return true
    }

    /** A step whose click certainly did not happen is returned, unless SMS is already chosen. */
    fun undoAlternative() {
        if (!smsSelected && alternativeSteps in 1..MAX_ALTERNATIVE_STEPS) alternativeSteps--
    }

    fun prepareSms(now: Long): Boolean {
        if (smsSelected) return false
        smsSelected = true
        alternativeSteps = MAX_ALTERNATIVE_STEPS
        if (started == null) started = now
        return true
    }

    fun observeOtp(now: Long) {
        // A challenge already exists, whether initiated by the picker or by the user.
        // Losing the field later must not trigger another automatic method request.
        smsSelected = true
        alternativeSteps = MAX_ALTERNATIVE_STEPS
        if (started == null) started = now
    }

    fun active(now: Long): Boolean {
        val requestTime = started ?: return false
        return !submitted && now >= requestTime && now - requestTime <= 180_000
    }

    fun acceptCode(body: String, sender: String, now: Long): Boolean {
        if (!active(now)) return false
        val code = LemidaSms.code(body, sender) ?: return false
        pending = code
        return true
    }

    fun pendingCode(now: Long): String? {
        if (!active(now)) pending = null
        return pending
    }

    fun consumeCode(now: Long): String? {
        val code = pendingCode(now) ?: return null
        pending = null
        submitted = true // Record before JavaScript can submit/navigate, even if its callback is lost.
        return code
    }

    fun discardCode() { pending = null }

    companion object { const val MAX_ALTERNATIVE_STEPS = 2 }
}
