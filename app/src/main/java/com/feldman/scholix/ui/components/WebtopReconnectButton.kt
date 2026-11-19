package com.feldman.scholix.ui.components

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.feldman.motion.MotionButton
import com.feldman.scholix.browser.ScholixBrowserActivity

internal const val WEBTOP_SIGN_IN_LABEL = "Sign in on Webtop"

internal fun openWebtopSignIn(context: Context) {
    context.startActivity(ScholixBrowserActivity.intent(context, "https://webtop.smartschool.co.il/account/login",
        "Webtop", profileName = "webtop-sign-in"))
}

@Composable
internal fun WebtopReconnectButton() {
    val context = LocalContext.current
    MotionButton(text = WEBTOP_SIGN_IN_LABEL, onClick = { openWebtopSignIn(context) })
}
