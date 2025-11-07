package com.feldman.scholix.pages

import android.content.Context
import android.os.Bundle
import android.text.BidiFormatter
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.Indicator
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.feldman.lockerapp.ui.theme.AppTheme
import com.feldman.scholix.R
import com.feldman.scholix.api.Course
import com.feldman.scholix.api.Grade
import com.feldman.scholix.api.GradesUiState
import com.feldman.scholix.api.GradesViewModel
import com.feldman.scholix.api.PlatformStorage
import com.feldman.scholix.page.gradeColor
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
                GradesScreen()
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
fun GradesScreen(
    modifier: Modifier = Modifier,
    viewModel: GradesViewModel = remember { GradesViewModel() }
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    // local tab state — this belongs to UI, not ViewModel
    var selectedTabIndex by rememberSaveable { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        viewModel.loadCourses(context)
    }

    when (uiState) {
        is GradesUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularWavyProgressIndicator()
        }

        is GradesUiState.Error -> {
            val msg = (uiState as GradesUiState.Error).message
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(msg, color = Color.Red)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { viewModel.loadCourses(context) }) { Text("Retry") }
            }
        }

        is GradesUiState.Success -> {
            val data = (uiState as GradesUiState.Success)
            // Ensure tab index doesn’t go out of bounds
            selectedTabIndex = selectedTabIndex.coerceIn(0, data.courses.lastIndex)

            GradesContent(
                modifier = modifier,
                courses = data.courses,
                selectedCourseIndex = selectedTabIndex,
                grades = data.grades,
                average = data.average,
                finalGrade = data.finalGrade,
                isRefreshing = data.isRefreshing,
                onRefresh = { viewModel.refreshGrades(context) },
                onSelectCourse = { idx ->
                    selectedTabIndex = idx        // UI updates instantly
                    viewModel.loadGrades(context, idx) // Tell VM to fetch new data
                },
                onSelectSemester = { year, sem -> viewModel.selectSemester(year, sem) }
            )
        }
    }
}


// A Composable broken out for the display
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GradesContent(
    modifier: Modifier = Modifier,
    courses: List<Course>,
    selectedCourseIndex: Int,
    grades: List<Grade>,
    average: Int,
    finalGrade: Grade?,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onSelectCourse: (Int) -> Unit,
    onSelectSemester: (Int, String) -> Unit
) {
    val currentYear = remember { java.time.Year.now().value }
    val currentMonth = remember { java.time.LocalDate.now().monthValue }
    val initialSemester = if (currentMonth in 9..12 || currentMonth == 1) "A" else "B"

    var semesterState by rememberSaveable { mutableStateOf(initialSemester) }
    var yearState by rememberSaveable { mutableIntStateOf(currentYear) }

    val pullRefreshState = rememberPullToRefreshState()
    val showSemesterPicker = remember(courses, selectedCourseIndex) {
        val course = courses.getOrNull(selectedCourseIndex)
        // Handle either direct field or backend JSON passthrough
        when (course) {
            is Course -> course.semesterPicker
            else -> false
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // ─── Courses tab row ───
        if (courses.size > 1) {
            ScrollableTabRow(
                selectedTabIndex = selectedCourseIndex,
                edgePadding = 0.dp,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            ) {
                courses.forEachIndexed { idx, course ->
                    Tab(
                        selected = selectedCourseIndex == idx,
                        onClick = { onSelectCourse(idx) },
                        text = {
                            Text(
                                text = course.name,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (selectedCourseIndex == idx) FontWeight.Bold else FontWeight.Normal,
                                color = if (selectedCourseIndex == idx)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        if (showSemesterPicker){
            // ─── Semester picker ───
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    ChipPicker(
                        label = "Year",
                        options = listOf(
                            (currentYear - 1).toString(),
                            currentYear.toString(),
                            (currentYear + 1).toString()
                        ),
                        selected = yearState.toString(),
                        onSelectedChange = { newYear ->
                            yearState = newYear.toInt()
                            onSelectSemester(yearState, semesterState)
                        }
                    )
                }

                Box(modifier = Modifier.weight(1f)) {
                    ChipPicker(
                        label = "Semester",
                        options = listOf("A", "B"),
                        selected = semesterState,
                        onSelectedChange = { newSemester ->
                            semesterState = newSemester
                            onSelectSemester(yearState, semesterState)
                        }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

        }

        // ─── Pull-to-refresh container ───
        PullToRefreshBox(
            state = pullRefreshState,
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            indicator = {
                PullToRefreshDefaults.Indicator(
                    modifier = Modifier.align(Alignment.TopCenter),
                    isRefreshing = isRefreshing,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    state = pullRefreshState
                )
            },
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                item {
                    // ─── Average card ───
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 20.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Average",
                                style = MaterialTheme.typography.titleLarge.copy(fontSize = 28.sp),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = average.toString(),
                                style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Black),
                                color = gradeColor(average.toString())
                            )
                        }
                    }

                    // ─── Final grade card ───
                    finalGrade?.let { fg ->
                        Spacer(Modifier.height(8.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 20.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Final Grade",
                                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 28.sp),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = fg.grade.toString(),
                                    style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Black),
                                    color = gradeColor(fg.grade.toString())
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                }

                // ─── Grade rows ───
                itemsIndexed(grades) { index, grade ->
                    val isFirst = index == 0
                    val isLast = index == grades.lastIndex

                    val shape = when {
                        isFirst && isLast -> RoundedCornerShape(16.dp)
                        isFirst -> RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 4.dp)
                        isLast -> RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = 16.dp, bottomEnd = 16.dp)
                        else -> RoundedCornerShape(4.dp)
                    }
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
                                .padding(
                                    start = 12.dp,
                                    top = 12.dp,
                                    end = 24.dp,
                                    bottom = 12.dp
                                ),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = grade.subject,
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontWeight = FontWeight.Medium
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = grade.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            val gradeStr = grade.grade

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
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}



fun processGrades(gradesArray: JSONArray): Triple<List<JSONObject>, Int, JSONObject?> {
    val list = mutableListOf<JSONObject>()
    var finalGrade: JSONObject? = null
    var sum = 0
    var count = 0

    for (i in 0 until gradesArray.length()) {
        val grade = gradesArray.optJSONObject(i) ?: continue
        if (grade.optString("grade") == "null") continue

        if (grade.optString("type") == "final") {
            finalGrade = grade
            continue
        }

        try {
            sum += grade.getInt("grade")
            count++
        } catch (_: Exception) {}

        list.add(grade)
    }

    val avg = if (count > 0) sum / count else 0
    return Triple(list, avg, finalGrade)
}
