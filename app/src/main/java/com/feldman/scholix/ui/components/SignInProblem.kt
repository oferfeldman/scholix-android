package com.feldman.scholix.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.feldman.motion.MotionButton
import com.feldman.motion.MotionButtonDefaults
import com.feldman.scholix.LocalAppState
import com.feldman.scholix.api.Platform
import com.feldman.scholix.api.platforms.WebtopPlatform
import com.feldman.scholix.api.providerName

private const val SIGN_IN_PREFIX = "Couldn't sign in to "

/** The Ministry sign-in finished, but Webtop refused the session it produced. */
const val WEBTOP_REJECTED_MOE = "The Ministry of Education signed you in, but Webtop didn't accept it. Try again in a few minutes."

/**
 * Why signing in to [platform] failed. Uses the provider's or the Ministry sign-in's own reason
 * when there is one, so "wrong password", "needs a new password" and "Webtop is down" no longer all
 * read as the same "Please re-login".
 */
fun signInFailedMessage(platform: Platform?, detail: String? = null): String {
    val name = platform?.providerName() ?: "the provider"
    val reason = detail?.takeIf { it.isNotBlank() }
        ?: (platform as? WebtopPlatform)?.loginError?.takeIf { it.isNotBlank() }
        ?: "The saved sign-in no longer works."
    return "$SIGN_IN_PREFIX$name.\n$reason"
}

fun isSignInProblem(message: String?): Boolean =
    message != null && (message.startsWith(SIGN_IN_PREFIX) || message.startsWith("Login failed"))

/**
 * An error in place of a page's content. A sign-in failure also offers Sign in again, which opens
 * the provider's account sheet (credentials and MOE SMS) instead of repeating the sign-in that just
 * failed.
 */
@Composable
fun PageProblem(
    message: String,
    providerId: String?,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
    retryLabel: String = "Retry",
) {
    val state = LocalAppState.current
    Column(modifier.padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Outside a Surface the default content color is black, unreadable on the dark theme.
        Text(message, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (providerId != null && isSignInProblem(message)) {
                MotionButton(text = "Sign in again", onClick = { state.onSignInAgain(providerId) },
                    sizes = MotionButtonDefaults.sizes(height = 48.dp, fontSize = 16.sp),
                    contentPadding = PaddingValues(horizontal = 24.dp))
            }
            if (onRetry != null) TextButton(onClick = onRetry) { Text(retryLabel) }
        }
    }
}
