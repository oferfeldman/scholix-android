package com.feldman.scholix.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.feldman.scholix.api.platforms.InbarPlatform
import com.google.android.gms.auth.api.phone.SmsRetriever
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

/** The page owns loading/error UI; the requested session owns SMS reception. */
@Composable
fun HiddenInbarLogin(
    account: InbarPlatform,
    onSmsRequested: suspend (InbarPlatform) -> Unit,
    onResult: suspend (InbarPlatform?, String?) -> Unit,
    reuseSavedSession: suspend () -> InbarPlatform? = { null },
    timeoutMs: Long = 90_000,
) {
    val context = LocalContext.current
    val saveAccount by rememberUpdatedState(onSmsRequested)
    val finish by rememberUpdatedState(onResult)
    val reuseSession by rememberUpdatedState(reuseSavedSession)
    var session by remember(account) { mutableStateOf<InbarSmsLoginSession?>(null) }
    val permission = remember(account) { CompletableDeferred<Boolean>() }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permission.complete(it)
    }
    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        (session ?: InbarSmsLoginSessions.find(account))?.acceptConsent(
            if (result.resultCode == Activity.RESULT_OK) result.data?.getStringExtra(SmsRetriever.EXTRA_SMS_MESSAGE) else null)
    }
    LaunchedEffect(session) {
        val current = session ?: return@LaunchedEffect
        current.consentIntent.collect { intent ->
            if (intent != null) {
                current.consentIntent.value = null
                runCatching { consentLauncher.launch(intent) }.onFailure { current.failConsent() }
            }
        }
    }
    LaunchedEffect(account) {
        try {
            val pending = InbarSmsLoginSessions.find(account) ?: run {
                val directAvailable = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
                    .requestedPermissions.orEmpty().contains(Manifest.permission.RECEIVE_SMS)
                val hasPermission = directAvailable && ContextCompat.checkSelfPermission(context,
                    Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
                val useDirect = hasPermission || (directAvailable && run {
                    permissionLauncher.launch(Manifest.permission.RECEIVE_SMS)
                    permission.await()
                })
                InbarSmsLoginSessions.startOrJoin(context, account, useDirect, saveAccount, reuseSession, timeoutMs)
            }
            session = pending
            Log.d("InbarLogin", "Awaiting shared sign-in")
            finish(pending.result.await(), null)
        } catch (cancelled: CancellationException) {
            // Disposing a page stops its wait, not the shared receiver or verification challenge.
            throw cancelled
        } catch (exception: Exception) {
            Log.d("InbarLogin", "Sign-in failed (${exception.javaClass.simpleName})")
            finish(null, if(exception is com.feldman.scholix.api.platforms.InbarGradeLayoutChanged)
                exception.message else "Login failed: ${exception.localizedMessage ?: "Unable to sign in"}")
        }
    }
}
