package com.feldman.scholix.lemida

import android.content.Context
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Uses isolated preferences and fake notification delivery; never reads the user's session. */
class LemidaAlertsTest {
    private val item = Homework("1:assign:9", 1, "Fixture", "Exercise", "assign", "", "")

    private fun isolated(block: suspend (LemidaRepository, SharedPreferences) -> Unit) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "lemida_alert_test_${UUID.randomUUID()}"
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        try {
            prefs.edit().putBoolean("enabled", true).putString("user_id", "fixture")
                .putString("pending", LemidaParser.encode(listOf(item))).commit()
            block(LemidaRepository(context, name), prefs)
        } finally { context.deleteSharedPreferences(name) }
    }

    @Test fun overlappingWorkersDeliverPendingHomeworkOnce() = isolated { repo, prefs ->
        val delivered = AtomicInteger()
        coroutineScope {
            (0 until 8).map {
                async(Dispatchers.Default) { repo.deliverPending("fixture") { delivered.incrementAndGet(); true } }
            }.awaitAll()
        }
        assertEquals(1, delivered.get())
        assertEquals("[]", prefs.getString("pending", null))
    }

    @Test fun aStaleAccountCannotDeliverOrClearAnotherAccountsAlerts() = isolated { repo, prefs ->
        prefs.edit().putString("user_id", "new-account").commit()
        repo.deliverPending("fixture") { fail("Stale account must not deliver"); true }
        assertEquals(listOf(item), LemidaParser.decode(prefs.getString("pending", "[]")!!))
        repo.deliverPending("new-account") { true }
        assertEquals("[]", prefs.getString("pending", null))
    }

    @Test fun unsuccessfulPausedAndExpiredDeliveryRetainPendingHomework() = isolated { repo, prefs ->
        repo.deliverPending("fixture") { false }
        assertEquals(listOf(item), LemidaParser.decode(prefs.getString("pending", "[]")!!))
        repo.setEnabled(false)
        repo.deliverPending("fixture") { fail("Paused updates must not notify"); true }
        repo.setEnabled(true)
        prefs.edit().putBoolean("needs_login", true).commit()
        repo.deliverPending("fixture") { fail("Expired account must not notify homework"); true }
        assertEquals(listOf(item), LemidaParser.decode(prefs.getString("pending", "[]")!!))
        prefs.edit().putBoolean("needs_login", false).commit()
        repo.deliverPending("fixture") { true }
        assertEquals("[]", prefs.getString("pending", null))
    }

    @Test fun signInRemindersAreNotRepeatedOrSentAfterRecovery() = isolated { repo, prefs ->
        var delivered = 0
        prefs.edit().putBoolean("needs_login", true).commit()
        repeat(2) { repo.deliverLoginReminder { delivered++; true } }
        assertEquals(1, delivered)
        prefs.edit().putBoolean("needs_login", false).putBoolean("login_notified", false).commit()
        repo.deliverLoginReminder { fail("Recovered session must not be asked to sign in"); true }
        repo.setEnabled(false)
        prefs.edit().putBoolean("needs_login", true).commit()
        repo.deliverLoginReminder { fail("Paused account must not notify"); true }
    }
}
