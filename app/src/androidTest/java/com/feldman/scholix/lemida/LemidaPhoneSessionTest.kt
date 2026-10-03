package com.feldman.scholix.lemida

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.test.core.app.ActivityScenario
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** Explicitly run after the account holder signs in on the phone. */
class LemidaPhoneSessionTest {
    @Test fun homeworkDetailLoadsInsideTheApp() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = LemidaRepository(context)
        val item = repo.cached().first()
        val detail = repo.detail(item)
        assertTrue("Homework content was loaded", detail.text.isNotBlank())
        assertTrue("Homework detail is available offline", repo.cachedDetail(item) != null)
    }
    @Test fun savedPhoneSessionCanSync() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = LemidaRepository(context)
        repo.sync()
        assertTrue("Successful sync timestamp was saved", repo.lastSync() > 0L)
    }
    @Test fun loginClosesOnlyAfterSuccessfulHomeworkLoad() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = LemidaRepository(context)
        val before = repo.lastSync()
        ActivityScenario.launch(LemidaLoginActivity::class.java).use { scenario ->
            withTimeout(90_000) {
                while (scenario.state != Lifecycle.State.DESTROYED) delay(500)
            }
            assertTrue("Login must load homework before returning", repo.lastSync() > before)
            assertTrue("Homework from the signed-in account is visible", repo.cached().isNotEmpty())
        }
    }
}
