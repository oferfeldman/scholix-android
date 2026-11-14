package com.feldman.scholix

import com.feldman.scholix.ui.HiddenMoeLogin
import com.feldman.scholix.ui.HiddenMashovSmsLogin
import com.feldman.scholix.api.providerName
import com.feldman.scholix.api.platforms.StudentsPortalPlatform
import com.feldman.scholix.courses.*
import com.feldman.scholix.lemida.LemidaRepository
import com.feldman.scholix.lemida.LemidaSnapshot
import com.feldman.scholix.lemida.HomeworkLinks
import com.feldman.scholix.api.platforms.GoogleClassroomPlatform
import com.feldman.motion.MotionBackNavigationBehavior
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
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
import com.feldman.scholix.ui.components.providerLabel
import org.json.JSONObject

val LocalProviderHeaderVisible = compositionLocalOf { false }

@Composable
fun TopBarSpacing(): Dp {
    if (LocalProviderHeaderVisible.current) return 0.dp
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
        com.feldman.scholix.lemida.HomeworkLinks.request(intent)
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
        if (savedInstanceState == null) com.feldman.scholix.lemida.HomeworkLinks.request(intent)
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
            // Web links stay in Scholix's own browser, which shares its university sign-ins.
            com.feldman.scholix.browser.ScholixWebLinkScope {
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
                        requestSmsPermission = false,
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
    val lemida = remember(context) { LemidaRepository(context) }
    val lemidaSnapshot by lemida.snapshots.collectAsState(initial = remember(lemida) { LemidaSnapshot(courses = lemida.cachedCourses(), enabled = lemida.enabled()) })
    val hasCourseProviders = platforms.any { it is OpenAUPlatform || it is GoogleClassroomPlatform } ||
        lemidaSnapshot.enabled || lemidaSnapshot.courses.isNotEmpty()
    var courseCacheEpoch by remember { mutableIntStateOf(0) }
    val courseCatalog = remember(preloadedCourses, platforms, lemidaSnapshot.courses, lemidaSnapshot.account, courseCacheEpoch) {
        platforms.filter { it is OpenAUPlatform || it is GoogleClassroomPlatform }.flatMap { provider ->
            val saved = if (provider is OpenAUPlatform)
                com.feldman.scholix.openu.OpenUAssignmentsRepository(context, provider).cachedCourses()
            else (provider as GoogleClassroomPlatform).coursesList.toList()
            val details = if (provider is OpenAUPlatform)
                com.feldman.scholix.openu.course.OpenUCourseRepository(context, provider) else null
            (preloadedCourses.filter { it.optString("platformId") == provider.id } + saved).map { course ->
                JSONObject(course.toString()).put("platformId", provider.id)
                    .put("courseKey", course.stableCourseKey()).apply {
                        details?.cached(course.stableCourseKey(), "overview")?.let { overview ->
                            if (overview.has("hasBooklet")) put("hasBooklet", overview.optBoolean("hasBooklet"))
                        }
                    }
            }
        }.plus(preloadedCourses.filter { course -> platforms.any { it.id == course.optString("platformId") && it !is OpenAUPlatform && it !is GoogleClassroomPlatform } })
            .filter { it.stableCourseKey().isNotBlank() }.distinctBy { it.courseDestination() } +
            if (lemidaSnapshot.account.isNotBlank()) lemidaSnapshot.courses.map { course ->
                JSONObject().put("platformId", "lemida:${lemidaSnapshot.account}")
                    .put("courseKey", course.id.toString()).put("name", course.name)
            } else emptyList()
    }
    val providerOptions = platforms.filter { com.feldman.scholix.courses.supportedProviderPages(it).isNotEmpty() }.map { it.id } + listOf("lemida")
    var selectedProviderId by rememberSaveable { mutableStateOf(platforms.firstOrNull { it.suportsGrades }?.id ?: providerOptions.firstOrNull().orEmpty()) }
    val activeProviderId = selectedProviderId.takeIf { it in providerOptions } ?: providerOptions.firstOrNull().orEmpty()
    val activeProvider = platforms.firstOrNull { it.id == activeProviderId }
    val supportedPages = com.feldman.scholix.courses.supportedProviderPages(activeProvider, activeProviderId == "lemida")
    var providerPickerExpanded by remember { mutableStateOf(false) }
    val requestedAssignment by HomeworkLinks.pending.collectAsState()

    val destinations = remember(hasGrades, hasSchedule, hasAttendance) {
        listOfNotNull(
            AppDest.Home,
            CourseSection(),
            ScopeSwitcher(),
            ProviderGrades(),
            if (hasGrades) AppDest.Grades else null,
            if (hasSchedule) AppDest.Schedule else null,
            if (hasAttendance) AppDest.Attendance else null,
            AppDest.Tiktek,
            AppDest.TiktekBook(bookId = "", bookName = "", subjectId = ""),
            AppDest.TiktekSolution(imageUrl = ""),
            AppDest.Locker,
            AppDest.Lockerim,
            AppDest.LockerContents,
            AppDest.LockerCodeEditor(),
            AppDest.Homework,
            AppDest.Materials,
            AppDest.Studies,
            AppDest.Settings,
            AppDest.Platforms,
            AppDest.AddPlatform,
            AppDest.EditProvider(providerId = ""),
            AppDest.Messages,
            AppDest.MessageDetail("", ""),
            AppDest.OpenUAssignment("", ""),
            AppDest.OpenUAttachment("", "", ""),
            AppDest.OpenUDraftHelp,
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
            AppDest.SubjectPortionsSettings(),
            AppDest.SchoolPortionsTable(),
            AppDest.Login,
            AppDest.Appearance
        ) + openUCourseDestinations()
    }

    val navigationOrder by remember(context) { context.navigationOrderFlow() }
        .collectAsState(initial = defaultNavigationOrder)
    // Lockerim is a service of its own; without an account its page has nothing to show, and its
    // lock sat in the bar next to Locker's, reading as the same page twice.
    val globalNavigationPages = remember(destinations, navigationOrder, hasCourseProviders, supportedPages) {
        destinations.filter { it.parent == null && it !is ProviderGrades && it.label in navigationOrder &&
            it != AppDest.Home && (it !in com.feldman.scholix.courses.providerDependentPages || it in supportedPages) }
            .sortedBy { navigationOrder.indexOf(it.label) }
    }

    val mainDestination = supportedPages.firstOrNull() ?: AppDest.Tiktek
    val startDestination = rememberSaveable {
        val activity = context as? android.app.Activity
        if (activity?.intent?.getBooleanExtra("open_crash_logs", false) == true) AppDest.CrashLogs
        else if (activity?.intent?.getBooleanExtra("open_messages", false) == true) AppDest.Messages
        else if (activity?.intent?.getBooleanExtra("open_homework", false) == true) {
            if (requestedAssignment != null) mainDestination else AppDest.Homework
        }
        else if (activity?.intent?.getBooleanExtra("open_schedule", false) == true && hasSchedule) AppDest.Schedule
        else mainDestination
    }

    val backStack = rememberMotionDestBackStack(startDestination)
    // Opening the app on its default Grades page is not a request for an Inbar SMS.
    var userOpenedPages by remember { mutableStateOf<Set<AppDest>>(emptySet()) }
    fun navigateFromUser(destination: MotionDest, topLevel: Boolean = false) {
        if (destination is ProviderGrades) {
            selectedProviderId = destination.providerId
            userOpenedPages = userOpenedPages + AppDest.Grades
            backStack.navigateTop(AppDest.Grades)
            return
        }
        if (destination == AppDest.Grades || destination == AppDest.Schedule) {
            userOpenedPages = userOpenedPages + (destination as AppDest)
        }
        if (destination is CourseSection) {
            val providerId = if (destination.providerId.startsWith("lemida:")) "lemida" else destination.providerId
            selectedProviderId = providerId
            val courseMainPage = com.feldman.scholix.courses.supportedProviderPages(
                platforms.firstOrNull { it.id == providerId }, providerId == "lemida").firstOrNull() ?: AppDest.Tiktek
            // Close the old course item before replacing the section; identities live in routes.
            backStack.navigateTop(courseMainPage)
            while (backStack.backStack.lastOrNull()?.courseScope() != null &&
                backStack.backStack.lastOrNull() !is CourseSection) backStack.pop()
            backStack.navigateTo(destination)
        } else if (topLevel) {
            backStack.navigateTop(destination)
            if (destination == mainDestination || destination == AppDest.Grades) {
                while (backStack.backStack.lastOrNull() != destination) {
                    if (!backStack.pop()) break
                }
            }
        } else backStack.navigateTo(destination)
    }
    var handledHomeworkOpenRequest by rememberSaveable { mutableIntStateOf(0) }
    var handledProvidersOpenRequest by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(providersOpenRequest) {
        if (providersOpenRequest > handledProvidersOpenRequest) {
            backStack.navigateTo(AppDest.Platforms)
            handledProvidersOpenRequest = providersOpenRequest
        }
    }
    LaunchedEffect(homeworkOpenRequest, requestedAssignment, lemidaSnapshot.loaded) {
        if (homeworkOpenRequest > handledHomeworkOpenRequest) {
            if (requestedAssignment != null && !lemidaSnapshot.loaded) return@LaunchedEffect
            val item = requestedAssignment?.let { HomeworkLinks.match(it, lemidaSnapshot.homework, lemidaSnapshot.account) }
            if (item != null) navigateFromUser(CourseSection("lemida:${lemidaSnapshot.account}",
                item.courseId.toString(), section = CourseSectionKind.ASSIGNMENTS))
            else backStack.navigateTop(AppDest.Homework)
            handledHomeworkOpenRequest = homeworkOpenRequest
        }
    }
    val currentScreen = backStack.backStack.lastOrNull() ?: startDestination
    LaunchedEffect(currentScreen, activeProviderId) {
        if (currentScreen == AppDest.Home ||
            (currentScreen in com.feldman.scholix.courses.providerDependentPages && currentScreen !in supportedPages)) {
            backStack.navigateTop(mainDestination)
        }
        if (currentScreen == mainDestination) courseCacheEpoch++
    }
    val courseScope = currentScreen.courseScope()
    val scopeCourse = courseScope?.let { scope -> courseCatalog.singleOrNull { scope.matches(it) } }
    val navigationPages = if (courseScope != null && scopeCourse != null) {
        courseSections(platforms.firstOrNull { it.id == courseScope.providerId }, scopeCourse)
            .map { courseScope.copy(section = it) } + listOf(mainDestination, AppDest.Settings)
    } else globalNavigationPages
    val activeNavigationPage = if (generateSequence<MotionDest>(currentScreen) { it.parent }.any { it is ProviderGrades })
        AppDest.Grades else backStack.backStack.asReversed().firstNotNullOfOrNull { screen ->
        generateSequence<MotionDest>(screen) { it.parent }.firstOrNull { it in navigationPages }
    }
    val courseStateHolder = rememberSaveableStateHolder()
    var courseLocations by rememberSaveable { mutableStateOf(arrayListOf<AppDest>()) }
    LaunchedEffect(currentScreen) {
        if (courseScope != null && scopeCourse != null && currentScreen is AppDest && currentScreen !is ScopeSwitcher) {
            courseLocations = ArrayList(listOf(currentScreen) + courseLocations.filterNot {
                it.courseScope()?.sameCourse(courseScope) == true
            }.take(7))
        }
    }
    var recentCourses by rememberSaveable { mutableStateOf(arrayListOf<CourseSection>()) }
    LaunchedEffect(courseScope) {
        if (courseScope != null && scopeCourse != null) recentCourses = ArrayList(
            listOf(courseScope) + recentCourses.filterNot { it.providerId == courseScope.providerId &&
                it.courseKey == courseScope.courseKey && it.term == courseScope.term }.take(7))
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
    LaunchedEffect(platforms, activeProviderId) { messagesViewModel.configure(platforms.filter { it.id == activeProviderId }) }

    val appState = AppState(
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
            userOpenedPages = userOpenedPages,
            activePage = currentScreen,
            selectedProviderId = activeProviderId,
            courseCatalog = courseCatalog,
            recentCourses = recentCourses,
            onCourseCacheChanged = { courseCacheEpoch++ },
            onScopeSelected = { destination -> navigateFromUser(destination,
                topLevel = destination.parent == null) },
            onCourseResumed = { course ->
                val providerId = if (course.providerId.startsWith("lemida:")) "lemida" else course.providerId
                selectedProviderId = providerId
                val courseMainPage = com.feldman.scholix.courses.supportedProviderPages(
                    platforms.firstOrNull { it.id == providerId }, providerId == "lemida").firstOrNull() ?: AppDest.Tiktek
                backStack.navigateTop(courseMainPage)
                if (backStack.backStack.lastOrNull()?.courseScope()?.sameCourse(course) != true) {
                    while (backStack.backStack.lastOrNull() != courseMainPage) { if (!backStack.pop()) break }
                    backStack.navigateTo(courseLocations.firstOrNull { it.courseScope()?.sameCourse(course) == true } ?: course)
                }
            },
        )
    val scopeLabel = if (scopeCourse != null) {
        val provider = platforms.firstOrNull { it.id == courseScope?.providerId }?.platformDisplayName ?: "Lemida"
        listOf(scopeCourse.optString("name"), provider, courseScope?.term.orEmpty())
            .filter { it.isNotBlank() }.joinToString(" \u00b7 ")
    } else null
    val scopeControl = if (courseScope != null || currentScreen == AppDest.Grades || currentScreen is ProviderGrades) ScopeControl(scopeLabel) {
        backStack.navigateTo(ScopeSwitcher(currentScreen as? AppDest ?: mainDestination))
    } else null

    CompositionLocalProvider(LocalAppState provides appState, LocalScopeControl provides scopeControl,
        LocalCourseStateHolder provides courseStateHolder) {
        val showProviderHeader = currentScreen is CourseSection || (courseScope == null && currentScreen in com.feldman.scholix.courses.providerDependentPages)
        val pickerCourses = providerOptions.flatMap { id ->
            val provider = platforms.firstOrNull { it.id == id }
            val catalogId = if (id == "lemida" && lemidaSnapshot.account.isNotBlank()) "lemida:${lemidaSnapshot.account}" else id
            listOf(JSONObject().put("platformId", catalogId).put("providerRoot", true)
                .put("platformName", provider?.providerLabel() ?: "Lemida")
                .put("name", provider?.providerLabel() ?: "Lemida").put("status", "All courses")) +
                courseCatalog.filter { it.optString("platformId") == catalogId }
        }
        val pickerIndex = pickerCourses.indexOfFirst { course ->
            if (courseScope != null) courseScope.matches(course)
            else course.optBoolean("providerRoot") &&
                (course.optString("platformId") == activeProviderId || activeProviderId == "lemida" && course.optString("platformId").startsWith("lemida:"))
        }.coerceAtLeast(0)
        Column(Modifier.fillMaxSize()) {
        if (showProviderHeader && pickerCourses.isNotEmpty()) {
            com.feldman.scholix.ui.components.CoursePickerBar(
                courses = pickerCourses, selectedIndex = pickerIndex,
                onSelected = { index ->
                    val course = pickerCourses[index]
                    val rawId = course.optString("platformId")
                    val id = if (rawId.startsWith("lemida:")) "lemida" else rawId
                    selectedProviderId = id
                    providerPickerExpanded = false
                    val provider = platforms.firstOrNull { it.id == id }
                    val pages = com.feldman.scholix.courses.supportedProviderPages(provider, id == "lemida")
                    if (course.optBoolean("providerRoot")) {
                        val target = (currentScreen as? AppDest)?.takeIf { it in pages } ?: pages.firstOrNull() ?: AppDest.Tiktek
                        navigateFromUser(target, topLevel = true)
                    } else {
                        val sections = courseSections(provider, course)
                        val preferred = courseScope?.section ?: when (currentScreen) {
                            AppDest.Grades -> com.feldman.scholix.courses.CourseSectionKind.GRADES
                            AppDest.Homework -> com.feldman.scholix.courses.CourseSectionKind.ASSIGNMENTS
                            AppDest.Materials -> com.feldman.scholix.courses.CourseSectionKind.FILES
                            else -> com.feldman.scholix.courses.CourseSectionKind.OVERVIEW
                        }
                        navigateFromUser(course.courseDestination(preferred.takeIf { it in sections } ?: sections.first()))
                    }
                },
                expanded = providerPickerExpanded, onExpandedChange = { providerPickerExpanded = it },
                providersById = platforms.associateBy { it.id }, groupAllProviders = true,
                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp))
        }
        CompositionLocalProvider(LocalProviderHeaderVisible provides showProviderHeader) {
        // Motion owns the shell: bar, rail, the bar's slide and predictive back, and the overflow
        // menu on the bar's button slot. The app keeps the back stack for its deep links.
        MotionNavFlow(
            startDestination = startDestination,
            destinations = destinations,
            modifier = Modifier.weight(1f),
            backStack = backStack,
            motionLevel = motionLevel,
            backNavigationBehavior = if (courseScope != null) MotionBackNavigationBehavior.PopLatest
                else MotionBackNavigationBehavior.PopPaneGroup,
            navigationDestinations = navigationPages,
            theme = null,
            navigation = { content ->
                MotionNavFlowAutomaticNavigation(
                    content = content,
                    snackbarHost = snackbarHost,
                    navigationDestinations = navigationPages,
                    selectedDestination = selectedNavbarDest,
                    // Outside a course the bar's few slots go to the pages, in the user's order;
                    // Course navigation returns to the original main page.
                    pinnedEndDestination = AppDest.Settings,
                    replacePinnedEndWhenFull = false,
                    onDestinationSelected = { dest -> navigateFromUser(dest, topLevel = dest.parent == null) },
                    contrastBlurEnabled = navigationBlurred,
                    onOverflowChanged = { overflowPagesList = it.filterIsInstance<AppDest>() },
                )
            }
        )
        }
        }
    }
}
