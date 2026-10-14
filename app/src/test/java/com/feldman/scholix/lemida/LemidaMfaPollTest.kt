package com.feldman.scholix.lemida

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Controlled callbacks exercise the actual polling sequence without a browser or SMS. */
class LemidaMfaPollTest {
    private class Fixture {
        val mfa = LemidaMfaState()
        var time = 1_000L
        var document = "https://login.microsoftonline.com/fixture"
        var receptionReady = true
        val calls = mutableListOf<Pair<String, (String?) -> Unit>>()
        val timeouts = mutableListOf<() -> Unit>()
        val statuses = mutableListOf<String>()
        val poll = LemidaMfaPoll(mfa, { time }, { true }, { document }, { receptionReady },
            { script, callback -> calls += script to callback }, { timeouts += it }, { statuses += it })

        fun buffer(code: String = "123456") {
            mfa.prepareSms(time)
            time++
            assertTrue(mfa.acceptCode("Microsoft verification code $code", "Microsoft", time))
        }
        fun answer(index: Int, value: String) = calls[index].second(value)
        fun selected(index: Int, value: String) = answer(index, JSONObject.quote(value))
    }

    @Test fun lostPickerCallbackRecoversWithoutAcceptingItsLateResult() {
        val f = Fixture()
        f.poll.poll()
        repeat(3) { f.time += 750; f.poll.poll() }
        assertEquals(1, f.calls.size)
        f.time += 5_000
        f.timeouts.first()()
        f.poll.poll()
        assertEquals(2, f.calls.size)
        f.selected(0, "sms")
        assertFalse("An abandoned picker must not request SMS", f.mfa.smsSelected)
        f.selected(1, "other")
        f.poll.poll()
        assertEquals(3, f.calls.size)
    }

    @Test fun lostSmsClickCallbackCannotRequestAnotherSmsOrChangeNewStatus() {
        val f = Fixture()
        f.poll.poll()
        f.selected(0, "sms")
        assertTrue(f.mfa.smsSelected)
        assertEquals(2, f.calls.size)
        f.time += 5_000
        f.timeouts.first()()
        f.poll.poll()
        f.selected(2, "sms")
        f.answer(1, "false")
        assertEquals("An attempted SMS choice must never be clicked twice", 3, f.calls.size)
        assertEquals(1, f.statuses.size)
    }

    @Test fun consentPreparationDoesNotLockSmsChoiceBeforeReceptionIsReady() {
        val f = Fixture()
        f.receptionReady = false
        f.poll.poll()
        f.selected(0, "sms")
        assertFalse(f.mfa.smsSelected)
        assertEquals(1, f.calls.size)
        f.receptionReady = true
        f.poll.poll()
        f.selected(1, "sms")
        assertTrue(f.mfa.smsSelected)
        assertEquals(3, f.calls.size)
        f.answer(2, "true")
    }

    @Test fun slowReadinessAndSubmissionRemainValidAcrossPollingTicks() {
        val f = Fixture()
        f.buffer()
        f.poll.poll()
        f.selected(0, "otp")
        repeat(3) { f.time += 750; f.poll.poll() }
        assertEquals("Preparation must keep the current sequence pending", 2, f.calls.size)
        assertFalse(f.mfa.submitted)
        f.answer(1, "true")
        assertTrue(f.mfa.submitted)
        assertEquals(3, f.calls.size)
        repeat(2) { f.time += 750; f.poll.poll() }
        assertEquals("Submission acknowledgement must keep the sequence pending", 3, f.calls.size)
        f.answer(2, "true")
        f.poll.poll()
        f.selected(3, "otp")
        assertEquals("An already submitted code must not be prepared again", 4, f.calls.size)
        assertEquals(1, f.statuses.count { it.contains("code submitted") })
    }

    @Test fun sameUrlNavigationRejectsOldReadinessAndOldTimeout() {
        val f = Fixture()
        f.buffer()
        f.poll.poll()
        f.selected(0, "otp")
        f.poll.invalidate() // A new document can retain the same Microsoft URL.
        f.poll.poll()
        f.timeouts.first()()
        f.answer(1, "true")
        assertFalse(f.mfa.submitted)
        f.selected(2, "otp")
        f.answer(3, "true")
        f.answer(4, "true")
        assertTrue(f.mfa.submitted)
        assertFalse("Navigation must retain the single-request guard", f.mfa.prepareSms(f.time))
    }

    @Test fun delayedReadinessCannotExtendTheOriginalCodeDeadline() {
        val f = Fixture()
        f.buffer()
        f.poll.poll()
        f.selected(0, "otp")
        f.time = 181_001L
        f.answer(1, "true")
        assertFalse(f.mfa.submitted)
        assertFalse(f.mfa.hasPending)
        f.poll.poll()
        f.selected(2, "otp")
        assertEquals(3, f.calls.size)
        assertFalse(f.mfa.active(f.time))
    }

    @Test fun changedBufferedCodeIsPreparedAgainBeforeItCanBeConsumed() {
        val f = Fixture()
        f.buffer()
        f.poll.poll()
        f.selected(0, "otp")
        f.time++
        assertTrue(f.mfa.acceptCode("Microsoft verification code 112233", "Microsoft", f.time))
        f.answer(1, "true")
        assertFalse(f.mfa.submitted)
        f.poll.poll()
        f.selected(2, "otp")
        assertTrue(f.calls[3].first.contains("112233"))
        f.answer(3, "true")
        assertTrue(f.calls[4].first.contains("112233"))
        f.answer(4, "true")
        assertTrue(f.mfa.submitted)
    }

    @Test fun aTemporarilyDisabledVerifyButtonRetainsTheCodeForTheNextSequence() {
        val f = Fixture()
        f.buffer()
        f.poll.poll()
        f.selected(0, "otp-waiting")
        f.answer(1, "false")
        assertFalse(f.mfa.submitted)
        assertEquals("123456", f.mfa.pendingCode(f.time))
        f.poll.poll()
        f.selected(2, "otp")
        f.answer(3, "true")
        f.answer(4, "true")
        assertTrue(f.mfa.submitted)
    }
}
