package com.feldman.scholix.drive

import android.app.Activity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Status
import org.junit.Assert.*
import org.junit.Test

class DriveAuthTest {
    @Test fun cancelledActivityWithDataStillParsesGoogleError() {
        val failure = ApiException(Status(10))
        try { DriveAuth.complete(Activity.RESULT_CANCELED, true) { throw failure }; fail("Must preserve the SDK failure") }
        catch (e: ApiException) {
            assertSame(failure, e)
            assertTrue(DriveAuth.message(e).contains("not configured"))
        }
    }
    @Test fun cancelledActivityWithoutDataIsCancellation() {
        try { DriveAuth.complete(Activity.RESULT_CANCELED, false) { fail("No data to parse"); null }; fail("Must cancel") }
        catch (_: DriveAuthorizationCancelled) { }
    }
    @Test fun successfulResultRequiresUsableToken() {
        assertEquals("synthetic-token", DriveAuth.complete(Activity.RESULT_OK, true) { "synthetic-token" })
        try { DriveAuth.complete(Activity.RESULT_OK, true) { "" }; fail("Missing token must require consent") }
        catch (_: DriveNeedsConsent) { }
    }
    @Test fun cancellationFromGoogleIsDistinguishedFromOtherSdkErrors() {
        assertEquals("Google Drive connection was cancelled.", DriveAuth.message(ApiException(Status(16))))
        assertTrue(DriveAuth.message(ApiException(Status(7))).contains("(7)"))
        assertTrue(DriveAuth.message(ApiException(Status(8))).contains("(8)"))
        assertFalse(DriveAuth.message(ApiException(Status(8))).contains("cancelled"))
    }
}
