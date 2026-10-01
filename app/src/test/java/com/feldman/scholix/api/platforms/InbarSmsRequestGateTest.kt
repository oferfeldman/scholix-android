package com.feldman.scholix.api.platforms

import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class InbarSmsRequestGateTest {
    @Test fun duplicateRequestsDuringTheActiveWindowAreRejected() {
        for (elapsed in listOf(0L, 1000L, 45_000L, 89_999L)) {
            assertThrows(IOException::class.java) { checkInbarSmsRequestInterval(100_000, 100_000 + elapsed) }
        }
    }
    @Test fun firstRequestAndRequestsAfterTheWindowAreAllowed() {
        checkInbarSmsRequestInterval(0, 100_000)
        checkInbarSmsRequestInterval(100_000, 190_000)
        checkInbarSmsRequestInterval(100_000, 200_000)
    }
    @Test fun changingDeviceClockDoesNotPermanentlyBlockLogin() {
        checkInbarSmsRequestInterval(200_000, 100_000)
    }
}
