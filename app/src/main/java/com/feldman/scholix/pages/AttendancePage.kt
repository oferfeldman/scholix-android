package com.feldman.scholix.pages

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import com.feldman.motion.MotionLazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.feldman.scholix.R
import androidx.compose.runtime.mutableIntStateOf
import com.feldman.scholix.TopBarSpacing
import com.feldman.scholix.AppDest
import com.feldman.scholix.api.Platform
import com.feldman.scholix.api.PlatformStorage
import com.feldman.scholix.attendance.*
import com.feldman.scholix.ui.components.ProviderPickerBar
import com.feldman.scholix.ui.components.ChipPicker
import com.feldman.scholix.ui.components.SubjectIcon
import com.feldman.motion.MotionSymbols
import com.feldman.motion.MotionItemPosition
import com.feldman.motion.MotionCard
import com.feldman.motion.MotionFonts
import com.feldman.motion.MotionNavigator
import com.feldman.motion.rememberSymbolPainter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun FiltersGrid(
    viewMode: String,
    onViewModeChange: (String) -> Unit,
    portionsEnabled: Boolean,
    sortBy: String,
    onSortChange: (String) -> Unit,
    year: Int,
    onYearChange: (Int) -> Unit,
    semester: String,
    onSemesterChange: (String) -> Unit,
    currentYear: Int,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(modifier = Modifier.weight(1f)) {
                ChipPicker(
                    label = stringResource(R.string.year),
                    options = listOf(
                        (currentYear - 1).toString(),
                        currentYear.toString(),
                        (currentYear + 1).toString()
                    ),
                    selected = year.toString(),
                    optionIcon = { rememberSymbolPainter(MotionSymbols.ic_calendar_month) },
                    onSelectedChange = { onYearChange(it.toInt()) }
                )
            }
            Box(modifier = Modifier.weight(1f)) {
                ChipPicker(
                    label = stringResource(R.string.semester),
                    options = listOf("A", "B"),
                    selected = semester,
                    optionIcon = { option ->
                        rememberSymbolPainter(
                            if (option == "A") MotionSymbols.ic_looks_one else MotionSymbols.ic_looks_two
                        )
                    },
                    onSelectedChange = onSemesterChange
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (portionsEnabled) {
                val eventsLabel = stringResource(R.string.attendance_view_events)
                val portionsLabel = stringResource(R.string.attendance_view_portions)
                Box(modifier = Modifier.weight(1f)) {
                    ChipPicker(
                        label = stringResource(R.string.view_mode),
                        options = listOf(eventsLabel, portionsLabel),
                        selected = if (viewMode == "portions") portionsLabel else eventsLabel,
                        optionIcon = { option ->
                            rememberSymbolPainter(
                                if (option == portionsLabel) MotionSymbols.ic_calculate else MotionSymbols.ic_list
                            )
                        },
                        onSelectedChange = { option ->
                            onViewModeChange(if (option == portionsLabel) "portions" else "events")
                        }
                    )
                }
            }
            if (!portionsEnabled || viewMode == "events") {
                Box(modifier = Modifier.weight(1f)) {
                    ChipPicker(
                        label = stringResource(R.string.sort_by),
                        options = listOf("Type", "Date", "Subject"),
                        selected = sortBy,
                        optionIcon = { option ->
                            rememberSymbolPainter(
                                when (option) {
                                    "Type" -> MotionSymbols.ic_event_note
                                    "Subject" -> MotionSymbols.ic_school
                                    else -> MotionSymbols.ic_calendar_month
                                }
                            )
                        },
                        onSelectedChange = onSortChange
                    )
                }
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

private val dateTryFormats = listOf(
    DateTimeFormatter.ISO_LOCAL_DATE,
    DateTimeFormatter.ISO_DATE,
    DateTimeFormatter.ISO_OFFSET_DATE_TIME,
    DateTimeFormatter.ISO_LOCAL_DATE_TIME,
    DateTimeFormatter.ofPattern("yyyy-MM-dd"),
    DateTimeFormatter.ofPattern("yyyy/MM/dd"),
    DateTimeFormatter.ofPattern("dd/MM/yyyy")
)

private val attendanceDateFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd/MM/yyyy")

private fun formatEventDate(raw: String?): String {
    val parsed = parseDateOrNull(raw) ?: return raw.orEmpty()
    return parsed.format(attendanceDateFormat)
}

private fun parseDateOrNull(raw: String?): LocalDate? {
    if (raw.isNullOrBlank()) return null
    for (fmt in dateTryFormats) {
        try { return LocalDate.parse(raw, fmt) } catch (_: Exception) {}
    }
    return try { java.time.OffsetDateTime.parse(raw).toLocalDate() } catch (_: Exception) {
        try { java.time.LocalDateTime.parse(raw).toLocalDate() } catch (_: Exception) { null }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AttendancePage(
    modifier: Modifier = Modifier,
    onNavigate: MotionNavigator? = null
) {
    var isLoading by remember { mutableStateOf(true) }
    var events by remember { mutableStateOf<Map<String, List<JSONObject>>>(emptyMap()) }

    val context = androidx.compose.ui.platform.LocalContext.current
    val currentYear = java.time.Year.now().value
    val currentMonth = LocalDate.now().monthValue

    val initialSemester = if (currentMonth in 9..12 || currentMonth == 1) "A" else "B"
    val initialYear = if (initialSemester == "A") currentYear + 1 else currentYear

    var semesterState by rememberSaveable { mutableStateOf(initialSemester) }
    var yearState by rememberSaveable { mutableIntStateOf(initialYear) }

    var sortBy by rememberSaveable { mutableStateOf("Date") }
    var viewMode by rememberSaveable { mutableStateOf("events") } // "events" or "portions"

    var editingSubject by remember { mutableStateOf<String?>(null) }
    var editingSubjectHours by remember { mutableIntStateOf(2) }

    // Remembered: a new flow every recomposition restarts the collection and re-reads prefs.
    val portionsConfigFlow = remember(context) { AttendancePortionsStorage.configFlow(context) }
    val initialPortionsConfig = remember(context) { AttendancePortionsStorage.getPortionsConfig(context) }
    val portionsConfig by portionsConfigFlow.collectAsState(initial = initialPortionsConfig)

    var attendancePlatforms by remember { mutableStateOf<List<Platform>>(emptyList()) }
    var selectedPlatformIndex by remember { mutableIntStateOf(0) }
    var platformPickerExpanded by remember { mutableStateOf(false) }
    var providersLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        attendancePlatforms = withContext(Dispatchers.IO) {
            runCatching {
                PlatformStorage.loadPlatforms(context).filter { it.supportsAttendance }
            }.getOrDefault(emptyList())
        }
        providersLoaded = true
    }
    val selectedPlatform = attendancePlatforms.getOrNull(selectedPlatformIndex)

    var scheduleHoursBySubject by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    LaunchedEffect(selectedPlatform?.id) {
        val platform = selectedPlatform ?: return@LaunchedEffect
        if (platform.supportsSchedule) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val hoursMap = mutableMapOf<String, Int>()
                    for (day in 0..6) {
                        val daySchedule = platform.getSchedule(day)
                        val hours = daySchedule.optJSONObject("hours") ?: continue
                        val keys = hours.keys()
                        while (keys.hasNext()) {
                            val hKey = keys.next()
                            val lesson = hours.optJSONObject(hKey) ?: continue
                            val sub = lesson.optString("subject").trim()
                            if (sub.isNotBlank()) {
                                hoursMap[sub] = (hoursMap[sub] ?: 0) + 1
                            }
                        }
                    }
                    if (hoursMap.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            scheduleHoursBySubject = hoursMap
                        }
                    }
                }
            }
        }
    }

    val portionsSummaries = remember(events, portionsConfig, scheduleHoursBySubject) {
        AttendancePortionsCalculator.parseEvents(events, portionsConfig, scheduleHoursBySubject)
    }

    val groupedEvents = remember(events, sortBy) {
        when (sortBy) {
            "Type" -> events.mapValues { (_, list) ->
                list.sortedBy { ev -> parseDateOrNull(ev.optString("date")) ?: LocalDate.MIN }
            }.toSortedMap()

            "Date" -> run {
                val grouped = events
                    .flatMap { it.value }
                    .groupBy { ev ->
                        parseDateOrNull(ev.optString("date"))?.format(attendanceDateFormat)
                            ?: "Unknown Date"
                    }
                    .mapValues { (_, list) ->
                        list.sortedBy { ev -> parseDateOrNull(ev.optString("date")) ?: LocalDate.MIN }
                    }

                val groupDate = grouped.mapValues { (_, list) ->
                    list.firstNotNullOfOrNull { parseDateOrNull(it.optString("date")) }
                }
                val comparator = Comparator<String> { a, b ->
                    val da = groupDate[a]
                    val db = groupDate[b]
                    when {
                        da != null && db != null -> da.compareTo(db)
                        da != null -> -1
                        db != null -> 1
                        else -> a.compareTo(b)
                    }
                }
                grouped.toSortedMap(comparator)
            }
            "Subject" -> events
                .flatMap { (_, list) -> list }
                .groupBy { it.optString("subject") }
                .toSortedMap()

            else -> events
        }
    }

    LaunchedEffect(selectedPlatform?.id, yearState, semesterState) {
        val platform = selectedPlatform
        if (platform == null) {
            if (attendancePlatforms.isEmpty() && providersLoaded) isLoading = false
            return@LaunchedEffect
        }
        isLoading = true
        withContext(Dispatchers.IO) {
            try {
                val json = platform.getAttendanceEvents(yearState, semesterState.lowercase())
                val grouped = mutableMapOf<String, MutableList<JSONObject>>()

                val eventsJson = json.optJSONObject("events")
                eventsJson?.keys()?.forEach { type ->
                    val arr = eventsJson.getJSONArray(type)
                    val list = mutableListOf<JSONObject>()
                    for (i in 0 until arr.length()) {
                        list.add(arr.getJSONObject(i))
                    }
                    grouped[type] = list
                }

                withContext(Dispatchers.Main) {
                    events = grouped
                    isLoading = false
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    isLoading = false
                }
            }
        }
    }

    if (editingSubject != null) {
        EditSubjectHoursDialog(
            subject = editingSubject.orEmpty(),
            initialHours = editingSubjectHours,
            onDismiss = { editingSubject = null },
            onConfirm = { newHours ->
                editingSubject?.let { sub ->
                    AttendancePortionsStorage.setSubjectWeeklyHours(context, sub, newHours)
                }
                editingSubject = null
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(TopBarSpacing()))
        if (attendancePlatforms.size > 1) {
            ProviderPickerBar(
                providers = attendancePlatforms,
                selectedIndex = selectedPlatformIndex,
                onSelected = { selectedPlatformIndex = it; isLoading = true },
                expanded = platformPickerExpanded,
                onExpandedChange = { platformPickerExpanded = it }
            )
            Spacer(Modifier.height(8.dp))
        }

        FiltersGrid(
            viewMode = viewMode,
            onViewModeChange = { viewMode = it },
            portionsEnabled = portionsConfig.enabled,
            sortBy = sortBy,
            onSortChange = { sortBy = it },
            year = yearState,
            onYearChange = { yearState = it; isLoading = true },
            semester = semesterState,
            onSemesterChange = { semesterState = it; isLoading = true },
            currentYear = currentYear,
            modifier = Modifier.padding(vertical = 8.dp)
        )

        Spacer(Modifier.height(12.dp))

        if (isLoading) {
            GradesLoadingIndicator(modifier = modifier.fillMaxSize())
        } else {
            if (events.isEmpty()) {
                Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    NoAttendanceState()
                }
            } else if (viewMode == "portions" && portionsConfig.enabled) {
                MotionLazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    item(key = "summary") {
                        PortionsSummaryBanner(
                            summaries = portionsSummaries,
                            onOpenTable = { onNavigate?.invoke(AppDest.SchoolPortionsTable()) },
                            onOpenSettings = { onNavigate?.invoke(AppDest.PortionsSettings) },
                            modifier = Modifier.padding(bottom = 14.dp)
                        )
                    }

                    if (portionsSummaries.isEmpty()) {
                        item(key = "empty") {
                            Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    text = stringResource(R.string.no_attendance_events_found),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        itemsIndexed(portionsSummaries, key = { _, it -> "subject:${it.subject}" }) { index, summary ->
                            SubjectPortionCard(
                                summary = summary,
                                config = portionsConfig,
                                position = itemPosition(index, portionsSummaries.size),
                                onEditHours = {
                                    editingSubject = summary.subject
                                    editingSubjectHours = summary.weeklyHours
                                }
                            )
                        }
                    }
                }
            } else {
                MotionLazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (portionsConfig.enabled && portionsSummaries.isNotEmpty()) {
                        val bonusCount = portionsSummaries.count { it.status == GradeImpactStatus.BONUS }
                        item(key = "portions-teaser") {
                            val chevronScale = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
                            MotionCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 6.dp)
                                    .clickable { viewMode = "portions" },
                                position = MotionItemPosition.Alone,
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentPadding = 0.dp
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        painter = rememberSymbolPainter(MotionSymbols.ic_calculate),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = stringResource(R.string.attendance_portions_title),
                                            style = MaterialTheme.typography.titleSmall,
                                            fontFamily = MotionFonts.feldman(weight = 700),
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                        Text(
                                            text = stringResource(R.string.bonus_active_summary_banner, bonusCount),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                        )
                                    }
                                    Icon(
                                        painter = rememberSymbolPainter(MotionSymbols.ic_chevron_right),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(20.dp).graphicsLayer { scaleX = chevronScale }
                                    )
                                }
                            }
                        }
                    }

                    groupedEvents.forEach { (group, list) ->
                        item(key = "header:$group", contentType = "header") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 4.dp, end = 4.dp, top = 16.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = group.ifBlank { "—" },
                                    style = MaterialTheme.typography.titleSmall,
                                    fontFamily = MotionFonts.feldman(weight = 700),
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = list.size.toString(),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        itemsIndexed(
                            list,
                            key = { index, _ -> "event:$group:$index" },
                            contentType = { _, _ -> "event" }
                        ) { index, event ->
                            AttendanceEventCard(
                                event = event,
                                position = itemPosition(index, list.size)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun itemPosition(index: Int, size: Int) = when {
    size == 1 -> MotionItemPosition.Alone
    index == 0 -> MotionItemPosition.Start
    index == size - 1 -> MotionItemPosition.End
    else -> MotionItemPosition.Middle
}

@Composable
private fun AttendanceEventCard(event: JSONObject, position: MotionItemPosition) {
    val subject = event.optString("subject")
    val type = event.optString("type")
    val isRtl = remember(subject) { isRtlText(subject) }
    val justified = event.optBoolean("isJustified", false) || type.contains("מוצדק")
    val late = type.contains("איחור") || type.contains("late", ignoreCase = true)
    val pillContainer = when {
        justified -> MaterialTheme.colorScheme.secondaryContainer
        late -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.errorContainer
    }
    val pillContent = when {
        justified -> MaterialTheme.colorScheme.onSecondaryContainer
        late -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onErrorContainer
    }

    MotionCard(
        modifier = Modifier.fillMaxWidth(),
        position = position,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentPadding = 0.dp
    ) {
        CompositionLocalProvider(
            LocalLayoutDirection provides if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SubjectIcon(subject = subject, assignment = type, size = 44.dp, iconSize = 26.dp)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = subject,
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = MotionFonts.feldman(weight = 700),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val secondary = listOf(event.optString("teacher"), formatEventDate(event.optString("date")))
                        .filter { it.isNotBlank() }
                        .joinToString(" · ")
                    if (secondary.isNotBlank()) {
                        Text(
                            text = secondary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    event.optString("remark").takeIf { it.isNotBlank() }?.let { remark ->
                        Text(
                            text = remark,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (type.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Surface(shape = CircleShape, color = pillContainer, contentColor = pillContent) {
                        Text(
                            text = type,
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = MotionFonts.feldman(weight = 700),
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NoAttendanceState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        SubjectIcon(
            subject = "Attendance",
            containerColor = MaterialTheme.colorScheme.primary,
            iconColor = MaterialTheme.colorScheme.onPrimary,
            size = 128.dp,
            iconSize = 68.dp,
            painter = rememberSymbolPainter(MotionSymbols.ic_event_busy),
            polygon = MaterialShapes.Cookie12Sided
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.no_attendance_events_found),
            style = MaterialTheme.typography.headlineMedium,
            fontFamily = MotionFonts.feldman(weight = 600, width = 140f),
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
    }
}
