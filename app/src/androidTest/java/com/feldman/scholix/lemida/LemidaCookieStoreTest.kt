package com.feldman.scholix.lemida

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class LemidaCookieStoreTest {
    @Test fun cookieSurvivesNewStoreWithoutPlaintextOnDisk() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "lemida_session_test.enc"
        val file = File(context.noBackupFilesDir, name)
        try {
            val value = "MoodleSession=fixture-not-a-real-session"
            LemidaCookieStore(context, name).save(value)
            assertEquals(value, LemidaCookieStore(context, name).load())
            assertFalse(file.readBytes().toString(Charsets.UTF_8).contains(value))
            file.writeText("corrupt")
            assertNull(LemidaCookieStore(context, name).load())
        } finally { file.delete() }
    }
}
