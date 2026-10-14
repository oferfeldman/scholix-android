package com.feldman.scholix.api.platforms

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.feldman.scholix.api.PlatformStorage
import com.feldman.scholix.ui.InbarSmsLoginSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in live check. Reuses valid cookies, otherwise requests one normal SMS sign-in. */
@RunWith(AndroidJUnit4::class)
class InbarLoginSmokeTest {
    @Test fun savedAccountCanReadGradesAfterSignIn() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("inbarLiveLogin") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(context.packageName.endsWith(".inbar.dev"))
        assumeTrue(context.checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED)
        val original = PlatformStorage.loadPlatforms(context).filterIsInstance<InbarPlatform>().firstOrNull()
        assumeTrue(original != null && original.hasSavedLoginDetails())
        val account = original!!.forSmsLogin()
        val overrides = PlatformStorage.loadProviderCourseOverrides(context, original.id)
        val verified = try { InbarSmsLoginSession().signIn(context, account, useDirect = true,
            saveAccount = { withContext(Dispatchers.IO) { PlatformStorage.saveInbarLoginProgress(context, it) } },
            reuseSession = { withContext(Dispatchers.IO) { PlatformStorage.restoreVerifiedInbarSession(context, account) } },
            timeoutMs = 90_000) } catch(e: InbarGradeLayoutChanged) {
            instrumentation.sendStatus(2, Bundle().apply { putString("inbarTableLayout", e.layout) })
            throw e
        }
        assertTrue(verified.isLoggedIn())
        // Exercise every available academic year, including an empty current year.
        val grades = withContext(Dispatchers.IO) { verified.getGrades("all", null, null) }
        for (i in 0 until grades.length()) assertFalse(grades.getJSONObject(i).has("error"))
        withContext(Dispatchers.IO) { PlatformStorage.saveInbarLoginProgress(context, verified) }
        assertEquals(original.id, verified.id)
        assertTrue(original.getUsername() == verified.getUsername() && original.mobile == verified.mobile)
        assertEquals(overrides, PlatformStorage.loadProviderCourseOverrides(context, original.id))
        assertEquals(1, PlatformStorage.loadPlatforms(context).count { it.id == original.id })
        instrumentation.sendStatus(2, Bundle().apply { putBoolean("inbarLoginAndGradesVerified", true) })
    }
}
