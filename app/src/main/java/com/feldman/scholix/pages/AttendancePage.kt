package com.feldman.scholix.pages

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.feldman.scholix.BottomBarSpacing
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
            horizontalArrangement = Arrangement.End
        ) {
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

    var showTableDialog by remember { mutableStateOf(false) }
    var editingSubject by remember { mutableStateOf<String?>(null) }
    var editingSubjectHours by remember { mutableIntStateOf(2) }

    val portionsConfig by AttendancePortionsStorage.configFlow(context)
        .collectAsState(initial = AttendancePortionsStorage.getPortionsConfig(context))

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

    if (showTableDialog) {
        SchoolPortionsTableDialog(onDismiss = { showTableDialog = false })
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
            sortBy = sortBy,
            onSortChange = { sortBy = it },
            year = yearState,
            onYearChange = { yearState = it; isLoading = true },
            semester = semesterState,
            onSemesterChange = { semesterState = it; isLoading = true },
            currentYear = currentYear,
            modifier = Modifier.padding(vertical = 8.dp)
        )

        if (portionsConfig.enabled) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = viewMode == "events",
                    onClick = { viewMode = "events" },
                    label = { Text(stringResource(R.string.attendance_view_events)) },
                    leadingIcon = {
                        Icon(
                            painter = rememberSymbolPainter(MotionSymbols.ic_list),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = viewMode == "portions",
                    onClick = { viewMode = "portions" },
                    label = { Text(stringResource(R.string.attendance_view_portions)) },
                    leadingIcon = {
                        Icon(
                            painter = rememberSymbolPainter(MotionSymbols.ic_calculate),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        if (isLoading) {
            GradesLoadingIndicator(modifier = modifier.fillMaxSize())
        } else {
            if (events.isEmpty()) {
                Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    NoAttendanceState()
                }
            } else if (viewMode == "portions" && portionsConfig.enabled) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        PortionsSummaryBanner(
                            summaries = portionsSummaries,
                            onOpenTable = { showTableDialog = true },
                            onOpenSettings = { onNavigate?.invoke(AppDest.PortionsSettings) }
                        )
                    }

                    if (portionsSummaries.isEmpty()) {
                        item {
                            Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    text = stringResource(R.string.no_attendance_events_found),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        itemsIndexed(portionsSummaries) { index, summary ->
                            SubjectPortionCard(
                                summary = summary,
                                position = when {
                                    portionsSummaries.size == 1 -> MotionItemPosition.Alone
                                    index == 0 -> MotionItemPosition.Start
                                    index == portionsSummaries.lastIndex -> MotionItemPosition.End
                                    else -> MotionItemPosition.Middle
                                },
                                onEditHours = {
                                    editingSubject = summary.subject
                                    editingSubjectHours = summary.weeklyHours
                                }
                            )
                        }
                    }

                    item { Spacer(Modifier.height(180.dp)) }
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    if (portionsConfig.enabled && portionsSummaries.isNotEmpty()) {
                        val bonusCount = portionsSummaries.count { it.status == GradeImpactStatus.BONUS }
                        item {
                            MotionCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewMode = "portions" },
                                position = MotionItemPosition.Alone,
                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        painter = rememberSymbolPainter(MotionSymbols.ic_calculate),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = stringResource(R.string.attendance_portions_title),
                                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = "בונוס פעיל ב-$bonusCount מקצועות • לחץ לצפייה במדדים המלאים",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Icon(
                                        painter = rememberSymbolPainter(MotionSymbols.ic_chevron_right),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                        }
                    }

                    groupedEvents.forEach { (_, list) ->
                        itemsIndexed(list) { index, event ->
                            val subject = event.optString("subject")
                            val isRtl = isRtlText(subject)

                            MotionCard(
                                modifier = Modifier.fillMaxWidth(),
                                position = when {
                                    list.size == 1 -> MotionItemPosition.Alone
                                    index == 0 -> MotionItemPosition.Start
                                    index == list.lastIndex -> MotionItemPosition.End
                                    else -> MotionItemPosition.Middle
                                },
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
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
                                        SubjectIcon(
                                            subject = subject,
                                            assignment = event.optString("type")
                                        )
                                        Spacer(Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = subject,
                                                style = MaterialTheme.typography.titleLarge.copy(
                                                    fontFamily = MotionFonts.feldman(weight = 700),
                                                    fontWeight = FontWeight.Bold
                                                ),
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = event.optString("teacher"),
                                                style = MaterialTheme.typography.titleMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            event.optString("remark").takeIf { it.isNotBlank() }?.let { remark ->
                                                Text(
                                                    text = remark,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                        Spacer(Modifier.width(12.dp))
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(
                                                text = event.optString("type"),
                                                style = MaterialTheme.typography.titleMedium.copy(
                                                    fontFamily = MotionFonts.feldman(weight = 700),
                                                    fontWeight = FontWeight.Bold
                                                ),
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = formatEventDate(event.optString("date")),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(2.dp))
                        }
                    }
                    item { Spacer(Modifier.height(180.dp)) }
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
