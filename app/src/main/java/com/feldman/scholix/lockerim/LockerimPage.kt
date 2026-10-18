package com.feldman.scholix.lockerim

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.feldman.motion.MotionButton
import com.feldman.motion.MotionButtonDefaults
import com.feldman.motion.MotionCard
import com.feldman.motion.MotionItemPosition
import com.feldman.motion.MotionScaffold
import com.feldman.scholix.LocalAppState
import com.feldman.scholix.api.PlatformStorage
import com.feldman.scholix.api.platforms.LockerimPlatform
import com.feldman.scholix.ui.components.ChipPicker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LockerimPage(searchQuery: String = "", onConnect: () -> Unit) {
    val context = LocalContext.current
    val state = LocalAppState.current
    val accounts = state.platforms.filterIsInstance<LockerimPlatform>()
    var accountId by rememberSaveable { mutableStateOf(accounts.firstOrNull()?.id) }
    val account = accounts.firstOrNull { it.id == accountId } ?: accounts.firstOrNull()
    val scope = rememberCoroutineScope()
    var lockers by remember(account?.id) { mutableStateOf(account?.subscriptions?.objects().orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember(account?.id) { mutableStateOf<String?>(null) }
    var error by remember(account?.id) { mutableStateOf<String?>(null) }
    var reconnect by remember { mutableStateOf(false) }
    var pendingOperation by remember { mutableStateOf<Pair<JSONObject, Boolean>?>(null) }

    fun saveSession(selected: LockerimPlatform) {
        val current = PlatformStorage.loadPlatforms(context).filterIsInstance<LockerimPlatform>().firstOrNull { it.id == selected.id }
            ?: return
        current.token = selected.token
        current.refreshToken = selected.refreshToken
        current.loggedIn = selected.loggedIn
        current.subscriptions = selected.subscriptions
        PlatformStorage.addPlatforms(context, listOf(current))
    }

    suspend fun refresh(selected: LockerimPlatform) {
        busy = true
        error = null
        try {
            lockers = withContext(Dispatchers.IO) { selected.refreshLockers() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.localizedMessage ?: "Could not load your lockers."
        } finally {
            withContext(Dispatchers.IO) { saveSession(selected) }
            busy = false
        }
    }
    LaunchedEffect(account?.id) { account?.let { refresh(it) } }

    fun operate(locker: JSONObject, unlock: Boolean) {
        val selected = account ?: return
        if (busy) return
        scope.launch {
            busy = true
            error = null
            message = if (unlock) "Connecting to open your locker…" else "Connecting to lock your locker…"
            try {
                withContext(Dispatchers.IO) { LockerimBluetooth(context).operate(selected, locker, unlock) }
                message = if (unlock) "Locker opened." else "Locker locked."
            } catch (timeout: TimeoutCancellationException) {
                message = null
                error = "No response from the locker. Move closer, wake the lock, and try again."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                message = null
                error = failure.localizedMessage ?: "Could not operate the locker."
            } finally {
                withContext(Dispatchers.IO) { saveSession(selected) }
                busy = false
            }
        }
    }
    val permissions = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val pending = pendingOperation
        pendingOperation = null
        if (permissions.all { granted[it] == true } && pending != null) operate(pending.first, pending.second)
        else error = "Allow Nearby devices access to open or lock your locker."
    }
    fun requestOperation(locker: JSONObject, unlock: Boolean) {
        if (permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) operate(locker, unlock)
        else {
            pendingOperation = locker to unlock
            permissionLauncher.launch(permissions)
        }
    }
    MotionScaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(title = { Text("לוקרים") }, actions = {
                if (account != null) IconButton(enabled = !busy, onClick = { scope.launch { refresh(account) } }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh lockers")
                }
            })
        }
    ) {
        Item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (account == null) {
                    Text("Connect Lockerim to see your school lockers and open or lock them with Bluetooth.")
                    MotionButton(text = "Connect לוקרים", onClick = onConnect, modifier = Modifier.fillMaxWidth(),
                        sizes = MotionButtonDefaults.sizes(height = 48.dp, fontSize = 16.sp))
                } else if (reconnect) {
                    LockerimLoginView(account, onSuccess = { updated ->
                        scope.launch {
                            withContext(Dispatchers.IO) { PlatformStorage.addPlatforms(context, listOf(updated)) }
                            reconnect = false
                            state.onPlatformsChanged()
                            refresh(updated)
                        }
                    }, onCancel = { reconnect = false })
                } else {
                    if (accounts.size > 1) ChipPicker("Account", accounts.map { it.phone }, account.phone, onSelectedChange = { phone ->
                        accountId = accounts.first { it.phone == phone }.id
                    })
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(account.phone, style = MaterialTheme.typography.titleMedium)
                        TextButton(enabled = !busy, onClick = { reconnect = true }) { Text("Reconnect") }
                    }
                    Text("Stand near your locker with Bluetooth enabled. Wake the lock before using the controls.",
                        style = MaterialTheme.typography.bodyMedium)
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    val visible = lockers.filter { locker ->
                        listOf("siteNameHE", "siteNameEN", "cellNumber", "closetNumber", "address")
                            .any { locker.optString(it).contains(searchQuery, true) }
                    }
                    if (!busy && lockers.isEmpty() && error == null) Text("No school lockers are assigned to this account.")
                    visible.forEach { locker ->
                        key(locker.optString("subscriptionId")) {
                            MotionCard(position = MotionItemPosition.Alone, modifier = Modifier.fillMaxWidth()) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(locker.optString("siteNameHE").ifBlank { locker.optString("siteNameEN") },
                                        style = MaterialTheme.typography.titleLarge)
                                    Text("Locker ${locker.optString("cellNumber")} · Cabinet ${locker.optString("closetNumber")}")
                                    locker.optString("address").takeIf { it.isNotBlank() }?.let { Text(it) }
                                    Text("${locker.optString("startDate").substringBefore('T')} – ${locker.optString("endDate").substringBefore('T')}",
                                        style = MaterialTheme.typography.bodySmall)
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        MotionButton(text = "Open", enabled = !busy, modifier = Modifier.weight(1f),
                                            sizes = MotionButtonDefaults.sizes(height = 48.dp, fontSize = 16.sp),
                                            onClick = { requestOperation(locker, true) })
                                        MotionButton(text = "Lock", enabled = !busy, modifier = Modifier.weight(1f),
                                            sizes = MotionButtonDefaults.sizes(height = 48.dp, fontSize = 16.sp),
                                            onClick = { requestOperation(locker, false) })
                                    }
                                }
                            }
                        }
                    }
                }
                TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://rb.lockerim.co.il/"))) }) {
                    Text("Rent a locker")
                }
            }
        }
    }
}
