package com.feldman.scholix.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.feldman.motion.MotionButton

internal const val WEBTOP_SIGN_IN_LABEL = "Sign in on Webtop"

internal fun openWebtopSignIn(context: Context) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://webtop.smartschool.co.il/account/login")).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No web browser is available.", Toast.LENGTH_SHORT).show()
    }
}

@Composable
internal fun WebtopReconnectButton() {
    val context = LocalContext.current
    MotionButton(text = WEBTOP_SIGN_IN_LABEL, onClick = { openWebtopSignIn(context) })
}
