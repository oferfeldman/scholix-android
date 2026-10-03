package com.feldman.scholix.lemida

import org.junit.Assert.*
import org.junit.Test

class LemidaSmsConsentStateTest {
    @Test fun recentListenerCoversTheWholeChallengeWithoutRestarting() {
        val state = LemidaSmsConsentState()
        val request = state.start(1_000)
        state.complete(request, true, 1_500)
        assertTrue(state.ready(2_000))
        assertFalse(state.prepareChallenge(120_999))
        assertFalse(state.prepareChallenge(400_000))
    }

    @Test fun longPasswordOrCaptchaWaitRenewsBeforeTheFirstSmsAttempt() {
        val state = LemidaSmsConsentState()
        val first = state.start(1_000)
        state.complete(first, true, 1_100)
        assertTrue(state.prepareChallenge(121_000))
        val renewed = state.start(121_000)
        assertFalse(state.ready(121_100))
        state.complete(renewed, true, 121_500)
        assertTrue(state.ready(121_500))
        assertFalse(state.prepareChallenge(121_500))
    }

    @Test fun failedStartupGetsOneChallengeRetryWithoutAnEndlessPickerWait() {
        val state = LemidaSmsConsentState()
        val first = state.start(1_000)
        state.complete(first, false, 1_100)
        assertTrue(state.ready(1_100))
        assertTrue(state.prepareChallenge(2_000))
        val renewed = state.start(2_000)
        state.complete(renewed, false, 2_100)
        assertTrue(state.ready(2_100))
        assertFalse(state.prepareChallenge(3_000))
    }

    @Test fun stalledStartupFallsBackAfterFiveSecondsAndIgnoresLateSuccess() {
        val state = LemidaSmsConsentState()
        val request = state.start(1_000)
        assertFalse(state.ready(5_999))
        assertTrue(state.ready(6_000))
        state.complete(request, true, 6_001)
        assertTrue(state.prepareChallenge(6_001))
    }

    @Test fun oldCallbacksCannotCompleteOrInvalidateTheNewListener() {
        val state = LemidaSmsConsentState()
        val old = state.start(1_000)
        val current = state.start(2_000)
        state.complete(old, true, 2_100)
        assertFalse(state.ready(2_100))
        state.complete(current, true, 2_500)
        state.complete(old, false, 2_600)
        assertTrue(state.ready(2_600))
        assertFalse(state.prepareChallenge(3_000))
    }

    @Test fun listenerRenewalDoesNotResetMfaDeadlineOrRepeatMethodSelection() {
        val state = LemidaSmsConsentState()
        val mfa = LemidaMfaState()
        mfa.observeOtp(1_000)
        assertTrue(state.prepareChallenge(2_000))
        val request = state.start(2_000)
        state.complete(request, true, 2_100)
        assertFalse(mfa.prepareSms(2_100))
        assertFalse(mfa.prepareAlternative())
        assertFalse(mfa.active(181_001))
        assertFalse(state.prepareChallenge(181_001))
    }
}
