package com.feldman.scholix.pages

import android.content.Context
import android.os.Bundle
import android.text.BidiFormatter
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.feldman.lockerapp.ui.theme.AppTheme
import com.feldman.scholix.R
import com.feldman.scholix.api.PlatformStorage
import com.feldman.scholix.ui.components.ActionRow
import com.feldman.scholix.ui.components.SegmentedOption
import com.feldman.scholix.ui.components.Title
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

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
fun gradeColor(gradeStr: String): Color {
    return try {
        val grade = gradeStr.toInt()
        when {
            grade >= 90 -> Color(0xFF3AC941) // Dark green
            grade >= 75 -> Color(0xFF80CB24) // Green
            grade >= 60 -> Color(0xFFFBC02D) // Yellow
            grade >= 50 -> Color(0xFFF57C00) // Orange
            else -> Color(0xFFDA3124) // Red
        }
    } catch (_: Exception) {
        Color(0xFF2196F3)
    }
}


@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GradesScreen(modifier: Modifier, preloadedCourses: List<JSONObject>) {
    val context = LocalContext.current

    var courses by remember { mutableStateOf(preloadedCourses) }
    var selectedTab by remember { mutableStateOf(0) }
    var grades by remember { mutableStateOf(listOf<JSONObject>()) }
    var average by remember { mutableStateOf(0) }
    var isRefreshing by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var requestId by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()


    fun launchGradesRequest(
        context: Context,
        course: JSONObject,
        year: Int? = null,
        semester: String? = null,
        onResult: (List<JSONObject>, Int) -> Unit
    ) {
        val currentId = ++requestId // bump id
        isLoading = true
        grades = emptyList()
        average = 0

        scope.launch {
            withContext(Dispatchers.IO) {
                val platformIndex = course.optInt("index")
                val platform = PlatformStorage.loadPlatforms(context)[platformIndex]

                val gradesArray = if (year != null && semester != null) {
                    platform.getGrades(course.getString("name"), year, semester)
                } else {
                    platform.getGrades(course.getString("name"))
                }

                val (list, avg) = processGrades(gradesArray)

                withContext(Dispatchers.Main) {
                    if (currentId == requestId) { // ✅ only update if still latest request
                        course.put("grades", gradesArray)
                        onResult(list, avg)
                        isLoading = false
                    }
                }
            }
        }
    }
    LaunchedEffect(selectedTab, courses) {
        if (courses.isNotEmpty()) {
            val selectedCourse = courses[selectedTab]
            launchGradesRequest(context, selectedCourse) { g, avg ->
                grades = g
                average = avg
            }
        }
    }
    LaunchedEffect(Unit) {
        if (courses.isNotEmpty()) {
            val selectedCourse = courses[selectedTab]
            withContext(Dispatchers.IO) {
                val platformIndex = selectedCourse.optInt("index")
                val platform = PlatformStorage.loadPlatforms(context)[platformIndex]
                val gradesArray = platform.getGrades(
                    selectedCourse.getString("name")
                )
                selectedCourse.put("grades", gradesArray)
                val (list, avg) = processGrades(gradesArray)
                grades = list
                average = avg
            }
        }
    }
    LaunchedEffect(preloadedCourses) {
        val newIndex = selectedTab.coerceIn(0, preloadedCourses.lastIndex.coerceAtLeast(0))
        courses = preloadedCourses
        selectedTab = newIndex

        if (preloadedCourses.isNotEmpty()) {
            val selectedCourse = preloadedCourses[newIndex]
            launchGradesRequest(context, selectedCourse) { g, avg ->
                grades = g
                average = avg
            }
        } else {
            grades = emptyList()
            average = 0
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 16.dp, top = 48.dp, end = 16.dp, bottom = 0.dp)
    ) {
        Title(stringResource(R.string.grades))

        if (courses.isNotEmpty()) {
            PrimaryScrollableTabRow(
                selectedTabIndex = selectedTab,
                edgePadding = 0.dp,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                divider = {},
                indicator = {
                    TabRowDefaults.PrimaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(selectedTab),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            ) {
                courses.forEachIndexed { index, course ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = {
                            Text(
                                text = course.optString("name", stringResource(R.string.course)),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal,
                                color = if (selectedTab == index)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    )
                }
            }

        }

        val pullRefreshState = rememberPullToRefreshState()

        PullToRefreshBox(
            state = pullRefreshState,
            isRefreshing = isRefreshing,
            onRefresh = {
                if (courses.isNotEmpty()) {
                    launchGradesRequest(context, courses[selectedTab]) { g, avg ->
                        grades = g
                        average = avg
                    }
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
        ) {
            LazyColumn(
                modifier = modifier
                    .fillMaxSize()

            ) {
                item {
                    Spacer(Modifier.height(12.dp))

                    val selectedCourse = courses[selectedTab]
                    if (selectedCourse.optBoolean("semesterPicker", false)) {
                        val currentYear = java.time.Year.now().value
                        val currentMonth = java.time.LocalDate.now().monthValue

                        val initialSemester = if (currentMonth in 9..12 || currentMonth == 1) "A" else "B"
                        val initialYear = if (initialSemester == "A") currentYear + 1 else currentYear

                        var semesterState by rememberSaveable { mutableStateOf(initialSemester) }
                        var yearState by rememberSaveable { mutableStateOf(initialYear) }

                        ActionRow {
                            addSegmentedToggleGroup(
                                state = remember { mutableStateOf(yearState.toString()) },
                                SegmentedOption((currentYear - 1).toString(), (currentYear - 1).toString(), textColor = MaterialTheme.colorScheme.onSurfaceVariant, selectedTextColor = MaterialTheme.colorScheme.onSurface),
                                SegmentedOption(currentYear.toString(), currentYear.toString(), textColor = MaterialTheme.colorScheme.onSurfaceVariant, selectedTextColor = MaterialTheme.colorScheme.onSurface),
                                SegmentedOption((currentYear + 1).toString(), (currentYear + 1).toString(), textColor = MaterialTheme.colorScheme.onSurfaceVariant, selectedTextColor = MaterialTheme.colorScheme.onSurface),
                                onSelectedChange = { newYear ->
                                    yearState = newYear.toInt()
                                    val selectedCourse = courses[selectedTab]
                                    launchGradesRequest(context, selectedCourse, yearState, semesterState.lowercase()) { g, avg ->
                                        grades = g
                                        average = avg
                                    }
                                }
                            )
                        }
                        Spacer(Modifier.height(12.dp))

                        ActionRow {
                            addSegmentedToggleGroup(
                                state = remember { mutableStateOf(semesterState) },
                                SegmentedOption("A", "A", selectedBackgroundColor = MaterialTheme.colorScheme.tertiary, textColor = MaterialTheme.colorScheme.onSurfaceVariant, selectedTextColor = MaterialTheme.colorScheme.onSurface),
                                SegmentedOption("B", "B", selectedBackgroundColor = MaterialTheme.colorScheme.secondary, textColor = MaterialTheme.colorScheme.onSurfaceVariant, selectedTextColor = MaterialTheme.colorScheme.onSurface),
                                onSelectedChange = { newSemester ->
                                    semesterState = newSemester
                                    val selectedCourse = courses[selectedTab]

                                    launchGradesRequest(
                                        context,
                                        selectedCourse,
                                        year = yearState,
                                        semester = semesterState.lowercase()
                                    ) { g, avg ->
                                        grades = g
                                        average = avg
                                    }
                                }
                            )
                        }

                    }
                    Spacer(Modifier.height(36.dp))

                    when {

                        isLoading -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularWavyProgressIndicator()
                            }
                        }
                        grades.isNotEmpty() -> {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 20.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier.fillMaxHeight(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = stringResource(R.string.average),
                                            fontSize = 60.sp
//                                            style = MaterialTheme.typography.bodyLarge.copy(
//                                                fontWeight = FontWeight.Medium,
//                                                fontSize = 50.sp,
//                                                fontFamily = FontFamily.SansSerif
//                                            )
                                        )
                                    }
                                    Box(
                                        modifier = Modifier.fillMaxHeight(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = average.toString(),
                                            style = MaterialTheme.typography.bodyLarge.copy(
                                                fontWeight = FontWeight.Black,
                                                fontSize = 60.sp,
                                                fontFamily = FontFamily.SansSerif
                                            ),
                                            color = gradeColor(average.toString())
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                        }
                        else -> {
                            Text(
                                text = stringResource(R.string.no_grades_for_semester),
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 18.sp
                                ),
                                color = Color.Gray,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                }
                if (!isLoading){
                    itemsIndexed(grades) { index, grade ->
                        val isFirst = index == 0
                        val isLast = index == grades.lastIndex

                        val shape = when {
                            isFirst && isLast -> RoundedCornerShape(16.dp)
                            isFirst -> RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 4.dp)
                            isLast -> RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = 16.dp, bottomEnd = 16.dp)
                            else -> RoundedCornerShape(4.dp)
                        }

                        val bidi = BidiFormatter.getInstance()
                        val subject = bidi.unicodeWrap(grade.optString("subject", "Unknown"))
                        val name = bidi.unicodeWrap(grade.optString("name", ""))

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = shape,
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            ),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 12.dp, top = 12.dp, end = 24.dp, bottom = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        text = subject,
                                        style = MaterialTheme.typography.titleLarge.copy(
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 22.sp
                                        )
                                    )
                                    Text(
                                        text = name,
                                        style = MaterialTheme.typography.bodyLarge.copy(
                                            fontWeight = FontWeight.Medium,
                                            fontSize = 18.sp
                                        )
                                    )
                                }

                                val gradeStr = grade.optString("grade", "-")

                                Text(
                                    text = gradeStr,
                                    style = MaterialTheme.typography.bodyLarge.copy(
                                        fontWeight = FontWeight.Black,
                                        fontFamily = FontFamily.SansSerif,
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

                        Spacer(Modifier.height(3.dp))
                    }
                }

            }
        }

    }
}


fun processGrades(gradesArray: JSONArray): Pair<List<JSONObject>, Int> {
    val list = mutableListOf<JSONObject>()
    var sum = 0
    var count = 0
    for (i in 0 until gradesArray.length()) {
        val grade = gradesArray.optJSONObject(i)
        if (grade != null && grade.optString("grade") != "null") {
            try {
                sum += grade.getInt("grade")
                count++
            } catch (_: Exception) {}
            list.add(grade)
        }
    }
    val avg = if (count > 0) sum / count else 0
    return list to avg
}