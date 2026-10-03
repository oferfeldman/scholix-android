package com.feldman.scholix.lemida

import org.junit.Assert.*
import org.junit.Test

class LemidaLoginProbeTest {
    @Test fun stalledCallbackCanBeAbandonedBeforeAnotherPoll() {
        val probe = LemidaLoginProbe()
        val stalled = probe.start()!!
        assertNull(probe.start())
        assertTrue(probe.abandon(stalled))
        assertFalse(probe.complete(stalled))
        assertFalse(probe.isCurrent(stalled))
        val next = probe.start()!!
        assertTrue(probe.complete(next))
    }

    @Test fun anOldTimeoutCannotAbandonTheNewDocumentPoll() {
        val probe = LemidaLoginProbe()
        val abandoned = probe.start()!!
        probe.invalidate()
        val next = probe.start()!!
        assertFalse(probe.abandon(abandoned))
        assertNull(probe.start())
        assertTrue(probe.complete(next))
    }

    @Test fun aCompletedPollCannotBeAbandonedByItsLaterTimeout() {
        val probe = LemidaLoginProbe()
        val request = probe.start()!!
        assertTrue(probe.complete(request))
        assertFalse(probe.abandon(request))
        assertTrue(probe.isCurrent(request))
    }

    @Test fun completedProbeRejectsSecondaryCallbacksAfterNavigationOrExplicitRetry() {
        val probe = LemidaLoginProbe()
        val request = probe.start()!!
        assertTrue(probe.complete(request))
        assertTrue(probe.isCurrent(request))
        probe.invalidate()
        assertFalse(probe.isCurrent(request))
        val retry = probe.start()!!
        assertTrue(probe.isCurrent(retry))
        assertFalse(probe.isCurrent(request))
    }

    @Test fun newerSameDocumentProbeInvalidatesAnEarlierSecondaryCallback() {
        val probe = LemidaLoginProbe()
        val first = probe.start()!!
        assertTrue(probe.complete(first))
        val next = probe.start()!!
        assertFalse(probe.isCurrent(first))
        assertTrue(probe.isCurrent(next))
        assertTrue(probe.complete(next))
    }

    @Test fun duplicatePageFinishesWaitForTheCurrentProbe() {
        val probe = LemidaLoginProbe()
        val request = probe.start()!!
        assertNull(probe.start())
        assertTrue(probe.complete(request))
        assertNotNull(probe.start())
    }

    @Test fun navigationReleasesALostCallbackWithoutBlockingTheNewPage() {
        val probe = LemidaLoginProbe()
        val abandoned = probe.start()!!
        probe.invalidate()
        val next = probe.start()!!
        assertFalse(probe.complete(abandoned))
        assertNull(probe.start())
        assertTrue(probe.complete(next))
    }

    @Test fun lateSamePageCallbacksCannotCompleteANewerProbe() {
        val probe = LemidaLoginProbe()
        val first = probe.start()!!
        assertTrue(probe.complete(first))
        val next = probe.start()!!
        assertFalse(probe.complete(first))
        assertTrue(probe.complete(next))
    }

    @Test fun failedPageAllowsRetryAndRejectsTheAbandonedResult() {
        val probe = LemidaLoginProbe()
        val failed = probe.start()!!
        probe.invalidate()
        probe.invalidate()
        assertFalse(probe.complete(failed))
        val retry = probe.start()!!
        assertTrue(probe.complete(retry))
    }
}
