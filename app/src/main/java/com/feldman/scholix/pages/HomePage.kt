package com.feldman.scholix.pages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.painterResource
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
import com.feldman.scholix.api.platforms.LockerimPlatform
import com.feldman.scholix.courses.*
import com.feldman.scholix.ui.components.SettingsTopBar
import com.feldman.scholix.ui.components.PickerBar
import com.feldman.scholix.ui.components.ProviderIcon
import com.feldman.scholix.ui.components.providerLabel
import com.feldman.scholix.ui.components.providerDescription
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
    var selectedProviderId by rememberSaveable {
        mutableStateOf(if (courses.isEmpty()) state.platforms.firstOrNull()?.id.orEmpty() else "")
    }
    var providerMenuExpanded by remember { mutableStateOf(false) }
    val providerOptions = listOf("") + state.platforms.map { it.id }
    val providerIndex = providerOptions.indexOf(selectedProviderId).coerceAtLeast(0)
    val selectedProvider = providers[providerOptions[providerIndex]]
    val visibleCourses = courses.filter { selectedProvider == null || it.optString("platformId") == selectedProvider.id }
    MotionScaffold(scrollable = false, topBar = {
        SettingsTopBar("Home", actions = {
            IconButton(onClick = { onNavigate(AppDest.Settings) }) {
                Icon(painterResource(AppDest.Settings.outlineIcon), contentDescription = "Settings")
            }
        })
    }) {
        if (state.platforms.isNotEmpty()) Item {
            PickerBar(
                options = providerOptions, selectedIndex = providerIndex,
                onSelected = { selectedProviderId = providerOptions[it.coerceIn(0, providerOptions.lastIndex)] },
                expanded = providerMenuExpanded, onExpandedChange = { providerMenuExpanded = it },
                optionLabel = { providers[it]?.providerLabel() ?: "All courses" },
                optionDescription = { providers[it]?.providerDescription().orEmpty() },
                optionKey = { it },
                optionIcon = { id -> providers[id]?.let { ProviderIcon(it) }
                    ?: SubjectIcon(subject = "Courses", assignment = "course", size = 40.dp, iconSize = 22.dp) },
                contentDescription = "Choose provider", previousDescription = "Previous provider",
                nextDescription = "Next provider"
            )
        }
        if (visibleCourses.isEmpty() && selectedProvider != null) Section {
            if (selectedProvider is LockerimPlatform) PageItem(title = "Lockerim",
                onClick = { onNavigate(AppDest.Lockerim) })
            if (selectedProvider.supportsSchedule) PageItem(title = "Schedule",
                onClick = { onNavigate(AppDest.Schedule) })
            if (selectedProvider.supportsAttendance) PageItem(title = "Attendance",
                onClick = { onNavigate(AppDest.Attendance) })
            if (selectedProvider.suportsGrades) PageItem(title = "Grades",
                onClick = { state.onScopeSelected(ProviderGrades(selectedProvider.id)) })
            PageItem(title = "Messages", onClick = { onNavigate(AppDest.Messages) })
        }
        Item {
            MotionLazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                if (courses.isEmpty() && selectedProvider == null) item {
                    Text("No saved courses yet. Connect or refresh your course provider in Assignments.")
                    MotionButton(text = "Connect a provider", onClick = { onNavigate(AppDest.Platforms) })
                }
                section(items = visibleCourses, key = { it.courseDestination() }, contentPadding = 0.dp) { course ->
                    HomeCourseRow(course, providers) { state.onScopeSelected(course.courseDestination()) }
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
                val metadata = listOf(providers[course.optString("platformId")]?.providerLabel() ?: "Lemida",
                    course.optString("term"), course.optString("status")).filter { it.isNotBlank() }
                if (metadata.isNotEmpty()) Text(metadata.joinToString(" \u00b7 "),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
