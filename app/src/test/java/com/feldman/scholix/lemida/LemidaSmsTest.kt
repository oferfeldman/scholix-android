package com.feldman.scholix.lemida

import org.junit.Assert.*
import org.junit.Test

class LemidaSmsTest {
    @Test fun acceptsMicrosoftVerificationOnly() {
        assertEquals("123456", LemidaSms.code("Use verification code 123456 for Microsoft authentication.", "1234"))
        assertEquals("654321", LemidaSms.code("קוד האימות שלך במיקרוסופט הוא 654321", "Microsoft"))
        assertNull(LemidaSms.code("Your bank verification code is 123456", "Bank"))
        assertNull(LemidaSms.code("Microsoft account phone: 123456", "Microsoft"))
    }
    @Test fun rejectsAmbiguousAndOversizedNumbers() {
        assertNull(LemidaSms.code("Microsoft verification 123456 or 654321", "Microsoft"))
        assertNull(LemidaSms.code("Microsoft verification 1234567890", "Microsoft"))
    }
    @Test fun submitCannotInjectJavascript() {
        assertThrows(IllegalArgumentException::class.java) { LemidaSms.chooseScript("'); alert('x") }
        assertThrows(IllegalArgumentException::class.java) { LemidaSms.submitScript("'); alert('x") }
        assertTrue(LemidaSms.submitScript("123456").contains("location.origin"))
    }
}
