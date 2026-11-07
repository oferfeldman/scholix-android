package com.feldman.scholix.sms

import com.feldman.scholix.api.platforms.inbarAutomaticSmsCode
import com.feldman.scholix.lemida.LemidaSms
import com.feldman.scholix.lockerim.lockerimSmsCode
import org.junit.Assert.*
import org.junit.Test

class ForwardedSmsTest {
    @Test fun forwardedCodeMustAppearInTheForwardedMessage() {
        assertNotNull(ForwardedSmsClip.from("Use 482913 as Microsoft account security code", "Microsoft", "482913", 10))
        assertNotNull(ForwardedSmsClip.from("Your login code: 123-456", "Bank", "123456", 10))
        assertNull(ForwardedSmsClip.from("Use 482913 as Microsoft account security code", "Microsoft", "999999", 10))
        assertNull(ForwardedSmsClip.from("Use 12 as code", "x", "12", 10))
    }

    @Test fun providersApplyTheirOwnSenderChecksToForwardedTexts() {
        val microsoft = ForwardedSmsClip.from("Use 482913 as Microsoft account verification code", "Microsoft", "482913", 10)!!
        assertEquals("482913", LemidaSms.code(microsoft.body, microsoft.sender))
        val other = ForwardedSmsClip.from("Your verification code is 482913", "Some shop", "482913", 10)!!
        assertNull(LemidaSms.code(other.body, other.sender))
        assertNull(lockerimSmsCode(other.body, other.sender))
        assertNull(inbarAutomaticSmsCode(other.body, other.sender))
        val lockerim = ForwardedSmsClip.from("קוד האימות שלך ללוקרים: 4821", "Lockerim", "4821", 10)!!
        assertEquals("4821", lockerimSmsCode(lockerim.body, lockerim.sender))
    }

    @Test fun onlyTextsForwardedAfterTheRequestAreDeliveredOnce() {
        assertFalse(ForwardedSmsWatcher.fresh(receivedAt = 900, since = 1_000, lastDelivered = 0))
        assertTrue(ForwardedSmsWatcher.fresh(receivedAt = 1_200, since = 1_000, lastDelivered = 0))
        assertFalse(ForwardedSmsWatcher.fresh(receivedAt = 1_200, since = 1_000, lastDelivered = 1_200))
    }
}
