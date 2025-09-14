package com.feldman.scholix.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.feldman.scholix.R
import com.feldman.scholix.api.PlatformStorage
import com.feldman.scholix.ui.components.ActionRow
import com.feldman.scholix.ui.components.SegmentedOption
import com.feldman.scholix.ui.components.Title
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter


@Composable
fun FiltersRow(
    sortBy: String,
    onSortChange: (String) -> Unit,
    year: Int,
    onYearChange: (Int) -> Unit,
    semester: String,
    onSemesterChange: (String) -> Unit,
    currentYear: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Sort chip
        ChipDropdown(
            label = "Sort by",
            icon = painterResource(R.drawable.ic_sort),
            options = listOf("Type", "Date", "Subject"),
            selected = sortBy,
            onSelectedChange = onSortChange
        )

        // Year chip
        ChipDropdown(
            label = "Year",
            icon = painterResource(R.drawable.ic_calendar),
            options = listOf((currentYear - 1).toString(), currentYear.toString(), (currentYear + 1).toString()),
            selected = year.toString(),
            onSelectedChange = { onYearChange(it.toInt()) }
        )

        // Semester chip
        ChipDropdown(
            label = "Semester",
            icon = painterResource(R.drawable.ic_book),
            options = listOf("A", "B"),
            selected = semester,
            onSelectedChange = onSemesterChange
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChipDropdown(
    label: String,
    icon: Painter,
    options: List<String>,
    selected: String,
    onSelectedChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = Modifier.wrapContentSize()) {


        TextButton(onClick = { expanded = true }) {
            Icon(
                painter = icon,
                contentDescription = null
            )
            Spacer(Modifier.width(4.dp))
            Text(
                label,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = RoundedCornerShape(12.dp)
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    leadingIcon = {
                        if (option == selected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    onClick = {
                        onSelectedChange(option)
                        expanded = false
                    }
                )
            }
        }
    }
}
private val dateTryFormats = listOf(
    DateTimeFormatter.ISO_LOCAL_DATE,                // 2025-09-10
    DateTimeFormatter.ISO_DATE,                      // 2025-09-10
    DateTimeFormatter.ISO_OFFSET_DATE_TIME,          // 2025-09-10T00:00:00Z
    DateTimeFormatter.ISO_LOCAL_DATE_TIME,           // 2025-09-10T00:00:00
    DateTimeFormatter.ofPattern("yyyy-MM-dd"),
    DateTimeFormatter.ofPattern("yyyy/MM/dd"),
    DateTimeFormatter.ofPattern("dd/MM/yyyy")
)
private fun parseDateOrNull(raw: String?): LocalDate? {
    if (raw.isNullOrBlank()) return null
    for (fmt in dateTryFormats) {
        try { return LocalDate.parse(raw, fmt) } catch (_: Exception) {}
    }
    // Try extracting DATE part from a datetime
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
    var yearState by rememberSaveable { mutableStateOf(initialYear) }

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
            val platform = PlatformStorage.getAccount(context, 0)
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
                    val json = platform.getDisciplineEvents(yearState, semesterState.lowercase())
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
            val platform = PlatformStorage.getAccount(context, 0)
            if (platform != null) {
                try {
                    val json = platform.getDisciplineEvents(yearState, semesterState.lowercase())
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
            .padding(start = 16.dp, top = 48.dp, end = 16.dp, bottom = 0.dp)
    ) {
        Title(stringResource(R.string.attendance))

        FiltersRow(
            sortBy = sortBy,
            onSortChange = { sortBy = it },
            year = yearState,
            onYearChange = { yearState = it; isLoading = true },
            semester = semesterState,
            onSemesterChange = { semesterState = it; isLoading = true },
            currentYear = currentYear,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        val semesterNumber = when (semesterState.uppercase()) {
            "A" -> "1"
            "B" -> "2"
            "C" -> "3"
            else -> semesterState
        }


        Spacer(Modifier.height(20.dp))
        Text(
            text = "$yearState H$semesterNumber",
            style = MaterialTheme.typography.titleLarge.copy(
                fontSize = 24.sp,          // bigger
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            ),
            modifier = Modifier.fillMaxWidth(), // centers the text in the row
            maxLines = 1
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
                        .navigationBarsPadding()
                ) {
                    groupedEvents.forEach { (groupKey, list) ->

                        itemsIndexed(list) { index, event ->
                            val shape = when (index) {
                                0 -> if (list.lastIndex == 0) RoundedCornerShape(16.dp)
                                else RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 4.dp)
                                list.lastIndex -> RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp, topStart = 4.dp, topEnd = 4.dp)
                                else -> RoundedCornerShape(4.dp)
                            }

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(100.dp),
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
                                                fontWeight = FontWeight.Bold
                                            )
                                        )
                                        Text(
                                            text = event.optString("date"),
                                            style = MaterialTheme.typography.titleMedium
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

                                    if (event.optBoolean("enableJustified", true)) {
                                        Text(
                                            text = if (event.optBoolean("isJustified")) stringResource(
                                                R.string.justified
                                            ) else stringResource(R.string.not_justified),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }


                            Spacer(Modifier.height(3.dp))
                        }

                        item { Spacer(Modifier.height(20.dp)) }
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }

            }
        }
    }


}
