package com.feldman.scholix.ui.components

import com.feldman.scholix.api.platforms.WebtopPlatform
import com.feldman.scholix.ui.moeProblemMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignInProblemTest {
    @Test fun webtopsOwnReasonReplacesTheGenericReLogin() {
        val webtop = webtopThatFailed("Webtop requires additional verification or a password update.")
        val message = signInFailedMessage(webtop)
        assertEquals("Couldn't sign in to Webtop.\nWebtop requires additional verification or a password update.", message)
        assertTrue(isSignInProblem(message))
    }

    @Test fun theMinistrySignInsReasonWinsOverAStaleWebtopOne() {
        val webtop = webtopThatFailed("old")
        assertEquals("Couldn't sign in to Webtop.\nThe page did not load.", signInFailedMessage(webtop, "The page did not load."))
    }

    @Test fun withoutAReasonItStillSaysWhichProvider() {
        assertEquals("Couldn't sign in to Webtop.\nThe saved sign-in no longer works.", signInFailedMessage(WebtopPlatform()))
    }

    @Test fun otherErrorsDoNotOfferSignInAgain() {
        assertFalse(isSignInProblem("Cannot reach the server.\nCheck your internet connection."))
        assertFalse(isSignInProblem(null))
    }

    @Test fun aFailedRefreshIsAnnouncedOnlyOnItsOwnProviderAndOnce() {
        val failures = setOf("Webtop")
        assertNull(refreshFailureToAnnounce(null, failures, emptyList()))        // Lemida pages
        assertNull(refreshFailureToAnnounce("OpenU", failures, emptyList()))
        assertEquals("Webtop", refreshFailureToAnnounce("Webtop", failures, emptyList()))
        assertNull(refreshFailureToAnnounce("Webtop", failures, listOf("Webtop")))
        assertNull(refreshFailureToAnnounce("Webtop", emptySet(), emptyList()))
    }

    @Test fun addingAnAccountShowsWebtopsOwnReason() {
        val blocked = webtopThatFailed("Webtop sign-in is temporarily unavailable (HTTP 403). Try again later or sign in on the provider website.")
        assertEquals(blocked.loginError, addAccountFailedMessage(blocked))
        assertEquals("Invalid credentials", addAccountFailedMessage(WebtopPlatform()))
        assertEquals("Invalid credentials", addAccountFailedMessage(null))
    }

    @Test fun aMinistryPageThatDidNotMoveOnIsExplained() {
        assertTrue(moeProblemMessage("problem:Wrong password")!!.startsWith("The Ministry of Education said: Wrong password"))
        assertTrue(moeProblemMessage("otp")!!.contains("MOE SMS"))
        assertTrue(moeProblemMessage("newpassword")!!.contains("new password"))
        assertNull(moeProblemMessage("none"))
        assertNull(moeProblemMessage(""))
    }
}

/** A Webtop account whose last sign-in failed with [reason]; the platform sets it only on a real sign-in. */
private fun webtopThatFailed(reason: String) = WebtopPlatform().also { webtop ->
    WebtopPlatform::class.java.getDeclaredField("loginError").apply { isAccessible = true }.set(webtop, reason)
}
