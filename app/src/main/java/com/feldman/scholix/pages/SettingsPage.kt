package com.feldman.scholix.pages
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.combinedClickable
import com.feldman.scholix.R
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.feldman.app.api.BarIlanPlatform
import com.feldman.scholix.api.LoginFields
import com.feldman.scholix.api.Platform
import com.feldman.scholix.api.PlatformInfo
import com.feldman.scholix.api.PlatformStorage
import com.feldman.scholix.api.Type
import com.feldman.scholix.api.applyLoginFields
import com.feldman.scholix.api.platformOptions
import com.feldman.scholix.api.platforms.WebtopPlatform
import com.feldman.scholix.Dest
import com.feldman.scholix.ui.components.ActionRow
import com.feldman.scholix.ui.components.SegmentedOption
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.reflect.full.companionObjectInstance

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PlatformsPage(
    modifier: Modifier = Modifier,
    onPlatformsChanged: () -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var refreshKey by remember { mutableIntStateOf(0) }
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

    val backdrop = rememberLayerBackdrop()


//    Column(
//        modifier = modifier
//            .verticalScroll(rememberScrollState())
//            .fillMaxSize()
//            .padding(horizontal = 24.dp),
//    ) {
//        Title("Settings")
//        ActionRow {
//            addVerticalActionList(
//                options = listOf(
//                    SegmentedOption(
//                        "customization",
//                        text = "Customization",
//                        desc = "Customize the app look",
//                        iconRes = R.drawable.ic_palette
//                    ),
//                    SegmentedOption("compass", text = "Compass", desc = "Adjust compass settings", iconRes = R.drawable.ic_compass)
//                ),
//                onClick = { option ->
//                    when (option) {
//                        "customization" -> navController.navigate("settings/customization")
//                        "compass" -> navController.navigate("settings/compass")
//                    }
//                }
//            )
//
//        }
//    }

    Scaffold(
        floatingActionButton = {
            Row(
                modifier = Modifier
                    .padding(end = 16.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 🔹 Logout FAB
                ExtendedFloatingActionButton(
                    onClick = { onLogout() },
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    icon = { Icon(painterResource(R.drawable.ic_logout), contentDescription = "Logout") },
                    text = { Text("Logout") }
                )

                // 🔹 Add Platform FAB
                ExtendedFloatingActionButton(
                    onClick = { showAddSheet = true },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.surface,
                    icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = "Add platform") },
                    text = { Text("Add platform") }
                )
            }
        },
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(start = 16.dp, top = 0.dp, end = 16.dp, bottom = 0.dp)
        ) {

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
                                    onClick = { editIndex = index },
                                    onLongClick = { editIndex = index }
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


    val infoByClassName = remember {
        platformOptions.associateBy { it.factory()::class.java.name }
    }

    // helper to resolve PlatformInfo dynamically
    fun resolveInfoFor(platform: Platform?): PlatformInfo? {
        val key = platform?.javaClass?.name ?: return null
        return infoByClassName[key]
    }
    if (editIndex != null) {
        val idx = editIndex!!
        val current = platforms.getOrNull(idx)
        val selectedPlatform = resolveInfoFor(current) ?: return

        var loginFields by remember {
            mutableStateOf(selectedPlatform.factory().getLoginFields().apply {
                current?.let { loadFrom(it) }
            })
        }

        var errorMessage by remember { mutableStateOf<String?>(null) }

        ModalBottomSheet(
            onDismissRequest = { if (!busy) editIndex = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {

                Text(
                    text = "Edit ${selectedPlatform.name} Account",
                    style = MaterialTheme.typography.titleMedium
                )

                Spacer(Modifier.height(16.dp))

                DynamicEditFields(
                    fields = loginFields,
                    onFieldsChanged = { loginFields = it },
                    isLoading = busy,
                    errorMessage = errorMessage,
                    onLogin = {
                        busy = true
                        errorMessage = null
                        val platformClass = current?.javaClass
                        val companion = platformClass?.kotlin?.companionObjectInstance

                        scope.launch {
                            try {
                                val isCorrect = withContext(Dispatchers.IO) {
                                    if (companion is Platform.Companion) {
                                        val ok = companion.checkCredentials(loginFields)
                                        Log.d("Login", "checkCredentials(${platformClass.simpleName}) → $ok")
                                        ok
                                    } else {
                                        Log.w("SettingsPage", "No static credential checker for ${platformClass?.simpleName}")
                                        false
                                    }
                                }

                                if (isCorrect) {
                                    withContext(Dispatchers.IO) {
                                        val list = PlatformStorage.loadPlatforms(context).toMutableList()
                                        val platform = list.getOrNull(idx) ?: return@withContext

                                        platform.applyLoginFields(loginFields)
                                        PlatformStorage.savePlatforms(context, list)
                                    }

                                    refreshKey++
                                    onPlatformsChanged()
                                    editIndex = null
                                    Log.d("Login", "Credentials updated successfully for ${platformClass?.simpleName}")
                                } else {
                                    errorMessage = "Invalid username or password"
                                    Log.w("Login", "Invalid credentials for ${platformClass?.simpleName}")
                                }
                            } catch (e: Exception) {
                                errorMessage = e.localizedMessage
                                Log.e("Login", "Error during login update", e)
                            } finally {
                                busy = false
                            }
                        }
                    },
                    onCancel = { editIndex = null }

                )

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
        var selectedPlatform by remember { mutableStateOf<PlatformInfo?>(null) }
        var loginFields by remember { mutableStateOf<LoginFields?>(null) }
        var isLoading by remember { mutableStateOf(false) }
        var errorMessage by remember { mutableStateOf<String?>(null) }

        ModalBottomSheet(
            onDismissRequest = { closeAddSheet() },
            sheetState = sheetState
        ) {
            if (selectedPlatform == null) {
                // ────────────── Platform Selection ──────────────
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Choose a platform",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Spacer(Modifier.height(12.dp))

                    ActionRow {
                        addVerticalActionList(
                            options = platformOptions.map { option ->
                                SegmentedOption(
                                    option,
                                    text = option.name,
                                    iconRes = option.iconRes
                                )
                            },
                            onClick = { option ->
                                selectedPlatform = option
                                loginFields = option.factory().getLoginFields()
                                errorMessage = null
                            },
                            isGlass = false,
                            backdrop = backdrop
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = { closeAddSheet() }) {
                        Text("Cancel")
                    }
                }

            } else {
                // ────────────── Login Fields Step ──────────────
                val fields = loginFields ?: selectedPlatform!!.factory().getLoginFields()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Back button + title
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        IconButton(
                            onClick = {
                                selectedPlatform = null
                                errorMessage = null
                            },
                            modifier = Modifier.align(Alignment.CenterStart)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                        Text(
                            text = selectedPlatform!!.name,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    DynamicAddFields(
                        fields = fields,
                        onFieldsChanged = { loginFields = it },
                        isLoading = isLoading,
                        errorMessage = errorMessage,
                        onLogin = {
                            val missing = fields.getFields().any { it.value.isNullOrBlank() }
                            if (missing) {
                                errorMessage = "Please fill in all fields"
                                return@DynamicAddFields
                            }

                            isLoading = true
                            errorMessage = null

                            scope.launch {
                                try {

                                    val created = withContext(Dispatchers.IO) {
                                        val info = selectedPlatform!!

                                        // Try to call constructor(LoginFields)
                                        val platformClass = info.factory()::class.java
                                        val constructor = platformClass.constructors.find { ctor ->
                                            ctor.parameterTypes.size == 1 && ctor.parameterTypes[0] == LoginFields::class.java
                                        }

                                        val instance = if (constructor != null) {
                                            // Platform supports direct loginFields constructor (e.g., WebtopPlatform)
                                            constructor.newInstance(fields) as Platform
                                        } else {
                                            // Fall back: create a blank one, then apply login fields
                                            info.factory().apply {
                                                applyLoginFields(fields)
                                            }
                                        }

                                        instance
                                    }
                                    println(created)
                                    println(created.isLoggedIn())
                                    println(created.toString())
                                    val ok = withContext(Dispatchers.IO) { created.isLoggedIn() }
                                    if (ok) {
                                        withContext(Dispatchers.IO) {
                                            val list = PlatformStorage.loadPlatforms(context).toMutableList()
                                            list.add(created)
                                            PlatformStorage.savePlatforms(context, list)
                                        }

                                        refreshKey++
                                        onPlatformsChanged()
                                        closeAddSheet()
                                    } else {
                                        errorMessage = "Invalid credentials"
                                    }
                                } catch (e: Exception) {
                                    errorMessage = "Error: ${e.localizedMessage}"
                                } finally {
                                    isLoading = false
                                }
                            }
                        },
                        onCancel = { closeAddSheet() }
                    )
                }
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


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPage(
    navController: NavController,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val backdrop = rememberLayerBackdrop()

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {

        ActionRow {
            addVerticalActionList(
                options = listOf(
                    SegmentedOption("platforms", text = "Platforms", desc = "Add, Remove Or Edit Platforms", iconRes = R.drawable.ic_account),
                ),
                onClick = { option ->
                    when (option) {
                        "platforms" -> navController.navigate(Dest.Platforms.name)
                    }
                },
                isGlass = false,
                backdrop = backdrop
            )

        }
        Spacer(Modifier.height(10.dp))
    }

}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DynamicEditFields(
    fields: LoginFields,
    onFieldsChanged: (LoginFields) -> Unit,
    isLoading: Boolean,
    errorMessage: String?,
    onLogin: () -> Unit,
    onCancel: () -> Unit
) {
    var mutableFields by remember { mutableStateOf(fields) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        fields.getFields().forEach { field ->
            var value by remember { mutableStateOf(field.value ?: "") }
            var passwordVisible by remember { mutableStateOf(false) }

            OutlinedTextField(
                value = value,
                onValueChange = {
                    value = it
                    mutableFields.setValue(field.id, it)
                    onFieldsChanged(mutableFields)
                },
                label = { Text(field.id.replaceFirstChar { c -> c.uppercase() }) },
                leadingIcon = {
                    when (field.type) {
                        Type.Username, Type.Id -> Icon(Icons.Default.Person, null)
                        Type.Password -> Icon(Icons.Default.Lock, null)
                        else -> {}
                    }
                },
                trailingIcon = if (field.type == Type.Password) {
                    {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible)
                                    Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = null
                            )
                        }
                    }
                } else null,
                visualTransformation = if (field.type == Type.Password && !passwordVisible)
                    PasswordVisualTransformation() else VisualTransformation.None,
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            )
        }

        Spacer(Modifier.height(16.dp))

        AnimatedVisibility(visible = isLoading, enter = fadeIn(), exit = fadeOut()) {
            CircularWavyProgressIndicator()
        }

        AnimatedVisibility(
            visible = !isLoading,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {

                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    shape = RoundedCornerShape(30)
                ) {
                    Text(
                        "Cancel",
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                Button(
                    onClick = onLogin,
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    shape = RoundedCornerShape(35)
                ) {
                    Text(
                        "Update",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }

        errorMessage?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
    }
}
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DynamicAddFields(
    fields: LoginFields,
    onFieldsChanged: (LoginFields) -> Unit,
    isLoading: Boolean,
    errorMessage: String?,
    onLogin: () -> Unit,
    onCancel: () -> Unit
) {
    var mutableFields by remember { mutableStateOf(fields) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        fields.getFields().forEach { field ->
            var value by remember { mutableStateOf(field.value ?: "") }
            var passwordVisible by remember { mutableStateOf(false) }

            OutlinedTextField(
                value = value,
                onValueChange = {
                    value = it
                    mutableFields.setValue(field.id, it)
                    onFieldsChanged(mutableFields)
                },
                label = { Text(field.id.replaceFirstChar { c -> c.uppercase() }) },
                leadingIcon = {
                    when (field.type) {
                        Type.Username, Type.Id -> Icon(Icons.Default.Person, null)
                        Type.Password -> Icon(Icons.Default.Lock, null)
                        else -> {}
                    }
                },
                trailingIcon = if (field.type == Type.Password) {
                    {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible)
                                    Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = null
                            )
                        }
                    }
                } else null,
                visualTransformation = if (field.type == Type.Password && !passwordVisible)
                    PasswordVisualTransformation() else VisualTransformation.None,
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            )
        }

        Spacer(Modifier.height(16.dp))

        AnimatedVisibility(visible = isLoading, enter = fadeIn(), exit = fadeOut()) {
            CircularWavyProgressIndicator()
        }

        AnimatedVisibility(
            visible = !isLoading,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {

                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    shape = RoundedCornerShape(30)
                ) {
                    Text(
                        "Cancel",
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                Button(
                    onClick = onLogin,
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    shape = RoundedCornerShape(35)
                ) {
                    Text(
                        "Add",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }

        errorMessage?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
    }
}