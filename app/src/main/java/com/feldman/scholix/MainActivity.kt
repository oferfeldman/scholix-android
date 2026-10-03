package com.feldman.scholix

import androidx.compose.ui.res.stringResource

import android.Manifest
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ToggleFloatingActionButtonDefaults
import androidx.compose.material3.ToggleFloatingActionButtonDefaults.animateIcon
import androidx.compose.material3.animateFloatingActionButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.draw.clip
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import android.content.pm.ActivityInfo
import androidx.core.app.ActivityCompat
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.Room
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.animateFloatingActionButton
import androidx.compose.ui.draw.alpha
import com.feldman.motion.MotionFloatingToolbarDefaults
import com.feldman.motion.MotionSymbols
import com.feldman.motion.motionFloatingContainer
import com.feldman.motion.rememberSymbolPainter
import com.feldman.motion.MotionDropdown
import com.feldman.motion.MotionDropdownDefaults
import com.feldman.motion.MotionDropdownDirection
import com.feldman.motion.MotionDropdownMenuAlignment
import com.feldman.motion.MotionBottomBarHost
import com.feldman.motion.MotionFabConfig
import com.feldman.motion.MotionDest
import com.feldman.motion.MotionLevel
import com.feldman.motion.MotionNavigationBar
import com.feldman.motion.MotionNavigationBarDefaults
import com.feldman.motion.MotionNavigationAxis
import com.feldman.motion.MotionNavHost
import com.feldman.motion.MotionThemeRepository
import com.feldman.lockerapp.ui.theme.AppTheme
import com.feldman.motion.rememberMotionDestBackStack
import com.feldman.motion.rememberMotionNavigationState
import com.feldman.motion.rememberMotionBlurState
import com.feldman.motion.MotionBlurBackdrop
import com.feldman.motion.LocalMotionBlurState
import com.feldman.motion.rememberMotionBlur
import com.feldman.scholix.api.Platform
import com.feldman.scholix.api.PlatformStorage
import com.feldman.scholix.pages.LockerDatabase
import com.feldman.scholix.pages.LockerRepository
import com.feldman.scholix.pages.LockerViewModel
import com.feldman.scholix.pages.LockerViewModelFactory
import com.feldman.scholix.pages.LoginPage
import com.feldman.scholix.pages.GradesLoadingIndicator
import com.feldman.scholix.pages.MessagesViewModel
import com.feldman.scholix.services.GradeMonitorWorker
import com.feldman.scholix.services.MessageMonitorWorker
import com.feldman.scholix.storage.OrientationMode
import com.feldman.scholix.storage.orientationModeFlow
import com.feldman.scholix.storage.defaultNavbarPages
import com.feldman.scholix.storage.navbarPagesFlow
import com.feldman.scholix.widgets.GradesWidgetReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
fun TopBarSpacing(): Dp {
    return WindowInsets.statusBars
        .asPaddingValues()
        .calculateTopPadding()
}

@Composable
fun BottomBarSpacing(): Dp {
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    return WindowInsets.navigationBars
        .asPaddingValues()
        .calculateBottomPadding() + if (isLandscape) 0.dp else 100.dp
}

class MainActivity : ComponentActivity() {
    private var homeworkOpenRequest by mutableIntStateOf(0)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("open_homework", false)) homeworkOpenRequest++
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("lemida_open_request", homeworkOpenRequest)
        super.onSaveInstanceState(outState)
    }

    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                Log.i("MainActivity", "Notification permission granted")
            } else {
                Log.w("MainActivity", "Notification permission denied by user")
            }
        }

    suspend fun isGoogleReachable(): Boolean {
        return try {
            val client = okhttp3.OkHttpClient()
            val request = okhttp3.Request.Builder()
                .url("https://www.google.com")
                .build()
            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            false
        }
    }

    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        // Take ownership of the window insets. Without this the framework keeps
        // decorFitsSystemWindows = true and resizes the window for the keyboard,
        // while Compose still reports the full IME inset -- so anything that pads
        // for the keyboard (every bottom sheet) pays for it twice.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        homeworkOpenRequest = savedInstanceState?.getInt("lemida_open_request", 0) ?: 0
        // Clean up grade notifications & legacy periodic worker
        GradeMonitorWorker.clearNotificationsAndChannel(this)
        GradeMonitorWorker.cancelPeriodicWorker(this)

        MessageMonitorWorker.schedule(this)
        lifecycleScope.launch(Dispatchers.IO) {
            val reachable = isGoogleReachable()
            Log.i("MainActivity", "Google reachable: $reachable")

            // This API is specifically for Android 15+ (API 35)
            if (Build.VERSION.SDK_INT >= 35) {
                // GlanceAppWidgetManager(this@MainActivity).setWidgetPreviews(GradesWidgetReceiver::class)
            }
        }

        lifecycleScope.launch {
            orientationModeFlow().collect { mode ->
                requestedOrientation = when (mode) {
                    OrientationMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    OrientationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    OrientationMode.AUTO -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }
        }

        val db = Room.databaseBuilder(
            applicationContext,
            LockerDatabase::class.java,
            "locker-db"
        )
            .fallbackToDestructiveMigration(false)
            .build()

        val repository = LockerRepository(
            itemDao = db.lockerItemDao(),
            tabDao = db.lockerTabDao()
        )

        setContent {
            AppTheme {
                var isLoggedIn by remember { mutableStateOf<Boolean?>(null) }
                val context = LocalContext.current
                val scope = rememberCoroutineScope()
                var platforms by remember { mutableStateOf<List<Platform>>(emptyList()) }
                var isLoading by remember { mutableStateOf(true) }
                var preloadedCourses by remember { mutableStateOf(listOf<JSONObject>()) }
                val snackbarHostState = remember { SnackbarHostState() }

                fun reloadPlatforms() {
                    val ctx = context
                    scope.launch(Dispatchers.IO) {
                        val newPlatforms = PlatformStorage.loadPlatforms(ctx)
                        val valid = newPlatforms.any { it.isLoggedIn() || it.canRestoreSession }
                        withContext(Dispatchers.Main) {
                            platforms = newPlatforms
                            isLoggedIn = valid
                            // delay, not Thread.sleep: this runs on the main
                            // thread, so sleeping here froze the UI for 120ms
                            // (~7 dropped frames) every time providers reloaded.
                            delay(120)
                            isLoading = false

                            if (valid) {
                                if (Build.VERSION.SDK_INT >= 35) {
                                    // GlanceAppWidgetManager(ctx).setWidgetPreviews(GradesWidgetReceiver::class)
                                }
                            }
                        }
                    }
                }

                fun reloadPreloads(snackbarHostState: SnackbarHostState) {
                    Log.d("MainActivity", "Reloading platforms")
                    scope.launch(Dispatchers.IO) {
                        val failedPlatforms = PlatformStorage.refreshCookies(this@MainActivity)
                        val courses = PlatformStorage.getCourses(this@MainActivity)

                        withContext(Dispatchers.Main) {
                            preloadedCourses = courses
                            isLoading = false

                            if (failedPlatforms.isNotEmpty()) {
                                scope.launch {
                                    val message = "Failed to reload: ${failedPlatforms.joinToString(", ")}"
                                    val result = snackbarHostState.showSnackbar(
                                        message = message,
                                        actionLabel = "Retry"
                                    )
                                    if (result == SnackbarResult.ActionPerformed) {
                                        isLoading = true
                                        reloadPreloads(snackbarHostState)
                                    }
                                }
                            }
                        }
                    }
                }

                LaunchedEffect(Unit) {
                    withContext(Dispatchers.IO) {
                        reloadPreloads(snackbarHostState)
                        val newPlatforms = PlatformStorage.loadPlatforms(context)
                        val valid = newPlatforms.any { it.isLoggedIn() || it.canRestoreSession }

                        withContext(Dispatchers.Main) {
                            platforms = newPlatforms
                            isLoggedIn = valid
                            isLoading = false
                        }
                    }
                }

                var initialResumeSeen by remember { mutableStateOf(false) }
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            if (initialResumeSeen) {
                                reloadPreloads(snackbarHostState)
                            } else {
                                initialResumeSeen = true
                            }
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                when {
                    isLoading || isLoggedIn == null -> {
                        GradesLoadingIndicator(modifier = Modifier.fillMaxSize())
                    }

                    isLoggedIn == false -> {
                        LoginPage(
                            onLoginSuccess = {
                                isLoading = true
                                reloadPlatforms()
                                isLoggedIn = true
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    else -> {
                        MainScreen(
                            homeworkOpenRequest = homeworkOpenRequest,
                            preloadedCourses = preloadedCourses,
                            repository = repository,
                            onPlatformsChanged = {
                                reloadPlatforms()
                                reloadPreloads(snackbarHostState)
                            },
                            platforms = platforms,
                            onLoginSuccess = {
                                isLoading = true
                                reloadPlatforms()
                                reloadPreloads(snackbarHostState)
                                isLoggedIn = true
                            },
                            onLogout = {
                                PlatformStorage.clearPlatforms(context)
                                isLoggedIn = false
                            }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MainScreen(
    preloadedCourses: List<JSONObject>,
    repository: LockerRepository,
    onPlatformsChanged: () -> Unit,
    platforms: List<Platform>,
    onLoginSuccess: () -> Unit,
    onLogout: () -> Unit,
    homeworkOpenRequest: Int = 0,
) {
    val context = LocalContext.current

    val hasGrades = platforms.any { it.suportsGrades }
    val hasSchedule = platforms.any { it.supportsSchedule }
    val hasAttendance = platforms.any { it.supportsAttendance }

    val destinations = remember(hasGrades, hasSchedule, hasAttendance) {
        listOfNotNull(
            if (hasGrades) AppDest.Grades else null,
            if (hasSchedule) AppDest.Schedule else null,
            if (hasAttendance) AppDest.Attendance else null,
            AppDest.Tiktek,
            AppDest.TiktekBook(bookId = "", bookName = "", subjectId = ""),
            AppDest.TiktekSolution(imageUrl = ""),
            AppDest.Locker,
            AppDest.Homework,
            AppDest.Settings,
            AppDest.Platforms,
            AppDest.AddPlatform,
            AppDest.EditProvider(providerId = ""),
            AppDest.Messages,
            AppDest.MessageDetail("", ""),
            AppDest.ComposeMessage("", ""),
            AppDest.MessagePeople(AppDest.ComposeMessage("", "")),
            AppDest.MessageOptions(AppDest.ComposeMessage("", "")),
            AppDest.MessageTools(""),
            AppDest.MessageFolders("", "INBOX"),
            AppDest.MessageSignature(""),
            AppDest.Notifications(""),
            AppDest.More,
            AppDest.NavigationSettings,
            AppDest.CrashLogs,
            AppDest.Login,
            AppDest.Appearance
        )
    }

    val navbarPages by remember(context) { context.navbarPagesFlow() }
        .collectAsState(initial = defaultNavbarPages)
    val navigationPages = remember(destinations) {
        destinations.filter { it in listOf(AppDest.Grades, AppDest.Schedule, AppDest.Attendance, AppDest.Tiktek, AppDest.Settings, AppDest.Messages, AppDest.Locker, AppDest.Homework) }
    }
    val bottomBarDestinations = navigationPages.filter { it.label in navbarPages }.ifEmpty { listOf(AppDest.Tiktek, AppDest.Settings) }

    val startDestination = rememberSaveable {
        val activity = context as? android.app.Activity
        if (activity?.intent?.getBooleanExtra("open_crash_logs", false) == true) AppDest.CrashLogs
        else if (activity?.intent?.getBooleanExtra("open_messages", false) == true) AppDest.Messages
        else if (activity?.intent?.getBooleanExtra("open_homework", false) == true) AppDest.Homework
        else if (hasGrades) AppDest.Grades else AppDest.Tiktek
    }

    val backStack = rememberMotionDestBackStack(startDestination)
    var handledHomeworkOpenRequest by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(homeworkOpenRequest) {
        if (homeworkOpenRequest > handledHomeworkOpenRequest) {
            backStack.navigateTop(AppDest.Homework)
            handledHomeworkOpenRequest = homeworkOpenRequest
        }
    }
    val currentScreen = backStack.backStack.lastOrNull() ?: startDestination
    val activeNavigationPage = backStack.backStack.asReversed().firstNotNullOfOrNull { screen ->
        generateSequence<MotionDest>(screen) { it.parent }.firstOrNull { it in navigationPages }
    }
    val visibleNavbarDestinations = if (activeNavigationPage != null && activeNavigationPage !in bottomBarDestinations) {
        bottomBarDestinations.take(3) + activeNavigationPage
    } else bottomBarDestinations

    val motionThemeRepository = remember(context) { MotionThemeRepository(context) }
    val motionLevel by motionThemeRepository.motionLevel
        .collectAsState(initial = MotionLevel.MEDIUM)
    val expressiveDesign by motionThemeRepository.expressiveDesign.collectAsState(initial = true)
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val navigationState = rememberMotionNavigationState(
        backStack = backStack.backStack,
        topLevelDestinations = bottomBarDestinations,
        motionLevel = motionLevel,
        axis = if (isLandscape) {
            MotionNavigationAxis.Vertical
        } else {
            MotionNavigationAxis.Horizontal
        },
        showsBottomBar = { it.showNavigation }
    )

    val selectedNavbarDest = activeNavigationPage ?: currentScreen
    val overflowPagesList = remember(navigationPages, visibleNavbarDestinations) {
        navigationPages.filter { it !in visibleNavbarDestinations }
    }
    val moreFab = remember(overflowPagesList, selectedNavbarDest) {
        MotionFabConfig(
            visible = true,
            content = {
                OverflowMenuFab(
                    overflowPages = overflowPagesList,
                    selectedDest = selectedNavbarDest,
                    onNavigate = { dest -> backStack.navigateTo(dest) }
                )
            }
        )
    }
    val navigationBackdrop = rememberMotionBlurState()
    val navigationBlurred = rememberMotionBlur()
    var bottomBarHeight by remember { mutableStateOf(0.dp) }
    var overflowFabSlot by remember { mutableStateOf<Rect?>(null) }
    var navigationOrigin by remember { mutableStateOf(Offset.Zero) }
    val showNavigationRail = isLandscape && currentScreen.showNavigation

    val lockerViewModel: LockerViewModel = viewModel(
        factory = LockerViewModelFactory(repository)
    )
    val messagesViewModel: MessagesViewModel = viewModel()
    LaunchedEffect(platforms) { messagesViewModel.configure(platforms) }

    val appState = remember(preloadedCourses, repository, platforms, lockerViewModel, navigationPages, navbarPages, visibleNavbarDestinations, overflowPagesList, homeworkOpenRequest) {
        AppState(
            preloadedCourses = preloadedCourses,
            repository = repository,
            platforms = platforms,
            onPlatformsChanged = onPlatformsChanged,
            onLogout = onLogout,
            onLoginSuccess = onLoginSuccess,
            lockerViewModel = lockerViewModel,
            navigationPages = navigationPages,
            overflowPages = overflowPagesList,
            messagesViewModel = messagesViewModel,
            homeworkOpenRequest = homeworkOpenRequest,
        )
    }

    Box(modifier = Modifier.fillMaxSize().onGloballyPositioned {
        navigationOrigin = it.positionInWindow()
    }) {
        MotionBlurBackdrop(state = navigationBackdrop, modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxSize()) {
                if (showNavigationRail) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        header = { moreFab.content?.invoke() }
                    ) {
                        Spacer(Modifier.weight(1f))
                        visibleNavbarDestinations.forEach { destination ->
                            val selected = selectedNavbarDest == destination
                            NavigationRailItem(
                                selected = selected,
                                onClick = { backStack.navigateTop(destination) },
                                icon = {
                                    Icon(
                                        painter = painterResource(
                                            if (selected) destination.filledIcon else destination.outlineIcon
                                        ),
                                        contentDescription = destination.label
                                    )
                                },
                                label = { Text(destination.label) },
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                    indicatorColor = MaterialTheme.colorScheme.primary,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                        Spacer(Modifier.weight(1f))
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    CompositionLocalProvider(LocalAppState provides appState) {
                        MotionNavHost(
                            backStack = backStack,
                            destinations = destinations,
                            navigationState = navigationState,
                            modifier = Modifier.fillMaxSize(),
                            onNavigate = { dest -> backStack.navigateTo(dest) },
                            onRootBack = {}
                        )
                    }
                }
            }
        }

        if (!isLandscape && expressiveDesign) {
            CompositionLocalProvider(LocalMotionBlurState provides navigationBackdrop) {
                MotionBottomBarHost(
                    navigationState = navigationState,
                    visible = currentScreen.showNavigation,
                    bottomBarHeight = bottomBarHeight,
                    backdropState = navigationBackdrop,
                    contrastBlurEnabled = navigationBlurred,
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                    MotionNavigationBar(
                        visible = true,
                        currentDest = currentScreen,
                        selectedDest = selectedNavbarDest,
                        destinations = visibleNavbarDestinations,
                        onNavigate = { dest -> backStack.navigateTop(dest) },
                        onHeightChanged = { bottomBarHeight = it },
                        modifier = Modifier.fillMaxWidth(),
                        floatingActionButton = if (overflowPagesList.isNotEmpty()) {
                            {
                                // Reserve the same button slot as the gallery's overlay menu.
                                Box(Modifier.size(56.dp).onGloballyPositioned {
                                    overflowFabSlot = it.boundsInWindow()
                                })
                            }
                        } else null,
                        fab = MotionNavigationBarDefaults.fab(morphEnabled = false),
                        colors = MotionNavigationBarDefaults.colors().let { colors ->
                            colors.copy(container = colors.container.copy(alpha = if (navigationBlurred) 0.84f else 1f))
                        },
                        contrast = MotionNavigationBarDefaults.contrast(enabled = false)
                    )
                }
            }
            // Material's menu grows above the bar, but its toggle covers the reserved slot.
            val slot = overflowFabSlot
            if (slot != null && overflowPagesList.isNotEmpty() &&
                currentScreen.showNavigation && navigationState.bottomBarVisible
            ) {
                CompositionLocalProvider(LocalMotionBlurState provides navigationBackdrop) {
                    Layout(
                        content = {
                            OverflowFabMenu(
                                overflowPages = overflowPagesList,
                                onNavigate = { dest -> backStack.navigateTo(dest) }
                            )
                        },
                        modifier = Modifier.matchParentSize()
                    ) { measurables, constraints ->
                        val menu = measurables.single().measure(constraints.copy(minWidth = 0, minHeight = 0))
                        layout(constraints.maxWidth, constraints.maxHeight) {
                            // Material adds 16 dp around its button, as in Motion's overlay host.
                            val inset = 16.dp.toPx()
                            val x = if (layoutDirection == LayoutDirection.Rtl) {
                                slot.left - navigationOrigin.x - inset
                            } else {
                                slot.right - navigationOrigin.x + inset - menu.width
                            }
                            val y = slot.bottom - navigationOrigin.y + inset - menu.height
                            menu.place(x.roundToInt(), y.roundToInt())
                        }
                    }
                }
            }
        } else if (!isLandscape && currentScreen.showNavigation) {
            NavigationBar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
            ) {
                visibleNavbarDestinations.forEach { destination ->
                    val selected = selectedNavbarDest == destination
                    NavigationBarItem(
                        selected = selected,
                        onClick = { backStack.navigateTop(destination) },
                        icon = {
                            Icon(
                                painter = painterResource(
                                    if (selected) destination.filledIcon else destination.outlineIcon
                                ),
                                contentDescription = destination.label
                            )
                        },
                        label = { Text(destination.label) }
                    )
                }
                moreFab.content?.invoke()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun OverflowFabMenu(
    overflowPages: List<AppDest>,
    onNavigate: (MotionDest) -> Unit
) {
    if (overflowPages.isEmpty()) return
    var menuExpanded by rememberSaveable { mutableStateOf(false) }
    BackHandler(menuExpanded) { menuExpanded = false }

    // Material's own menu, like the gallery demo's native menu: the toggle is primary like
    // every other button in the bar, and the items open from the default lighter container.
    val toggleProgress by animateFloatAsState(
        targetValue = if (menuExpanded) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "OverflowToggleProgress",
    )
    val toggleSize = ToggleFloatingActionButtonDefaults.containerSize()(toggleProgress)
    val toggleCornerRadius = ToggleFloatingActionButtonDefaults.containerCornerRadius()(toggleProgress)
    val toggleShape = RoundedCornerShape(toggleCornerRadius)
    FloatingActionButtonMenu(
        expanded = menuExpanded,
        horizontalAlignment = Alignment.End,
        button = {
            // Keep the menu's button footprint fixed while its visible surface shrinks.
            Box(Modifier.size(56.dp), contentAlignment = Alignment.TopEnd) {
                FilledIconButton(
                    onClick = { menuExpanded = !menuExpanded },
                    shape = toggleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    modifier = Modifier.size(toggleSize).clip(toggleShape)
                        .motionFloatingContainer(toggleShape, MaterialTheme.colorScheme.primary)
                ) {
                    Icon(
                        painter = rememberSymbolPainter(
                            if (menuExpanded) MotionSymbols.ic_close else MotionSymbols.ic_menu
                        ),
                        contentDescription = if (menuExpanded) "Close more pages" else "More pages",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = with(ToggleFloatingActionButtonDefaults) {
                            Modifier.animateIcon(
                                checkedProgress = { toggleProgress },
                                color = iconColor(MaterialTheme.colorScheme.onPrimary, MaterialTheme.colorScheme.onPrimary)
                            )
                        }
                    )
                }
            }
        }
    ) {
        overflowPages.forEach { page ->
            FloatingActionButtonMenuItem(
                onClick = {
                    menuExpanded = false
                    onNavigate(page)
                },
                icon = {
                    if (page.filledIcon != 0) {
                        Icon(
                            painter = painterResource(page.filledIcon),
                            contentDescription = null
                        )
                    }
                },
                text = { Text(page.label) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun OverflowMenuFab(
    overflowPages: List<AppDest>,
    selectedDest: MotionDest,
    onNavigate: (MotionDest) -> Unit
) {
    MotionDropdown(
        options = overflowPages,
        selected = selectedDest as? AppDest,
        onSelected = onNavigate,
        items = MotionDropdownDefaults.items(
            label = { it.label },
            icon = { painterResource(it.filledIcon) },
            key = { it }
        ),
        sizes = MotionDropdownDefaults.sizes(
            menuMinWidth = 200.dp,
            menuMaxWidth = 280.dp,
            menuMaxHeight = 380.dp
        ),
        menu = MotionDropdownDefaults.menu(
            alignment = MotionDropdownMenuAlignment.End,
            matchAnchorWidth = false,
            darkenBackground = true
        ),
        direction = MotionDropdownDirection.Auto,
        contentDescription = "More pages",
        anchorContent = { _, toggle ->
            MotionFloatingToolbarDefaults.StandardFloatingActionButton(
                onClick = toggle,
                modifier = Modifier.size(56.dp),
                // Same pair as the navigation bar itself: the button reads as part of the
                // pill rather than a second accent. Both blur identically behind.
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_menu),
                    contentDescription = "More pages"
                )
            }
        }
    )
}
