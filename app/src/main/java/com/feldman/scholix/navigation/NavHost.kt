package com.feldman.scholix.navigation

import SchedulePage
import android.util.Log
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import com.feldman.scholix.R
import com.feldman.scholix.api.Platform
import com.feldman.scholix.api.PlatformStorage
import com.feldman.scholix.pages.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.text.compareTo

enum class Dest(
    val label: String,
    val filledIcon: Int,
    val outlineIcon: Int,
    val parent: Dest? = null,
    val visible: Boolean = true
) {
    Grades("Grades", R.drawable.ic_docs, R.drawable.ic_docs_outline),
    Schedule("Schedule", R.drawable.ic_schedule, R.drawable.ic_schedule_outline),
    Attendance("Attendance", R.drawable.ic_alarm, R.drawable.ic_alarm_outline),
    Messages("Messages", R.drawable.ic_message, R.drawable.ic_message_outline),
    Locker("Locker", R.drawable.ic_lock, R.drawable.ic_lock_outline),
    Settings("Settings", R.drawable.ic_settings, R.drawable.ic_settings_outline),
    Platforms("Platforms", R.drawable.ic_account, R.drawable.ic_account_outline, parent = Settings),
    Login("Login", R.drawable.ic_login, R.drawable.ic_login, visible = false),

}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun NavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    preloadedCourses: List<JSONObject>,
    repository: LockerRepository,
    platforms: List<Platform>,
    onPlatformsChanged: () -> Unit,
    onLogout: () -> Unit,
    onLoginSuccess: () -> Unit,
    ) {
    Log.d("NavHost", "Loading")
    println(platforms)
    println(platforms)
    println(platforms)
    println(platforms)
    println(platforms)
    println(platforms)
    println(platforms)
    println(platforms)
    println(platforms)
    println(platforms)
    println(platforms)
    println(platforms)
    val context = LocalContext.current
    val viewModel: LockerViewModel = viewModel(
        factory = LockerViewModelFactory(repository)
    )

    val items = Dest.entries
    var currentRoute by remember { mutableStateOf<String?>(null) }
    var prevIndex by remember { mutableIntStateOf(0) }
    var forward by remember { mutableStateOf(true) }




    var isLoggedIn by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val platforms = PlatformStorage.loadPlatforms(context)
            val valid = platforms.any { it.isLoggedIn() }
            withContext(Dispatchers.Main) {
                isLoggedIn = valid
            }
        }
    }

    DisposableEffect(navController) {
        val listener = NavController.OnDestinationChangedListener { _, destination, _ ->
            val newRoute = destination.route ?: return@OnDestinationChangedListener

            val oldRoute = currentRoute
            if (oldRoute != null && newRoute != oldRoute) {
                val oldIndex = items.indexOfFirst {
                    oldRoute.startsWith(it.name, ignoreCase = true)
                }.takeIf { it != -1 } ?: prevIndex
                val newIndex = items.indexOfFirst {
                    newRoute.startsWith(it.name, ignoreCase = true)
                }.takeIf { it != -1 } ?: oldIndex
                forward = newIndex > oldIndex

                println("new: $newRoute $newIndex, old: $oldRoute $oldIndex forward $forward")
                prevIndex = newIndex
            }

            currentRoute = newRoute
        }

        navController.addOnDestinationChangedListener(listener)
        onDispose { navController.removeOnDestinationChangedListener(listener) }
    }


    if (isLoggedIn == null) {
        CircularWavyProgressIndicator()
        return
    }

    val startDestination = if (isLoggedIn == true) Dest.Grades.name else Dest.Login.name

    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = {
            val from = initialState.destination.route
            val to = targetState.destination.route
            if (from == Dest.Login.name || to == Dest.Login.name) {
                fadeIn() // just fade, no slide
            } else if (forward) {
                slideInHorizontally(initialOffsetX = { fullWidth -> fullWidth }) + fadeIn()
            } else {
                slideInHorizontally(initialOffsetX = { fullWidth -> -fullWidth }) + fadeIn()
            }
        },
        exitTransition = {
            val from = initialState.destination.route
            val to = targetState.destination.route
            if (from == Dest.Login.name || to == Dest.Login.name) {
                fadeOut() // just fade, no slide
            } else if (forward) {
                slideOutHorizontally(targetOffsetX = { fullWidth -> -fullWidth }) + fadeOut()
            } else {
                slideOutHorizontally(targetOffsetX = { fullWidth -> fullWidth }) + fadeOut()
            }
        },
        popEnterTransition = {
            val from = initialState.destination.route
            val to = targetState.destination.route
            if (from == Dest.Login.name || to == Dest.Login.name) {
                fadeIn()
            } else if (forward) {
                slideInHorizontally(initialOffsetX = { fullWidth -> -fullWidth }) + fadeIn()
            } else {
                slideInHorizontally(initialOffsetX = { fullWidth -> fullWidth }) + fadeIn()
            }
        },
        popExitTransition = {
            val from = initialState.destination.route
            val to = targetState.destination.route
            if (from == Dest.Login.name || to == Dest.Login.name) {
                fadeOut()
            } else if (forward) {
                slideOutHorizontally(targetOffsetX = { fullWidth -> fullWidth }) + fadeOut()
            } else {
                slideOutHorizontally(targetOffsetX = { fullWidth -> -fullWidth }) + fadeOut()
            }
        },

        modifier = modifier
    ) {
        composable(Dest.Login.name) {
            LoginPage(
                onLoginSuccess = {
                    isLoggedIn = true
                    onPlatformsChanged()
                    navController.navigate(Dest.Grades.name) {
                        popUpTo(Dest.Login.name) { inclusive = true }
                    }
                    onLoginSuccess()
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        composable(Dest.Grades.name) {
            GradesScreen(
                preloadedCourses = preloadedCourses,
                modifier = Modifier.fillMaxSize()
            )
        }

        composable(Dest.Schedule.name) {
            SchedulePage(
                modifier = Modifier.fillMaxSize(),
            )
        }

        composable(Dest.Attendance.name) {
            AttendancePage(modifier = Modifier.fillMaxSize())
        }

        composable(Dest.Locker.name) {
            LockerApp(
                modifier = Modifier.fillMaxSize(),
                viewModel = viewModel,
                platforms = platforms
            )
        }

        composable(Dest.Settings.name) {
            SettingsPage(
                navController = navController,
                modifier = Modifier
            )
        }

        composable(Dest.Platforms.name) {
            PlatformsPage(
                modifier = Modifier.fillMaxSize(),
                onPlatformsChanged = onPlatformsChanged,
                onLogout = {
                    PlatformStorage.clearPlatforms(context)
                    isLoggedIn = false
                    navController.navigate(Dest.Login.name) {
                        popUpTo(0)
                    }
                    onLogout()
                }
            )
        }

        composable(Dest.Messages.name) {
            MessagesScreen()
        }

    }
}