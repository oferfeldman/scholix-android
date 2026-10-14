package com.feldman.scholix.pages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.feldman.motion.MotionButton
import com.feldman.motion.MotionItemPosition
import com.feldman.motion.MotionLazyColumn
import com.feldman.motion.MotionSymbols
import com.feldman.scholix.ui.components.ChipPicker
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

internal enum class SchoolCalendarMode(val label: String) {
    Month("Month"),
    Week("Week"),
    List("List"),
}

internal data class SchoolCalendarItem(
    val id: String,
    val date: LocalDate,
    val title: String,
    val subtitle: String = "",
    val details: List<String> = emptyList(),
    val rooms: List<String> = emptyList(),
    val isExam: Boolean = false,
) {
    fun toScheduleItem(): JSONObject = JSONObject()
        .put("subject", title)
        .put("calendarType", if (isExam) "exam" else "event")
        .apply {
            if (rooms.size == 1) put("room", rooms.single())
            val extra = buildList {
                if (subtitle.isNotBlank()) add(subtitle)
                addAll(details.filter(String::isNotBlank))
            }.distinct().joinToString("\n")
            if (extra.isNotBlank()) put(if (isExam) "exams" else "changes", extra)
        }
}

@Composable
internal fun SchoolCalendarView(
    items: List<SchoolCalendarItem>,
    mode: SchoolCalendarMode,
    emptyMessage: String,
    modifier: Modifier = Modifier,
) {
    val sortedItems = remember(items) {
        items.distinctBy { it.id }.sortedWith(compareBy<SchoolCalendarItem> { it.date }.thenBy { it.title })
    }
    val itemsByDate = remember(sortedItems) { sortedItems.groupBy { it.date } }
    val now = remember { LocalDate.now() }
    val initialDate = remember(sortedItems, now) {
        sortedItems.map { it.date }.distinct().sorted().firstOrNull { !it.isBefore(now) }
            ?: sortedItems.maxOfOrNull { it.date } ?: now
    }
    var selectedDate by remember(sortedItems) { mutableStateOf(initialDate) }
    var displayedMonth by remember(sortedItems) { mutableStateOf(YearMonth.from(initialDate)) }
    var weekStart by remember(sortedItems) {
        mutableStateOf(initialDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY)))
    }

    val selectDate: (LocalDate) -> Unit = { date ->
        selectedDate = date
        displayedMonth = YearMonth.from(date)
        weekStart = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
    }

    LaunchedEffect(mode) {
        if (mode == SchoolCalendarMode.Week) {
            weekStart = selectedDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
        }
    }

    MotionLazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
    ) {
        when (mode) {
            SchoolCalendarMode.Month -> {
                item(key = "calendar-month-$displayedMonth") {
                    SchoolCalendarMonth(
                        displayedMonth = displayedMonth,
                        itemsByDate = itemsByDate,
                        selectedDate = selectedDate,
                        onMonthChanged = { displayedMonth = it },
                        onDateSelected = selectDate,
                    )
                }
                if (itemsByDate[selectedDate].isNullOrEmpty()) item(key = "calendar-empty-$selectedDate") {
                    Text(emptyMessage, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp))
                }
                itemsByDate[selectedDate]?.let { dayItems ->
                    item(key = "calendar-heading-$selectedDate") {
                        SchoolCalendarDateHeading(selectedDate)
                    }
                    item(key = "calendar-items-$selectedDate") {
                        SchoolCalendarCards(dayItems)
                    }
                }
            }

            SchoolCalendarMode.Week -> {
                item(key = "calendar-week-$weekStart") {
                    SchoolCalendarWeek(
                        weekStart = weekStart,
                        itemDates = itemsByDate.keys,
                        selectedDate = selectedDate,
                        onPreviousWeek = {
                            val newStart = weekStart.minusWeeks(1)
                            weekStart = newStart
                            itemsByDate.keys.sorted().firstOrNull {
                                !it.isBefore(newStart) && it.isBefore(newStart.plusDays(7))
                            }?.let(selectDate)
                        },
                        onNextWeek = {
                            val newStart = weekStart.plusWeeks(1)
                            weekStart = newStart
                            itemsByDate.keys.sorted().firstOrNull {
                                !it.isBefore(newStart) && it.isBefore(newStart.plusDays(7))
                            }?.let(selectDate)
                        },
                        onDateSelected = selectDate,
                    )
                }
                val inWeek = !selectedDate.isBefore(weekStart) && selectedDate.isBefore(weekStart.plusDays(7))
                val dayItems = if (inWeek) itemsByDate[selectedDate].orEmpty() else emptyList()
                if (dayItems.isNotEmpty()) {
                    item(key = "calendar-week-heading-$selectedDate") {
                        SchoolCalendarDateHeading(selectedDate)
                    }
                    item(key = "calendar-week-items-$selectedDate") {
                        SchoolCalendarCards(dayItems)
                    }
                } else {
                    item(key = "calendar-week-empty-$weekStart") {
                        Text(
                            text = "No tests or events this week.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(20.dp),
                        )
                    }
                }
            }

            SchoolCalendarMode.List -> {
                itemsByDate.toSortedMap().forEach { (date, dayItems) ->
                    item(key = "calendar-list-heading-$date") {
                        SchoolCalendarDateHeading(date)
                    }
                    item(key = "calendar-list-items-$date") {
                        SchoolCalendarCards(dayItems)
                    }
                }
            }
        }
    }
}

@Composable
private fun SchoolCalendarDateHeading(date: LocalDate) {
    val label = remember(date) {
        date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(Locale.getDefault()))
    }
    Text(
        text = label,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun SchoolCalendarCards(items: List<SchoolCalendarItem>) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items.forEachIndexed { index, item ->
            val position = when {
                items.size == 1 -> MotionItemPosition.Alone
                index == 0 -> MotionItemPosition.Start
                index == items.lastIndex -> MotionItemPosition.End
                else -> MotionItemPosition.Middle
            }
            val scheduleItem = item.toScheduleItem()
            ScheduleCardConnected(
                item = scheduleItem,
                position = position,
                color = scheduleColor(scheduleItem.optString("subject")),
                index = index,
            )
        }
    }
}

@Composable
private fun SchoolCalendarMonth(
    displayedMonth: YearMonth,
    itemsByDate: Map<LocalDate, List<SchoolCalendarItem>>,
    selectedDate: LocalDate,
    onMonthChanged: (YearMonth) -> Unit,
    onDateSelected: (LocalDate) -> Unit,
) {
    val locale = Locale.getDefault()
    val minMonth = remember(itemsByDate.keys) {
        YearMonth.from(itemsByDate.keys.minOrNull() ?: LocalDate.now()).minusMonths(3)
    }
    val maxMonth = remember(itemsByDate.keys) {
        YearMonth.from(itemsByDate.keys.maxOrNull() ?: LocalDate.now()).plusMonths(3)
    }
    val monthOptions = remember(minMonth, maxMonth) {
        buildList {
            var month = minMonth
            while (!month.isAfter(maxMonth)) {
                add(month)
                month = month.plusMonths(1)
            }
        }
    }
    val formatter = remember(locale) { DateTimeFormatter.ofPattern("MMMM yyyy", locale) }
    val monthLabels = remember(monthOptions, locale) { monthOptions.map { it.format(formatter) } }
    val firstDayOffset = displayedMonth.atDay(1).dayOfWeek.value % 7
    val cells = remember(displayedMonth) {
        List(firstDayOffset) { null } + (1..displayedMonth.lengthOfMonth()).map(displayedMonth::atDay)
    }
    val paddedCells = remember(cells) { cells + List((7 - cells.size % 7) % 7) { null } }
    val weekdayLabels = remember(locale) {
        listOf(
            DayOfWeek.SUNDAY,
            DayOfWeek.MONDAY,
            DayOfWeek.TUESDAY,
            DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY,
            DayOfWeek.FRIDAY,
            DayOfWeek.SATURDAY,
        ).map { it.getDisplayName(TextStyle.NARROW, locale) }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MotionButton(
                icon = MotionSymbols.ic_chevron_left,
                onClick = { onMonthChanged(displayedMonth.minusMonths(1)) },
                enabled = displayedMonth > minMonth,
                modifier = Modifier.size(56.dp),
            )
            Box(Modifier.weight(1f)) {
                ChipPicker(
                    label = "Month",
                    options = monthLabels,
                    selected = displayedMonth.format(formatter),
                    onSelectedChange = { label ->
                        monthOptions.getOrNull(monthLabels.indexOf(label))?.let(onMonthChanged)
                    },
                )
            }
            MotionButton(
                icon = MotionSymbols.ic_chevron_right,
                onClick = { onMonthChanged(displayedMonth.plusMonths(1)) },
                enabled = displayedMonth < maxMonth,
                modifier = Modifier.size(56.dp),
            )
        }

        Row(Modifier.fillMaxWidth()) {
            weekdayLabels.forEach { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).padding(vertical = 6.dp),
                )
            }
        }

        paddedCells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { date ->
                    if (date == null) {
                        Spacer(Modifier.weight(1f).height(54.dp))
                    } else {
                        val count = itemsByDate[date]?.size ?: 0
                        val selected = date == selectedDate
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .height(54.dp)
                                .clickable { onDateSelected(date) },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                            ) {
                                Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                                    Text(
                                        text = date.dayOfMonth.toString(),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = if (selected) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.onSurface,
                                        fontWeight = if (count > 0) FontWeight.SemiBold else FontWeight.Normal,
                                    )
                                }
                            }
                            if (count > 0) {
                                Text(
                                    text = if (count == 1) "•" else count.toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SchoolCalendarWeek(
    weekStart: LocalDate,
    itemDates: Set<LocalDate>,
    selectedDate: LocalDate,
    onPreviousWeek: () -> Unit,
    onNextWeek: () -> Unit,
    onDateSelected: (LocalDate) -> Unit,
) {
    val days = remember(weekStart) { (0L..6L).map(weekStart::plusDays) }
    val formatter = remember { DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()) }
    val label = "${weekStart.format(formatter)} – ${weekStart.plusDays(6).format(formatter)}"

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MotionButton(
                icon = MotionSymbols.ic_chevron_left,
                onClick = onPreviousWeek,
                modifier = Modifier.size(52.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            MotionButton(
                icon = MotionSymbols.ic_chevron_right,
                onClick = onNextWeek,
                modifier = Modifier.size(52.dp),
            )
        }

        Row(Modifier.fillMaxWidth()) {
            days.forEach { date ->
                val hasItem = date in itemDates
                val selected = date == selectedDate
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onDateSelected(date) }
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Surface(
                        shape = CircleShape,
                        color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                    ) {
                        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                            Text(
                                text = date.dayOfMonth.toString(),
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (selected) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (hasItem) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                    if (hasItem) {
                        Text("•", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
