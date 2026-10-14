package com.feldman.scholix

import com.feldman.scholix.ui.HiddenMoeLogin
import com.feldman.scholix.ui.HiddenMashovSmsLogin
import com.feldman.scholix.api.providerName
import com.feldman.scholix.api.platforms.StudentsPortalPlatform
import com.feldman.scholix.api.platforms.OpenAUPlatform
import com.feldman.scholix.api.platforms.MashovPlatform
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.sp
import com.feldman.motion.MotionButtonDefaults
import com.feldman.motion.MotionButton
import com.feldman.motion.MotionSnackbar
import com.feldman.motion.MotionSnackbarHost
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.animateFloatingActionButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarDuration
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
import androidx.compose.ui.layout.onSizeChanged
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
import com.feldman.motion.MotionBottomBarHost
import com.feldman.motion.MotionFabConfig
import com.feldman.motion.MotionDest
import com.feldman.motion.MotionLevel
import com.feldman.motion.MotionNavigationBar
import com.feldman.motion.MotionNavigationBarDefaults
import com.feldman.motion.MotionNavHost
import com.feldman.motion.MotionNavFlow
import com.feldman.motion.MotionNavFlowAutomaticNavigation
import com.feldman.motion.MotionThemeRepository
import com.feldman.motion.MotionTheme
import com.feldman.motion.rememberMotionDestBackStack
import com.feldman.motion.rememberMotionNavigationState
import com.feldman.motion.rememberMotionBlurState
import com.feldman.motion.MotionBlurBackdrop
import com.feldman.motion.LocalMotionBlurState
import com.feldman.motion.LocalMotionBottomBarGeometry
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
import com.feldman.scholix.storage.defaultNavigationOrder
import com.feldman.scholix.storage.navigationOrderFlow
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
    // Bumped by the refresh-failure snackbar's action; MainScreen opens the providers page.
    private var providersOpenRequest by mutableIntStateOf(0)

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
            MotionTheme {
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

                // The failures last reported, so a resume that fails the same way stays quiet.
                var reportedFailures by remember { mutableStateOf<Set<String>>(emptySet()) }
                // Failures waiting on the off-screen portal sign-in before they are reported.
                var pendingFailures by remember { mutableStateOf<List<String>>(emptyList()) }
                var portalRelogin by remember { mutableStateOf<StudentsPortalPlatform?>(null) }
                var mashovRelogin by remember { mutableStateOf<MashovPlatform?>(null) }
                var preloadRefreshRunning by remember { mutableStateOf(false) }

                fun reportFailures(failedPlatforms: List<String>) {
                    val failures = failedPlatforms.toSet()
                    if (failures.isNotEmpty() && failures != reportedFailures) {
                        scope.launch {
                            // These are expired sign-ins: retrying fails the same way,
                            // signing in again is what fixes them.
                            val result = snackbarHostState.showSnackbar(
                                message = getString(R.string.providers_refresh_failed, failedPlatforms.joinToString(", ")),
                                actionLabel = getString(R.string.providers_sign_in_again),
                                duration = SnackbarDuration.Long
                            )
                            if (result == SnackbarResult.ActionPerformed) providersOpenRequest++
                        }
                    }
                    reportedFailures = failures
                }

                fun reloadPreloads(snackbarHostState: SnackbarHostState) {
                    if (preloadRefreshRunning || portalRelogin != null || mashovRelogin != null) return
                    preloadRefreshRunning = true
                    Log.d("MainActivity", "Reloading platforms")
                    scope.launch(Dispatchers.IO) {
                        try {
                            var failedPlatforms = PlatformStorage.refreshCookies(this@MainActivity)
                            val courses = PlatformStorage.getCourses(this@MainActivity)
                            val refreshedPlatforms = PlatformStorage.loadPlatforms(this@MainActivity)
                            // The subsequent course request may already have recovered OpenU.
                            refreshedPlatforms.filterIsInstance<OpenAUPlatform>().filter { it.isLoggedIn() }
                                .forEach { failedPlatforms = failedPlatforms - it.providerName() }
                            // Mashov SMS accounts first try their saved private-device token inside
                            // refreshCookies(). If Mashov has revoked it, recover with a fresh OTP
                            // while an SMS listener is already armed, rather than leaving the app in
                            // a permanent signed-out state.
                            val mashovSmsRecovery = refreshedPlatforms
                                .filterIsInstance<MashovPlatform>()
                                .firstOrNull {
                                    !it.isLoggedIn() && it.loginMethod == "sms" && it.hasSmsLoginDetails()
                                }
                            // The portal's sign-in page blocks headless requests, so its lapsed
                            // session is restored by signing in again off-screen with the saved
                            // credentials -- the same recovery the grades page runs.
                            val relogin = if (portalRelogin == null) {
                                refreshedPlatforms
                                    .filterIsInstance<StudentsPortalPlatform>()
                                    .firstOrNull { it.needsInteractiveRelogin() }
                            } else null

                            withContext(Dispatchers.Main) {
                                preloadedCourses = courses
                                isLoading = false
                                if (mashovSmsRecovery != null) {
                                    pendingFailures = failedPlatforms
                                    mashovRelogin = mashovSmsRecovery.forSmsLogin()
                                } else if (relogin != null) {
                                    pendingFailures = failedPlatforms
                                    portalRelogin = relogin
                                } else {
                                    reportFailures(failedPlatforms)
                                }
                            }
                        } finally {
                            withContext(Dispatchers.Main) { preloadRefreshRunning = false }
                        }
                    }
                }

                mashovRelogin?.let { mashov ->
                    HiddenMashovSmsLogin(
                        account = mashov,
                        onSmsRequested = { account ->
                            withContext(Dispatchers.IO) {
                                PlatformStorage.addPlatforms(context, listOf(account))
                            }
                        },
                        onResult = { account, error ->
                            scope.launch {
                                val restored = account != null
                                if (account != null) withContext(Dispatchers.IO) {
                                    PlatformStorage.addPlatforms(context, listOf(account))
                                }
                                Log.i(
                                    "MainActivity",
                                    "Mashov SMS recovery " + if (restored) "restored the session"
                                    else "failed: ${error ?: "OTP was not verified"}",
                                )
                                val remaining = if (restored) pendingFailures - mashov.providerName()
                                else pendingFailures
                                mashovRelogin = null
                                pendingFailures = emptyList()
                                if (restored) {
                                    preloadedCourses = withContext(Dispatchers.IO) {
                                        PlatformStorage.getCourses(context)
                                    }
                                    // A different provider (for example the Education Portal) may
                                    // still need its own recovery after Mashov finishes.
                                    reloadPreloads(snackbarHostState)
                                } else {
                                    reportFailures(remaining)
                                }
                            }
                        },
                    )
                }

                portalRelogin?.let { portal ->
                    HiddenMoeLogin(
                        username = portal.getUsername(),
                        password = portal.getPassword()
                    ) { cookies, csrt, error ->
                        scope.launch {
                            val restored = cookies != null && withContext(Dispatchers.IO) {
                                portal.adoptSession(cookies, csrt).also { ok ->
                                    if (!ok) return@also
                                    val platforms = PlatformStorage.loadPlatforms(context).toMutableList()
                                    val index = platforms.indexOfFirst { it.id == portal.id }
                                    if (index >= 0) platforms[index] = portal else platforms += portal
                                    PlatformStorage.savePlatforms(context, platforms)
                                }
                            }
                            Log.i("MainActivity", "Portal re-login " + if (restored) "restored the session" else "failed: ${error ?: "session not adopted"}")
                            val remaining = if (restored) pendingFailures - portal.providerName() else pendingFailures
                            portalRelogin = null
                            pendingFailures = emptyList()
                            if (restored) {
                                preloadedCourses = withContext(Dispatchers.IO) { PlatformStorage.getCourses(context) }
                            }
                            reportFailures(remaining)
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

                val snackbarHost: @Composable () -> Unit = {
                    MotionSnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier
                            .padding(bottom = BottomBarSpacing())
                    ) { data ->
                        // The action is the point of the message, so it is a filled button
                        // rather than Material's text action.
                        MotionSnackbar(
                            modifier = Modifier.padding(horizontal = 12.dp),
                            action = data.visuals.actionLabel?.let { label ->
                                {
                                    MotionButton(
                                        onClick = { data.performAction() },
                                        text = label,
                                        sizes = MotionButtonDefaults.sizes(height = 40.dp, fontSize = 14.sp),
                                        contentPadding = PaddingValues(horizontal = 16.dp),
                                    )
                                }
                            }
                        ) { Text(data.visuals.message) }
                    }
                }
                Box(Modifier.fillMaxSize()) {
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
                                snackbarHost = snackbarHost,
                                homeworkOpenRequest = homeworkOpenRequest,
                                providersOpenRequest = providersOpenRequest,
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
                    if (isLoggedIn != true || isLoading) {
                        Box(Modifier.align(Alignment.BottomCenter)) { snackbarHost() }
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
    providersOpenRequest: Int = 0,
    snackbarHost: @Composable () -> Unit = {},
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
            AppDest.PortionsSettings,
            AppDest.SchoolPortionsTable(),
            AppDest.Login,
            AppDest.Appearance
        )
    }

    val navigationOrder by remember(context) { context.navigationOrderFlow() }
        .collectAsState(initial = defaultNavigationOrder)
    val navigationPages = remember(destinations, navigationOrder) {
        destinations.filter { it.label in navigationOrder }.sortedBy { navigationOrder.indexOf(it.label) }
    }

    val startDestination = rememberSaveable {
        val activity = context as? android.app.Activity
        if (activity?.intent?.getBooleanExtra("open_crash_logs", false) == true) AppDest.CrashLogs
        else if (activity?.intent?.getBooleanExtra("open_messages", false) == true) AppDest.Messages
        else if (activity?.intent?.getBooleanExtra("open_homework", false) == true) AppDest.Homework
        else if (hasGrades) AppDest.Grades else AppDest.Tiktek
    }

    val backStack = rememberMotionDestBackStack(startDestination)
    var handledHomeworkOpenRequest by rememberSaveable { mutableIntStateOf(0) }
    var handledProvidersOpenRequest by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(providersOpenRequest) {
        if (providersOpenRequest > handledProvidersOpenRequest) {
            backStack.navigateTo(AppDest.Platforms)
            handledProvidersOpenRequest = providersOpenRequest
        }
    }
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
    val motionThemeRepository = remember(context) { MotionThemeRepository(context) }
    val motionLevel by motionThemeRepository.motionLevel
        .collectAsState(initial = MotionLevel.MEDIUM)

    val selectedNavbarDest = activeNavigationPage ?: currentScreen
    var overflowPagesList by remember { mutableStateOf<List<AppDest>>(emptyList()) }
    val navigationBlurred = rememberMotionBlur()

    val lockerViewModel: LockerViewModel = viewModel(
        factory = LockerViewModelFactory(repository)
    )
    val messagesViewModel: MessagesViewModel = viewModel()
    LaunchedEffect(platforms) { messagesViewModel.configure(platforms) }

    val appState = remember(preloadedCourses, repository, platforms, lockerViewModel, navigationPages, overflowPagesList, homeworkOpenRequest) {
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

    CompositionLocalProvider(LocalAppState provides appState) {
        // Motion owns the shell: bar, rail, the bar's slide and predictive back, and the overflow
        // menu on the bar's button slot. The app keeps the back stack for its deep links.
        MotionNavFlow(
            startDestination = startDestination,
            destinations = destinations,
            modifier = Modifier.fillMaxSize(),
            backStack = backStack,
            motionLevel = motionLevel,
            navigationDestinations = navigationPages,
            theme = null,
            navigation = { content ->
                MotionNavFlowAutomaticNavigation(
                    content = content,
                    snackbarHost = snackbarHost,
                    navigationDestinations = navigationPages,
                    selectedDestination = selectedNavbarDest,
                    pinnedEndDestination = AppDest.Settings,
                    replacePinnedEndWhenFull = true,
                    onDestinationSelected = { dest -> backStack.navigateTop(dest) },
                    contrastBlurEnabled = navigationBlurred,
                    onOverflowChanged = { overflowPagesList = it.filterIsInstance<AppDest>() },
                )
            }
        )
    }
}
