package com.feldman.scholix

import SchedulePage
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.Room
import com.feldman.scholix.pages.LockerApp
import com.feldman.scholix.pages.LockerDatabase
import com.feldman.scholix.pages.LockerRepository
import com.feldman.scholix.pages.LockerViewModel
import com.feldman.scholix.pages.LockerViewModelFactory
import com.feldman.lockerapp.ui.theme.AppTheme
import com.feldman.scholix.api.Platform
import com.feldman.scholix.api.PlatformStorage
import com.feldman.scholix.api.WebtopPlatform
import com.feldman.scholix.pages.AttendancePage
import com.feldman.scholix.pages.GradesScreen
import com.feldman.scholix.pages.SettingsPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = Room.databaseBuilder(
            applicationContext,
            LockerDatabase::class.java,
            "locker-db"
        ).build()
        val repository = LockerRepository(db.lockerItemDao())
        setContent {
            AppTheme {
                var isLoggedIn by remember { mutableStateOf<Boolean?>(null) }
                val context = LocalContext.current

                LaunchedEffect(Unit) {
                    withContext(Dispatchers.IO) {
                        val platforms = PlatformStorage.loadPlatforms(context)
                        val valid = platforms.any { it.isLoggedIn() }
                        withContext(Dispatchers.Main) {
                            isLoggedIn = valid
                        }
                    }
                }

                when (isLoggedIn) {
                    null -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularWavyProgressIndicator()
                        }
                    }
                    false -> {
                        LoginScreen(onLoginSuccess = { isLoggedIn = true })
                    }
                    true -> {
                        var isLoading by remember { mutableStateOf(true) }
                        var preloadedCourses by remember { mutableStateOf(listOf<JSONObject>()) }
                        val scope = rememberCoroutineScope()
                        var selectedItem by remember { mutableStateOf(0) }
                        var platforms by remember { mutableStateOf<List<Platform>>(emptyList()) }
                        LaunchedEffect(Unit) {
                            withContext(Dispatchers.IO) {
                                platforms = PlatformStorage.loadPlatforms(context)
                                val valid = platforms.any { it.isLoggedIn() }
                                withContext(Dispatchers.Main) { isLoggedIn = valid }
                            }
                        }
                        fun reloadPreloads() {
                            scope.launch(Dispatchers.IO) {
                                PlatformStorage.refreshCookies(this@MainActivity)
                                val courses = PlatformStorage.getCourses(this@MainActivity)
//                                val filled = coroutineScope {
//                                    courses.map { course ->
//                                        async {
//                                            val platformIndex = course.optInt("index")
//                                            val courseName = course.optString("name")
//                                            try {
//                                                val gradesArray = platforms[platformIndex].getGrades(courseName)
//                                                course.put("grades", gradesArray)
//                                            } catch (_: Exception) {
//                                                course.put("grades", JSONArray())
//                                            }
//                                            course
//                                        }
//                                    }.awaitAll()
//                                }
                                withContext(Dispatchers.Main) {
                                    preloadedCourses = courses
                                    isLoading = false
                                }
                            }
                        }

                        LaunchedEffect(Unit) { reloadPreloads() }

                        if (isLoading) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularWavyProgressIndicator()
                            }
                        } else {

                            MainScreen(
                                preloadedCourses = preloadedCourses,
                                repository = repository,
                                selectedItem = selectedItem,
                                onSelectedChange = { selectedItem = it },
                                onPlatformsChanged = { reloadPreloads() }
                            )
                        }

                    }
                }
            }
        }

    }
}

@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Login", style = MaterialTheme.typography.headlineMedium)

            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Username") }
            )

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                visualTransformation = PasswordVisualTransformation()
            )


            if (isLoading) {
                CircularProgressIndicator()
            } else {
                Button(onClick = {
                    if (username.isBlank() || password.isBlank()) {
                        errorMessage = "Please enter both fields"
                        return@Button
                    }
                    isLoading = true
                    errorMessage = null

                    scope.launch {
                        try {
                            val platform = withContext(Dispatchers.IO) {
                                WebtopPlatform(username, password)
                            }

                            if (platform.isLoggedIn()) {
                                PlatformStorage.savePlatforms(context, listOf(platform))
                                onLoginSuccess()
                            } else {
                                errorMessage = "Invalid credentials"
                            }
                        } catch (e: Exception) {
                            errorMessage = "Login error: ${e.localizedMessage}"
                        } finally {
                            isLoading = false
                        }
                    }
                }) {
                    Text("Login")
                }
            }

            errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}



@Composable
fun MainScreen(
    preloadedCourses: List<JSONObject>,
    repository: LockerRepository,
    selectedItem: Int,
    onSelectedChange: (Int) -> Unit,
    onPlatformsChanged: () -> Unit
) {

    val items = listOf(
        stringResource(R.string.grades),
        stringResource(R.string.schedule),
        stringResource(R.string.attendance),
        stringResource(R.string.locker),
        "Settings"
    )

    val icons = listOf(
        R.drawable.ic_grade,
        R.drawable.ic_schedule,
        R.drawable.ic_alarm,
        R.drawable.ic_locker,
        R.drawable.ic_settings
    )
    val context = LocalContext.current

    val factory = LockerViewModelFactory(repository)
    val viewModel: LockerViewModel = viewModel(factory = factory)

    Scaffold(
        bottomBar = {
            NavigationBar {
                items.forEachIndexed { index, label ->
                    NavigationBarItem(
                        selected = selectedItem == index,
                        onClick = { onSelectedChange(index) },
                        icon = {
                            Icon(
                                painter = painterResource(id = icons[index]),
                                contentDescription = label
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                        ),
                        label = {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        },
                    )
                }
            }
        }
    ) { innerPadding ->
        when (selectedItem) {
            0 -> GradesScreen(
                modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding()),
                preloadedCourses = preloadedCourses
            )
            1 -> SchedulePage(
                modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding()),
                fetchScheduleUpdated = { dayIdx ->
                    val platform = PlatformStorage.getAccount(context, 0)
                    if (platform != null) {
                        withContext(Dispatchers.IO) {
                            val schedule = platform.getSchedule(dayIdx)
                            schedule.keys().asSequence().map { schedule.getJSONObject(it) }.toList()
                        }
                    } else {
                        emptyList()
                    }
                },
                fetchScheduleOriginal = { dayIdx ->
                    val platform = PlatformStorage.getAccount(context, 0)
                    if (platform != null) {
                        withContext(Dispatchers.IO) {
                            val schedule = platform.getOriginalSchedule(dayIdx)
                            schedule.keys().asSequence().map { schedule.getJSONObject(it) }.toList()
                        }
                    } else {
                        emptyList()
                    }
                }
            )
            2 -> AttendancePage(modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding()))
            3 -> LockerApp(modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding()), viewModel = viewModel)
            4 -> SettingsPage(
                modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding()),
                onPlatformsChanged = onPlatformsChanged
            )
        }
    }
}
