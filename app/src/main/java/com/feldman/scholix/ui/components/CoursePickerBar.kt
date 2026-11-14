package com.feldman.scholix.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.feldman.motion.MotionDropdownFolder
import com.feldman.motion.MotionDropdownAnimation
import com.feldman.motion.MotionSymbols
import com.feldman.motion.rememberSymbolPainter
import com.feldman.scholix.R
import com.feldman.scholix.api.Platform
import com.feldman.scholix.pages.isRtlText
import org.json.JSONObject

internal fun courseSupportingText(course: JSONObject): String =
    course.optString("status").ifBlank {
        course.optString("rawTerm").ifBlank {
            course.optString("term").ifBlank {
                course.optInt("year").takeIf { it > 0 }?.toString().orEmpty()
            }
        }
    }

private sealed interface CourseDropdownEntry {
    data class Course(val courseIndex: Int) : CourseDropdownEntry
    data class Folder(val platformId: String, val courseIndices: List<Int>) : CourseDropdownEntry
}

private fun buildCourseDropdownEntries(courses: List<JSONObject>): List<CourseDropdownEntry> {
    val byProvider = courses.indices.groupBy { index -> courses[index].optString("platformId") }
    val emittedFolders = mutableSetOf<String>()
    return buildList {
        courses.indices.forEach { index ->
            val platformId = courses[index].optString("platformId")
            val providerCourses = byProvider[platformId].orEmpty()
            if (platformId.isNotBlank() && providerCourses.size >= 2) {
                if (emittedFolders.add(platformId)) {
                    add(CourseDropdownEntry.Folder(platformId, providerCourses))
                }
            } else {
                add(CourseDropdownEntry.Course(index))
            }
        }
    }
}

@Composable
private fun CourseDropdownMenuRow(
    title: String,
    description: String,
    selected: Boolean,
    leadingContent: @Composable () -> Unit,
    trailingSymbol: String? = null,
    onClick: () -> Unit,
) {
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }
    CompositionLocalProvider(
        LocalLayoutDirection provides if (isRtlText(title)) LayoutDirection.Rtl else LayoutDirection.Ltr
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(
                    if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                )
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leadingContent()
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = contentColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (description.isNotBlank()) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor.copy(alpha = 0.74f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            when {
                selected && trailingSymbol == null -> Icon(
                    painter = rememberSymbolPainter(MotionSymbols.ic_check),
                    contentDescription = "Selected",
                    tint = contentColor,
                )
                trailingSymbol != null -> Icon(
                    painter = rememberSymbolPainter(trailingSymbol),
                    contentDescription = null,
                    tint = contentColor,
                )
            }
        }
    }
}

/**
 * The icon for a course: its own subject icon when the subject is recognisable,
 * otherwise the logo of the provider it came from -- more informative than the
 * stock school glyph every unmatched course used to share.
 */
@Composable
internal fun courseIconPainter(
    course: JSONObject,
    providersById: Map<String, Platform>
): Painter? {
    if (hasSubjectIcon(course.optString("name"), "course")) return null
    val provider = providersById[course.optString("platformId")] ?: return null
    return providerPainter(provider)
}

@Composable
internal fun CoursePickerBar(
    courses: List<JSONObject>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    providersById: Map<String, Platform>,
    modifier: Modifier = Modifier
) {
    val courseLabel = stringResource(R.string.course)
    val menuEntries = remember(courses) { buildCourseDropdownEntries(courses) }
    val hasFolders = remember(menuEntries) { menuEntries.any { it is CourseDropdownEntry.Folder } }
    var openFolderPlatformId by remember { mutableStateOf<String?>(null) }

    val courseDisplayLabel: (JSONObject) -> String = { course ->
        val name = course.optString("name").ifBlank { courseLabel }
        val year = course.optInt("year")
        val multipleYears = courses.filter { it.optString("name") == course.optString("name") }
            .map { it.optInt("year") }.distinct().size > 1
        val term = course.optString("term")
        val multipleTerms = courses.filter { it.optString("name") == course.optString("name") }
            .map { it.optString("term") }.distinct().size > 1
        when {
            term.isNotBlank() && multipleTerms -> "$name · $term"
            year > 0 && multipleYears -> "$name · $year"
            else -> name
        }
    }

    val visibleMenuEntries = if (openFolderPlatformId == null) menuEntries else {
        menuEntries.filterIsInstance<CourseDropdownEntry.Folder>()
            .firstOrNull { it.platformId == openFolderPlatformId }?.courseIndices
            .orEmpty().map { CourseDropdownEntry.Course(it) }
    }
    val visibleMenuLabels = if (!hasFolders) courses.map(courseDisplayLabel) else visibleMenuEntries.map { entry ->
        when (entry) {
            is CourseDropdownEntry.Course -> courseDisplayLabel(courses[entry.courseIndex])
            is CourseDropdownEntry.Folder -> providersById[entry.platformId]?.getName()
                ?: courses[entry.courseIndices.first()].optString("platformName")
        }
    } + listOfNotNull(openFolderPlatformId?.let { providersById[it]?.getName() })
    val visibleMenuDescriptions = if (!hasFolders) courses.map(::courseSupportingText) else visibleMenuEntries.map { entry ->
        when (entry) {
            is CourseDropdownEntry.Course -> courseSupportingText(courses[entry.courseIndex])
            is CourseDropdownEntry.Folder -> "${entry.courseIndices.size} courses"
        }
    }

    PickerBar(
        options = courses,
        selectedIndex = selectedIndex,
        onSelected = onSelected,
        expanded = expanded,
        onExpandedChange = { opening ->
            if (opening) openFolderPlatformId = menuEntries.filterIsInstance<CourseDropdownEntry.Folder>()
                .firstOrNull { selectedIndex in it.courseIndices }?.platformId
            onExpandedChange(opening)
        },
        optionLabel = courseDisplayLabel,
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
        menuContainerColor = MaterialTheme.colorScheme.secondaryContainer,
        menuAnimation = MotionDropdownAnimation.Morph,
        showOptionsInMenu = !hasFolders,
        menuLabels = visibleMenuLabels,
        menuDescriptions = visibleMenuDescriptions,
        menuHeader = if (!hasFolders) null else {
            {
                MotionDropdownFolder(
                    inFolder = openFolderPlatformId != null,
                    modifier = Modifier.fillMaxWidth(),
                ) { inFolder ->
                    val activeFolderId = openFolderPlatformId
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (inFolder && activeFolderId != null) {
                            val folder = menuEntries.filterIsInstance<CourseDropdownEntry.Folder>()
                                .firstOrNull { it.platformId == activeFolderId }
                            val provider = providersById[activeFolderId]
                            val providerName = provider?.getName()?.takeIf { it.isNotBlank() }
                                ?: courses.firstOrNull { it.optString("platformId") == activeFolderId }
                                    ?.optString("platformName").orEmpty().ifBlank { "Courses" }

                            CourseDropdownMenuRow(
                                title = providerName,
                                description = folder?.courseIndices?.size?.let { "$it courses" }.orEmpty(),
                                selected = false,
                                leadingContent = {
                                    SubjectIcon(
                                        subject = providerName,
                                        assignment = "folder",
                                        painter = provider?.let { providerPainter(it) }
                                            ?: rememberSymbolPainter(MotionSymbols.ic_folder_open),
                                        size = 40.dp,
                                        iconSize = 22.dp,
                                    )
                                },
                                trailingSymbol = MotionSymbols.ic_chevron_left,
                                onClick = { openFolderPlatformId = null },
                            )

                            folder?.courseIndices.orEmpty().forEach { courseIndex ->
                                val course = courses[courseIndex]
                                CourseDropdownMenuRow(
                                    title = courseDisplayLabel(course),
                                    description = courseSupportingText(course),
                                    selected = courseIndex == selectedIndex,
                                    leadingContent = {
                                        SubjectIcon(
                                            subject = course.optString("name").ifBlank { courseLabel },
                                            assignment = "course",
                                            painter = courseIconPainter(course, providersById),
                                            size = 40.dp,
                                            iconSize = 22.dp,
                                        )
                                    },
                                    onClick = {
                                        onSelected(courseIndex)
                                        onExpandedChange(false)
                                    },
                                )
                            }
                        } else {
                            menuEntries.forEach { entry ->
                                when (entry) {
                                    is CourseDropdownEntry.Course -> {
                                        val course = courses[entry.courseIndex]
                                        CourseDropdownMenuRow(
                                            title = courseDisplayLabel(course),
                                            description = courseSupportingText(course),
                                            selected = entry.courseIndex == selectedIndex,
                                            leadingContent = {
                                                SubjectIcon(
                                                    subject = course.optString("name").ifBlank { courseLabel },
                                                    assignment = "course",
                                                    painter = courseIconPainter(course, providersById),
                                                    size = 40.dp,
                                                    iconSize = 22.dp,
                                                )
                                            },
                                            onClick = {
                                                onSelected(entry.courseIndex)
                                                onExpandedChange(false)
                                            },
                                        )
                                    }

                                    is CourseDropdownEntry.Folder -> {
                                        val provider = providersById[entry.platformId]
                                        val providerName = provider?.getName()?.takeIf { it.isNotBlank() }
                                            ?: courses[entry.courseIndices.first()].optString("platformName").ifBlank { "Courses" }
                                        val selectedIsInside = courses.getOrNull(selectedIndex)
                                            ?.optString("platformId") == entry.platformId
                                        CourseDropdownMenuRow(
                                            title = providerName,
                                            description = listOfNotNull(provider?.providerDescription()?.takeIf { it.isNotBlank() }, "${entry.courseIndices.size} courses").joinToString(" · "),
                                            selected = selectedIsInside,
                                            leadingContent = {
                                                SubjectIcon(
                                                    subject = providerName,
                                                    assignment = "folder",
                                                    painter = provider?.let { providerPainter(it) }
                                                        ?: rememberSymbolPainter(MotionSymbols.ic_folder),
                                                    size = 40.dp,
                                                    iconSize = 22.dp,
                                                )
                                            },
                                            trailingSymbol = MotionSymbols.ic_chevron_right,
                                            onClick = { openFolderPlatformId = entry.platformId },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        modifier = modifier
    )
}
