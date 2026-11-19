package com.feldman.scholix.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import com.feldman.motion.MotionButton

@Composable
internal fun WebtopReconnectButton() {
    val browser = LocalUriHandler.current
    MotionButton(text = "Sign in on Webtop", onClick = {
        browser.openUri("https://webtop.smartschool.co.il/account/login")
    })
}
