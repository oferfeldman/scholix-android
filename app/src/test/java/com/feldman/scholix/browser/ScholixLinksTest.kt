package com.feldman.scholix.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScholixLinksTest {
    @Test fun webPagesStayInsideScholix() {
        assertEquals(LinkRoute.InApp("https://lemida.biu.ac.il/mod/assign/view.php?id=7#top"),
            ScholixLinks.route("https://lemida.biu.ac.il/mod/assign/view.php?id=7#top"))
        // Unencrypted pages are blocked by Android, so the same address loads over HTTPS.
        assertEquals(LinkRoute.InApp("https://www.example.edu/page?a=1"), ScholixLinks.route("HTTP://www.example.edu/page?a=1"))
        assertEquals(LinkRoute.InApp("https://host.example:8443/x%20y"), ScholixLinks.route(" https://host.example:8443/x%20y "))
    }

    @Test fun classroomAndDriveOpenInGoogleAppsFirst() {
        assertEquals(LinkRoute.GoogleApp("https://classroom.google.com/c/abc", "com.google.android.apps.classroom"),
            ScholixLinks.route("https://classroom.google.com/c/abc"))
        assertEquals("com.google.android.apps.docs",
            (ScholixLinks.route("https://drive.google.com/file/d/1/view") as LinkRoute.GoogleApp).packageName)
    }

    @Test fun emailAndPhoneGoToTheirApps() {
        assertEquals(LinkRoute.Contact("mailto:office@example.edu"), ScholixLinks.route("mailto:office@example.edu"))
        assertEquals(LinkRoute.Contact("tel:+972000000"), ScholixLinks.route("tel:+972000000"))
    }

    @Test fun otherSchemesAndDisguisedAddressesAreRefused() {
        listOf("intent://scan/#Intent;scheme=zxing;end", "javascript:alert(1)", "file:///sdcard/x.pdf",
            "content://com.example/file", "market://details?id=x", "https://user@evil.example/", "https://", "not a url")
            .forEach { assertTrue(it, ScholixLinks.route(it) is LinkRoute.Refused) }
    }

    @Test fun onlyHttpsAddressesAreLoadable() {
        assertTrue(ScholixLinks.loadable("https://lemida.biu.ac.il/my/"))
        assertTrue(ScholixLinks.loadable("https://classroom.google.com/c/abc"))
        assertFalse(ScholixLinks.loadable("http://lemida.biu.ac.il/my/"))
        assertFalse(ScholixLinks.loadable("mailto:office@example.edu"))
        assertFalse(ScholixLinks.loadable("javascript:alert(1)"))
    }
}
