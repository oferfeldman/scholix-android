package com.feldman.scholix.pages

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.feldman.scholix.BottomBarSpacing
import com.feldman.scholix.R
import com.feldman.scholix.TOP_BAR_SPACING
import com.feldman.scholix.api.PlatformStorage
import com.feldman.scholix.ui.components.ChipPicker
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
                    label = "Sort by",
                    options = listOf("Type", "Date", "Subject"),
                    selected = sortBy,
                    onSelectedChange = onSortChange
                )
            }
            Box(modifier = Modifier.weight(1f)) {
                ChipPicker(
                    label = "Year",
                    options = listOf(
                        (currentYear - 1).toString(),
                        currentYear.toString(),
                        (currentYear + 1).toString()
                    ),
                    selected = year.toString(),
                    onSelectedChange = { onYearChange(it.toInt()) }
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(modifier = Modifier.weight(1f)) {
                ChipPicker(
                    label = "Semester",
                    options = listOf("A", "B"),
                    selected = semester,
                    onSelectedChange = onSemesterChange
                )
            }
            Spacer(modifier = Modifier.weight(1f))
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

    val groupedEvents = remember(events, sortBy) {
        when (sortBy) {
            "Type" -> events.mapValues { (_, list) ->
                list.sortedBy { ev ->
                    try { LocalDate.parse(ev.optString("date"), dateFormatter) }
                    catch (_: Exception) { LocalDate.MIN }
                }
            }.toSortedMap()

            "Date" -> run {
                val grouped = events
                    .flatMap { it.value }
                    .groupBy { ev ->
                        parseDateOrNull(ev.optString("date"))?.toString() ?: "Unknown Date"
                    }
                    .mapValues { (_, list) ->
                        list.sortedBy { ev -> parseDateOrNull(ev.optString("date")) ?: LocalDate.MIN }
                    }

                // Sort keys chronologically; put "Unknown Date" at the end
                val comparator = Comparator<String> { a, b ->
                    val da = runCatching { LocalDate.parse(a) }.getOrNull()
                    val db = runCatching { LocalDate.parse(b) }.getOrNull()
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

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val platform = PlatformStorage.getPlatform(context, 0)
            println(platform)
            println(platform)
            println(platform)
            println(platform)
            println(platform)
            println(platform)
            println(platform)
            if (platform != null) {
                println(1)
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
    LaunchedEffect(yearState, semesterState) {
        withContext(Dispatchers.IO) {
            val platform = PlatformStorage.getPlatform(context, 0)
            if (platform != null) {
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
            .padding(horizontal = 8.dp)
    ) {
        Spacer(Modifier.height(TOP_BAR_SPACING))
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
            Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularWavyProgressIndicator()
            }
        } else {
            if (events.isEmpty()) {
                Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.no_attendance_events_found))
                }
            } else {

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(16.dp))
                ) {
                    groupedEvents.forEach { (groupKey, list) ->

                        itemsIndexed(list) { index, event ->
                            val shape = when (index) {
                                0 -> if (list.lastIndex == 0) RoundedCornerShape(24.dp)
                                else RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 4.dp, bottomEnd = 4.dp)
                                list.lastIndex -> RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp, topStart = 4.dp, topEnd = 4.dp)
                                else -> RoundedCornerShape(4.dp)
                            }

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(80.dp),
                                shape = shape,
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                ),
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 20.dp),
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    // First row: type + date
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = event.optString("type"),
                                            style = MaterialTheme.typography.titleLarge.copy(
                                                fontWeight = FontWeight.Medium
                                            ),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = event.optString("date"),
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(6.dp))

                                    // Second row: teacher - subject
                                    Text(
                                        text = event.optString("teacher") + " - " + event.optString("subject"),
                                        style = MaterialTheme.typography.bodyMedium
                                    )

                                    val remark = event.optString("remark")
                                    if (remark.isNotBlank()) {
                                        Text("Notes: $remark", style = MaterialTheme.typography.bodySmall)
                                    }

//                                    if (event.optBoolean("enableJustified", true)) {
//                                        Text(
//                                            text = if (event.optBoolean("isJustified")) stringResource(
//                                                R.string.justified
//                                            ) else stringResource(R.string.not_justified),
//                                            style = MaterialTheme.typography.bodySmall
//                                        )
//                                    }
                                }
                            }


                            Spacer(Modifier.height(2.dp))
                        }

                        item { Spacer(Modifier.height(12.dp)) }
                    }
                    item { Spacer(Modifier.height(BottomBarSpacing())) }

                }

            }
        }
    }


}
