package com.feldman.scholix.pages

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import com.feldman.scholix.api.Platform
import com.feldman.scholix.api.PlatformStorage
import com.feldman.scholix.ui.components.ProviderPickerBar
import com.feldman.scholix.ui.components.ChipPicker
import com.feldman.scholix.ui.components.SubjectIcon
import com.feldman.motion.MotionSymbols
import com.feldman.motion.MotionItemPosition
import com.feldman.motion.MotionCard
import com.feldman.motion.MotionFonts
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
/**
 * How a date is shown on a card: "01/09/2026".
 *
 * All digits on purpose. These cards lay out right-to-left for Hebrew content,
 * and a written month splits the date into separate directional runs -- "1 Sep
 * 2026" renders as "Sep 2026 1". Digits and slashes stay one run either way.
 */
private val attendanceDateFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd/MM/yyyy")

/**
 * The event's date, formatted for display.
 *
 * Providers hand back whatever their API uses -- Mashov sends full ISO stamps
 * like "2026-09-01T00:00:00" -- so parse it and print a readable date, falling
 * back to the raw string only when it cannot be understood at all.
 */
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
fun AttendancePage(modifier: Modifier = Modifier) {
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
    val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    // The page used to read provider #0 with no way to change it. Pick between
    // every provider that publishes attendance, like Grades and Schedule do.
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

                // Chronological by the group's own events, with undated last.
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
                .flatMap { (type, list) -> list.map { it } }
                .groupBy { it.optString("subject") }
                .toSortedMap()

            else -> events
        }
    }

    LaunchedEffect(selectedPlatform?.id, yearState, semesterState) {
        val platform = selectedPlatform
        if (platform == null) {
            // Nothing to load from: stop showing a spinner that will never end.
            if (attendancePlatforms.isEmpty() && providersLoaded) isLoading = false
            return@LaunchedEffect
        }
        isLoading = true
        withContext(Dispatchers.IO) {
            run {
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

        Spacer(Modifier.height(20.dp))

        if (isLoading) {
            GradesLoadingIndicator(modifier = modifier.fillMaxSize())
        } else {
            if (events.isEmpty()) {
                Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    NoAttendanceState()
                }
            } else {

                LazyColumn(modifier = Modifier.fillMaxSize()) {
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
