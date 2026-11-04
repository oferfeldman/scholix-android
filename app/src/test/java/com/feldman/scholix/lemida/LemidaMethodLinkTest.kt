package com.feldman.scholix.lemida

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The link that leads from Authenticator to the SMS option, as Microsoft words it. */
class LemidaMethodLinkTest {
    private val pattern = LemidaSms.selectScript(alternativeClicked = false, smsSelected = true)
        .lines().first { "verification methods" in it && "/^(" in it }
        .substringAfter("/^(").substringBefore(")/i")
        .let { Regex("^($it)", RegexOption.IGNORE_CASE) }

    @Test fun everyWordingOfTheOtherMethodsLinkIsRecognized() {
        listOf("Show more verification methods", "Show me verification methods", "Sign in another way",
            "I can't use my Microsoft Authenticator app right now").forEach {
            assertTrue(it, pattern.containsMatchIn(it))
        }
    }

    @Test fun otherLinksOnTheVerifyYourIdentityPageAreNotPressed() {
        listOf("More information", "Use a verification code", "Approve a request on my Microsoft Authenticator app",
            "Cancel", "What is my Username?").forEach {
            assertFalse(it, pattern.containsMatchIn(it))
        }
    }
}
