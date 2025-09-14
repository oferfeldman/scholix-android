package com.feldman.scholix.pages
import androidx.compose.foundation.combinedClickable
import com.feldman.scholix.R
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.feldman.app.api.BarIlanPlatform
import com.feldman.scholix.api.PlatformStorage
import com.feldman.scholix.api.WebtopPlatform
import com.feldman.scholix.ui.components.Title
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SettingsPage(
    modifier: Modifier = Modifier,
    onPlatformsChanged: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var refreshKey by remember { mutableStateOf(0) }
    val platforms = remember(refreshKey) { PlatformStorage.loadPlatforms(context) }
    var showAddSheet by rememberSaveable { mutableStateOf(false) }
    var editIndex by remember { mutableStateOf<Int?>(null) }
    var editUsername by rememberSaveable { mutableStateOf("") }
    var editPassword by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var showAddPassword by rememberSaveable { mutableStateOf(false) }

    val platformTypes = listOf("Webtop", "Bar-Ilan")
    var selectedType by rememberSaveable { mutableStateOf(platformTypes.first()) }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }

    var confirmDeleteIndex by remember { mutableStateOf<Int?>(null) }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddSheet = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.surface,
                icon = { Icon(painter = painterResource(R.drawable.ic_add), contentDescription = null) },
                text = { Text("Add platform") }
            )
        },
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding()
    ) { innerPadding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(start = 16.dp, top = 48.dp, end = 16.dp, bottom = 0.dp)
        ) {
            Title("Settings")
            Spacer(Modifier.height(12.dp))

            if (platforms.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    Text("No platforms yet")
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .navigationBarsPadding()
                ) {
                    itemsIndexed(platforms) { index, platform ->
                        val isPrimary = index == 0
                        val label = when (platform) {
                            is WebtopPlatform -> "Webtop"
                            is BarIlanPlatform -> "Bar-Ilan"
                            else -> platform.javaClass.simpleName ?: "Unknown"
                        }

                        val shape = when (index) {
                            0 ->
                                if (platforms.lastIndex == 0) RoundedCornerShape(16.dp)
                                else RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 4.dp)
                            platforms.lastIndex ->
                                RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp, topStart = 4.dp, topEnd = 4.dp)
                            else -> RoundedCornerShape(4.dp)
                        }

                        Card(
                            shape = shape,
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = { /* no-op or future details */ },
                                    onLongClick = {
                                        editIndex = index
                                        val p = platform
                                        // Prefill using reflection-safe helpers below
                                        editUsername = extractUsername(p) ?: ""
                                        editPassword = extractPassword(p) ?: ""
                                        showPassword = false
                                    }
                                ),

                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.titleLarge
                                    )
                                    val secondary = if (isPrimary) "Primary" else "Tap star to make primary"
                                    Text(secondary, style = MaterialTheme.typography.bodySmall)
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    IconButton(
                                        onClick = {
                                            if (!isPrimary) {
                                                scope.launch(Dispatchers.IO) {
                                                    val list = PlatformStorage.loadPlatforms(context).toMutableList()
                                                    if (index in list.indices) {
                                                        val item = list.removeAt(index)
                                                        list.add(0, item)
                                                        PlatformStorage.savePlatforms(context, list)
                                                    }
                                                    withContext(Dispatchers.Main) {
                                                        refreshKey++
                                                        onPlatformsChanged()
                                                    }
                                                }
                                            }
                                        }
                                    ) {
                                        if (isPrimary) {
                                            Icon(painter = painterResource(R.drawable.ic_star), contentDescription = "Primary")
                                        } else {
                                            Icon(painter = painterResource(R.drawable.ic_star_border), contentDescription = "Make primary")
                                        }
                                    }
//
//                                    IconButton(
//                                        onClick = {
//                                            scope.launch(Dispatchers.IO) {
//                                                PlatformStorage.refreshCookies(context)
//                                                withContext(Dispatchers.Main) { toast = "Cookies refreshed." }
//                                            }
//                                        }
//                                    ) {
//                                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh cookies")
//                                    }

                                    IconButton(onClick = { confirmDeleteIndex = index }) {
                                        Icon(painter = painterResource(R.drawable.ic_delete), contentDescription = "Remove")
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(3.dp))
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
    }

//
//    // ── layout ───────────────────────────────────────────────────────────────────
//    Column(
//        modifier = modifier
//            .fillMaxSize()
//            .padding(start = 16.dp, top = 48.dp, end = 16.dp, bottom = 0.dp)
//    ) {
//        Title("Settings")
//
//        Spacer(Modifier.height(12.dp))
//
//        // ── Add platform card (matches chip-driven header style) ─────────────────
//        ElevatedCard(
//            modifier = Modifier.fillMaxWidth(),
//            shape = RoundedCornerShape(16.dp)
//        ) {
//            Column(Modifier.padding(16.dp)) {
//                Text("Add a platform", style = MaterialTheme.typography.titleMedium)
//                Spacer(Modifier.height(8.dp))
//
//                // Use your ChipDropdown for platform type selection
//                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
//                    ChipDropdown(
//                        label = "Platform: $selectedType",
//                        options = platformTypes,
//                        selected = selectedType,
//                        onSelectedChange = { selectedType = it }
//                    )
//                }
//
//                Spacer(Modifier.height(12.dp))
//                OutlinedTextField(
//                    value = username,
//                    onValueChange = { username = it },
//                    label = {
//                        Text(if (selectedType == "Bar-Ilan") "Student ID" else "Username")
//                    },
//                    modifier = Modifier.fillMaxWidth()
//                )
//                Spacer(Modifier.height(8.dp))
//                OutlinedTextField(
//                    value = password,
//                    onValueChange = { password = it },
//                    label = { Text("Password") },
//                    visualTransformation = PasswordVisualTransformation(),
//                    modifier = Modifier.fillMaxWidth()
//                )
//
//                Spacer(Modifier.height(12.dp))
//                Button(
//                    enabled = !busy && username.isNotBlank() && password.isNotBlank(),
//                    onClick = {
//                        busy = true; toast = null
//                        scope.launch {
//                            try {
//                                val newPlatform = withContext(Dispatchers.IO) {
//                                    when (selectedType) {
//                                        "Webtop" -> WebtopPlatform(username.trim(), password)
//                                        "Bar-Ilan" -> BarIlanPlatform(username.trim(), password)
//                                        else -> null
//                                    }
//                                } ?: return@launch.also { toast = "Unsupported platform." }
//
//                                val ok = withContext(Dispatchers.IO) { newPlatform.isLoggedIn() }
//                                if (!ok) {
//                                    toast = "Login failed. Check credentials."
//                                } else {
//                                    withContext(Dispatchers.IO) {
//                                        val list = PlatformStorage.loadPlatforms(context).toMutableList()
//                                        list.add(newPlatform)
//                                        PlatformStorage.savePlatforms(context, list)
//                                    }
//                                    username = ""; password = ""
//                                    toast = "Platform added."
//                                    refreshKey++
//                                }
//                            } catch (t: Throwable) {
//                                toast = "Error: ${t.localizedMessage}"
//                            } finally {
//                                busy = false
//                            }
//                        }
//                    }
//                ) {
//                    if (busy) CircularWavyProgressIndicator()
//                    else Text("Add platform")
//                }
//
//                Spacer(Modifier.height(8.dp))
//                TextButton(onClick = {
//                    scope.launch(Dispatchers.IO) {
//                        PlatformStorage.refreshCookies(context)
//                        withContext(Dispatchers.Main) { toast = "Cookies refreshed for all platforms." }
//                    }
//                }) { Text("Refresh cookies for all") }
//
//                toast?.let {
//                    Spacer(Modifier.height(6.dp))
//                    Text(it, color = MaterialTheme.colorScheme.primary)
//                }
//            }
//        }
//
//        Spacer(Modifier.height(20.dp))
//
//        // ── Connected platforms list (segmented cards, like Attendance list) ─────
//        Text("Connected platforms", style = MaterialTheme.typography.titleMedium)
//        Spacer(Modifier.height(8.dp))
//
//        if (platforms.isEmpty()) {
//            Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
//                Text("No platforms yet")
//            }
//        } else {
//            LazyColumn(
//                modifier = Modifier
//                    .fillMaxWidth()
//                    .clip(RoundedCornerShape(16.dp))
//                    .navigationBarsPadding()
//            ) {
//                itemsIndexed(platforms) { index, platform ->
//                    val isPrimary = index == 0
//                    val label = when (platform) {
//                        is WebtopPlatform -> "Webtop"
//                        is BarIlanPlatform -> "Bar-Ilan"
//                        else -> platform.javaClass.simpleName ?: "Unknown"
//                    }
//
//                    val shape = when (index) {
//                        0 ->
//                            if (platforms.lastIndex == 0) RoundedCornerShape(16.dp)
//                            else RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 4.dp)
//                        platforms.lastIndex ->
//                            RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp, topStart = 4.dp, topEnd = 4.dp)
//                        else -> RoundedCornerShape(4.dp)
//                    }
//
//                    Card(shape = shape, modifier = Modifier.fillMaxWidth()) {
//                        Row(
//                            modifier = Modifier
//                                .fillMaxWidth()
//                                .padding(horizontal = 20.dp, vertical = 14.dp),
//                            verticalAlignment = Alignment.CenterVertically,
//                            horizontalArrangement = Arrangement.SpaceBetween
//                        ) {
//                            Column(Modifier.weight(1f)) {
//                                Text(
//                                    text = label,
//                                    style = MaterialTheme.typography.titleLarge
//                                )
//                                val secondary = if (isPrimary) "Primary" else "Tap star to make primary"
//                                Text(secondary, style = MaterialTheme.typography.bodySmall)
//                            }
//
//                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
//                                IconButton(
//                                    onClick = {
//                                        if (!isPrimary) {
//                                            scope.launch(Dispatchers.IO) {
//                                                val list = PlatformStorage.loadPlatforms(context).toMutableList()
//                                                if (index in list.indices) {
//                                                    val item = list.removeAt(index)
//                                                    list.add(0, item)
//                                                    PlatformStorage.savePlatforms(context, list)
//                                                }
//                                                withContext(Dispatchers.Main) { refreshKey++ }
//                                            }
//                                        }
//                                    }
//                                ) {
//                                    if (isPrimary) {
//                                        Icon(Icons.Filled.Star, contentDescription = "Primary")
//                                    } else {
//                                        Icon(Icons.Outlined.StarBorder, contentDescription = "Make primary")
//                                    }
//                                }
//
//                                IconButton(
//                                    onClick = {
//                                        scope.launch(Dispatchers.IO) {
//                                            PlatformStorage.refreshCookies(context)
//                                            withContext(Dispatchers.Main) { toast = "Cookies refreshed." }
//                                        }
//                                    }
//                                ) {
//                                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh cookies")
//                                }
//
//                                IconButton(onClick = { confirmDeleteIndex = index }) {
//                                    Icon(Icons.Filled.Delete, contentDescription = "Remove")
//                                }
//                            }
//                        }
//                    }
//
//                    Spacer(Modifier.height(3.dp))
//                }
//                item { Spacer(Modifier.height(80.dp)) }
//            }
//        }
//    }

    val editSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    if (editIndex != null) {
        val idx = editIndex!!
        val current = platforms.getOrNull(idx)
        val label = when (current) {
            is WebtopPlatform -> "Webtop"
            is BarIlanPlatform -> "Bar-Ilan"
            else -> "Unknown"
        }

        ModalBottomSheet(
            onDismissRequest = {
                if (!busy) {
                    editIndex = null
                    toast = null
                }
            },
            sheetState = editSheetState
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Edit $label", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = editUsername,
                    onValueChange = { editUsername = it },
                    label = { Text(if (current is BarIlanPlatform) "Student ID" else "Username") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = editPassword,
                    onValueChange = { editPassword = it },
                    label = { Text("Password") },
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                imageVector = if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (showPassword) "Hide password" else "Show password"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        enabled = !busy && editUsername.isNotBlank() && editPassword.isNotBlank(),
                        onClick = {
                            busy = true; toast = null
                            scope.launch {
                                try {
                                    val newPlatform = withContext(Dispatchers.IO) {
                                        when (current) {
                                            is WebtopPlatform -> WebtopPlatform(editUsername.trim(), editPassword)
                                            is BarIlanPlatform -> BarIlanPlatform(editUsername.trim(), editPassword)
                                            else -> null
                                        }
                                    } ?: run { toast = "Unsupported platform."; busy = false; return@launch }

                                    val ok = withContext(Dispatchers.IO) { newPlatform.isLoggedIn() }
                                    if (!ok) {
                                        toast = "Login failed. Check credentials."
                                    } else {
                                        withContext(Dispatchers.IO) {
                                            val list = PlatformStorage.loadPlatforms(context).toMutableList()
                                            if (idx in list.indices) {
                                                list[idx] = newPlatform
                                                PlatformStorage.savePlatforms(context, list)
                                            }
                                        }
                                        withContext(Dispatchers.Main) {
                                            toast = "Updated."
                                            refreshKey++
                                            onPlatformsChanged()
                                            editIndex = null
                                        }
                                    }
                                } catch (t: Throwable) {
                                    toast = "Error: ${t.localizedMessage}"
                                } finally {
                                    busy = false
                                }
                            }
                        }
                    ) {
                        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text("Save")
                    }
                    TextButton(enabled = !busy, onClick = { editIndex = null }) {
                        Text("Cancel")
                    }
                }

                toast?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = MaterialTheme.colorScheme.primary)
                }

                Spacer(Modifier.height(12.dp))
            }
        }
    }



    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val closeAddSheet: () -> Unit = {
        if (!busy) {
            scope.launch {
                sheetState.hide()
                showAddSheet = false
                toast = null
            }
        }
    }

    if (showAddSheet) {
        ModalBottomSheet(
            onDismissRequest = { closeAddSheet() },
            sheetState = sheetState
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Add a platform", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))

                // Reuse your ChipDropdown for platform type
                ChipDropdown(
                    label = "Platform: $selectedType",
                    icon = painterResource(R.drawable.ic_school),
                    options = platformTypes,
                    selected = selectedType,
                    onSelectedChange = { selectedType = it }
                )

                Spacer(Modifier.height(12.dp))
                val focusManager = LocalFocusManager.current

                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(if (selectedType == "Bar-Ilan") "Student ID" else "Username") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() })
                )

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = if (showAddPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showAddPassword = !showAddPassword }) {
                            Icon(
                                imageVector = if (showAddPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (showAddPassword) "Hide password" else "Show password"
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    modifier = Modifier.fillMaxWidth()
                )


                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        enabled = !busy && username.isNotBlank() && password.isNotBlank(),
                        onClick = {
                            busy = true; toast = null
                            scope.launch {
                                try {
                                    val newPlatform = withContext(Dispatchers.IO) {
                                        when (selectedType) {
                                            "Webtop" -> WebtopPlatform(username.trim(), password)
                                            "Bar-Ilan" -> BarIlanPlatform(username.trim(), password)
                                            else -> null
                                        }
                                    } ?: run { toast = "Unsupported platform."; busy = false; return@launch }

                                    val ok = withContext(Dispatchers.IO) { newPlatform.isLoggedIn() }
                                    if (!ok) {
                                        toast = "Login failed. Check credentials."
                                    } else {
                                        withContext(Dispatchers.IO) {
                                            val list = PlatformStorage.loadPlatforms(context).toMutableList()
                                            list.add(newPlatform)
                                            PlatformStorage.savePlatforms(context, list)
                                        }
                                        username = ""; password = ""
                                        toast = "Platform added."
                                        refreshKey++
                                        showAddSheet = false
                                        onPlatformsChanged()
                                    }
                                } catch (t: Throwable) {
                                    toast = "Error: ${t.localizedMessage}"
                                } finally {
                                    busy = false
                                }
                            }
                        }
                    ) {
                        if (busy) CircularWavyProgressIndicator()
                        else Text("Add")
                    }
                    TextButton(enabled = !busy, onClick = { showAddSheet = false }) {
                        Text("Cancel")
                    }
                }

                toast?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = MaterialTheme.colorScheme.primary)
                }

                Spacer(Modifier.height(12.dp))
            }
        }
    }

    // ── remove confirmation dialog ────────────────────────────────────────────────
    if (confirmDeleteIndex != null) {
        AlertDialog(
            onDismissRequest = { confirmDeleteIndex = null },
            title = { Text("Remove platform?") },
            text = { Text("This will sign out that platform from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    val idx = confirmDeleteIndex!!
                    confirmDeleteIndex = null
                    scope.launch(Dispatchers.IO) {
                        val list = PlatformStorage.loadPlatforms(context).toMutableList()
                        if (idx in list.indices) {
                            list.removeAt(idx)
                            PlatformStorage.savePlatforms(context, list)
                        }
                        withContext(Dispatchers.Main) { refreshKey++ }
                        onPlatformsChanged()
                    }
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteIndex = null }) { Text("Cancel") } }
        )
    }
}
@Suppress("SwallowedException")
private fun extractUsername(platform: Any): String? {
    // Try common field names without breaking compilation
    val candidates = listOf("username", "user", "id", "studentId", "login")
    for (name in candidates) {
        try {
            val f = platform.javaClass.getDeclaredField(name)
            f.isAccessible = true
            (f.get(platform) as? String)?.let { if (it.isNotBlank()) return it }
        } catch (_: Throwable) {}
    }
    // Try getter methods
    val getters = listOf("getUsername", "getUser", "getId", "getStudentId", "getLogin")
    for (m in getters) {
        try {
            val method = platform.javaClass.getMethod(m)
            (method.invoke(platform) as? String)?.let { if (it.isNotBlank()) return it }
        } catch (_: Throwable) {}
    }
    return null
}

@Suppress("SwallowedException")
private fun extractPassword(platform: Any): String? {
    val candidates = listOf("password", "pass", "pwd")
    for (name in candidates) {
        try {
            val f = platform.javaClass.getDeclaredField(name)
            f.isAccessible = true
            (f.get(platform) as? String)?.let { if (it.isNotBlank()) return it }
        } catch (_: Throwable) {}
    }
    val getters = listOf("getPassword", "getPass", "getPwd")
    for (m in getters) {
        try {
            val method = platform.javaClass.getMethod(m)
            (method.invoke(platform) as? String)?.let { if (it.isNotBlank()) return it }
        } catch (_: Throwable) {}
    }
    return null
}
