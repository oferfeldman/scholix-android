package com.feldman.lockerapp.ui.theme

import androidx.compose.runtime.Composable
import com.feldman.motion.MotionTheme

/** Keep feature screens and their test hosts on the shared Motion theme. */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MotionTheme(content = content)
}
