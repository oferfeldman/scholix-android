package com.feldman.scholix.lockerim

import org.junit.Assert.*
import org.junit.Test

class LockerCodeTest {
    @Test fun numericCodesKeepLeadingZerosAndVariableLengths() {
        for (pin in listOf("007", "0123", "00005", "01234567890123456789")) {
            val code = LockerCode(name = "Locker", pin = pin)
            assertTrue(code.valid())
            assertEquals(pin, LockerCode.fromJson(code.toJson()).pin)
        }
        assertFalse(LockerCode(name = "Locker", pin = "12a").valid())
        assertFalse(LockerCode(name = "Locker").valid())
    }

    @Test fun dialCodesKeepEachDirectionTurnCountAndNumber() {
        val code = LockerCode(name = "Gym", dial = true, pin = "unused", steps = listOf(
            DialStep("0", false, 4), DialStep("32", true, 2), DialStep("007", false, 1), DialStep("81", true, 1)))
        val restored = LockerCode.fromJson(code.toJson())
        assertTrue(restored.valid())
        assertEquals(code.steps, restored.steps)
        assertEquals("", restored.pin)
        assertFalse(code.copy(steps = emptyList()).valid())
        assertFalse(code.copy(steps = listOf(DialStep("7", turns = 0))).valid())
        assertFalse(code.copy(steps = listOf(DialStep(""))).valid())
    }
}
