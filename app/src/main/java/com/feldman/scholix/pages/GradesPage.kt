package com.feldman.scholix.pages

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.text.BidiFormatter
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.Indicator
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.feldman.motion.AutoSizeText
import com.feldman.motion.ITEM_SPACER
import com.feldman.motion.ItemPosition
import com.feldman.motion.MotionCard
import com.feldman.motion.MotionButton
import com.feldman.motion.MotionButtonState
import com.feldman.motion.MotionDropdown
import com.feldman.motion.MotionDropdownDefaults
import com.feldman.motion.MotionDropdownDirection
import com.feldman.motion.MotionDropdownMenuAlignment
import com.feldman.motion.MotionDropdownTextFit
import com.feldman.motion.MotionLazyColumn
import com.feldman.motion.MotionSymbols
import com.feldman.motion.feldmanFont
import com.feldman.motion.rememberSymbolPainter
import com.feldman.lockerapp.ui.theme.AppTheme
import com.feldman.scholix.BottomBarSpacing
import com.feldman.scholix.R
import com.feldman.scholix.TopBarSpacing
import com.feldman.scholix.api.Platform
import com.feldman.scholix.api.PlatformStorage
import com.feldman.scholix.api.platforms.StudentsPortalPlatform
import com.feldman.scholix.ui.HiddenMoeLogin
import com.feldman.scholix.api.platforms.WebtopPlatform
import com.feldman.scholix.ui.HiddenWebtopMoeLogin
import com.feldman.scholix.storage.expressiveDesignFlow
import com.feldman.scholix.ui.components.ChipPicker
import com.feldman.scholix.ui.components.hasSubjectIcon
import com.feldman.scholix.ui.components.providerPainter
import com.feldman.scholix.ui.components.PickerBar
import com.feldman.scholix.ui.components.SubjectIcon
import com.feldman.scholix.ui.components.subjectIconName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.checkerframework.common.subtyping.qual.Bottom
import org.json.JSONArray
import org.json.JSONObject

fun isRtlText(text: String): Boolean {
    for (char in text) {
        when (Character.getDirectionality(char)) {
            Character.DIRECTIONALITY_RIGHT_TO_LEFT,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> return true
            Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return false
        }
    }
    return false
}

class GradesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("NAV_DEBUG", "GradesActivity started")

        setContent {
            AppTheme {
                GradesScreen(Modifier, emptyList())
            }
        }
    }
}

@Composable
fun gradeColor(gradeStr: String): Color {
    val colors = MaterialTheme.colorScheme
    val grade = gradeStr.toIntOrNull() ?: return colors.primary

    // clamp the grade to 0..100
    val clamped = grade.coerceIn(0, 100)
    val fraction = clamped / 100f

    // linearly blend between secondary (bad) → primary (good)
    return lerp(colors.secondary, colors.primary, fraction)
}



@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GradesLoadingIndicator(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        ContainedLoadingIndicator(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            indicatorColor = MaterialTheme.colorScheme.onTertiaryContainer
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NoGradesCourseState(
    courseName: String,
    painter: Painter? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        SubjectIcon(
            subject = courseName,
            assignment = "course",
            painter = painter,
            containerColor = MaterialTheme.colorScheme.primary,
            iconColor = MaterialTheme.colorScheme.onPrimary,
            size = 128.dp,
            iconSize = 68.dp,
            polygon = MaterialShapes.Cookie12Sided
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = "No grades yet",
            style = MaterialTheme.typography.headlineMedium,
            fontFamily = feldmanFont(weight = 600, width = 140f),
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
    }
}

private fun courseSupportingText(course: JSONObject): String =
    course.optString("status").ifBlank {
        course.optString("rawTerm").ifBlank {
            course.optString("term").ifBlank {
                course.optInt("year").takeIf { it > 0 }?.toString().orEmpty()
            }
        }
    }

/**
 * The icon for a course: its own subject icon when the subject is recognisable,
 * otherwise the logo of the provider it came from -- more informative than the
 * stock school glyph every unmatched course used to share.
 */
@Composable
private fun courseIconPainter(
    course: JSONObject,
    providersById: Map<String, Platform>
): Painter? {
    if (hasSubjectIcon(course.optString("name"), "course")) return null
    val provider = providersById[course.optString("platformId")] ?: return null
    return providerPainter(provider)
}

@Composable
private fun CoursePickerBar(
    courses: List<JSONObject>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    providersById: Map<String, Platform>,
    modifier: Modifier = Modifier
) {
    val courseLabel = stringResource(R.string.course)
    PickerBar(
        options = courses,
        selectedIndex = selectedIndex,
        onSelected = onSelected,
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        optionLabel = { course -> course.optString("name").ifBlank { courseLabel } },
        optionDescription = { course -> courseSupportingText(course) },
        optionKey = { course ->
            "${course.optString("platformId")}|${course.optString("courseKey")}|${course.optString("name")}"
        },
        optionIcon = { course ->
            SubjectIcon(
                subject = course.optString("name").ifBlank { courseLabel },
                assignment = "course",
                painter = courseIconPainter(course, providersById),
                size = 40.dp,
                iconSize = 22.dp
            )
        },
        contentDescription = "Choose course",
        previousDescription = "Previous course",
        nextDescription = "Next course",
        modifier = modifier
    )
}

@Composable
private fun CoursePickerPane(
    courses: List<JSONObject>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    providersById: Map<String, Platform>,
    modifier: Modifier = Modifier,
    includeTopInset: Boolean = false
) {
    var query by rememberSaveable(courses.size) { mutableStateOf("") }
    val filteredCourses = remember(courses, query) {
        val normalizedQuery = query.trim().lowercase()
        courses.mapIndexedNotNull { index, course ->
            val searchableText = buildString {
                append(course.optString("name"))
                append(' ')
                append(courseSupportingText(course))
            }.lowercase()
            IndexedValue(index, course).takeIf {
                normalizedQuery.isBlank() || normalizedQuery in searchableText
            }
        }
    }
    val initialVisibleIndex = filteredCourses.indexOfFirst { it.index == selectedIndex }
        .coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialVisibleIndex)

    Column(
        modifier = modifier
            .padding(horizontal = 16.dp)
            .imePadding()
    ) {
        if (includeTopInset) Spacer(Modifier.height(TopBarSpacing()))
        if (includeTopInset) {
            Spacer(Modifier.height(8.dp))
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Courses",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = courses.size.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(if (includeTopInset) "Find ${courses.size} courses" else "Find a course")
            },
            leadingIcon = {
                Icon(
                    painter = rememberSymbolPainter(MotionSymbols.ic_search),
                    contentDescription = null
                )
            },
            trailingIcon = if (query.isNotEmpty()) {
                {
                    IconButton(onClick = { query = "" }) {
                        Icon(
                            painter = rememberSymbolPainter(MotionSymbols.ic_close),
                            contentDescription = "Clear search"
                        )
                    }
                }
            } else {
                null
            },
            singleLine = true,
            shape = MaterialTheme.shapes.extraLarge
        )
        Spacer(Modifier.height(12.dp))

        if (filteredCourses.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No matching courses",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(2.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                itemsIndexed(
                    items = filteredCourses,
                    key = { _, item ->
                        val course = item.value
                        "${course.optString("platformId")}|${course.optString("courseKey")}|${course.optString("name")}|${item.index}"
                    }
                ) { filteredIndex, item ->
                    val course = item.value
                    val selected = item.index == selectedIndex
                    MotionCard(
                        position = when {
                            filteredCourses.size == 1 -> ItemPosition.Alone
                            filteredIndex == 0 -> ItemPosition.Start
                            filteredIndex == filteredCourses.lastIndex -> ItemPosition.End
                            else -> ItemPosition.Middle
                        },
                        selected = selected,
                        containerColor = if (selected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainer
                        },
                        contentPadding = 0.dp,
                        modifier = Modifier.clickable(
                            onClickLabel = "Show grades for ${course.optString("name")}",
                            onClick = { onSelected(item.index) }
                        )
                    ) {
                        val rtl = isRtlText(course.optString("name"))
                        CompositionLocalProvider(
                            LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                SubjectIcon(
                                    subject = course.optString("name"),
                                    assignment = "course",
                                    painter = courseIconPainter(course, providersById),
                                    size = 44.dp,
                                    iconSize = 24.dp
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = course.optString("name", stringResource(R.string.course)),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (selected) {
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    courseSupportingText(course).takeIf { it.isNotBlank() }?.let { supportingText ->
                                        Text(
                                            text = supportingText,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (selected) {
                                                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f)
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                if (selected) {
                                    Icon(
                                        painter = rememberSymbolPainter(MotionSymbols.ic_check),
                                        contentDescription = "Selected",
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GradesScreen(modifier: Modifier, preloadedCourses: List<JSONObject>) {
    val context = LocalContext.current
    val expressiveDesign by context.expressiveDesignFlow().collectAsState(initial = true)


    val currentYear = java.time.Year.now().value
    val currentMonth = java.time.LocalDate.now().monthValue

    val initialSemester = if (currentMonth in 9..12 || currentMonth == 1) "A" else "B"
    val initialYear = if (initialSemester == "A") currentYear + 1 else currentYear

    var semesterState by rememberSaveable { mutableStateOf(initialSemester) }
    var yearState by rememberSaveable { mutableIntStateOf(initialYear) }

    var courses by remember { mutableStateOf(preloadedCourses) }
    var courseLoadInProgress by remember { mutableStateOf(false) }
    var courseLoadFinished by remember { mutableStateOf(preloadedCourses.isNotEmpty()) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var courseMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var grades by remember { mutableStateOf(listOf<JSONObject>()) }
    var average by remember { mutableFloatStateOf(0f) }
    var isRefreshing by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var requestId by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    var finalGrade by remember { mutableStateOf<JSONObject?>(null) }

    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Tracks which (course, year, semester) the current grades/errorMessage actually belong to,
    // so the UI can tell "no grades were found" apart from "haven't asked yet" without waiting on
    // LaunchedEffect to fire — that gap is what caused the raw-JSON debug text to flash before the
    // loading indicator appeared when switching from the courses spinner to a per-course fetch.
    var loadedKey by remember { mutableStateOf<String?>(null) }
    // The students-portal SSO session lapses well before the app is next opened.
    // We hold the credentials, so re-run the off-screen sign-in and retry the
    // request instead of showing "please re-login" for a recoverable session.
    // Providers by id, so a course with no subject icon can borrow its logo.
    var providersById by remember { mutableStateOf<Map<String, Platform>>(emptyMap()) }
    LaunchedEffect(Unit) {
        providersById = withContext(Dispatchers.IO) {
            runCatching {
                PlatformStorage.loadPlatforms(context).associateBy { it.id }
            }.getOrDefault(emptyMap())
        }
    }
    var portalRelogin by remember { mutableStateOf<StudentsPortalPlatform?>(null) }
    var webtopRelogin by remember { mutableStateOf<WebtopPlatform?>(null) }
    var reloginAttempted by remember { mutableStateOf(false) }
    fun keyFor(course: JSONObject?, year: Int, semester: String) =
        course?.let {
            "${it.optString("courseKey").ifBlank { it.optString("name") }}|$year|${semester.lowercase()}"
        }

    Log.d("GradesPage", "initial year: $initialYear | initial semester: $initialSemester")

    fun launchGradesRequest(
        context: Context,
        course: JSONObject,
        year: Int = initialYear,
        semester: String = initialSemester,
        onResult: (List<JSONObject>, Float, String?) -> Unit
    ) {
        val currentId = ++requestId
        isLoading = true
        grades = emptyList()
        average = 0f

        scope.launch {
            var gradesArray = JSONArray()
            var list = emptyList<JSONObject>()
            var avg = 0f
            var finalGradeObj: JSONObject? = null
            var requestError: String? = null
            var requestPlatform: com.feldman.scholix.api.Platform? = null
            // The raw code, kept alongside the human-readable message so
            // recoverable failures can be told apart after the fact.
            var requestErrorCode: String? = null

            try {
                withContext(Dispatchers.IO) {
                    val availablePlatforms = PlatformStorage.loadPlatforms(context)
                    val platformId = course.optString("platformId")
                    val platform = availablePlatforms.firstOrNull { it.id == platformId }
                        ?: availablePlatforms.getOrNull(course.optInt("index", -1))
                        ?: availablePlatforms.firstOrNull { p ->
                            val targetKey = course.optString("courseKey").ifBlank { course.optString("name") }
                            p.getCourses().any { c ->
                                val key = c.optString("courseKey").ifBlank { c.optString("name") }
                                key == targetKey || c.optString("id") == course.optString("id")
                            }
                        }
                        ?: if (availablePlatforms.size == 1) availablePlatforms.first() else null

                    requestPlatform = platform
                    if (platform == null) {
                        gradesArray = JSONArray()
                        requestError = "Could not find account for this course."
                    } else {
                        gradesArray = platform.getGrades(
                            course = course.optString("courseKey").ifBlank { course.optString("name") },
                            year = year,
                            semester = semester
                        )
                    }

                    if (gradesArray.length() == 1) {
                        val first = gradesArray.optJSONObject(0)
                        if (first != null && first.has("error")) {
                            requestErrorCode = first.optString("error")
                            requestError = when (requestErrorCode) {
                                "server_unreachable" -> "Cannot reach the server.\nCheck your internet connection."
                                "login_failed" -> "Login failed.\nPlease re-login."
                                else -> "Unknown error occurred while loading grades."
                            }
                        }
                    }

                    if (requestError == null) {
                        processGrades(gradesArray).let { (loadedGrades, loadedAverage, loadedFinalGrade) ->
                            list = loadedGrades
                            avg = loadedAverage
                            finalGradeObj = loadedFinalGrade
                        }
                    }
                }
            } catch (exception: Exception) {
                Log.w("GradesPage", "Failed to load grades", exception)
                requestError = "Cannot reach the server.\nCheck your internet connection."
            }

            if (currentId == requestId) {
                val portal = requestPlatform as? StudentsPortalPlatform
                if (requestErrorCode == "login_failed" && portal != null &&
                    portal.needsInteractiveRelogin() && !reloginAttempted
                ) {
                    // Keep the spinner up: the retry replaces this result.
                    Log.d("GradesPage", "portal session expired; re-signing in off-screen")
                    reloginAttempted = true
                    portalRelogin = portal
                    return@launch
                }
                val webtop = requestPlatform as? WebtopPlatform
                if (requestErrorCode == "login_failed" && webtop != null &&
                    webtop.needsInteractiveRelogin() && !reloginAttempted
                ) {
                    Log.d("GradesPage", "webtop session expired; re-signing in off-screen")
                    reloginAttempted = true
                    webtopRelogin = webtop
                    return@launch
                }
                course.put("grades", gradesArray)
                onResult(list, avg, requestError)
                finalGrade = finalGradeObj
                isLoading = false
                loadedKey = keyFor(course, year, semester)
            }
        }
    }


    var sortBy by rememberSaveable { mutableStateOf("Date") }

    LaunchedEffect(courses, selectedTab, semesterState, yearState) {
        val selectedCourse = courses.getOrNull(selectedTab)
        val selectedKey = keyFor(selectedCourse, yearState, semesterState)
        if (selectedCourse != null && selectedKey != loadedKey) {
            launchGradesRequest(
                context,
                selectedCourse,
                year = yearState,
                semester = semesterState.lowercase()
            ) { g, avg, err ->
                grades = g
                average = avg
                errorMessage = err
            }
        }
    }
    fun refreshCoursesIfEmpty() {
        if (courses.isEmpty() && !courseLoadInProgress) {
            courseLoadInProgress = true
            scope.launch(Dispatchers.IO) {
                val refreshed = try {
                    PlatformStorage.getCourses(context)
                } catch (exception: Exception) {
                    Log.w("GradesPage", "Failed to load courses", exception)
                    arrayListOf()
                }
                withContext(Dispatchers.Main) {
                    if (refreshed.isNotEmpty()) {
                        courses = refreshed
                        selectedTab = 0
                    }
                    courseLoadInProgress = false
                    courseLoadFinished = true
                }
            }
        }
    }

    LaunchedEffect(preloadedCourses) {
        if (preloadedCourses.isNotEmpty()) {
            courses = preloadedCourses
            selectedTab = selectedTab.coerceIn(0, preloadedCourses.lastIndex)
            courseLoadFinished = true
        }
    }

    LaunchedEffect(Unit) {
        refreshCoursesIfEmpty()
    }

    // Silent students-portal session recovery: sign in again off-screen with the
    // stored credentials, then replay the request that hit the expired session.
    portalRelogin?.let { portal ->
        HiddenMoeLogin(
            username = portal.getUsername().orEmpty(),
            password = portal.getPassword().orEmpty()
        ) { cookies, csrt, error ->
            portalRelogin = null
            val course = courses.getOrNull(selectedTab)
            if (cookies == null || course == null) {
                Log.w("GradesPage", "portal re-login failed: " + (error ?: "no cookie"))
                isLoading = false
                errorMessage = "Login failed.\nPlease re-login."
                return@HiddenMoeLogin
            }
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    portal.adoptSession(cookies, csrt).also { success ->
                        if (success) {
                            // loadPlatforms re-parses from storage, so swap our
                            // refreshed instance in by id before saving.
                            val platforms = PlatformStorage.loadPlatforms(context).toMutableList()
                            val index = platforms.indexOfFirst { it.id == portal.id }
                            if (index >= 0) platforms[index] = portal else platforms += portal
                            PlatformStorage.savePlatforms(context, platforms)
                        }
                    }
                }
                if (!ok) {
                    isLoading = false
                    errorMessage = "Login failed.\nPlease re-login."
                    return@launch
                }
                launchGradesRequest(
                    context,
                    course,
                    year = yearState,
                    semester = semesterState.lowercase()
                ) { g, avg, err ->
                    grades = g
                    average = avg
                    errorMessage = err
                    // Allow another recovery later in the session, but only once
                    // this one actually worked -- otherwise a failing sign-in
                    // would retry itself forever.
                    if (err == null) reloginAttempted = false
                }
            }
        }
    }

    webtopRelogin?.let { webtop ->
        HiddenWebtopMoeLogin(
            username = webtop.getUsername().orEmpty(),
            password = webtop.getPassword().orEmpty()
        ) { key, error ->
            webtopRelogin = null
            val course = courses.getOrNull(selectedTab)
            if (key == null || course == null) {
                Log.w("GradesPage", "webtop re-login failed: " + (error ?: "no key"))
                isLoading = false
                errorMessage = "Login failed.\nPlease re-login."
                return@HiddenWebtopMoeLogin
            }
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    webtop.adoptSession(key).also { success ->
                        if (success) {
                            val platforms = PlatformStorage.loadPlatforms(context).toMutableList()
                            val index = platforms.indexOfFirst { it.id == webtop.id }
                            if (index >= 0) platforms[index] = webtop else platforms += webtop
                            PlatformStorage.savePlatforms(context, platforms)
                        }
                    }
                }
                if (!ok) {
                    isLoading = false
                    errorMessage = "Login failed.\nPlease re-login."
                    return@launch
                }
                launchGradesRequest(
                    context,
                    course,
                    year = yearState,
                    semester = semesterState.lowercase()
                ) { g, avg, err ->
                    grades = g
                    average = avg
                    errorMessage = err
                    if (err == null) reloginAttempted = false
                }
            }
        }
    }

    if (courses.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (!courseLoadFinished) {
                GradesLoadingIndicator(modifier = Modifier.fillMaxSize())
            } else {
                Text(
                    text = stringResource(R.string.no_courses_available),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )
            }
        }
        return
    }
    else{
        val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        val stillLoading = isLoading ||
            keyFor(courses.getOrNull(selectedTab), yearState, semesterState) != loadedKey

        Box(modifier = modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxSize()) {
            if (isLandscape && courses.size > 1) {
                CoursePickerPane(
                    courses = courses,
                    selectedIndex = selectedTab,
                    onSelected = { selectedTab = it },
                    providersById = providersById,
                    modifier = Modifier
                        .width(280.dp)
                        .fillMaxHeight(),
                    includeTopInset = true
                )
                VerticalDivider(
                    modifier = Modifier.fillMaxHeight(),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            }
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(TopBarSpacing()))
            if (!isLandscape && courses.size > 1) {
                CoursePickerBar(
                    courses = courses,
                    selectedIndex = selectedTab,
                    onSelected = { selectedTab = it },
                    expanded = courseMenuExpanded,
                    onExpandedChange = { courseMenuExpanded = it },
                    providersById = providersById
                )
                Spacer(Modifier.height(8.dp))
            }

            val pullRefreshState = rememberPullToRefreshState()
            var gradesViewportHeight by remember { mutableIntStateOf(0) }
            var gradesControlsHeight by remember { mutableIntStateOf(0) }
            val density = LocalDensity.current

            PullToRefreshBox(
                state = pullRefreshState,
                isRefreshing = isRefreshing,
                onRefresh = {
                    val selectedCourse = courses.getOrNull(selectedTab)
                    if (selectedCourse != null) {
                        launchGradesRequest(
                            context,
                            selectedCourse,
                            year = yearState,
                            semester = semesterState.lowercase()
                        ) { g, avg, err ->
                            grades = g
                            average = avg
                            errorMessage = err
                        }
                    } else {
                        refreshCoursesIfEmpty()
                    }
                },
                indicator = {
                    Indicator(
                        modifier = Modifier.align(Alignment.TopCenter),
                        isRefreshing = isRefreshing,
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        state = pullRefreshState
                    )
                },
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { gradesViewportHeight = it.height },
                contentAlignment = Alignment.TopCenter
            ) {

                MotionLazyColumn(
                    modifier = Modifier
                        .fillMaxSize(),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    item {
                        val selectedCourse = courses.getOrNull(selectedTab)
                        val showSemesterPicker = selectedCourse?.optBoolean("semesterPicker", false) == true

                        Column(
                            modifier = Modifier.onSizeChanged { gradesControlsHeight = it.height }
                        ) {
                            Spacer(Modifier.height(12.dp))

                            if (showSemesterPicker) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Box(modifier = Modifier.weight(1f)) {
                                        ChipPicker(
                                            label = stringResource(R.string.year),
                                            options = listOf(
                                                (currentYear - 1).toString(),
                                                currentYear.toString(),
                                                (currentYear + 1).toString()
                                            ),
                                            selected = yearState.toString(),
                                            optionIcon = { rememberSymbolPainter(name = MotionSymbols.ic_calendar_month) },
                                            onSelectedChange = { newYear ->
                                                yearState = newYear.toInt()
                                                val selectedCourse = courses[selectedTab]
                                                launchGradesRequest(
                                                    context,
                                                    selectedCourse,
                                                    yearState,
                                                    semesterState.lowercase()
                                                ) { g, avg, err ->
                                                    grades = g
                                                    average = avg
                                                    errorMessage = err
                                                }
                                            }
                                        )
                                    }


                                    Box(modifier = Modifier.weight(1f)) {
                                        ChipPicker(
                                            label = stringResource(R.string.semester),
                                            options = listOf("A", "B"),
                                            selected = semesterState,
                                            optionIcon = { option ->
                                                rememberSymbolPainter(
                                                    name = if (option == "A") MotionSymbols.ic_looks_one else MotionSymbols.ic_looks_two
                                                )
                                            },
                                            onSelectedChange = { newSemester ->
                                                semesterState = newSemester
                                                val selectedCourse = courses[selectedTab]
                                                launchGradesRequest(
                                                    context,
                                                    selectedCourse,
                                                    year = yearState,
                                                    semester = semesterState.lowercase()
                                                ) { g, avg, err ->
                                                    grades = g
                                                    average = avg
                                                    errorMessage = err
                                                }
                                            }
                                        )
                                    }
                                }
                                Spacer(Modifier.height(12.dp))
                            }

                            // Sort Filter
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                               ChipPicker(
                                   label = stringResource(R.string.sort_by),
                                   options = listOf("Date", "Grade"),
                                   selected = sortBy,
                                   optionIcon = { option ->
                                       rememberSymbolPainter(
                                           name = if (option == "Grade") MotionSymbols.ic_grade else MotionSymbols.ic_calendar_month
                                       )
                                   },
                                   onSelectedChange = { sortBy = it }
                               )
                            }

                            Spacer(Modifier.height(12.dp))
                        }

                        when {

                            stillLoading -> {}
                            grades.isNotEmpty() -> {
                                MotionCard(
                                    position = ItemPosition.Alone,
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentPadding = 0.dp,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    val averageLabel = stringResource(R.string.average)
                                    val isRtl = isRtlText(averageLabel)
                                    CompositionLocalProvider(LocalLayoutDirection provides if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 16.dp, vertical = 20.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            AutoSizeText(
                                                text = averageLabel,
                                                style = MaterialTheme.typography.titleLarge.copy(
                                                    fontWeight = FontWeight.Medium,
                                                    fontSize = 32.sp
                                                ),
                                                minFontSize = 18.sp,
                                                maxLines = 1,
                                                color = MaterialTheme.colorScheme.onPrimary,
                                                modifier = Modifier.weight(1f, fill = false)
                                            )

                                            Spacer(Modifier.width(16.dp))

                                            AutoSizeText(
                                                text = String.format("%.1f", average),
                                                style = MaterialTheme.typography.bodyLarge.copy(
                                                    fontFamily = if (expressiveDesign) feldmanFont(weight = 900) else null,
                                                    fontWeight = FontWeight.Black,
                                                    fontSize = 48.sp
                                                ),
                                                minFontSize = 24.sp,
                                                maxLines = 1,
                                                color = MaterialTheme.colorScheme.onPrimary,
                                                modifier = Modifier.weight(1f, fill = false)
                                            )
                                        }
                                    }
                                }

                                if (finalGrade != null) {
                                    Spacer(Modifier.height(ITEM_SPACER))
                                    MotionCard(
                                        position = ItemPosition.Alone,
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentPadding = 0.dp,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        val finalGradeLabel = stringResource(R.string.final_grade)
                                        val isRtl = isRtlText(finalGradeLabel)
                                        CompositionLocalProvider(LocalLayoutDirection provides if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 16.dp, vertical = 20.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                AutoSizeText(
                                                    text = finalGradeLabel,
                                                    style = MaterialTheme.typography.titleLarge.copy(
                                                        fontWeight = FontWeight.Medium,
                                                        fontSize = 32.sp
                                                    ),
                                                    minFontSize = 18.sp,
                                                    maxLines = 1,
                                                    color = MaterialTheme.colorScheme.onPrimary,
                                                    modifier = Modifier.weight(1f, fill = false)
                                                )
                                                Spacer(Modifier.width(16.dp))

                                                AutoSizeText(
                                                    text = finalGrade!!.optString("grade"),
                                                    style = MaterialTheme.typography.bodyLarge.copy(
                                                        fontFamily = if (expressiveDesign) feldmanFont(weight = 900) else null,
                                                        fontWeight = FontWeight.Black,
                                                        fontSize = 48.sp,
                                                    ),
                                                    minFontSize = 24.sp,
                                                    maxLines = 1,
                                                    color = MaterialTheme.colorScheme.onPrimary,
                                                    modifier = Modifier.weight(1f, fill = false)
                                                )
                                            }
                                        }
                                    }
                                }

                                Spacer(Modifier.height(ITEM_SPACER))
                            }
                            else -> {
                                val displayError = errorMessage
                                if (displayError == null) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(
                                                with(density) {
                                                    (gradesViewportHeight - gradesControlsHeight)
                                                        .coerceAtLeast(0)
                                                        .toDp()
                                                }
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        NoGradesCourseState(
                                            courseName = selectedCourse?.optString("name").orEmpty(),
                                            painter = selectedCourse?.let {
                                                courseIconPainter(it, providersById)
                                            }
                                        )
                                    }
                                } else {
                                    Text(
                                        text = displayError,
                                        style = MaterialTheme.typography.bodyLarge.copy(
                                            fontWeight = FontWeight.Medium,
                                            fontSize = 18.sp
                                        ),
                                        color = Color.Red,
                                        modifier = Modifier.fillMaxWidth(),
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }

                        }

                    }
                    if (!stillLoading){
                        val indexedGrades = grades.withIndex().toList()
                        val sortedGrades = if (sortBy == "Grade") {
                            indexedGrades.sortedByDescending {
                                it.value.optDouble("grade", Double.MIN_VALUE)
                            }
                        } else {
                            indexedGrades.sortedWith(
                                compareBy<IndexedValue<JSONObject>> {
                                    it.value.optInt("assignmentOrder", Int.MAX_VALUE)
                                }.thenBy { it.index }
                            )
                        }
                        section(
                            items = sortedGrades,
                            key = { it.index },
                            contentType = { "grade" },
                            contentPadding = 0.dp,
                        ) { indexedGrade ->
                            val grade = indexedGrade.value
                            val rawSubject = grade.optString("subject", "Unknown")
                            val rawName = grade.optString("name", "")
                            val isRtl = isRtlText("$rawSubject $rawName")

                            val bidi = BidiFormatter.getInstance()
                            val subject = bidi.unicodeWrap(rawSubject)
                            val name = bidi.unicodeWrap(rawName)

                            CompositionLocalProvider(LocalLayoutDirection provides if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(
                                            start = 16.dp,
                                            top = 12.dp,
                                            end = 16.dp,
                                            bottom = 12.dp
                                        ),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    SubjectIcon(
                                        subject = rawSubject,
                                        assignment = rawName
                                    )
                                    Spacer(Modifier.width(12.dp))

                                    Column(
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(
                                            text = subject,
                                            style = MaterialTheme.typography.titleLarge.copy(
                                                fontFamily = feldmanFont(weight = 500),
                                                fontWeight = FontWeight.Medium
                                            ),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        if (rawName.isNotEmpty()) {
                                            Text(
                                                text = name,
                                                style = MaterialTheme.typography.titleMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }

                                    val gradeStr = grade.optString("grade", "-")

                                    Text(
                                        text = gradeStr,
                                        style = MaterialTheme.typography.bodyLarge.copy(
                                            fontFamily = feldmanFont(weight = 700, width = 50f),
                                            lineHeight = 40.sp
                                        ),
                                        color = gradeColor(gradeStr),
                                        textAlign = TextAlign.Center,
                                        maxLines = 2,
                                        modifier = Modifier.widthIn(max = 120.dp),
                                        autoSize = TextAutoSize.StepBased(
                                            minFontSize = 10.sp,
                                            maxFontSize = 60.sp,
                                            stepSize = 2.sp
                                        )
                                    )
                                }
                            }
                        }
                    }
                    item {
                        Spacer(Modifier.height(180.dp))
                    }
                }
            }

        }
        }
            if (stillLoading) {
                GradesLoadingIndicator(modifier = Modifier.fillMaxSize())
            }
        }
    }

}

fun processGrades(gradesArray: JSONArray): Triple<List<JSONObject>, Float, JSONObject?> {
    val list = mutableListOf<JSONObject>()
    var finalGrade: JSONObject? = null
    var sum = 0f
    var count = 0

    for (i in 0 until gradesArray.length()) {
        val grade = gradesArray.optJSONObject(i) ?: continue
        if (grade.optString("grade") == "null") continue

        if (grade.optString("type") == "final") {
            finalGrade = grade
            continue
        }

        val g = grade.optDouble("grade", Double.NaN)
        if (!g.isNaN() && g != 0.0) {
            sum += g.toFloat()
            count++
        }

        list.add(grade)
    }

    val avg = if (count > 0) sum / count else 0f
    return Triple(list.reversed(), avg, finalGrade)
}
