package com.feldman.scholix.classroom

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.feldman.motion.MotionScaffold
import com.feldman.motion.MotionTheme
import com.feldman.scholix.ui.components.SettingsTopBar

/**
 * Dedicated Google Classroom connection activity.
 * Supports first-time connection as well as re-authentication (when passing email).
 */
class ClassroomLoginActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val email = intent.getStringExtra("email")

        setContent {
            MotionTheme {
                MotionScaffold(
                    modifier = Modifier.fillMaxSize(),
                    // The login view scrolls itself.
                    scrollable = false,
                    topBar = {
                        SettingsTopBar(
                            title = if (email.isNullOrBlank()) "Google Classroom" else "Reconnect Account",
                            onBack = { finish() }
                        )
                    }
                ) {
                    Item {
                        Box(Modifier.fillMaxSize().padding(vertical = 8.dp)) {
                            ClassroomLoginView(
                                onSuccess = {
                                    setResult(Activity.RESULT_OK)
                                    finish()
                                },
                                onCancel = { finish() },
                                initialEmail = email
                            )
                        }
                    }
                }
            }
        }
    }
}
