package com.feldman.scholix.api.platforms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MashovSmsTest {
    @Test fun codeFromMashovSenderIsAccepted() {
        assertEquals("123456", mashovSmsCode("קוד ההתחברות הוא 123456", "Mashov"))
    }

    @Test fun HebrewMashovBrandWithQuotationMarksIsAccepted() {
        assertEquals("654321", mashovSmsCode("משו\"ב: קוד הכניסה שלך הוא 654321"))
    }

    @Test fun unrelatedSmsCodeIsIgnored() {
        assertNull(mashovSmsCode("קוד ההתחברות לבנק הוא 123456", "Bank"))
    }
}
