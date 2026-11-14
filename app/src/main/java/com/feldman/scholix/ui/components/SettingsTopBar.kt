package com.feldman.scholix.ui.components

import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.feldman.motion.MotionBackButton
import com.feldman.scholix.AppDest
import com.feldman.scholix.CourseSection
import com.feldman.scholix.LocalAppState
import com.feldman.scholix.courses.courseDestination
import com.feldman.scholix.courses.courseScope
import com.feldman.scholix.courses.courseSections
import com.feldman.scholix.courses.matches
import com.feldman.scholix.courses.CourseSectionKind

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    chromeColor: Color = colorScheme.surfaceContainer,
    chromeContentColor: Color = colorScheme.onSurface,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val state = if (com.feldman.scholix.LocalScopeControl.current != null) LocalAppState.current else null
    val courseScope = state?.activePage?.courseScope()?.takeIf { title != "Home" }
    val courses = state?.courseCatalog.orEmpty().filter { it.optString("platformId") == courseScope?.providerId }
    val selectedCourseIndex = courses.indexOfFirst { courseScope?.matches(it) == true }
    var courseMenuExpanded by remember(courseScope) { mutableStateOf(false) }
    if (com.feldman.scholix.LocalProviderHeaderVisible.current && courseScope != null) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, content = actions)
        return
    }
    if (selectedCourseIndex >= 0 && state?.activePage is CourseSection) {
        Row(
            modifier = Modifier.fillMaxWidth().background(chromeColor).statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CoursePickerBar(
                courses = courses,
                selectedIndex = selectedCourseIndex,
                onSelected = { index ->
                    val course = courses[index.coerceIn(0, courses.lastIndex)]
                    val provider = state.platforms.firstOrNull { it.id == course.optString("platformId") }
                    val section = courseScope?.section?.takeIf { it in courseSections(provider, course) }
                        ?: CourseSectionKind.OVERVIEW
                    state.onScopeSelected(course.courseDestination(section))
                },
                expanded = courseMenuExpanded,
                onExpandedChange = { courseMenuExpanded = it },
                providersById = state.platforms.associateBy { it.id },
                modifier = Modifier.weight(1f)
            )
            actions()
        }
        return
    }
    CenterAlignedTopAppBar(
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                com.feldman.scholix.LocalScopeControl.current?.label?.takeIf { courseScope == null && title != "Home" && it != title }?.let { course ->
                    Text(course, style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        },
        navigationIcon = {
            if (onBack != null) {
                MotionBackButton(
                    onClick = onBack,
                    containerColor = colorScheme.surface,
                    contentColor = colorScheme.onSurface
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = chromeColor, titleContentColor = chromeContentColor),
        actions = {
            if (courseScope != null && state != null && state.activePage !is CourseSection) {
                IconButton(onClick = { state.onScopeSelected(AppDest.Home) }) {
                    Icon(painterResource(AppDest.Home.outlineIcon), contentDescription = "Home")
                }
            }
            actions()
        }
    )
}
