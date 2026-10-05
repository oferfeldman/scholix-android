package com.feldman.scholix.pages

import androidx.compose.ui.res.stringResource
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import com.feldman.scholix.R
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import com.feldman.scholix.AppDest
import com.feldman.scholix.BottomBarSpacing
import com.feldman.scholix.api.LoginFields
import com.feldman.scholix.api.Platform
import com.feldman.scholix.api.PlatformInfo
import com.feldman.scholix.api.PlatformStorage
import com.feldman.scholix.api.ProviderCourseOverrides
import com.feldman.scholix.api.applyLoginFields
import com.feldman.scholix.api.platformOptions
import com.feldman.motion.MotionItemPosition
import com.feldman.motion.MotionCard
import com.feldman.motion.MotionSectionDefaults
import com.feldman.motion.MotionScaffold
import com.feldman.motion.MotionSymbols
import com.feldman.motion.MotionButton
import com.feldman.motion.MotionButtonDefaults
import com.feldman.motion.MotionButtonState
import com.feldman.motion.isMotionDarkTheme
import com.feldman.motion.motionBottomSheetAnchor
import com.feldman.motion.rememberSymbolPainter
import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.feldman.scholix.api.platforms.StudentsPortalPlatform
import com.feldman.scholix.ui.HiddenMoeLogin
import com.feldman.scholix.ui.HiddenInbarLogin
import com.feldman.scholix.api.platforms.InbarPlatform
import com.feldman.scholix.ui.HiddenWebtopMoeLogin
import com.feldman.scholix.api.platforms.WebtopPlatform
import com.feldman.scholix.ui.components.ProviderPickerList
import com.feldman.scholix.ui.components.AccountIconColor
import com.feldman.scholix.ui.components.WebtopLoginMethodPicker
import com.feldman.scholix.ui.components.SettingsCategoryColor
import com.feldman.scholix.ui.components.SettingsTopBar
import com.feldman.scholix.ui.components.SubjectIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale
import kotlin.reflect.full.companionObjectInstance

private fun platformAccountColorSeed(platform: Platform): String =
    "${platform.javaClass.name}|${platform.getUsername().trim().lowercase(Locale.ROOT)}"

private fun assignPlatformAccountColors(platforms: List<Platform>): Map<String, AccountIconColor> {
    val palette = AccountIconColor.entries
    val usedIndices = mutableSetOf<Int>()

    return platforms
        .sortedWith(compareBy({ platformAccountColorSeed(it) }, { it.id }))
        .associate { platform ->
            val preferredIndex = Math.floorMod(platformAccountColorSeed(platform).hashCode(), palette.size)
            var colorIndex = preferredIndex
            if (usedIndices.size < palette.size) {
                while (colorIndex in usedIndices) {
                    colorIndex = (colorIndex + 1) % palette.size
                }
                usedIndices += colorIndex
            }
            platform.id to palette[colorIndex]
        }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PlatformsPage(
    modifier: Modifier = Modifier,
    onPlatformsChanged: () -> Unit,
    onLogout: () -> Unit,
    onAddPlatform: () -> Unit,
    onEditProvider: (String) -> Unit,
    platforms: List<Platform>,
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var currentPlatforms by remember(platforms) {
        mutableStateOf(platforms)
    }
    val reorderSaveMutex = remember { Mutex() }

    var confirmDeleteIndex by remember { mutableStateOf<Int?>(null) }

    val useDark = isMotionDarkTheme()
    val accountColors = remember(currentPlatforms) {
        assignPlatformAccountColors(currentPlatforms)
    }

    MotionScaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            SettingsTopBar(
                title = "Providers",
                onBack = onBack,
                chromeColor = SettingsCategoryColor.PLATFORMS.container(useDark)
            )
        },
        floatingActionButton = {
            var fabMenuExpanded by rememberSaveable { mutableStateOf(false) }
            BackHandler(fabMenuExpanded) { fabMenuExpanded = false }

            FloatingActionButtonMenu(
                expanded = fabMenuExpanded,
                button = {
                    val largeFabSize =
                        ToggleFloatingActionButtonDefaults.containerSizeLarge()(0f)
                    val largeFabCornerRadius =
                        ToggleFloatingActionButtonDefaults.containerCornerRadiusLarge()(0f)
                    val largeIconSize =
                        ToggleFloatingActionButtonDefaults.iconSizeLarge()(0f)
                    val fabContainerColor =
                        ToggleFloatingActionButtonDefaults.containerColor()(0f)
                    val fabIconColor = ToggleFloatingActionButtonDefaults.iconColor()(0f)
                    ToggleFloatingActionButton(
                        checked = fabMenuExpanded,
                        onCheckedChange = { fabMenuExpanded = !fabMenuExpanded },
                        contentAlignment = Alignment.BottomEnd,
                        modifier = Modifier.animateFloatingActionButton(
                            visible = true,
                            alignment = Alignment.BottomEnd
                        ),
                        containerSize = { largeFabSize },
                        containerColor = { fabContainerColor },
                        containerCornerRadius = { progress ->
                            lerp(largeFabCornerRadius, largeFabSize / 2, progress)
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = if (fabMenuExpanded) {
                                "Close provider actions"
                            } else {
                                "Open provider actions"
                            },
                            tint = fabIconColor,
                            modifier = Modifier
                                .size(largeIconSize)
                                .graphicsLayer { rotationZ = checkedProgress * 45f }
                        )
                    }
                }
            ) {
                FloatingActionButtonMenuItem(
                    modifier = Modifier.motionBottomSheetAnchor(),
                    onClick = {
                        fabMenuExpanded = false
                        onAddPlatform()
                    },
                    icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = "Add provider") },
                    text = { Text("Add provider") },
                    containerColor = MaterialTheme.colorScheme.tertiary,
                    contentColor = MaterialTheme.colorScheme.onTertiary
                )
                FloatingActionButtonMenuItem(
                    onClick = {
                        fabMenuExpanded = false
                        onLogout()
                    },
                    icon = {
                        Icon(painterResource(R.drawable.ic_logout), contentDescription = "Log out")
                    },
                    text = { Text("Log out") },
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            }
        }
    ) {
        if (currentPlatforms.isEmpty()) {
            Item {
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    Text("No providers yet")
                }
            }
        } else {
            ReorderableSection(
                items = currentPlatforms,
                key = { platform -> platform.id },
                onReorder = { fromIndex, toIndex ->
                    if (
                        fromIndex in currentPlatforms.indices &&
                        toIndex in currentPlatforms.indices &&
                        fromIndex != toIndex
                    ) {
                        val reordered = currentPlatforms.toMutableList().apply {
                            add(toIndex, removeAt(fromIndex))
                        }
                        currentPlatforms = reordered

                        scope.launch {
                            withContext(Dispatchers.IO) {
                                reorderSaveMutex.withLock {
                                    PlatformStorage.savePlatforms(context, reordered)
                                }
                            }
                            onPlatformsChanged()
                        }
                    }
                },
                title = "Providers"
            ) { platform, _ ->
                val index = currentPlatforms.indexOfFirst { it.id == platform.id }
                PlatformSettingsItem(
                    platform = platform,
                    accountColor = accountColors.getValue(platform.id),
                    useDark = useDark,
                    onEdit = { onEditProvider(platform.id) },
                    onDelete = { if (index >= 0) confirmDeleteIndex = index }
                )
            }
        }

        Item {
            Spacer(Modifier.height(120.dp))
        }
    }
    // --- Delete confirmation ---
    if (confirmDeleteIndex != null) {
        val idx = confirmDeleteIndex!!
        val platform = PlatformStorage.loadPlatforms(context).getOrNull(idx)

        if (platform != null) {
            AlertDialog(
                onDismissRequest = { confirmDeleteIndex = null },
                title = { Text("Remove provider") },
                text = { Text("Are you sure you want to remove this provider?") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    val list = PlatformStorage.loadPlatforms(context).toMutableList()
                                    if (idx in list.indices) {
                                        val removed = list.removeAt(idx)
                                        PlatformStorage.savePlatforms(context, list)
                                        PlatformStorage.clearProviderCourseOverrides(context, removed.id)
                                    }
                                }
                                withContext(Dispatchers.Main) {
                                    confirmDeleteIndex = null
                                    currentPlatforms = PlatformStorage.loadPlatforms(context)
                                    onPlatformsChanged()
                                    Log.d("ProvidersPage", "Deleted ${platform.javaClass.simpleName}")
                                }
                            }
                        }
                    ) {
                        Text("Remove", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDeleteIndex = null }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

/**
 * A section heading with the explanation tucked behind an info button.
 *
 * Keeps the sheet short: the description is a tap away in a rich tooltip rather
 * than a paragraph every reader has to scroll past once they know what it does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SectionHeaderWithHelp(
    title: String,
    helpTitle: String,
    helpText: String
) {
    val tooltipState = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.width(4.dp))
        TooltipBox(
            positionProvider = TooltipDefaults.rememberRichTooltipPositionProvider(),
            tooltip = {
                RichTooltip(
                    title = { Text(helpTitle) },
                    action = {
                        TextButton(onClick = { tooltipState.dismiss() }) { Text("Got it") }
                    }
                ) { Text(helpText) }
            },
            state = tooltipState
        ) {
            IconButton(
                onClick = { scope.launch { tooltipState.show() } },
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    painter = rememberSymbolPainter(MotionSymbols.ic_info),
                    contentDescription = "About $title",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AddPlatformSheet(
    onAdded: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedPlatform by remember { mutableStateOf<PlatformInfo?>(null) }
    var inbarLogin by remember { mutableStateOf<InbarPlatform?>(null) }
    val savedInbar = remember(selectedPlatform) {
        if (selectedPlatform?.name == "Inbar (Bar-Ilan)")
            PlatformStorage.loadPlatforms(context).filterIsInstance<InbarPlatform>().lastOrNull()
        else null
    }
    var loginFields by remember { mutableStateOf<LoginFields?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Credentials for the off-screen MOE sign-in (set when the user submits).
    var portalCreds by remember { mutableStateOf<Pair<String, String>?>(null) }
    var webtopMoeCreds by remember { mutableStateOf<Pair<String, String>?>(null) }
    // Webtop can be added with its own username/password or via MOE SSO.
    val webtopLoginMethod = remember { mutableStateOf("password") }
    // For rendering only -- see submitAdd(), which reads the state directly.
    val webtopMoe = webtopLoginMethod.value == "moe"
    // What the busy screen says while an off-screen sign-in runs.
    val addStatus = when {
        webtopMoeCreds != null || portalCreds != null -> "Signing in with the Ministry of Education"
        else -> "Signing in"
    }

    inbarLogin?.let { pending ->
        HiddenInbarLogin(account = pending,
            onSmsRequested = { account ->
                withContext(Dispatchers.IO) { PlatformStorage.saveInbarLoginProgress(context, account) }
            }, onResult = { account, error ->
                if (account != null) withContext(Dispatchers.IO) {
                    PlatformStorage.saveInbarLoginProgress(context, account)
                }
                inbarLogin = null
                isLoading = false
                errorMessage = error
                if (account != null) { onAdded(); onClose() }
            })
    }

    // Off-screen MOE sign-in: the user types into this sheet's own fields and no
    // browser UI is shown (the IdP is bot-protected, so a real engine must run).
    // Webtop via MOE, driven off-screen (accept cookies -> MOE button ->
    // credentials -> adopt the SPA's LoginMoe session).
    webtopMoeCreds?.let { (user, pass) ->
        HiddenWebtopMoeLogin(username = user, password = pass) { key, error ->
            webtopMoeCreds = null
            if (key == null) {
                errorMessage = error ?: "Ministry of Education login failed"
                isLoading = false
                return@HiddenWebtopMoeLogin
            }
            scope.launch {
                try {
                    val created = withContext(Dispatchers.IO) {
                        WebtopPlatform.loginWithMoe(key, user, pass)
                    }
                    if (created.isLoggedIn()) {
                        withContext(Dispatchers.IO) {
                            val platforms = PlatformStorage.loadPlatforms(context).toMutableList()
                            platforms += created
                            PlatformStorage.savePlatforms(context, platforms)
                        }
                        onAdded()
                        onClose()
                    } else {
                        errorMessage = "Could not start the Webtop session"
                    }
                } catch (e: Exception) {
                    errorMessage = "Error: ${e.localizedMessage}"
                } finally {
                    isLoading = false
                }
            }
        }
    }

    portalCreds?.let { (user, pass) ->
        HiddenMoeLogin(username = user, password = pass) { cookies, csrt, error ->
            portalCreds = null
            if (cookies == null) {
                errorMessage = error ?: "Ministry of Education login failed"
                isLoading = false
                return@HiddenMoeLogin
            }
            scope.launch {
                try {
                    val created = withContext(Dispatchers.IO) {
                        StudentsPortalPlatform.loginWithCookies(cookies, csrt, user, pass)
                    }
                    val ok = withContext(Dispatchers.IO) {
                        runCatching { created.isLoggedIn() }.getOrElse { false }
                    }
                    if (ok) {
                        withContext(Dispatchers.IO) {
                            val platforms = PlatformStorage.loadPlatforms(context).toMutableList()
                            platforms += created
                            PlatformStorage.savePlatforms(context, platforms)
                        }
                        onAdded()
                        onClose()
                    } else {
                        errorMessage = "Ministry of Education login failed"
                    }
                } catch (e: Exception) {
                    errorMessage = "Error: ${e.localizedMessage}"
                } finally {
                    isLoading = false
                }
            }
        }
    }

    // Extracted so the sheet's action button and the keyboard's Done key
    // trigger the same submit.
    fun submitAdd() {
        val fields = loginFields ?: selectedPlatform?.factory()?.getLoginFields() ?: return
        if (fields.getFields().any { it.value.isNullOrBlank() }) {
            errorMessage = "Please fill in all fields"
            return
        }
        isLoading = true
        errorMessage = null
        if (selectedPlatform!!.name == "Inbar (Bar-Ilan)") {
            inbarLogin = (savedInbar?.forSmsLogin() ?: InbarPlatform(fields)).apply {
                applyLoginFields(fields)
            }
            return
        }
        // Read the picker's state here, not a value captured when this function was
        // created: onSubmit outlives the composition that built it, so a snapshot
        // would send Ministry-of-Education credentials down the Webtop password
        // path and fail as "invalid credentials".
        if (selectedPlatform!!.name == "Webtop" && webtopLoginMethod.value == "moe") {
            // Webtop via MOE, driven off-screen.
            webtopMoeCreds = Pair(
                fields.getValue("username").orEmpty(),
                fields.getValue("password").orEmpty()
            )
            return
        }
        if (selectedPlatform!!.name == "Education Portal") {
            // Relayed to the MOE page off-screen; no browser UI.
            portalCreds = Pair(
                fields.getValue("username").orEmpty(),
                fields.getValue("password").orEmpty()
            )
            return
        }
        scope.launch {
            try {
                val info = selectedPlatform!!
                val created = withContext(Dispatchers.IO) {
                    val platformClass = info.factory()::class.java
                    val constructor = platformClass.constructors.find { constructor ->
                        constructor.parameterTypes.size == 1 &&
                            constructor.parameterTypes[0] == LoginFields::class.java
                    }
                    if (constructor != null) {
                        constructor.newInstance(fields) as Platform
                    } else {
                        info.factory().apply { applyLoginFields(fields) }
                    }
                }
                val isLoggedIn = withContext(Dispatchers.IO) {
                    runCatching { created.isLoggedIn() }.getOrElse {
                        Log.e("AddPlatform", "Login check failed", it)
                        false
                    }
                }
                if (isLoggedIn) {
                    withContext(Dispatchers.IO) {
                        val platforms = PlatformStorage.loadPlatforms(context).toMutableList()
                        platforms += created
                        PlatformStorage.savePlatforms(context, platforms)
                    }
                    onAdded()
                    onClose()
                } else {
                    errorMessage = "Invalid credentials"
                }
            } catch (exception: Exception) {
                errorMessage = "Error: ${exception.localizedMessage}"
            } finally {
                isLoading = false
            }
        }
    }

    if (isLoading) {
        MotionScaffold(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp),
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0),
            fitContentHeight = true
        ) {
            Item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 220.dp)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    ContainedLoadingIndicator(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        indicatorColor = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = addStatus,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
        }
        return
    }

    MotionScaffold(
        modifier = Modifier.fillMaxWidth(),
        // Keyboard insets are handled once, by MotionBottomSheetScene.
        contentPadding = PaddingValues(0.dp),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        fitContentHeight = true
    ) {
        Item {
            if (selectedPlatform == null) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Choose a provider",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(12.dp))
            ProviderPickerList(
                providers = platformOptions,
                onSelect = { option ->
                    selectedPlatform = option
                    val draft = option.factory()
                    loginFields = draft.getLoginFields().apply {
                        if (draft is InbarPlatform) PlatformStorage.loadPlatforms(context)
                            .filterIsInstance<InbarPlatform>().lastOrNull()?.let { loadFrom(it) }
                    }
                    errorMessage = null
                }
            )
            Spacer(Modifier.height(16.dp))
            MotionButton(
                icon = MotionSymbols.ic_close,
                onClick = onClose,
                modifier = Modifier.semantics { contentDescription = "Cancel" },
                sizes = MotionButtonDefaults.sizes(width = ProviderSheetActionButtonWidth, height = ProviderSheetActionButtonHeight, iconSize = 22.dp),
                states = MotionButtonDefaults.states(default = MotionButtonState(
                    backgroundColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                ))
            )
        }
            } else {
                Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
                ) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                IconButton(
                    onClick = {
                        selectedPlatform = null
                        errorMessage = null
                    },
                    modifier = Modifier.align(Alignment.CenterStart)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Text(
                    text = selectedPlatform!!.name,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(16.dp))
            if (selectedPlatform!!.name == "Webtop") {
                WebtopLoginMethodPicker(
                    state = webtopLoginMethod,
                    onSelectedChange = { errorMessage = null }
                )

                Spacer(Modifier.height(12.dp))
            }
            DynamicLoginFields(
                fields = loginFields ?: selectedPlatform!!.factory().getLoginFields(),
                onFieldsChanged = { loginFields = it },
                isLoading = isLoading,
                errorMessage = errorMessage,
                onSubmit = ::submitAdd,
                onCancel = onClose,
                buttonText = "Add provider",
                useMotionButtons = true
            )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun EditProviderSheet(
    provider: Platform,
    onChanged: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saveMutex = remember { Mutex() }
    val providerInfo = remember(provider.javaClass.name) {
        platformOptions.associateBy { it.factory()::class.java.name }[provider.javaClass.name]
    }
    var providerName by remember(provider.id) {
        mutableStateOf(provider.getName())
    }
    var loginFields by remember(provider.id) {
        mutableStateOf(
            (providerInfo?.factory()?.getLoginFields() ?: provider.getLoginFields()).apply {
                loadFrom(provider)
            }
        )
    }
    var busy by remember { mutableStateOf(false) }
    val webtopLoginMethod = remember(provider.id) {
        val platform = provider as? WebtopPlatform
        val method = platform?.loginMethod
        val initialMethod = if (!method.isNullOrBlank() && method != "password") {
            method
        } else {
            val user = platform?.getUsername().orEmpty()
            if (user.any { it.isLetter() }) "moe" else (method ?: "password")
        }
        mutableStateOf<String>(initialMethod ?: "password")
    }
    var webtopRelogin by remember { mutableStateOf<Pair<String, String>?>(null) }
    var inbarRelogin by remember(provider.id) { mutableStateOf<InbarPlatform?>(null) }
    // Editing the portal's password must also re-establish its SSO session:
    // the stored cookie is what actually authenticates, and new credentials
    // alone would leave grades failing until the next manual re-add.
    var portalRelogin by remember { mutableStateOf<Pair<String, String>?>(null) }
    var savingStatus by remember { mutableStateOf("Verifying changes") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var coursesLoading by remember { mutableStateOf(true) }
    val orderedCoursesState = remember(provider.id) {
        mutableStateOf<List<JSONObject>>(emptyList())
    }
    val courseNamesState = remember(provider.id) {
        mutableStateOf(
            PlatformStorage.loadProviderCourseOverrides(context, provider.id).courseNames
        )
    }
    var renamingCourseKey by remember(provider.id) { mutableStateOf<String?>(null) }
    val hiddenCourseKeysState = remember(provider.id) {
        mutableStateOf<Set<String>>(emptySet())
    }
    var courseOverridesChanged by remember { mutableStateOf(false) }

    LaunchedEffect(provider.id) {
        val loaded = withContext(Dispatchers.IO) {
            PlatformStorage.getProviderCourses(context, provider, includeHidden = true)
        }
        val overrides = PlatformStorage.loadProviderCourseOverrides(context, provider.id)
        orderedCoursesState.value = loaded
        hiddenCourseKeysState.value = loaded
            .filter { PlatformStorage.isCourseHidden(it, overrides.hiddenCourseKeys) }
            .mapTo(mutableSetOf(), PlatformStorage::courseOverrideKey)
        coursesLoading = false
    }

    DisposableEffect(provider.id) {
        onDispose {
            if (courseOverridesChanged) onChanged()
        }
    }

    fun saveCourseOverrides(
        nextHidden: Set<String>,
        nextCourses: List<JSONObject>,
        nextNames: Map<String, String> = courseNamesState.value
    ) {
        val indexed = nextCourses.withIndex().toList()
        val normalized = indexed.sortedWith(
            compareBy<IndexedValue<JSONObject>> { it.value.optInt("courseStatusRank", 1) }
                .thenBy { it.index }
        ).map { it.value }
        orderedCoursesState.value = normalized
        hiddenCourseKeysState.value = nextHidden.toSet()
        courseNamesState.value = nextNames
        courseOverridesChanged = true
        PlatformStorage.saveProviderCourseOverrides(
            context = context,
            providerId = provider.id,
            overrides = ProviderCourseOverrides(
                hiddenCourseKeys = nextHidden,
                courseOrder = normalized.map(PlatformStorage::courseOverrideKey),
                courseNames = nextNames
            )
        )
    }

    /** Apply (or, for a blank name, clear) the user's name for one course. */
    fun renameCourse(course: JSONObject, newName: String) {
        val key = PlatformStorage.courseOverrideKey(course)
        val trimmed = newName.trim()
        val nextNames = courseNamesState.value.toMutableMap().apply {
            if (trimmed.isBlank()) remove(key) else put(key, trimmed)
        }
        // Re-apply to the in-memory copy so the row updates without a reload.
        val renamed = orderedCoursesState.value.map { existing ->
            if (PlatformStorage.courseOverrideKey(existing) != key) {
                existing
            } else {
                JSONObject(existing.toString())
                    .put(
                        PlatformStorage.SOURCE_NAME,
                        existing.optString(PlatformStorage.SOURCE_NAME)
                            .ifBlank { existing.optString("name") }
                    )
                    .put(
                        "name",
                        trimmed.ifBlank {
                            existing.optString(PlatformStorage.SOURCE_NAME)
                                .ifBlank { existing.optString("name") }
                        }
                    )
            }
        }
        saveCourseOverrides(hiddenCourseKeysState.value, renamed, nextNames)
    }

    fun setCourseHidden(course: JSONObject, hidden: Boolean) {
        val key = PlatformStorage.courseOverrideKey(course)
        val nextHidden = hiddenCourseKeysState.value.toMutableSet().apply {
            if (hidden) add(key) else remove(key)
        }
        saveCourseOverrides(nextHidden, orderedCoursesState.value)
    }

    // --- free periods ("חלונות") -----------------------------------------
    // Subjects the user no longer attends are shown in the schedule as gaps.
    val windowSubjectsState = remember(provider.id) {
        mutableStateOf(PlatformStorage.loadWindowSubjects(context, provider.id))
    }
    val subjectOptionsState = remember(provider.id) { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(provider.id) {
        subjectOptionsState.value = withContext(Dispatchers.IO) {
            runCatching { provider.getSubjectList() }.getOrDefault(emptyList())
        }
    }
    fun toggleWindowSubject(subject: String) {
        val next = windowSubjectsState.value.toMutableSet()
        if (!next.remove(subject)) next.add(subject)
        windowSubjectsState.value = next
        PlatformStorage.saveWindowSubjects(context, provider.id, next)
    }

    portalRelogin?.let { (user, pass) ->
        HiddenMoeLogin(username = user, password = pass) { cookies, csrt, error ->
            portalRelogin = null
            if (cookies == null) {
                errorMessage = error ?: "Ministry of Education login failed"
                busy = false
                return@HiddenMoeLogin
            }
            scope.launch {
                try {
                    savingStatus = "Saving changes"
                    val ok = withContext(Dispatchers.IO) {
                        val refreshed = StudentsPortalPlatform
                            .loginWithCookies(cookies, csrt, user, pass)
                        if (!refreshed.isLoggedIn()) return@withContext false
                        // Keep the existing id so course overrides and free-period
                        // settings stay attached to this provider.
                        refreshed.setName(providerName)
                        saveMutex.withLock {
                            val providers = PlatformStorage.loadPlatforms(context)
                            val index = providers.indexOfFirst { it.id == provider.id }
                            if (index >= 0) {
                                providers[index] = refreshed.withId(provider.id)
                                PlatformStorage.savePlatforms(context, providers)
                            }
                        }
                        true
                    }
                    if (ok) {
                        onChanged()
                        courseOverridesChanged = false
                        onClose()
                    } else {
                        errorMessage = "Invalid provider credentials"
                    }
                } catch (e: Exception) {
                    errorMessage = "Error updating provider: ${e.localizedMessage}"
                } finally {
                    busy = false
                }
            }
        }
    }

    webtopRelogin?.let { (user, pass) ->
        HiddenWebtopMoeLogin(username = user, password = pass) { key, error ->
            webtopRelogin = null
            if (key == null) {
                errorMessage = error ?: "Ministry of Education login failed"
                busy = false
                return@HiddenWebtopMoeLogin
            }
            scope.launch {
                try {
                    savingStatus = "Saving changes"
                    val ok = withContext(Dispatchers.IO) {
                        val refreshed = WebtopPlatform.loginWithMoe(key, user, pass)
                        if (!refreshed.isLoggedIn()) return@withContext false
                        // Keep the existing id so course overrides and free-period
                        // settings stay attached to this provider.
                        if (providerName.isNotBlank()) {
                            refreshed.setName(providerName)
                        }
                        refreshed.loginMethod = "moe"
                        refreshed.setUsername(user)
                        refreshed.setPassword(pass)
                        if (provider is WebtopPlatform) {
                            if (refreshed.studentId.isNullOrBlank()) refreshed.studentId = provider.studentId
                            if (refreshed.studentClass.isNullOrBlank()) refreshed.studentClass = provider.studentClass
                            if (refreshed.studentInstitution.isNullOrBlank()) refreshed.studentInstitution = provider.studentInstitution
                            if (providerName.isBlank()) refreshed.studentName = provider.studentName
                            if (refreshed.userStudentId.isNullOrBlank()) refreshed.userStudentId = provider.userStudentId
                            if (refreshed.userType == null) refreshed.userType = provider.userType
                            if (refreshed.schoolName.isNullOrBlank()) refreshed.schoolName = provider.schoolName
                            refreshed.platformDisplayName = provider.platformDisplayName
                        }
                        saveMutex.withLock {
                            val providers = PlatformStorage.loadPlatforms(context)
                            val index = providers.indexOfFirst { it.id == provider.id }
                            if (index >= 0) {
                                providers[index] = refreshed.withId(provider.id)
                                PlatformStorage.savePlatforms(context, providers)
                            }
                        }
                        true
                    }
                    if (ok) {
                        onChanged()
                        courseOverridesChanged = false
                        onClose()
                    } else {
                        errorMessage = "Could not start the Webtop session"
                    }
                } catch (e: Exception) {
                    errorMessage = "Error updating provider: ${e.localizedMessage}"
                } finally {
                    busy = false
                }
            }
        }
    }

    inbarRelogin?.let { pending ->
        HiddenInbarLogin(account = pending,
            onSmsRequested = { account ->
                if (providerName.isNotBlank()) account.setName(providerName)
                withContext(Dispatchers.IO) { PlatformStorage.saveInbarLoginProgress(context, account) }
            }, onResult = { account, error ->
                if (account != null) {
                    if (providerName.isNotBlank()) account.setName(providerName)
                    withContext(Dispatchers.IO) { PlatformStorage.saveInbarLoginProgress(context, account) }
                }
                inbarRelogin = null
                busy = false
                errorMessage = error
                if (account != null) { onChanged(); courseOverridesChanged = false; onClose() }
            })
    }

    val orderedCourses = orderedCoursesState.value
    val hiddenCourseKeys = hiddenCourseKeysState.value
    val visibleCourses = orderedCourses.filter {
        PlatformStorage.courseOverrideKey(it) !in hiddenCourseKeys
    }
    val removedCourses = orderedCourses.filter {
        PlatformStorage.courseOverrideKey(it) in hiddenCourseKeys
    }

    if (busy) {
        MotionScaffold(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp),
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0),
            fitContentHeight = true
        ) {
            Item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 220.dp)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    ContainedLoadingIndicator(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        indicatorColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = savingStatus,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
        }
        return
    }

    // Extracted so the sheet's action button and the keyboard's Done key
    // trigger the same submit.
    fun submitEdit() {
        val user = loginFields.getValue("username").orEmpty()
        val pass = loginFields.getValue("password").orEmpty()
        if (provider !is InbarPlatform && loginFields.getFields().isNotEmpty() && (user.isBlank() || pass.isBlank())) {
            errorMessage = "Please fill in all fields"
            return
        }
        if (provider is InbarPlatform && loginFields.getFields().any { it.value.isNullOrBlank() }) {
            errorMessage = "Please fill in all fields"
            return
        }

        savingStatus = "Verifying changes"
        busy = true
        errorMessage = null

        val currentMethod = (provider as? WebtopPlatform)?.let { webtopLoginMethod.value }
        val savedMethod = (provider as? WebtopPlatform)?.loginMethod
        val credsChanged = if (provider is InbarPlatform)
            loginFields.getValue("id").orEmpty().trim() != provider.getUsername() ||
                loginFields.getValue("mobile").orEmpty().trim() != provider.mobile
        else user != provider.getUsername() || pass != provider.getPassword()

        if (!credsChanged && provider.isLoggedIn()) {
            scope.launch {
                var dismissing = false
                try {
                    savingStatus = "Saving changes"
                    withContext(Dispatchers.IO) {
                        saveMutex.withLock {
                            val providers = PlatformStorage.loadPlatforms(context)
                            val index = providers.indexOfFirst { it.id == provider.id }
                            if (index >= 0) {
                                val updated = providers[index]
                                if (providerName.isNotBlank()) {
                                    updated.setName(providerName)
                                }
                                if (updated is WebtopPlatform) {
                                    currentMethod?.let { updated.loginMethod = it }
                                    updated.setUsername(user)
                                    updated.setPassword(pass)
                                } else {
                                    updated.applyLoginFields(loginFields)
                                }
                                providers[index] = updated
                                PlatformStorage.savePlatforms(context, providers)
                            }
                        }
                    }
                    onChanged()
                    courseOverridesChanged = false
                    dismissing = true
                    onClose()
                } catch (e: Exception) {
                    errorMessage = "Error updating provider: ${e.localizedMessage}"
                } finally {
                    if (!dismissing) busy = false
                }
            }
            return
        }

        if (provider is InbarPlatform) {
            savingStatus = "Signing in"
            inbarRelogin = provider.forSmsLogin().apply { applyLoginFields(loginFields) }
            return
        }

        if (provider is StudentsPortalPlatform) {
            savingStatus = "Signing in"
            portalRelogin = Pair(user, pass)
            return
        }

        if (provider is WebtopPlatform && webtopLoginMethod.value == "moe") {
            savingStatus = "Signing in with the Ministry of Education"
            webtopRelogin = Pair(user, pass)
            return
        }

        scope.launch {
            var dismissing = false
            try {
                if (provider is WebtopPlatform && webtopLoginMethod.value == "password") {
                    savingStatus = "Signing in"
                    val refreshed = withContext(Dispatchers.IO) {
                        WebtopPlatform(loginFields)
                    }
                    if (refreshed.isLoggedIn()) {
                        savingStatus = "Saving changes"
                        withContext(Dispatchers.IO) {
                            saveMutex.withLock {
                                val providers = PlatformStorage.loadPlatforms(context)
                                val index = providers.indexOfFirst { it.id == provider.id }
                                if (index >= 0) {
                                    if (providerName.isNotBlank()) {
                                        refreshed.setName(providerName)
                                    }
                                    refreshed.loginMethod = "password"
                                    refreshed.setUsername(user)
                                    refreshed.setPassword(pass)
                                    if (provider is WebtopPlatform) {
                                        if (refreshed.studentId.isNullOrBlank()) refreshed.studentId = provider.studentId
                                        if (refreshed.studentClass.isNullOrBlank()) refreshed.studentClass = provider.studentClass
                                        if (refreshed.studentInstitution.isNullOrBlank()) refreshed.studentInstitution = provider.studentInstitution
                                        if (providerName.isBlank()) refreshed.studentName = provider.studentName
                                        if (refreshed.userStudentId.isNullOrBlank()) refreshed.userStudentId = provider.userStudentId
                                        if (refreshed.userType == null) refreshed.userType = provider.userType
                                        if (refreshed.schoolName.isNullOrBlank()) refreshed.schoolName = provider.schoolName
                                        refreshed.platformDisplayName = provider.platformDisplayName
                                    }
                                    providers[index] = refreshed.withId(provider.id)
                                    PlatformStorage.savePlatforms(context, providers)
                                }
                            }
                        }
                        onChanged()
                        courseOverridesChanged = false
                        dismissing = true
                        onClose()
                    } else {
                        errorMessage = "Invalid provider credentials"
                    }
                    return@launch
                }

                val companion = provider.javaClass.kotlin.companionObjectInstance
                val isCorrect = withContext(Dispatchers.IO) {
                    companion !is Platform.Companion || companion.checkCredentials(loginFields)
                }
                if (isCorrect) {
                    savingStatus = "Saving changes"
                    withContext(Dispatchers.IO) {
                        saveMutex.withLock {
                            val providers = PlatformStorage.loadPlatforms(context)
                            val index = providers.indexOfFirst { it.id == provider.id }
                            if (index >= 0) {
                                val updated = providers[index]
                                updated.setName(providerName)
                                updated.applyLoginFields(loginFields)
                                providers[index] = updated
                                PlatformStorage.savePlatforms(context, providers)
                            }
                        }
                    }
                    onChanged()
                    courseOverridesChanged = false
                    dismissing = true
                    onClose()
                } else {
                    errorMessage = "Invalid provider credentials"
                }
            } catch (exception: Exception) {
                errorMessage = "Error updating provider: ${exception.localizedMessage}"
            } finally {
                if (!dismissing) busy = false
            }
        }
    }

    MotionScaffold(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        fitContentHeight = true
    ) {
        Item {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                Text(
                    text = "Edit provider",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = providerName,
                    onValueChange = { providerName = it },
                    label = { Text("Provider name") },
                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        }

        // Nothing to choose from means no section at all, rather than a heading
        // over a "nothing here yet" line.
        if (provider.supportsSchedule && subjectOptionsState.value.isNotEmpty()) {
            Item {
                Column(modifier = Modifier.padding(top = 20.dp)) {
                    SectionHeaderWithHelp(
                        title = "Dropped subjects",
                        helpTitle = "Dropped subjects",
                        helpText = "Subjects you no longer attend. Pick one and it " +
                            "shows in the schedule as a free period instead of a lesson."
                    )
                    Spacer(Modifier.height(10.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        subjectOptionsState.value.forEach { subject ->
                            val selected = PlatformStorage.isWindowSubject(
                                subject, windowSubjectsState.value
                            )
                            FilterChip(
                                selected = selected,
                                onClick = { toggleWindowSubject(subject) },
                                label = { Text(subject) }
                            )
                        }
                    }
                }
            }
        }

        if (coursesLoading) {
            Item {
                Box(
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
        } else {
            ReorderableSection(
                items = visibleCourses,
                key = { course -> "visible:${PlatformStorage.courseOverrideKey(course)}" },
                title = "Courses",
                onReorder = { fromIndex, toIndex ->
                    val currentCourses = orderedCoursesState.value
                    val currentHidden = hiddenCourseKeysState.value
                    val currentVisible = currentCourses.filter {
                        PlatformStorage.courseOverrideKey(it) !in currentHidden
                    }
                    if (fromIndex !in currentVisible.indices || toIndex !in currentVisible.indices) {
                        return@ReorderableSection
                    }
                    val reorderedVisible = currentVisible.toMutableList().apply {
                        add(toIndex, removeAt(fromIndex))
                    }
                    val visibleKeys = currentVisible
                        .map(PlatformStorage::courseOverrideKey)
                        .toSet()
                    var visibleIndex = 0
                    val merged = currentCourses.map { course ->
                        if (PlatformStorage.courseOverrideKey(course) in visibleKeys) {
                            reorderedVisible[visibleIndex++]
                        } else {
                            course
                        }
                    }
                    saveCourseOverrides(currentHidden, merged)
                }
            ) { course, _ ->
                val courseKey = PlatformStorage.courseOverrideKey(course)
                ProviderCourseRow(
                    course = course,
                    actionIcon = MotionSymbols.ic_visibility_off,
                    actionDescription = "Remove course",
                    onAction = {
                        setCourseHidden(course, hidden = true)
                    },
                    renaming = renamingCourseKey == courseKey,
                    onRenameStart = { renamingCourseKey = courseKey },
                    onRenameDone = { newName ->
                        renameCourse(course, newName)
                        renamingCourseKey = null
                    }
                )
            }

            if (removedCourses.isNotEmpty()) {
                Title("Removed courses")
                Item {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        removedCourses.forEachIndexed { index, course ->
                            key("removed:${PlatformStorage.courseOverrideKey(course)}") {
                                MotionCard(
                                    position = when {
                                        removedCourses.size == 1 -> MotionItemPosition.Alone
                                        index == 0 -> MotionItemPosition.Start
                                        index == removedCourses.lastIndex -> MotionItemPosition.End
                                        else -> MotionItemPosition.Middle
                                    },
                                    contentPadding = 0.dp
                                ) {
                                    ProviderCourseRow(
                                        course = course,
                                        actionIcon = MotionSymbols.ic_add,
                                        actionDescription = "Add course back",
                                        onAction = {
                                            setCourseHidden(course, hidden = false)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (loginFields.getFields().isNotEmpty()) Title("Provider credentials")
        Item {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (provider is WebtopPlatform) {
                    WebtopLoginMethodPicker(
                        state = webtopLoginMethod,
                        onSelectedChange = { errorMessage = null }
                    )

                    Spacer(Modifier.height(12.dp))
                }
                DynamicLoginFields(
                    fields = loginFields,
                    onFieldsChanged = { loginFields = it },
                    isLoading = busy,
                    errorMessage = errorMessage,
                    onSubmit = ::submitEdit,
                    onCancel = onClose,
                    buttonText = "Update provider",
                    useMotionButtons = true
                )
            }
        }
        Item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun ProviderCourseRow(
    course: JSONObject,
    actionIcon: String,
    actionDescription: String,
    onAction: () -> Unit,
    renaming: Boolean = false,
    onRenameStart: (() -> Unit)? = null,
    onRenameDone: ((String) -> Unit)? = null
) {
    val courseName = course.optString("name")
    val startsWithHebrew = courseName.firstOrNull { !it.isWhitespace() }
        ?.let { it in '\u0590'..'\u05FF' } == true
    CompositionLocalProvider(
        LocalLayoutDirection provides if (startsWithHebrew) LayoutDirection.Rtl else LayoutDirection.Ltr
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SubjectIcon(subject = courseName, assignment = "course")
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                if (renaming && onRenameDone != null) {
                    var draft by remember(courseName) { mutableStateOf(courseName) }
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = {
                            Text(course.optString(PlatformStorage.SOURCE_NAME).ifBlank { courseName })
                        },
                        trailingIcon = {
                            IconButton(onClick = { onRenameDone(draft) }) {
                                Icon(
                                    painter = rememberSymbolPainter(MotionSymbols.ic_check),
                                    contentDescription = "Save name"
                                )
                            }
                        }
                    )
                } else {
                    Text(
                        text = courseName,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Start
                    )
                }
                val supportingText = course.optString("status").ifBlank { course.optString("term") }
                if (supportingText.isNotBlank()) {
                    Text(
                        text = supportingText,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Start
                    )
                }
            }
            if (onRenameStart != null && !renaming) {
                IconButton(onClick = onRenameStart) {
                    Icon(
                        painter = painterResource(R.drawable.ic_edit),
                        contentDescription = "Rename course"
                    )
                }
            }
            IconButton(onClick = onAction) {
                Icon(
                    painter = rememberSymbolPainter(actionIcon),
                    contentDescription = actionDescription
                )
            }
        }
    }
}

@Composable
private fun PlatformSettingsItem(
    platform: Platform,
    accountColor: AccountIconColor,
    useDark: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val platformInfo = remember(platform.javaClass.name) {
        platformOptions.associateBy { it.factory()::class.java.name }[platform.javaClass.name]
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val providerName = platformInfo?.name
            ?: platform.javaClass.simpleName.removeSuffix("Platform")
        val providerPainter = platformInfo?.iconSymbol?.let {
            rememberSymbolPainter(name = it, fill = 1f)
        } ?: platformInfo?.let { painterResource(it.iconRes) }

        SubjectIcon(
            subject = providerName,
            painter = providerPainter,
            containerColor = accountColor.container(useDark),
            iconColor = accountColor.content(useDark)
        )
        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = providerName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = platform.getName(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(
                onClick = onEdit,
                modifier = Modifier.motionBottomSheetAnchor()
            ) {
                Icon(painterResource(R.drawable.ic_edit), contentDescription = "Edit provider")
            }

            IconButton(onClick = onDelete) {
                Icon(
                    painterResource(R.drawable.ic_delete),
                    contentDescription = "Remove provider",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPage(
    onOpenPlatforms: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenNavigation: () -> Unit,
    onOpenCrashLogs: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val useDark = isMotionDarkTheme()

    MotionScaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { SettingsTopBar("Settings") }
    ) {
        Title("App")
        Section {
            PageItem(
                title = "Appearance",
                description = "Theme, colors, motion, and orientation",
                icon = painterResource(R.drawable.ic_settings),
                iconStyle = MotionSectionDefaults.iconStyle(containerColor = SettingsCategoryColor.APPEARANCE.container(useDark), contentColor = SettingsCategoryColor.APPEARANCE.content(useDark)),
                onClick = onOpenAppearance,
                paneDestination = AppDest.Appearance
            )
            PageItem(
                title = "Navigation",
                description = "Choose the pages in your navbar",
                icon = painterResource(R.drawable.ic_menu),
                iconStyle = MotionSectionDefaults.iconStyle(containerColor = SettingsCategoryColor.NAVIGATION.container(useDark), contentColor = SettingsCategoryColor.NAVIGATION.content(useDark)),
                onClick = onOpenNavigation,
                paneDestination = AppDest.NavigationSettings
            )
            PageItem(
                title = stringResource(R.string.crash_logs),
                description = stringResource(R.string.crash_logs_description),
                icon = painterResource(R.drawable.ic_bug_report),
                iconStyle = MotionSectionDefaults.iconStyle(containerColor = SettingsCategoryColor.SYSTEM.container(useDark), contentColor = SettingsCategoryColor.SYSTEM.content(useDark)),
                onClick = onOpenCrashLogs,
                paneDestination = AppDest.CrashLogs
            )
        }

        Title("Providers")
        Section {
            PageItem(
                title = "Providers",
                description = "Add, remove, edit, and reorder providers",
                icon = painterResource(R.drawable.ic_account),
                iconStyle = MotionSectionDefaults.iconStyle(containerColor = SettingsCategoryColor.PLATFORMS.container(useDark), contentColor = SettingsCategoryColor.PLATFORMS.content(useDark)),
                onClick = onOpenPlatforms,
                paneDestination = AppDest.Platforms
            )
        }

        Item {
            Spacer(Modifier.height(BottomBarSpacing()))
        }
    }
}
