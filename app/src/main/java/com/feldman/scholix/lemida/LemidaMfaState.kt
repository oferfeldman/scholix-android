package com.feldman.scholix.lemida

/** In-memory challenge state. Codes never enter saved state, preferences, or logs. */
internal class LemidaMfaState {
    var alternativeClicked = false
        private set
    var smsSelected = false
        private set
    var submitted = false
        private set
    private var started: Long? = null
    private var pending: String? = null
    val hasPending get() = pending != null

    fun prepareAlternative(): Boolean {
        if (alternativeClicked) return false
        alternativeClicked = true
        return true
    }

    fun prepareSms(now: Long): Boolean {
        if (smsSelected) return false
        smsSelected = true
        alternativeClicked = true
        if (started == null) started = now
        return true
    }

    fun observeOtp(now: Long) {
        // A challenge already exists, whether initiated by the picker or by the user.
        // Losing the field later must not trigger another automatic method request.
        smsSelected = true
        alternativeClicked = true
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
}
