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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.feldman.lockerapp.ui.theme.AppTheme
import com.feldman.scholix.BottomBarSpacing
import com.feldman.scholix.R
import com.feldman.scholix.TOP_BAR_SPACING
import com.feldman.scholix.api.PlatformStorage
import com.feldman.scholix.ui.components.ChipPicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.checkerframework.common.subtyping.qual.Bottom
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
fun GradesScreen(modifier: Modifier, preloadedCourses: List<JSONObject>) {
    val context = LocalContext.current


    val currentYear = java.time.Year.now().value
    val currentMonth = java.time.LocalDate.now().monthValue

    val initialSemester = if (currentMonth in 9..12 || currentMonth == 1) "A" else "B"
    val initialYear = if (initialSemester == "A") currentYear + 1 else currentYear

    var semesterState by rememberSaveable { mutableStateOf(initialSemester) }
    var yearState by rememberSaveable { mutableIntStateOf(initialYear) }

    var courses by remember { mutableStateOf(preloadedCourses) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var grades by remember { mutableStateOf(listOf<JSONObject>()) }
    var average by remember { mutableIntStateOf(0) }
    var isRefreshing by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var requestId by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    var finalGrade by remember { mutableStateOf<JSONObject?>(null) }

    var errorMessage by remember { mutableStateOf<String?>(null) }


    Log.d("GradesPage", "initial year: $initialYear | initial semester: $initialSemester")

    fun launchGradesRequest(
        context: Context,
        course: JSONObject,
        year: Int = initialYear,
        semester: String = initialSemester,
        onResult: (List<JSONObject>, Int, String?) -> Unit
    ) {
        val currentId = ++requestId
        isLoading = true
        grades = emptyList()
        average = 0

        scope.launch {
            withContext(Dispatchers.IO) {
                val platformIndex = course.optInt("index")
                val platform = PlatformStorage.loadPlatforms(context)[platformIndex]
                val gradesArray = platform.getGrades(
                    course = course.getString("name"),
                    year = year,
                    semester = semester
                )

                var errorMessage: String? = null
                if (gradesArray.length() == 1) {
                    val first = gradesArray.optJSONObject(0)
                    if (first != null && first.has("error")) {
                        errorMessage = when (first.optString("error")) {
                            "server_unreachable" -> "Cannot reach the server.\nCheck your internet connection."
                            "login_failed" -> "Login failed.\nPlease re-login."
                            else -> "Unknown error occurred while loading grades."
                        }
                    }
                }

                // updated to capture finalGradeObj
                val (list, avg, finalGradeObj) =
                    if (errorMessage == null) processGrades(gradesArray)
                    else Triple(emptyList(), 0, null)

                withContext(Dispatchers.Main) {
                    if (currentId == requestId) {
                        course.put("grades", gradesArray)
                        onResult(list, avg, errorMessage)

                        finalGrade = finalGradeObj

                        isLoading = false
                    }
                }
            }
        }
    }


    LaunchedEffect(courses, selectedTab, semesterState, yearState) {
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
        }
    }
    fun refreshCoursesIfEmpty() {
        if (courses.isEmpty()) {
            scope.launch(Dispatchers.IO) {
                val refreshed = PlatformStorage.getCourses(context)
                withContext(Dispatchers.Main) {
                    if (refreshed.isNotEmpty()) {
                        courses = refreshed
                        selectedTab = 0
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        refreshCoursesIfEmpty()
    }

    if (courses.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text("Loading courses…", color = Color.Gray)
        }
        refreshCoursesIfEmpty()
        return
    }
    else{
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
        ) {
            Spacer(Modifier.height(TOP_BAR_SPACING))
            if (courses.size > 1) {
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
                    val selectedCourse = courses.getOrNull(selectedTab)
                    if (selectedCourse != null) {
                        launchGradesRequest(context, selectedCourse) { g, avg, err ->
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
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopCenter
            ) {

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize(),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    item {
                        Spacer(Modifier.height(12.dp))

                        val selectedCourse = courses.getOrNull(selectedTab)
                        val showSemesterPicker = selectedCourse?.optBoolean("semesterPicker", false) == true

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
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = if (finalGrade != null)
                                        RoundedCornerShape(16.dp, 16.dp, 4.dp, 4.dp)
                                    else
                                        RoundedCornerShape(16.dp),
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
                                        Text(
                                            text = stringResource(R.string.average),
                                            style = MaterialTheme.typography.titleLarge.copy(
                                                fontWeight = FontWeight.Medium,
                                                fontSize = 40.sp
                                            ),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )

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

                                if(finalGrade != null){
                                    Spacer(Modifier.height(3.dp))
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = if (finalGrade != null)
                                            RoundedCornerShape(4.dp, 4.dp, 16.dp, 16.dp)
                                        else
                                            RoundedCornerShape(16.dp),
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
                                            Text(
                                                text = stringResource(R.string.final_grade),
                                                style = MaterialTheme.typography.titleLarge.copy(
                                                    fontWeight = FontWeight.Medium,
                                                    fontSize = 40.sp
                                                ),
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = finalGrade!!.optString("grade"),
                                                style = MaterialTheme.typography.bodyLarge.copy(
                                                    fontWeight = FontWeight.Black,
                                                    fontSize = 60.sp,
                                                    fontFamily = FontFamily.SansSerif
                                                ),
                                                color = gradeColor(finalGrade.toString())
                                            )
                                        }
                                    }
                                }


                                Spacer(Modifier.height(12.dp))
                            }
                            else -> {
                                val message = errorMessage ?: stringResource(R.string.no_grades_for_semester)
                                Text(
                                    text = message,
                                    style = MaterialTheme.typography.bodyLarge.copy(
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 18.sp
                                    ),
                                    color = if (errorMessage != null) Color.Red else Color.Gray,
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
                                            text = subject,
                                            style = MaterialTheme.typography.titleLarge.copy(
                                                fontWeight = FontWeight.Medium
                                            ),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = name,
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
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

                            Spacer(Modifier.height(2.dp))
                        }
                    }
                    item {
                        Spacer(Modifier.height(BottomBarSpacing()))
                    }
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
