package com.feldman.scholix.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.feldman.scholix.api.PlatformStorage
import com.feldman.scholix.api.platforms.OpenAUPlatform
import com.feldman.scholix.ui.components.SubjectIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private data class HomeGrade(
    val course: String,
    val name: String,
    val grade: String,
    val issuedAt: String,
    val subject: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomePage() {
    val context = LocalContext.current
    var courses by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var recentGrades by remember { mutableStateOf<List<HomeGrade>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        loading = true
        val result = withContext(Dispatchers.IO) {
            val loadedCourses = mutableListOf<JSONObject>()
            val loadedGrades = mutableListOf<HomeGrade>()
            PlatformStorage.loadPlatforms(context).forEachIndexed { index, platform ->
                val platformCourses = runCatching {
                    PlatformStorage.getProviderCourses(context, platform)
                }.getOrDefault(emptyList())
                platformCourses.forEach { course ->
                    val copy = JSONObject(course.toString()).apply {
                        put("index", index)
                        put("platformId", platform.id)
                    }
                    loadedCourses += copy
                    if (platform is OpenAUPlatform) return@forEach
                    if (!platform.suportsGrades) return@forEach

                    val courseId = copy.optString("courseKey").ifBlank { copy.optString("name") }
                    val grades = runCatching { platform.getGrades(courseId) }.getOrNull() ?: return@forEach
                    for (gradeIndex in 0 until grades.length()) {
                        val grade = grades.optJSONObject(gradeIndex) ?: continue
                        val value = grade.optString("grade").trim()
                        if (value.isBlank() || value.equals("null", ignoreCase = true)) continue
                        val issuedAt = grade.optString("date").trim()
                        loadedGrades += HomeGrade(
                            course = copy.optString("name"),
                            name = grade.optString("name").ifBlank { "Grade" },
                            grade = value,
                            issuedAt = issuedAt,
                            subject = grade.optString("subject").ifBlank { copy.optString("name") }
                        )
                    }
                }
                if (platform is OpenAUPlatform) {
                    val grades = runCatching { platform.getRecentGrades(context) }
                        .getOrDefault(org.json.JSONArray())
                    for (gradeIndex in 0 until grades.length()) {
                        val grade = grades.optJSONObject(gradeIndex) ?: continue
                        val value = grade.optString("grade").trim()
                        if (value.isBlank() || value.equals("null", ignoreCase = true)) continue
                        val subject = grade.optString("subject").ifBlank { "Open University" }
                        loadedGrades += HomeGrade(
                            course = subject,
                            name = grade.optString("name").ifBlank { "Grade" },
                            grade = value,
                            issuedAt = grade.optString("gradedAt").trim(),
                            subject = subject
                        )
                    }
                }
            }
            loadedCourses.sortedWith(
                compareBy<JSONObject> { it.optInt("courseStatusRank", 1) }
                    .thenBy { it.optString("name") }
            ) to loadedGrades.sortedByDescending { gradeIssuedAt(it.issuedAt) }.take(8)
        }
        courses = result.first
        recentGrades = result.second
        loading = false
    }

    Scaffold(topBar = {
        CenterAlignedTopAppBar(title = { Text("Home") })
    }) { padding ->
        if (loading) {
            GradesLoadingIndicator(Modifier.fillMaxSize().padding(padding))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        shape = MaterialTheme.shapes.extraLarge,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(24.dp)) {
                            Text("Active semester", style = MaterialTheme.typography.labelLarge)
                            Text(
                                "${courses.count { it.optInt("courseStatusRank", 1) == 0 }} courses",
                                style = MaterialTheme.typography.headlineMedium
                            )
                        }
                    }
                }

                if (recentGrades.isNotEmpty()) {
                    item { Text("Latest grades", style = MaterialTheme.typography.titleLarge) }
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(recentGrades) { grade -> LatestGradeCard(grade) }
                        }
                    }
                }

                item { Text("Courses", style = MaterialTheme.typography.titleLarge) }
                if (courses.isEmpty()) {
                    item {
                        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                            Text("No courses", modifier = Modifier.padding(28.dp))
                        }
                    }
                }
                itemsIndexed(
                    courses,
                    key = { index, course ->
                        "${course.optString("platformId")}:${course.optString("courseKey")}:${course.optString("id")}:$index"
                    }
                ) { _, course ->
                    HomeCourseCard(course)
                }
            }
        }
    }
}

@Composable
private fun LatestGradeCard(grade: HomeGrade) {
    val isRtl = isMostlyRtl(grade.course)
    androidx.compose.runtime.CompositionLocalProvider(
        LocalLayoutDirection provides if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
    ) {
        Surface(
            color = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.width(220.dp)
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(grade.grade, style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.height(8.dp))
                Text(grade.name, style = MaterialTheme.typography.titleMedium)
                Text(grade.course, style = MaterialTheme.typography.bodySmall)
                grade.issuedAt.takeIf { it.isNotBlank() }?.let {
                    Text(it.take(10), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun HomeCourseCard(course: JSONObject) {
    val statusRank = course.optInt("courseStatusRank", 1)
    val colors = when (statusRank) {
        0 -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        1 -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurface
    }
    val courseName = course.optString("name")
    val isRtl = isMostlyRtl(courseName)
    androidx.compose.runtime.CompositionLocalProvider(
        LocalLayoutDirection provides if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
    ) {
        Surface(
            color = colors.first,
            contentColor = colors.second,
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SubjectIcon(
                    subject = courseName,
                    containerColor = if (statusRank == 0) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                    iconColor = if (statusRank == 0) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onPrimary,
                    size = 60.dp,
                    iconSize = 34.dp
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(courseName, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        HomeCourseChip(course.optString("id"))
                        course.optString("term").takeIf { it.isNotBlank() }?.let { HomeCourseChip(it) }
                        course.optString("status").takeIf { it.isNotBlank() }?.let { HomeCourseChip(it) }
                        course.optString("finalGrade").takeIf { it.isNotBlank() }?.let { HomeCourseChip("Final $it") }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeCourseChip(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = MaterialTheme.shapes.small
    ) {
        Text(text, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
    }
}

private fun gradeIssuedAt(value: String): Long = runCatching { Instant.parse(value).toEpochMilli() }
    .recoverCatching { LocalDateTime.parse(value).toInstant(ZoneOffset.UTC).toEpochMilli() }
    .recoverCatching { LocalDate.parse(value.take(10)).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() }
    .getOrDefault(Long.MIN_VALUE)

private fun isMostlyRtl(text: String): Boolean {
    var rtl = 0
    var ltr = 0
    text.forEach { char ->
        when (Character.getDirectionality(char)) {
            Character.DIRECTIONALITY_RIGHT_TO_LEFT,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> rtl++
            Character.DIRECTIONALITY_LEFT_TO_RIGHT -> ltr++
        }
    }
    return rtl > ltr
}
