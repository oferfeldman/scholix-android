package com.feldman.scholix.lemida

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LemidaCredentialPollTest {
    private class Fixture {
        var details: Pair<String, String>? = "student@example.edu" to "test-only-password"
        var document = "https://login.microsoftonline.com/fixture"
        val calls = mutableListOf<Pair<String, (String?) -> Unit>>()
        val timeouts = mutableListOf<() -> Unit>()
        val statuses = mutableListOf<String>()
        var missing = 0
        var preferred: String? = null
        var mfa = 0
        val poll = LemidaCredentialPoll({ details }, { true }, { document },
            { script, callback -> calls += script to callback }, { timeouts += it },
            { missing++ }, { statuses += it }, { mfa++ }, preferredAccount = { preferred ?: details?.first })
        fun answer(index: Int, value: String) = calls[index].second(JSONObject.quote(value))
    }
    @Test fun oneEmailAndPasswordAttemptEvenWhenClickAcknowledgementIsLost() {
        val f = Fixture()
        f.poll.poll(); f.answer(0, "email")
        f.poll.invalidate(); f.poll.poll(); f.answer(2, "email")
        assertEquals(3, f.calls.size)
        f.poll.poll(); f.answer(3, "password")
        f.timeouts.last()(); f.poll.poll(); f.answer(5, "password")
        assertEquals(6, f.calls.size)
    }
    @Test fun staleSameUrlCallbackCannotSubmitPasswordAfterReload() {
        val f = Fixture()
        f.poll.poll(); f.poll.invalidate(); f.poll.poll()
        f.answer(0, "password")
        assertEquals(2, f.calls.size)
        f.answer(1, "password")
        assertEquals(3, f.calls.size)
    }
    @Test fun changedCredentialsRejectOldCallbackAndExplicitEditAllowsNewAttempt() {
        val f = Fixture()
        f.poll.poll(); f.details = "new@example.edu" to "new-test-password"
        f.poll.detailsChanged(); f.answer(0, "password")
        assertEquals(1, f.calls.size)
        f.poll.poll(); f.answer(1, "password")
        assertTrue(f.calls.last().first.contains("new-test-password"))
    }
    @Test fun missingCredentialsDoNotSubmitOrStartMfa() {
        val f = Fixture(); f.details = null
        f.poll.poll(); f.answer(0, "email")
        assertEquals(1, f.missing); assertEquals(0, f.mfa); assertEquals(1, f.calls.size)
    }
    @Test fun errorsAndAccountMismatchNeverSubmitOrChooseSms() {
        val f = Fixture()
        for (result in listOf("blocked", "mismatch", "waiting")) {
            f.poll.poll(); f.answer(f.calls.lastIndex, result)
        }
        assertEquals(3, f.calls.size); assertEquals(0, f.mfa)
        assertTrue(f.statuses.none { it.contains("test-only-password") })
    }
    @Test fun unrelatedPageContinuesExistingMfaSequence() {
        val f = Fixture(); f.poll.poll(); f.answer(0, "other")
        assertEquals(1, f.mfa)
    }
    @Test fun oldTimeoutCannotAbandonNewDocumentProbe() {
        val f = Fixture(); f.poll.poll(); f.poll.invalidate(); f.poll.poll()
        f.timeouts.first()(); f.answer(1, "password")
        assertEquals(3, f.calls.size)
    }
    @Test fun rememberedAccountCanBeTappedWithoutAnyPassword() {
        val f = Fixture(); f.details = null; f.preferred = "student@example.edu"
        f.poll.poll(); f.answer(0, "account")
        assertEquals(2, f.calls.size); assertEquals(0, f.missing)
        assertFalse(f.calls.last().first.contains("test-only-password"))
        f.poll.invalidate(); f.poll.poll(); f.answer(2, "account")
        assertEquals(3, f.calls.size)
    }
}
