package com.feldman.scholix

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.room.Room
import com.feldman.scholix.pages.LockerDatabase
import com.feldman.scholix.pages.LockerRepository
import com.feldman.lockerapp.ui.theme.AppTheme
import com.feldman.scholix.api.Platform
import com.feldman.scholix.api.PlatformStorage
import com.feldman.scholix.pages.LoginPage
import com.feldman.scholix.services.GradeMonitorWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.lang.Thread.sleep
import java.io.IOException



val TOP_BAR_SPACING = 50.dp
val BOTTOM_BAR_SPACING = 100.dp

fun isSmartSchoolReachable(): Boolean {
    return try {
        val process = Runtime.getRuntime().exec("ping -c 1 smartschool.co.il")
        val exitCode = process.waitFor()
        exitCode == 0 // success if 0
    } catch (e: IOException) {
        false
    } catch (e: InterruptedException) {
        false
    }
}

class MainActivity : ComponentActivity() {
    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                Log.i("MainActivity", "✅ Notification permission granted — scheduling worker")
                GradeMonitorWorker.schedule(this)
            } else {
                Log.w("MainActivity", "❌ Notification permission denied by user")
            }
        }



    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                            sleep(120.toLong())
                            isLoading = false

                        }
                    }
                }

                fun reloadPreloads(snackbarHostState: SnackbarHostState) {
                    Log.d("MainActivity", "Reloading platforms")
                    scope.launch(Dispatchers.IO) {
                        val reachable = isSmartSchoolReachable()
                        if (!reachable) {
                            withContext(Dispatchers.Main) {
                                scope.launch {
                                    val result = snackbarHostState.showSnackbar(
                                        message = "Cannot connect to Webtop. Check your internet connection.",
                                        actionLabel = "Retry"
                                    )
                                    if (result == SnackbarResult.ActionPerformed) {
                                        reloadPreloads(snackbarHostState)
                                    }
                                }
                            }
                            return@launch
                        }

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

                when {
                    isLoading -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularWavyProgressIndicator()
                        }
                    }

                    isLoggedIn==false -> {
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
                            }
                        )
                    }
                }
            }
        }

    }

    override fun onResume() {
        super.onResume()
        Log.i("MainActivity", "onResume() — ensuring notification permission checked")
        checkNotificationPermissionAndStartService()
    }

    private fun checkNotificationPermissionAndStartService() {
        val permission = Manifest.permission.POST_NOTIFICATIONS

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ActivityCompat.checkSelfPermission(this, permission) ==
                    PackageManager.PERMISSION_GRANTED
            when {
                granted -> {
                    Log.i("MainActivity", "Permission already granted — scheduling worker")
                    GradeMonitorWorker.schedule(this)
                }
                shouldShowRequestPermissionRationale(permission) -> {
                    Log.w("MainActivity", "User previously denied permission — showing rationale and re-requesting")
                    requestNotificationPermissionLauncher.launch(permission)
                }
                else -> {
                    Log.i("MainActivity", "Requesting POST_NOTIFICATIONS permission for first time")
                    requestNotificationPermissionLauncher.launch(permission)
                }
            }
        } else {
            // On Android 12 and below, permission not required
            Log.i("MainActivity", "Pre-Android 13 device — no permission required, scheduling worker directly")
            GradeMonitorWorker.schedule(this)
        }
    }


}
private fun routeToDest(route: String?): Dest? =
    route?.substringBefore('/')?.let { runCatching { Dest.valueOf(it) }.getOrNull() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    preloadedCourses: List<JSONObject>,
    repository: LockerRepository,
    onPlatformsChanged: () -> Unit,
    platforms: List<Platform>,
    onLoginSuccess: () -> Unit,
) {
    // Local login state (independent of MainActivity)
    var isLoggedIn by remember { mutableStateOf<Boolean?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Feature availability
    var hasGrades by remember { mutableStateOf(false) }
    var hasSchedule by remember { mutableStateOf(false) }
    var hasAttendance by remember { mutableStateOf(false) }

    // Determine feature support
    LaunchedEffect(platforms) {
        withContext(Dispatchers.IO) {
            val g = PlatformStorage.hasGradesSupport(context)
            val s = PlatformStorage.hasScheduleSupport(context)
            val a = PlatformStorage.hasAttendanceSupport(context)
            withContext(Dispatchers.Main) {
                hasGrades = g
                hasSchedule = s
                hasAttendance = a
            }
        }
    }

    // 🔄 Check login state once when screen loads
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val valid = PlatformStorage.loadPlatforms(context).any { it.isLoggedIn() }
            withContext(Dispatchers.Main) {
                isLoggedIn = valid
            }
        }
    }

    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val currentDest = Dest.entries.firstOrNull { dest ->
        currentRoute?.startsWith(dest.name) == true
    }

    val currentTitle = currentDest?.label ?: "Scholix"
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    val visibleDests = Dest.entries.filter { dest ->
        dest.visible && dest.parent==null && when (dest) {
            Dest.Grades -> hasGrades
            Dest.Schedule -> hasSchedule
            Dest.Attendance -> hasAttendance
            Dest.Messages -> false
            else -> true
        }
    }
    val visibleSideDests = Dest.entries.filter { dest ->
        dest.visible && dest.parent==null  && when (dest) {
            Dest.Grades -> hasGrades
            Dest.Schedule -> hasSchedule
            Dest.Attendance -> hasAttendance
            else -> true
        }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
//            topBar = {
//                if (isLoggedIn == true) {
//
//                    FloatingTopAppBar(
//                        title = {
//                            Text(
//                                text = currentDest?.label ?: "",
//                                style = MaterialTheme.typography.titleLarge.copy(
//                                    fontWeight = FontWeight.Bold
//                                ),
//                                color = MaterialTheme.colorScheme.onBackground
//                            )
//                        },
//                        navigationIcon = {
//
//                            // If destination has a parent → show back button
//                            if (currentDest?.parent != null) {
//                                IconButton(onClick = { navController.navigateUp() }) {
//                                    Icon(
//                                        painter = painterResource(id = R.drawable.ic_back),
//                                        contentDescription = "Back",
//                                        tint = MaterialTheme.colorScheme.onBackground
//                                    )
//                                }
//                            }
//                            // Otherwise → show menu button (root destinations)
//                            else {
//                                IconButton(onClick = { scope.launch { drawerState.open() } }) {
//                                    Icon(
//                                        painter = painterResource(id = R.drawable.ic_menu),
//                                        contentDescription = "Menu",
//                                        tint = MaterialTheme.colorScheme.onBackground
//                                    )
//                                }
//                            }
//                        },
//                        actions = {
//                            // You have no actionIcon / onAction in Dest
//                            // so actions remain empty for now.
//                        }
//                    )
//                }
//            },
            bottomBar = {
                if (isLoggedIn == true) {

                    // בוחר אילו טאבים להציג
                    val rootTabs = visibleDests

                    // בדיקה מי דסטיניישן פעיל
                    val currentDest = remember(currentRoute) { routeToDest(currentRoute) }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.navigationBars)   // <-- real bottom inset
                            .padding(bottom = 12.dp)                            // your custom spacing
                            .padding(horizontal = 8.dp)
                    ) {

                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            tonalElevation = NavigationBarDefaults.Elevation,
                            modifier = Modifier.height(76.dp)
                        ) {

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically
                            ) {

                                rootTabs.forEach { dest ->

                                    val selected =
                                        currentDest == dest || currentDest?.parent == dest

                                    NavigationBarItem(
                                        selected = selected,
                                        onClick = {
                                            if (!selected || currentDest?.parent == dest) {
                                                navController.navigate(dest.name) {
                                                    launchSingleTop = true
                                                    restoreState = true
                                                }
                                            }
                                        },
                                        icon = {

                                            Icon(
                                                painter = painterResource(
                                                    if (selected) dest.filledIcon else dest.outlineIcon
                                                ),
                                                contentDescription = dest.label,
                                                tint = if (selected)
                                                    MaterialTheme.colorScheme.primary
                                                else
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        },
                                        label = { Text(dest.label) }
                                    )
                                }
                            }
                        }
                    }
                }

            }
        ) { innerPadding ->
            NavHost(
                navController = navController,
                modifier = Modifier.fillMaxSize(), //.padding(innerPadding)
                preloadedCourses = preloadedCourses,
                repository = repository,
                platforms = platforms,
                onPlatformsChanged = onPlatformsChanged,
                onLogout = { isLoggedIn = false }, // 👈 internal state changes
                onLoginSuccess = { onLoginSuccess(); isLoggedIn = true } // 👈 update internal state
            )
        }

        // Drawer overlay
        AnimatedVisibility(visible = drawerState.isOpen, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f))
                    .noRippleClickable { scope.launch { drawerState.close() } }
            )
        }

        val offsetX by animateDpAsState(
            targetValue = if (drawerState.isOpen) 0.dp else (-260).dp,
            label = "drawerOffset"
        )

        Surface(
            modifier = Modifier
                .width(260.dp)
                .fillMaxHeight()
                .offset(x = offsetX)
                .align(Alignment.CenterStart),
            tonalElevation = 4.dp,
            shadowElevation = 8.dp,
            color = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            Column(modifier = Modifier.padding(top = 60.dp)) {
                Spacer(Modifier.height(8.dp))
                visibleSideDests.forEach { dest ->
                    val selected = currentDest == dest
                    NavigationDrawerItem(
                        icon = {
                            Icon(
                                painterResource(
                                    id = if (selected) dest.filledIcon else dest.outlineIcon
                                ),
                                contentDescription = dest.label
                            )
                        },
                        label = {
                            Text(
                                text = dest.label,
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        },
                        selected = selected,
                        onClick = {
                            if (!selected) {
                                navController.navigate(dest.name.lowercase()) {
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }
        }
    }
}



@Composable
fun Modifier.noRippleClickable(onClick: () -> Unit): Modifier =
    pointerInput(Unit) {
        detectTapGestures(onTap = { onClick() })
    }