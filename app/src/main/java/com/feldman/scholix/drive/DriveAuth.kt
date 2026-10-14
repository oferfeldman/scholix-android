package com.feldman.scholix.drive

import android.accounts.Account
import android.content.Context
import android.app.Activity
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class DriveNeedsConsent : Exception("Open Materials and reconnect Google Drive.")
class DriveAuthorizationCancelled : Exception("Google Drive connection was cancelled.")

object DriveAuth {
    const val SCOPE = "https://www.googleapis.com/auth/drive.readonly"
    /** Google returns SDK errors in the Intent even when the Activity result is cancelled. */
    internal fun complete(resultCode: Int, hasData: Boolean, parse: () -> String?): String {
        if (!hasData && resultCode == Activity.RESULT_CANCELED) throw DriveAuthorizationCancelled()
        return parse()?.takeIf { it.isNotBlank() } ?: throw DriveNeedsConsent()
    }
    const val EDIT_SCOPE = "https://www.googleapis.com/auth/drive.file"
    fun request(account: String?, edits: Boolean = false) = AuthorizationRequest.builder()
        .setRequestedScopes(if (edits) listOf(Scope(SCOPE), Scope(EDIT_SCOPE)) else listOf(Scope(SCOPE))).apply {
            if (!account.isNullOrBlank()) setAccount(Account(account, "com.google"))
        }.build()
    suspend fun authorize(context: Context, account: String?, edits: Boolean = false): AuthorizationResult =
        suspendCancellableCoroutine { continuation ->
            Identity.getAuthorizationClient(context).authorize(request(account, edits))
                .addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
                .addOnFailureListener {
                    android.util.Log.i("ScholixDriveAuth", "Authorization request failed, sdkStatus=${(it as? ApiException)?.statusCode}")
                    if (continuation.isActive) continuation.resumeWithException(it)
                }
        }
    suspend fun token(context: Context, account: String, edits: Boolean = false): String {
        val result = authorize(context, account, edits)
        if (result.hasResolution()) throw DriveNeedsConsent()
        return result.accessToken ?: throw DriveNeedsConsent()
    }
    suspend fun clear(context: Context, token: String) = suspendCancellableCoroutine<Unit> { c ->
        Identity.getAuthorizationClient(context).clearToken(ClearTokenRequest.builder().setToken(token).build())
            .addOnSuccessListener { if (c.isActive) c.resume(Unit) }
            .addOnFailureListener { if (c.isActive) c.resumeWithException(it) }
    }
    fun message(e: Exception): String = when {
        DriveConnection.unavailable(e) -> "Google Drive is unreachable. Check your internet connection or try again later."
        e is DriveAuthorizationCancelled || (e is ApiException && e.statusCode == 16) -> "Google Drive connection was cancelled."
        e is ApiException && e.statusCode == 10 -> "Google sign-in is not configured for this Scholix build. Register its Android package and signing certificate in Google Cloud."
        e is ApiException && e.statusCode == 8 -> "Google could not complete authorization (8). Try connecting again. If it keeps failing, check this app's Google Cloud registration."
        e is ApiException -> "Google authorization failed (${e.statusCode}). Check your connection and try reconnecting."
        else -> e.message ?: "Google Drive is unavailable. Try again."
    }
}
