package com.feldman.scholix.lemida

import org.junit.Assert.*
import org.junit.Test

class LemidaMfaStateTest {
    private val message = "Use verification code 123456 for Microsoft authentication."

    @Test fun formPreparationKeepsTheCodeUntilVerifyIsReady() {
        val state = LemidaMfaState()
        state.observeOtp(1_000)
        state.acceptCode(message, "Microsoft", 1_500)
        assertEquals("123456", state.pendingCode(2_000))
        assertEquals("123456", state.pendingCode(3_000))
        assertFalse(state.submitted)
        assertTrue(state.hasPending)
        assertEquals("123456", state.consumeCode(3_000))
        assertTrue(state.submitted)
        assertNull(state.pendingCode(3_500))
    }

    @Test fun disabledFormCannotKeepACodeBeyondTheOriginalDeadline() {
        val state = LemidaMfaState()
        state.observeOtp(1_000)
        state.acceptCode(message, "Microsoft", 1_500)
        assertEquals("123456", state.pendingCode(181_000))
        state.observeOtp(181_001)
        assertNull(state.pendingCode(181_001))
        assertFalse(state.hasPending)
        assertFalse(state.submitted)
    }

    @Test fun existingOtpPreventsAnotherAutomaticMethodRequest() {
        val state = LemidaMfaState()
        state.observeOtp(1_000)
        assertFalse(state.prepareAlternative())
        assertFalse(state.prepareSms(2_000))
        assertTrue(state.active(181_000))
        state.observeOtp(181_001)
        assertFalse("Repeated probes must not extend the deadline", state.active(181_001))
    }

    @Test fun earlySmsBeforeTheCodeFieldIsBufferedAndConsumedOnce() {
        val state = LemidaMfaState()
        assertTrue(state.prepareSms(0))
        assertTrue(state.acceptCode(message, "Microsoft", 500))
        state.observeOtp(1_000)
        assertEquals("123456", state.consumeCode(1_000))
        assertNull(state.consumeCode(1_500))
        assertFalse(state.acceptCode(message, "Microsoft", 2_000))
        assertFalse(state.prepareSms(2_000))
    }

    @Test fun bufferedCodesAreDiscardedAfterExpiry() {
        val state = LemidaMfaState()
        state.prepareSms(1_000)
        state.acceptCode(message, "Microsoft", 1_500)
        assertNull(state.consumeCode(181_001))
        assertFalse(state.hasPending)
        assertFalse(state.prepareSms(181_001))
    }

    @Test fun unsolicitedUnrelatedAndOlderMessagesCannotPopulateAChallenge() {
        val state = LemidaMfaState()
        assertFalse(state.acceptCode(message, "Microsoft", 500))
        state.prepareSms(1_000)
        assertFalse(state.acceptCode(message, "Microsoft", 999))
        assertFalse(state.acceptCode("Your bank verification code is 123456", "Bank", 1_500))
        assertNull(state.consumeCode(1_500))
        assertTrue(state.acceptCode(message, "Microsoft", 1_500))
    }

    @Test fun failedClickCannotRepeatTheAutomaticSmsRequest() {
        val state = LemidaMfaState()
        assertTrue(state.prepareAlternative())
        assertFalse(state.prepareAlternative())
        assertTrue(state.prepareSms(1_000))
        // A missing/lost WebView click callback doesn't undo the native attempt flag.
        assertFalse(state.prepareSms(2_000))
    }
}
