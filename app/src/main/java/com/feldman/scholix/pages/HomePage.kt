package com.feldman.scholix.pages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.feldman.motion.*
import com.feldman.scholix.*
import com.feldman.scholix.api.Platform
import com.feldman.scholix.courses.*
import com.feldman.scholix.ui.components.SettingsTopBar
import com.feldman.scholix.ui.components.SubjectIcon
import com.feldman.scholix.ui.components.courseIconPainter
import org.json.JSONObject

/** Home reads saved courses without starting sign-in or a provider refresh. */
@Composable
fun HomePage(onNavigate: MotionNavigator) {
    val state = LocalAppState.current
    val providers = state.platforms.associateBy { it.id }
    val courses = state.courseCatalog.sortedWith(compareBy<JSONObject> { it.optInt("courseStatusRank", 1) }
        .thenBy { it.optString("name") })
    val recent = state.recentCourses.firstOrNull { route -> courses.any { route.matches(it) } }
    MotionScaffold(scrollable = false, topBar = { SettingsTopBar("Home") }) {
        Item {
            MotionLazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                if (recent != null) {
                    val course = courses.single { recent.matches(it) }
                    item {
                        MotionCard(position = MotionItemPosition.Alone,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                                .clickable(onClickLabel = "Resume course") { state.onCourseResumed(recent) }) {
                            Column(Modifier.padding(16.dp)) {
                                Text("Resume", style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary)
                                Text(course.optString("name"), style = MaterialTheme.typography.titleLarge,
                                    fontFamily = MotionFonts.feldman(weight = 500), maxLines = 2,
                                    overflow = TextOverflow.Ellipsis)
                                Text(recent.section.label, style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                if (courses.isEmpty()) item {
                    Text("No saved courses yet. Connect or refresh your course provider in Assignments.")
                    MotionButton(text = "Connect a provider", onClick = { onNavigate(AppDest.Platforms) })
                }
                courses.groupBy { it.optString("platformId") }.forEach { (providerId, grouped) ->
                    item {
                        Text("${providers[providerId]?.platformDisplayName ?: "Lemida"} \u00b7 ${grouped.size} courses",
                            modifier = Modifier.padding(vertical = 12.dp),
                            style = MaterialTheme.typography.titleMedium)
                    }
                    section(items = grouped, key = { it.courseDestination() }, contentPadding = 0.dp) { course ->
                        HomeCourseRow(course, providers) { state.onScopeSelected(course.courseDestination()) }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeCourseRow(course: JSONObject, providers: Map<String, Platform>, onClick: () -> Unit) {
    val name = course.optString("name")
    CompositionLocalProvider(LocalLayoutDirection provides if (isRtlText(name)) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        Row(Modifier.fillMaxWidth().clickable(onClickLabel = "Open course", onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            SubjectIcon(subject = name, assignment = "course", painter = courseIconPainter(course, providers))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleLarge,
                    fontFamily = MotionFonts.feldman(weight = 500), fontWeight = FontWeight.Medium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                val metadata = listOf(course.optString("term"), course.optString("status")).filter { it.isNotBlank() }
                if (metadata.isNotEmpty()) Text(metadata.joinToString(" \u00b7 "),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            course.optString("finalGrade").takeIf { it.isNotBlank() && it != "-" }?.let { grade ->
                Spacer(Modifier.width(8.dp))
                Text(grade, style = MaterialTheme.typography.headlineLarge,
                    fontFamily = MotionFonts.feldman(weight = 700), color = gradeColor(grade), maxLines = 1)
            }
        }
    }
}
