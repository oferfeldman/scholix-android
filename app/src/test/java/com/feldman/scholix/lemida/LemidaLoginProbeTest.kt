package com.feldman.scholix.lemida

import org.junit.Assert.*
import org.junit.Test

class LemidaLoginProbeTest {
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
