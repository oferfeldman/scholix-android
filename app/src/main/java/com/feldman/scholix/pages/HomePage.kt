package com.feldman.scholix.pages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.feldman.motion.MotionCard
import com.feldman.motion.MotionLazyColumn
import com.feldman.motion.MotionNavigator
import com.feldman.motion.MotionScaffold
import com.feldman.scholix.*
import com.feldman.scholix.api.platforms.GoogleClassroomPlatform
import com.feldman.scholix.api.platforms.OpenAUPlatform
import com.feldman.scholix.courses.*
import com.feldman.scholix.ui.components.SettingsTopBar
import org.json.JSONObject

/** Home reads already saved provider data. Opening it must not start sign-in or an SMS challenge. */
@Composable
fun HomePage(onNavigate: MotionNavigator) {
    val state = LocalAppState.current
    val providers = state.platforms.associateBy { it.id }
    val courses = state.courseCatalog.sortedWith(compareBy<JSONObject> { it.optInt("courseStatusRank", 1) }
        .thenBy { it.optString("name") })
    val schoolProviders = state.platforms.filter { it.suportsGrades && it !is OpenAUPlatform && it !is GoogleClassroomPlatform }
    val messageState = state.messagesViewModel
    val unread = if (messageState?.folder == com.feldman.scholix.api.platforms.MailboxFolder.INBOX)
        messageState.messages.count { it.has("hasRead") && it.optInt("hasRead") == 0 } else 0
    MotionScaffold(topBar = { SettingsTopBar("Home") }) {
        Item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofLocalizedDate(
                    java.time.format.FormatStyle.MEDIUM)), modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium)
                if (state.platforms.any { it.supportsSchedule }) TextButton(onClick = { state.onScopeSelected(AppDest.Schedule) }) {
                    Text("Schedule")
                }
                if (unread > 0) TextButton(onClick = { state.onScopeSelected(AppDest.Messages) }) { Text("$unread unread") }
            }
        }
        Title("Courses")
        if (courses.isEmpty()) Item {
            Column {
                Text("No saved courses yet. Open Assignments to connect or refresh your course provider.")
                TextButton(onClick = { state.onScopeSelected(AppDest.Homework) }) { Text("Open assignments") }
            }
        }
        courses.forEach { course ->
            val provider = providers[course.optString("platformId")]
            Item {
                key(course.courseDestination()) {
                    HomeCourseCard(course, provider?.platformDisplayName ?: "Lemida",
                        courseSections(provider, course), onNavigate = { state.onScopeSelected(it) })
                }
            }
        }
        if (schoolProviders.isNotEmpty()) {
            Title("School providers")
            schoolProviders.forEach { provider ->
                val saved = state.preloadedCourses.filter { it.optString("platformId") == provider.id }
                val grades = saved.flatMap { course ->
                    val values = course.optJSONArray("grades")
                    (0 until (values?.length() ?: 0)).mapNotNull { values?.optJSONObject(it) }
                }.filter { it.optString("grade").toDoubleOrNull() != null }
                val scored = grades.filter { it.optString("type") != "final" &&
                    (it.optDouble("grade") != 0.0 || it.has("points")) }
                val average = scored.map { it.optDouble("grade") }.takeIf { it.isNotEmpty() }?.average()
                val latest = scored.mapNotNull { grade -> homeGradeDate(grade.optString("date")
                    .ifBlank { grade.optString("gradedAt") })?.let { it to grade } }.maxByOrNull { it.first }?.second
                Section {
                    PageItem(title = provider.platformDisplayName.ifBlank { provider.getName() },
                        description = buildString {
                            if (average != null) append("Saved average ${"%.1f".format(average)}")
                            if (latest != null) { if (isNotEmpty()) append(" \u00b7 "); append("Latest ${latest.optString("grade")}") }
                            if (isEmpty()) append("Tap to load grades")
                        }, onClick = { state.onScopeSelected(ProviderGrades(provider.id)) })
                }
            }
        }
        BottomBarSpacer(minMargin = 24.dp)
    }
}

@Composable
private fun HomeCourseCard(course: JSONObject, provider: String, sections: List<CourseSectionKind>, onNavigate: (CourseSection) -> Unit) {
    val destination = course.courseDestination()
    MotionCard(position = com.feldman.motion.MotionItemPosition.Alone,
        modifier = Modifier.fillMaxWidth().clickable { onNavigate(destination) }) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(course.optString("name"), style = MaterialTheme.typography.titleLarge)
            Text(listOf(provider, course.optString("term"), course.optString("status")).filter { it.isNotBlank() }
                .joinToString(" \u00b7 "), style = MaterialTheme.typography.bodyMedium)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(sections.filter { it in listOf(CourseSectionKind.ASSIGNMENTS, CourseSectionKind.UNITS,
                    CourseSectionKind.RECORDINGS, CourseSectionKind.FILES) }.take(3)) { section ->
                    AssistChip(onClick = { onNavigate(destination.copy(section = section)) },
                        label = { Text(section.label, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                }
            }
        }
    }
}

private fun homeGradeDate(value: String): java.time.LocalDate? {
    for (format in listOf(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE,
        java.time.format.DateTimeFormatter.ofPattern("d/M/yyyy"),
        java.time.format.DateTimeFormatter.ofPattern("d.M.yyyy"))) {
        runCatching { java.time.LocalDate.parse(value.substringBefore('T'), format) }.getOrNull()?.let { return it }
    }
    return null
}
