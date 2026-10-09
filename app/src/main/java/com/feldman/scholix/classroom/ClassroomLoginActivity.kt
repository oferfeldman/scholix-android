package com.feldman.scholix.classroom

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.feldman.lockerapp.ui.theme.AppTheme
import com.feldman.motion.MotionTheme

/**
 * Dedicated Google Classroom connection activity.
 * Supports first-time connection as well as re-authentication (when passing email).
 */
class ClassroomLoginActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val email = intent.getStringExtra("email")

        setContent {
            AppTheme {
                MotionTheme {
                    Scaffold(
                        containerColor = MaterialTheme.colorScheme.background,
                        topBar = {
                            TopAppBar(
                                title = {
                                    Text(
                                        text = if (email.isNullOrBlank()) "Google Classroom" else "Reconnect Account",
                                        style = MaterialTheme.typography.titleLarge
                                    )
                                },
                                navigationIcon = {
                                    IconButton(onClick = { finish() }) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = "Back"
                                        )
                                    }
                                },
                                colors = TopAppBarDefaults.topAppBarColors(
                                    containerColor = Color.Transparent
                                )
                            )
                        }
                    ) { innerPadding ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
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
