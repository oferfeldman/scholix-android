package com.feldman.scholix

import androidx.compose.ui.res.stringResource

import android.Manifest
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.platform.LocalView
import com.feldman.motion.MotionBlurDefaults
import com.feldman.motion.motionBlurBehind
import com.feldman.motion.rememberMotionBlur
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
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
import androidx.compose.ui.draw.alpha
import com.feldman.motion.MotionFloatingToolbarDefaults
import com.feldman.motion.MotionDropdown
import com.feldman.motion.MotionDropdownDefaults
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
import com.feldman.scholix.api.Platform
import com.feldman.scholix.api.PlatformStorage
import com.feldman.scholix.pages.LockerDatabase
import com.feldman.scholix.pages.LockerRepository
import com.feldman.scholix.pages.LockerViewModel
import com.feldman.scholix.pages.LockerViewModelFactory
import com.feldman.scholix.pages.LoginPage
import com.feldman.scholix.pages.GradesLoadingIndicator
import com.feldman.scholix.pages.MessagesViewModel
import com.feldman.scholix.pages.isRtlText
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
import kotlin.math.max

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
                        val valid = newPlatforms.any { it.isLoggedIn() }
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
                        val valid = newPlatforms.any { it.isLoggedIn() }

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
        destinations.filter { it in listOf(AppDest.Grades, AppDest.Schedule, AppDest.Attendance, AppDest.Tiktek, AppDest.Settings, AppDest.Messages, AppDest.Locker) }
    }
    val bottomBarDestinations = navigationPages.filter { it.label in navbarPages }.ifEmpty { listOf(AppDest.Tiktek, AppDest.Settings) }

    val startDestination = remember {
        val activity = context as? android.app.Activity
        if (activity?.intent?.getBooleanExtra("open_crash_logs", false) == true) AppDest.CrashLogs
        else if (activity?.intent?.getBooleanExtra("open_messages", false) == true) AppDest.Messages
        else if (hasGrades) AppDest.Grades else AppDest.Tiktek
    }

    val backStack = rememberMotionDestBackStack(startDestination)
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
    val moreFab = remember(overflowPagesList, selectedNavbarDest, motionLevel) {
        MotionFabConfig(
            visible = true,
            content = {
                OverflowMenuFab(
                    overflowPages = overflowPagesList,
                    selectedDest = selectedNavbarDest,
                    motionLevel = motionLevel,
                    onNavigate = { dest -> backStack.navigateTo(dest) }
                )
            }
        )
    }
    var bottomBarHeight by remember { mutableStateOf(0.dp) }
    val showNavigationRail = isLandscape && currentScreen.showNavigation

    val lockerViewModel: LockerViewModel = viewModel(
        factory = LockerViewModelFactory(repository)
    )
    val messagesViewModel: MessagesViewModel = viewModel()
    LaunchedEffect(platforms) { messagesViewModel.configure(platforms) }

    val appState = remember(preloadedCourses, repository, platforms, lockerViewModel, navigationPages, navbarPages, visibleNavbarDestinations, overflowPagesList) {
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
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
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

        if (!isLandscape && expressiveDesign) {
            MotionBottomBarHost(
                navigationState = navigationState,
                visible = currentScreen.showNavigation,
                bottomBarHeight = bottomBarHeight,
                fullyDarkened = true,
                darkeningHeight = 240.dp,
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                MotionNavigationBar(
                    visible = true,
                    currentDest = currentScreen,
                    selectedDest = selectedNavbarDest,
                    destinations = visibleNavbarDestinations,
                    onNavigate = { dest -> backStack.navigateTop(dest) },
                    floatingActionButton = moreFab.content,
                    onHeightChanged = { bottomBarHeight = it },
                    modifier = Modifier.fillMaxWidth(),
                    contrast = MotionNavigationBarDefaults.contrast(enabled = false, fullyDarkened = true, height = 180.dp)
                )
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
private fun OverflowMenuFab(
    overflowPages: List<AppDest>,
    selectedDest: MotionDest,
    motionLevel: MotionLevel,
    onNavigate: (MotionDest) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val expandedStates = remember { MutableTransitionState(false) }
    expandedStates.targetState = expanded

    val density = LocalDensity.current
    var opensAbove by remember { mutableStateOf(true) }

    Box {
        MotionFloatingToolbarDefaults.StandardFloatingActionButton(
            onClick = { expanded = !expanded }
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_menu),
                contentDescription = "More pages"
            )
        }

        if (expandedStates.currentState || expandedStates.targetState) {
            val positionProvider = remember(density) {
                object : PopupPositionProvider {
                    override fun calculatePosition(
                        anchorBounds: IntRect,
                        windowSize: IntSize,
                        layoutDirection: LayoutDirection,
                        popupContentSize: IntSize
                    ): IntOffset {
                        val offsetYPx = with(density) { 8.dp.roundToPx() }
                        val below = anchorBounds.bottom + offsetYPx
                        val above = anchorBounds.top - popupContentSize.height - offsetYPx
                        val fitsBelow = below + popupContentSize.height <= windowSize.height
                        val fitsAbove = above >= 0
                        val openAbove = !fitsBelow && fitsAbove
                        opensAbove = openAbove

                        val y = when {
                            fitsBelow -> below
                            fitsAbove -> above
                            else -> max(0, windowSize.height - popupContentSize.height)
                        }

                        val rtl = layoutDirection == LayoutDirection.Rtl
                        val rawX = if (rtl) {
                            anchorBounds.left
                        } else {
                            anchorBounds.right - popupContentSize.width
                        }

                        val x = rawX.coerceIn(0, max(0, windowSize.width - popupContentSize.width))
                        return IntOffset(x, y)
                    }
                }
            }

            Popup(
                popupPositionProvider = positionProvider,
                onDismissRequest = { expanded = false },
                properties = PopupProperties(focusable = true)
            ) {
                val transition = rememberTransition(expandedStates, label = "OverflowDropdownMenu")
                val scale by transition.animateFloat(
                    transitionSpec = {
                        when (motionLevel) {
                            MotionLevel.NONE -> snap()
                            MotionLevel.LOW -> tween(if (targetState) 150 else 100, easing = FastOutSlowInEasing)
                            MotionLevel.MEDIUM -> spring(dampingRatio = 0.8f, stiffness = 600f)
                            MotionLevel.HIGH -> spring(dampingRatio = 0.65f, stiffness = 450f)
                        }
                    },
                    label = "scale"
                ) { open -> if (open) 1f else 0.9f }
                val alpha by transition.animateFloat(
                    transitionSpec = {
                        when (motionLevel) {
                            MotionLevel.NONE -> snap()
                            MotionLevel.LOW -> tween(if (targetState) 150 else 100, easing = FastOutSlowInEasing)
                            MotionLevel.MEDIUM -> spring(dampingRatio = 0.8f, stiffness = 600f)
                            MotionLevel.HIGH -> spring(dampingRatio = 0.65f, stiffness = 450f)
                        }
                    },
                    label = "alpha"
                ) { open -> if (open) 1f else 0f }

                val view = LocalView.current
                val targetDim = (0.32f * alpha).coerceIn(0f, 1f)
                DisposableEffect(view, targetDim) {
                    try {
                        var targetView: View? = view
                        while (targetView != null && targetView.layoutParams !is WindowManager.LayoutParams) {
                            targetView = targetView.parent as? View
                        }
                        val params = targetView?.layoutParams as? WindowManager.LayoutParams
                        if (params != null && targetView != null) {
                            params.flags = params.flags or WindowManager.LayoutParams.FLAG_DIM_BEHIND
                            params.dimAmount = targetDim
                            val wm = view.context.getSystemService(android.content.Context.WINDOW_SERVICE) as? WindowManager
                            wm?.updateViewLayout(targetView, params)
                        }
                    } catch (_: Throwable) {}
                    onDispose {}
                }

                val ambientLayoutDirection = LocalLayoutDirection.current
                val originX = if (ambientLayoutDirection == LayoutDirection.Rtl) 0f else 1f
                val menuTransformOrigin = TransformOrigin(originX, if (opensAbove) 1f else 0f)
                val menuShape = RoundedCornerShape(20.dp)
                val menuBlurred = rememberMotionBlur(true)

                Box(
                    modifier = Modifier.graphicsLayer {
                        if (motionLevel != MotionLevel.NONE) {
                            scaleX = scale
                            scaleY = scale
                            transformOrigin = menuTransformOrigin
                            this.alpha = alpha
                        }
                    }
                ) {
                    Surface(
                        modifier = Modifier
                            .widthIn(min = 200.dp, max = 280.dp)
                            .motionBlurBehind(
                                shape = menuShape,
                                enabled = menuBlurred
                            ),
                        shape = menuShape,
                        color = MotionBlurDefaults.containerColor(
                            MaterialTheme.colorScheme.surfaceContainerLow,
                            menuBlurred
                        ),
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        shadowElevation = 6.dp,
                        tonalElevation = 0.dp
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 380.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(6.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            overflowPages.forEach { destination ->
                                val isSelected = selectedDest == destination
                                OverflowDropdownMenuItem(
                                    title = destination.label,
                                    icon = painterResource(destination.filledIcon),
                                    isSelected = isSelected,
                                    onClick = {
                                        expanded = false
                                        onNavigate(destination)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OverflowDropdownMenuItem(
    title: String,
    icon: Painter,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val isRtl = isRtlText(title)
    val itemBg = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    val itemContentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    val iconTint = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant

    CompositionLocalProvider(
        LocalLayoutDirection provides if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(itemBg)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(),
                    onClick = onClick
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = itemContentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
