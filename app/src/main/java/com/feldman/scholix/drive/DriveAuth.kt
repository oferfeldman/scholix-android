package com.feldman.scholix.drive

import android.accounts.Account
import android.content.Context
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

object DriveAuth {
    const val SCOPE = "https://www.googleapis.com/auth/drive.readonly"
    fun request(account: String?) = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(SCOPE))).apply {
            if (!account.isNullOrBlank()) setAccount(Account(account, "com.google"))
        }.build()
    suspend fun authorize(context: Context, account: String?): AuthorizationResult =
        suspendCancellableCoroutine { continuation ->
            Identity.getAuthorizationClient(context).authorize(request(account))
                .addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
                .addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
        }
    suspend fun token(context: Context, account: String): String {
        val result = authorize(context, account)
        if (result.hasResolution()) throw DriveNeedsConsent()
        return result.accessToken ?: throw DriveNeedsConsent()
    }
    suspend fun clear(context: Context, token: String) = suspendCancellableCoroutine<Unit> { c ->
        Identity.getAuthorizationClient(context).clearToken(ClearTokenRequest.builder().setToken(token).build())
            .addOnSuccessListener { if (c.isActive) c.resume(Unit) }
            .addOnFailureListener { if (c.isActive) c.resumeWithException(it) }
    }
    fun message(e: Exception): String = when {
        e is ApiException && e.statusCode == 10 -> "Google sign-in is not configured for this Scholix build. Register its Android package and signing certificate in Google Cloud."
        e is ApiException -> "Google authorization failed. Check your connection and try reconnecting."
        else -> e.message ?: "Google Drive is unavailable. Try again."
    }
}
