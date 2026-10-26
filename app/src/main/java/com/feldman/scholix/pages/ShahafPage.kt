package com.feldman.scholix.pages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import com.feldman.motion.MotionButton
import com.feldman.motion.MotionCard
import com.feldman.motion.MotionFonts
import com.feldman.motion.MotionItemPosition
import com.feldman.motion.MotionLazyColumn
import com.feldman.motion.MotionSectionDefaults
import com.feldman.motion.MotionSymbols
import com.feldman.motion.rememberSymbolPainter
import com.feldman.scholix.R
import com.feldman.scholix.api.PlatformStorage
import com.feldman.scholix.api.platforms.ShahafPlatform
import com.feldman.scholix.ui.components.ChipPicker
import com.feldman.scholix.ui.components.SubjectIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Calendar
import java.util.Locale

/** Shahaf's server-defined views include timetables, exams, events and notices. */
@Composable
fun ShahafPage(account: ShahafPlatform, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var schools by remember(account.id) { mutableStateOf(emptyList<JSONObject>()) }
    var classes by remember(account.id) { mutableStateOf(emptyList<JSONObject>()) }
    var data by remember(account.id) { mutableStateOf<JSONObject?>(null) }
    var school by remember(account.id) { mutableStateOf(account.selectedSchool) }
    var classId by remember(account.id) { mutableStateOf(account.selectedClassId) }
    var viewIndex by remember(account.id) { mutableIntStateOf(-1) }
    var reload by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var signIn by remember { mutableStateOf(!account.isLoggedIn()) }
    var current by remember(account.id) { mutableStateOf(account) }

    LaunchedEffect(account.getViewParameters()) {
        if (current.getViewParameters() != account.getViewParameters()) current = account
    }

    if (signIn) {
        ShahafLoginView(current, onSuccess = { signedIn ->
            current = signedIn
            scope.launch { withContext(Dispatchers.IO) { PlatformStorage.addPlatforms(context, listOf(signedIn)) } }
            signIn = false
            reload++
        }, onCancel = { signIn = false })
        return
    }
    LaunchedEffect(current, reload) {
        busy = true
        error = null
        try {
            schools = withContext(Dispatchers.IO) { current.getSchools().objects() }
            val saved = schools.firstOrNull { it.optString("semel") == school.optString("semel") }
            school = saved ?: schools.firstOrNull() ?: JSONObject()
            if (saved == null && school.length() > 0) {
                current.selectSchool(school)
                classId = current.selectedClassId
            }
        } catch (e: Exception) { error = e.localizedMessage }
        finally { busy = false }
    }
    LaunchedEffect(current, school.toString(), classId, reload) {
        data = null
        classes = emptyList()
        if (school.length() == 0) return@LaunchedEffect
        busy = true
        error = null
        try {
            val result = withContext(Dispatchers.IO) {
                current.selectSchool(school)
                val classList = current.getClasses().objects()
                val selectedClass = classList.firstOrNull { it.optString("Id") == classId }
                    ?: classList.firstOrNull { it.optString("Id") == current.selectedClassId }
                    ?: classList.firstOrNull()
                current.selectClass(selectedClass?.optString("Id") ?: current.selectedClassId)
                val appData = current.getAppData(emptyMap())
                PlatformStorage.addPlatforms(context, listOf(current))
                classList to appData
            }
            classes = result.first
            classId = current.selectedClassId
            data = result.second
        } catch (e: Exception) { error = e.localizedMessage }
        finally { busy = false }
    }

    val views = data?.optJSONArray("views").objects()
    val changesView = views.firstOrNull(::isShahafChangesView)
    val examView = views.firstOrNull(::isShahafExamView)
    val eventView = views.firstOrNull(::isShahafEventView)
    val calendarView = examView ?: eventView
    val userVisibleViews = views.filterNot { view ->
        isShahafChangesView(view) ||
            ((isShahafExamView(view) || isShahafEventView(view)) && view !== calendarView)
    }

    val selectedView = views.getOrNull(viewIndex)
        ?: views.firstOrNull { it.optJSONObject("tb")?.optJSONArray("tb")?.length()?.let { count -> count > 0 } == true }
        ?: userVisibleViews.firstOrNull()
        ?: views.firstOrNull()
    val tabs = selectedView?.optJSONObject("tb")
    val tabOptions = tabs?.optJSONArray("tb").objects()
    val selectedViewKey = selectedView?.optString("k").orEmpty().ifBlank {
        selectedView?.optString("h").orEmpty()
    }
    val isTimetableView = tabOptions.isNotEmpty()
    val isCalendarView = selectedView === calendarView
    var scheduleMode by remember(current.id, selectedViewKey) { mutableStateOf(ScheduleMode.Updated) }
    var calendarMode by remember(current.id, classId) { mutableStateOf(ShahafExamCalendarMode.Month) }
    val todayCalendarDay = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
    val weekdayTabs = tabOptions.size in 6..7
    val todayPage = when {
        !weekdayTabs -> -1
        todayCalendarDay == Calendar.SATURDAY && tabOptions.size == 6 -> -1
        else -> (todayCalendarDay + 6) % 7
    }
    val tomorrowPage = when {
        !weekdayTabs -> -1
        todayPage == -1 -> 0
        todayPage < tabOptions.lastIndex -> todayPage + 1
        else -> 0
    }
    val initialDayPage = when {
        todayPage in tabOptions.indices -> todayPage
        weekdayTabs -> tomorrowPage.coerceAtLeast(0)
        else -> (tabs?.optInt("dt", 0) ?: 0).coerceIn(0, (tabOptions.size - 1).coerceAtLeast(0))
    }
    val pagerState = key(current.id, selectedViewKey, tabOptions.size) {
        rememberPagerState(
            initialPage = initialDayPage,
            pageCount = { tabOptions.size.coerceAtLeast(1) },
        )
    }
    val dataBlocks = selectedView?.optJSONArray("d").objects()
    val localizedDayNames = listOf(
        stringResource(R.string.sunday),
        stringResource(R.string.monday),
        stringResource(R.string.tuesday),
        stringResource(R.string.wednesday),
        stringResource(R.string.thursday),
        stringResource(R.string.friday),
        stringResource(R.string.saturday),
    )
    var autoAdvancedDay by remember(current.id, selectedViewKey, school.toString(), classId) {
        mutableStateOf(false)
    }

    LaunchedEffect(data, selectedViewKey, todayPage, tomorrowPage) {
        if (!weekdayTabs || autoAdvancedDay || todayPage !in tabOptions.indices || tomorrowPage !in tabOptions.indices) {
            return@LaunchedEffect
        }
        if (pagerState.currentPage != todayPage) return@LaunchedEffect

        val todayBlocks = blocksForTab(tabOptions[todayPage], dataBlocks)
        val explicitEndMinutes = todayBlocks
            .flatMap { it.optJSONArray("r").objects() }
            .flatMap { row -> row.optJSONArray("h").objects().map { it.optString("t") } }
            .flatMap { text ->
                Regex("""\b\d{1,2}:\d{2}\b""").findAll(text).mapNotNull { match ->
                    val parts = match.value.split(':').mapNotNull(String::toIntOrNull)
                    if (parts.size == 2 && parts[0] in 0..23 && parts[1] in 0..59) {
                        parts[0] * 60 + parts[1]
                    } else null
                }.toList()
            }
            .maxOrNull()

        val now = Calendar.getInstance()
        val nowMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val todayEnded = explicitEndMinutes?.let { nowMinutes >= it }
            ?: (now.get(Calendar.HOUR_OF_DAY) >= 20)

        if (todayEnded) {
            autoAdvancedDay = true
            pagerState.scrollToPage(tomorrowPage)
        }
    }


    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Do not briefly draw a one-control layout while Shahaf is still
            // resolving the class/view payload. The whole picker layout appears
            // together once the response that defines it is ready.
            val showViewPicker = userVisibleViews.size > 1
            val showVersionPicker = isTimetableView && changesView != null
            val showCalendarModePicker = isCalendarView
            val calendarLabel = stringResource(R.string.calendar)

            // Match Webtop's control hierarchy: View gets the first row by itself,
            // then schedule selection/version controls share the row beneath it.
            if (showViewPicker) Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.weight(1f)) {
                    ChipPicker(
                        stringResource(R.string.view_mode),
                        userVisibleViews.map { shahafViewLabel(it, calendarLabel) },
                        selectedView?.let { shahafViewLabel(it, calendarLabel) }.orEmpty(),
                        onSelectedChange = { name ->
                            val chosen = userVisibleViews.firstOrNull { shahafViewLabel(it, calendarLabel) == name }
                            viewIndex = views.indexOf(chosen).coerceAtLeast(0)
                        },
                    )
                }
            }

            if (data != null && (classes.isNotEmpty() || showVersionPicker || showCalendarModePicker)) Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (showVersionPicker) Box(Modifier.weight(1f)) {
                    val original = stringResource(R.string.original)
                    val updated = stringResource(R.string.updated)
                    ChipPicker(
                        label = stringResource(R.string.version),
                        options = listOf(original, updated),
                        selected = if (scheduleMode == ScheduleMode.Original) original else updated,
                        onSelectedChange = { value ->
                            scheduleMode = if (value == original) ScheduleMode.Original else ScheduleMode.Updated
                        },
                    )
                }
                if (classes.isNotEmpty()) {
                    val selectedClass = classes.firstOrNull { it.optString("Id") == classId }
                    val gradeOptions = classes.mapNotNull(JSONObject::shahafGradeLabel).distinct()
                    val selectedGrade = selectedClass?.shahafGradeLabel()
                        ?: gradeOptions.firstOrNull().orEmpty()
                    val classesInGrade = classes.filter { it.shahafGradeLabel() == selectedGrade }
                    val classOptions = classesInGrade.mapNotNull(JSONObject::shahafClassNumberLabel).distinct()
                    val selectedClassNumber = selectedClass?.shahafClassNumberLabel()
                        ?: classOptions.firstOrNull().orEmpty()

                    if (gradeOptions.isNotEmpty()) Box(Modifier.weight(1f)) {
                        ChipPicker(
                            label = stringResource(R.string.grade),
                            options = gradeOptions,
                            selected = selectedGrade,
                            onSelectedChange = { grade ->
                                classes.firstOrNull { it.shahafGradeLabel() == grade }
                                    ?.let { classId = it.optString("Id") }
                            },
                        )
                    }
                    if (classOptions.isNotEmpty()) Box(Modifier.weight(1f)) {
                        ChipPicker(
                            label = stringResource(R.string.classroom),
                            options = classOptions,
                            selected = selectedClassNumber,
                            onSelectedChange = { number ->
                                classesInGrade.firstOrNull { it.shahafClassNumberLabel() == number }
                                    ?.let { classId = it.optString("Id") }
                            },
                        )
                    }
                }
                if (showCalendarModePicker) Box(Modifier.weight(1f)) {
                    val month = stringResource(R.string.month)
                    val week = stringResource(R.string.week)
                    val list = stringResource(R.string.list)
                    ChipPicker(
                        label = stringResource(R.string.display),
                        options = listOf(month, week, list),
                        selected = when (calendarMode) {
                            ShahafExamCalendarMode.Month -> month
                            ShahafExamCalendarMode.Week -> week
                            ShahafExamCalendarMode.List -> list
                        },
                        onSelectedChange = { label ->
                            calendarMode = when (label) {
                                month -> ShahafExamCalendarMode.Month
                                week -> ShahafExamCalendarMode.Week
                                list -> ShahafExamCalendarMode.List
                                else -> calendarMode
                            }
                        },
                    )
                }
            }

            // A Shahaf account normally has one course. If it has several, keep the
            // exceptional course switch available without displacing the Webtop-like
            // View -> Version/Grade/Class hierarchy above.
            if (data != null && schools.size > 1) Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.weight(1f)) {
                    ChipPicker(
                        stringResource(R.string.course),
                        schools.map { it.optString("name") },
                        school.optString("name"),
                        onSelectedChange = { name ->
                            schools.firstOrNull { it.optString("name") == name }?.let {
                                school = it
                                current.selectSchool(it)
                                classId = current.selectedClassId
                                viewIndex = -1
                            }
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(if (data == null || schools.isEmpty()) 0.dp else 12.dp))

        if (tabOptions.isNotEmpty()) {
            PrimaryTabRow(selectedTabIndex = pagerState.currentPage.coerceAtMost(tabOptions.lastIndex),
                containerColor = MaterialTheme.colorScheme.background) {
                tabOptions.forEachIndexed { index, day ->
                    Tab(selected = pagerState.currentPage == index,
                        onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                        selectedContentColor = MaterialTheme.colorScheme.primary,
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant) {
                        Text(day.localizedDayLabel(index, tabOptions.size, localizedDayNames), maxLines = 1, softWrap = false,
                            overflow = TextOverflow.Clip, style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(horizontal = 3.dp, vertical = 16.dp))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        when {
            busy -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            error != null -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                ShahafMessageState(
                    message = error.orEmpty(),
                    action = if (!current.isLoggedIn()) stringResource(R.string.sign_in) to { signIn = true } else null,
                )
            }
            schools.isEmpty() -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                ShahafMessageState(stringResource(R.string.shahaf_no_school, current.getName()))
            }
            data != null && dataBlocks.isEmpty() && tabOptions.isEmpty() && !isCalendarView ->
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    val className = classes.firstOrNull { it.optString("Id") == classId }?.optString("Name")
                    ShahafMessageState(stringResource(R.string.shahaf_no_timetable, school.optString("name") + className?.let { " · $it" }.orEmpty()))
            }
            isCalendarView -> ShahafExamCalendar(
                examBlocks = examView?.optJSONArray("d").objects(),
                eventBlocks = eventView?.optJSONArray("d").objects(),
                className = classes.firstOrNull { it.optString("Id") == classId }?.classLabel(current).orEmpty(),
                mode = calendarMode,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            else -> HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth().weight(1f)) { page ->
                val visibleBlocks = if (tabOptions.isNotEmpty()) {
                    blocksForTab(tabOptions.getOrNull(page), dataBlocks)
                } else dataBlocks
                val changesByHour = if (
                    isTimetableView && scheduleMode == ScheduleMode.Updated && changesView != null
                ) {
                    changesForTab(changesView, tabOptions.getOrNull(page))
                } else emptyMap()
                MotionLazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    Section(
                        key = "shahaf:$selectedViewKey:$page",
                        itemSpacing = MotionSectionDefaults.ItemSpacing,
                    ) {
                        visibleBlocks.forEachIndexed { index, block ->
                            Content(key = "block:$index") {
                                ShahafDataBlock(
                                    block = block,
                                    timetable = isTimetableView,
                                    changesByHour = changesByHour,
                                )
                            }
                            if (index != visibleBlocks.lastIndex) {
                                Spacer(Modifier.height(MotionSectionDefaults.ItemSpacing))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Friendly, expressive empty/error state matching the Schedule page's no-data view. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ShahafMessageState(
    message: String,
    action: Pair<String, () -> Unit>? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SubjectIcon(
            subject = "Schedule",
            containerColor = MaterialTheme.colorScheme.primary,
            iconColor = MaterialTheme.colorScheme.onPrimary,
            size = 128.dp,
            iconSize = 68.dp,
            painter = rememberSymbolPainter(MotionSymbols.ic_schedule),
            polygon = MaterialShapes.Cookie12Sided,
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.headlineSmall,
            fontFamily = MotionFonts.feldman(weight = 600, width = 140f),
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        action?.let { (label, onClick) ->
            Spacer(Modifier.height(20.dp))
            MotionButton(text = label, onClick = onClick)
        }
    }
}

@Composable
private fun ShahafDataBlock(
    block: JSONObject,
    timetable: Boolean,
    changesByHour: Map<Int, ShahafScheduleChange> = emptyMap(),
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        block.optString("t").takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 12.dp, bottom = 3.dp),
            )
        }
        val rows = block.optJSONArray("r").objects()
        if (timetable) {
            data class RenderedRow(
                val sourceIndex: Int,
                val hour: Int,
                val time: String,
                val item: JSONObject,
            )

            val renderedRows = rows.mapIndexedNotNull { sourceIndex, row ->
                val header = row.optJSONArray("h").objects()
                    .map { it.optString("t") }
                    .filter { it.isNotBlank() }
                val hour = header.firstOrNull()?.toIntOrNull() ?: -1
                val item = changesByHour[hour]?.toScheduleItem()
                    ?: row.toShahafScheduleItem()
                item?.let {
                    RenderedRow(
                        sourceIndex = sourceIndex,
                        hour = hour,
                        time = header.filter { it.toIntOrNull() == null }.joinToString("\n"),
                        item = it,
                    )
                }
            }

            renderedRows.forEachIndexed { visibleIndex, rendered ->
                key(rendered.sourceIndex) {
                    val position = when {
                        renderedRows.size == 1 -> MotionItemPosition.Alone
                        visibleIndex == 0 -> MotionItemPosition.Start
                        visibleIndex == renderedRows.lastIndex -> MotionItemPosition.End
                        else -> MotionItemPosition.Middle
                    }
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) {
                                ScheduleCardConnected(
                                    rendered.item,
                                    position,
                                    scheduleColor(rendered.item.optString("subject")),
                                    visibleIndex,
                                )
                            }
                            HourLabel(rendered.hour, rendered.time)
                        }
                    }
                }
            }
        } else {
            rows.forEachIndexed { index, row ->
                key(index) {
                    val position = when {
                        rows.size == 1 -> MotionItemPosition.Alone
                        index == 0 -> MotionItemPosition.Start
                        index == rows.lastIndex -> MotionItemPosition.End
                        else -> MotionItemPosition.Middle
                    }
                    MotionCard(
                    position = position,
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    contentPadding = 16.dp,
                    ) {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            ShahafElements(
                                row.optJSONArray("h"),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            ShahafElements(
                                row.optJSONArray("i"),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShahafElements(
    elements: JSONArray?,
    style: androidx.compose.ui.text.TextStyle,
    color: androidx.compose.ui.graphics.Color,
) {
    val uriHandler = LocalUriHandler.current
    elements.objects().forEachIndexed { index, element ->
        key(index) {
            val text = element.optString("t")
            val url = element.optString("u")
            if (url.startsWith("https://") || url.startsWith("http://")) {
                TextButton(
                    onClick = { uriHandler.openUri(url) },
                    contentPadding = PaddingValues(0.dp),
                ) { Text(text, style = style) }
            } else if (text.isNotBlank()) Text(text, style = style, color = color)
        }
    }
}

private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList()
    else (0 until length()).mapNotNull { optJSONObject(it) }

private fun JSONObject.classLabel(account: ShahafPlatform): String {
    (opt("Name") as? String)?.cleanShahafLabel()?.let { return it }
    if (optString("Id") == account.getStudentClassId()) {
        account.getStudentClassLabel().cleanShahafLabel()?.let { return it }
    }
    val parts = listOf(opt("Grade"), opt("Number"))
        .filter { it is String || it is Number }
        .mapNotNull { it?.toString()?.cleanShahafLabel() }
    if (parts.isNotEmpty()) return parts.joinToString(" / ")
    return optString("Id").cleanShahafLabel() ?: "All classes"
}

private fun JSONObject.shahafGradeLabel(): String? =
    opt("Grade")?.toString()?.cleanShahafLabel()

private fun JSONObject.shahafClassNumberLabel(): String? =
    opt("Number")?.toString()?.cleanShahafLabel()
        ?: (opt("Name") as? String)
            ?.trim()
            ?.let { name -> Regex("""(\d+)\s*$""").find(name)?.groupValues?.getOrNull(1) }
            ?.cleanShahafLabel()

private fun String.cleanShahafLabel(): String? = trim().takeIf {
    val compact = it.replace(" ", "")
    it.isNotBlank() && it != "0" && compact != "0/0" && compact != "0-0" &&
        it != "[]" && !it.equals("null", ignoreCase = true)
}

private fun JSONObject.localizedDayLabel(
    index: Int,
    tabCount: Int,
    localizedDays: List<String>,
): String {
    if (tabCount in 6..7 && index in localizedDays.indices) return localizedDays[index]
    return optString("t")
}

internal fun shahafWidgetLessons(data: JSONObject, day: Int): List<JSONObject> {
    val views = data.optJSONArray("views").objects()
    val view = views.firstOrNull { !isShahafChangesView(it) && !isShahafExamView(it) && !isShahafEventView(it) &&
        it.optJSONObject("tb")?.optJSONArray("tb")?.length() in 6..7 } ?: error("No daily schedule published")
    val tab = view.optJSONObject("tb")?.optJSONArray("tb")?.optJSONObject(day) ?: return emptyList()
    val changes = views.firstOrNull(::isShahafChangesView)?.let { changesForTab(it, tab) }.orEmpty()
    return blocksForTab(tab, view.optJSONArray("d").objects()).flatMap { block ->
        block.optJSONArray("r").objects().mapNotNull { row ->
            val headers = row.optJSONArray("h").objects().map { it.optString("t") }.filter { it.isNotBlank() }
            val hour = headers.firstOrNull()?.toIntOrNull() ?: -1
            (changes[hour]?.toScheduleItem() ?: row.toShahafScheduleItem())?.put("hour", hour)
                ?.put("time", headers.filter { it.toIntOrNull() == null }.joinToString(" - "))
        }
    }
}

private fun blocksForTab(tab: JSONObject?, dataBlocks: List<JSONObject>): List<JSONObject> {
    if (tab == null) return emptyList()
    val keys = listOf(tab.optString("k"), tab.optString("id"))
        .filter { it.isNotBlank() }
        .toSet()
    if (keys.isNotEmpty()) {
        val keyedBlocks = dataBlocks.filter { block -> block.optString("k") in keys }
        if (keyedBlocks.isNotEmpty()) return keyedBlocks
    }
    return tab.optJSONArray("i").objects()
}

private data class ShahafScheduleGroup(
    val subject: String,
    val teacher: String = "",
    val room: String = "",
)

private data class ShahafScheduleChange(
    val title: String,
    val details: String = "",
) {
    fun toScheduleItem(): JSONObject = JSONObject()
        .put("subject", title)
        .apply { if (details.isNotBlank()) put("changes", details) }
}

internal fun JSONObject.toShahafScheduleItem(): JSONObject? {
    val headings = optJSONArray("h").objects().map { it.optString("t").trim() }
    val elements = optJSONArray("i").objects().filter { element ->
        val text = element.optString("t").trim()
        text.isNotBlank() && text !in headings && !isShahafPeriodLabel(text)
    }
    if (elements.isEmpty()) return null

    val groups = mutableListOf<ShahafScheduleGroup>()
    var subject: String? = null
    var details = mutableListOf<String>()

    fun flush() {
        val currentSubject = subject?.trim().orEmpty()
        if (currentSubject.isBlank()) return
        val roomPrefix = Regex("""^(?:חדר|Room)\s*:\s*""", RegexOption.IGNORE_CASE)
        val room = details.firstOrNull { roomPrefix.containsMatchIn(it) }
            ?.replace(roomPrefix, "")
            ?.trim()
            .orEmpty()
        val teacher = details.firstOrNull { !roomPrefix.containsMatchIn(it) }.orEmpty().trim()
        groups += ShahafScheduleGroup(currentSubject, teacher, room)
        subject = null
        details = mutableListOf()
    }

    elements.forEach { element ->
        val text = element.optString("t").trim()
        if (text.isBlank()) return@forEach
        val style = element.opt("s")?.toString().orEmpty()
        val startsGroup = style.contains("bold", ignoreCase = true)
        if (startsGroup) {
            flush()
            subject = text
        } else if (subject != null) {
            details += text
        } else {
            // Fallback for schools whose Shahaf theme does not mark subjects bold.
            subject = text
        }
    }
    flush()

    if (groups.isEmpty()) return null
    if (groups.size == 1) {
        val group = groups.single()
        return JSONObject()
            .put("subject", group.subject)
            .apply {
                if (group.teacher.isNotBlank()) put("teacher", group.teacher)
                if (group.room.isNotBlank()) put("room", group.room)
            }
    }

    // Split lessons publish every parallel group in the class. We do not know the
    // student's personal English/math group from Shahaf's auth payload, so showing
    // every teacher and room is misleading and unreadable. Keep only the distinct
    // subject alternatives; a single repeated subject still renders once.
    val subjects = groups.map { it.subject }.distinct()
    return JSONObject().put("subject", subjects.joinToString(" / "))
}

/** Shahaf sometimes repeats the row's period heading inside the content cells. */
private fun isShahafPeriodLabel(text: String): Boolean {
    val normalized = text.trim().replace(Regex("\\s+"), " ")
    val hebrewOrdinal = "(?:ראשונה|ראשון|שניה|שנייה|שני|שלישית|שלישי|רביעית|רביעי|חמישית|חמישי|שישית|שישי|שביעית|שביעי|שמינית|שמיני|תשיעית|תשיעי|עשירית|עשירי|אחת עשרה|אחד עשר|שתים עשרה|שנים עשר|\\d+)"
    return Regex("^(?:שעה|שיעור)\\s+$hebrewOrdinal(?:\\s*[-–—]\\s*$hebrewOrdinal)?$").matches(normalized) ||
        Regex("^(?:Hour|Period)\\s+\\d+(?:\\s*[-–—]\\s*\\d+)?$", RegexOption.IGNORE_CASE).matches(normalized)
}

private fun isShahafChangesView(view: JSONObject): Boolean {
    val heading = view.optString("h")
    return heading.contains("שינויי מערכת") || heading.contains("הודעות ושינוי")
}

private fun isShahafExamView(view: JSONObject): Boolean = view.optString("h").contains("מבח")

private fun isShahafEventView(view: JSONObject): Boolean {
    val heading = view.optString("h")
    return heading.contains("אירוע")
}

private fun shahafViewLabel(view: JSONObject, calendarLabel: String): String = when {
    isShahafExamView(view) || isShahafEventView(view) -> calendarLabel
    else -> view.optString("h", calendarLabel)
}

private data class ShahafExamEvent(
    val date: LocalDate,
    val title: String,
    val periods: String,
    val subjects: List<String>,
    val rooms: List<String>,
    val details: List<String> = emptyList(),
    val isExam: Boolean = true,
)

private fun ShahafExamEvent.toScheduleItem(): JSONObject {
    val subject = subjects.joinToString(" / ").ifBlank { title }
    return JSONObject()
        .put("subject", subject)
        .put("calendarType", if (isExam) "exam" else "event")
        .apply {
            if (rooms.size == 1) put("room", rooms.single())
            val extra = buildList {
                if (title != subject) add(title)
                if (periods.isNotBlank()) add(periods)
                addAll(details.filter { it.isNotBlank() })
            }.distinct().joinToString("\n")
            if (extra.isNotBlank()) {
                put(if (isExam) "exams" else "changes", extra)
            }
        }
}

private enum class ShahafExamCalendarMode(val label: String) {
    Month("Month"),
    Week("Week"),
    List("List"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShahafExamCalendar(
    examBlocks: List<JSONObject>,
    eventBlocks: List<JSONObject>,
    className: String,
    mode: ShahafExamCalendarMode,
    modifier: Modifier = Modifier,
) {
    val exams = remember(examBlocks, eventBlocks) {
        (parseShahafExamEvents(examBlocks) + parseShahafPublishedEvents(eventBlocks))
            .sortedWith(compareBy<ShahafExamEvent> { it.date }.thenBy { it.title })
    }
    if (exams.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            ShahafMessageState(
                if (className.isBlank()) stringResource(R.string.no_calendar_items)
                else stringResource(R.string.no_calendar_items_class, className),
            )
        }
        return
    }

    val examsByDate = remember(exams) { exams.groupBy { it.date } }
    val examDates = remember(examsByDate) { examsByDate.keys }
    val now = remember { LocalDate.now() }
    val initialDate = remember(exams, now) {
        exams.map { it.date }.distinct().sorted().firstOrNull { !it.isBefore(now) }
            ?: exams.maxOf { it.date }
    }
    var selectedDate by remember(exams) { mutableStateOf(initialDate) }
    var displayedMonth by remember(exams) { mutableStateOf(YearMonth.from(initialDate)) }
    var weekStart by remember(exams) {
        mutableStateOf(initialDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY)))
    }
    val selectDate: (LocalDate) -> Unit = { date ->
        selectedDate = date
        weekStart = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
        displayedMonth = YearMonth.from(date)
    }

    MotionLazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        when (mode) {
            ShahafExamCalendarMode.Month -> {
                item(key = "exam-calendar-month") {
                    ShahafExamMonth(
                        displayedMonth = displayedMonth,
                        itemsByDate = examsByDate,
                        selectedDate = selectedDate,
                        onMonthChanged = { displayedMonth = it },
                        onDateSelected = selectDate,
                    )
                }
                val selectedExams = exams.filter { it.date == selectedDate }
                if (selectedExams.isNotEmpty()) {
                    item(key = "exam-heading-$selectedDate") {
                        ShahafExamDateHeading(selectedDate)
                    }
                    item(key = "exam-cards-$selectedDate") {
                        ShahafExamCards(selectedExams)
                    }
                }
            }

            ShahafExamCalendarMode.Week -> {
                item(key = "exam-calendar-week-$weekStart") {
                    ShahafExamWeek(
                        weekStart = weekStart,
                        examDates = examDates,
                        selectedDate = selectedDate,
                        onPreviousWeek = {
                            val newWeekStart = weekStart.minusWeeks(1)
                            weekStart = newWeekStart
                            exams.firstOrNull { exam ->
                                !exam.date.isBefore(newWeekStart) && exam.date.isBefore(newWeekStart.plusDays(7))
                            }?.date?.let(selectDate)
                        },
                        onNextWeek = {
                            val newWeekStart = weekStart.plusWeeks(1)
                            weekStart = newWeekStart
                            exams.firstOrNull { exam ->
                                !exam.date.isBefore(newWeekStart) && exam.date.isBefore(newWeekStart.plusDays(7))
                            }?.date?.let(selectDate)
                        },
                        onDateSelected = selectDate,
                    )
                }
                val selectedIsInWeek = !selectedDate.isBefore(weekStart) &&
                    selectedDate.isBefore(weekStart.plusDays(7))
                val selectedExams = if (selectedIsInWeek) {
                    exams.filter { it.date == selectedDate }
                } else emptyList()
                if (selectedExams.isNotEmpty()) {
                    item(key = "exam-week-heading-$selectedDate") {
                        ShahafExamDateHeading(selectedDate)
                    }
                    item(key = "exam-week-cards-$selectedDate") {
                        ShahafExamCards(selectedExams)
                    }
                } else {
                    item(key = "exam-week-empty-$weekStart") {
                        Text(
                            text = stringResource(R.string.no_calendar_items_week),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 20.dp),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }

            ShahafExamCalendarMode.List -> {
                exams.groupBy { it.date }.toSortedMap().forEach { (date, dateExams) ->
                    item(key = "exam-list-heading-$date") {
                        ShahafExamDateHeading(date)
                    }
                    item(key = "exam-list-cards-$date") {
                        ShahafExamCards(dateExams)
                    }
                }
            }
        }
    }
}

@Composable
private fun ShahafExamMonth(
    displayedMonth: YearMonth,
    itemsByDate: Map<LocalDate, List<ShahafExamEvent>>,
    selectedDate: LocalDate,
    onMonthChanged: (YearMonth) -> Unit,
    onDateSelected: (LocalDate) -> Unit,
) {
    val locale = Locale.getDefault()
    val allDates = itemsByDate.keys
    val minMonth = remember(allDates) {
        YearMonth.from(allDates.minOrNull() ?: LocalDate.now()).minusMonths(6)
    }
    val maxMonth = remember(allDates) {
        YearMonth.from(allDates.maxOrNull() ?: LocalDate.now()).plusMonths(6)
    }
    val monthOptions = remember(minMonth, maxMonth) {
        buildList {
            var current = minMonth
            while (!current.isAfter(maxMonth)) {
                add(current)
                current = current.plusMonths(1)
            }
        }
    }
    val monthFormatter = remember(locale) { DateTimeFormatter.ofPattern("MMMM yyyy", locale) }
    val monthLabels = remember(monthOptions, locale) { monthOptions.map { it.format(monthFormatter) } }
    val selectedMonthLabel = displayedMonth.format(monthFormatter)
    val firstDayOffset = displayedMonth.atDay(1).dayOfWeek.value % 7
    val cells = remember(displayedMonth) {
        List(firstDayOffset) { null } +
            (1..displayedMonth.lengthOfMonth()).map { displayedMonth.atDay(it) }
    }
    val paddedCells = remember(cells) {
        cells + List((7 - cells.size % 7) % 7) { null }
    }
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

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
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
                    label = stringResource(R.string.month),
                    options = monthLabels,
                    selected = selectedMonthLabel,
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
                        Spacer(Modifier.weight(1f).height(52.dp))
                    } else {
                        val count = itemsByDate[date]?.size ?: 0
                        val selected = date == selectedDate
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp)
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
private fun ShahafExamDateHeading(date: LocalDate) {
    val label = remember(date) {
        date.format(
            DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)
                .withLocale(Locale.getDefault())
        )
    }
    Text(
        text = label,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun ShahafExamWeek(
    weekStart: LocalDate,
    examDates: Set<LocalDate>,
    selectedDate: LocalDate,
    onPreviousWeek: () -> Unit,
    onNextWeek: () -> Unit,
    onDateSelected: (LocalDate) -> Unit,
) {
    val weekDays = remember(weekStart) { (0L..6L).map(weekStart::plusDays) }
    val weekLabel = remember(weekStart) {
        val formatter = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
        "${weekStart.format(formatter)} – ${weekStart.plusDays(6).format(formatter)}"
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MotionButton(
                icon = MotionSymbols.ic_chevron_left,
                onClick = onPreviousWeek,
                modifier = Modifier.size(52.dp),
            )
            Text(
                text = weekLabel,
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
            weekDays.forEach { date ->
                val hasExam = date in examDates
                val selected = date == selectedDate && hasExam
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(enabled = hasExam) { onDateSelected(date) }
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = date.dayOfWeek.name.take(1),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Surface(
                        shape = CircleShape,
                        color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                    ) {
                        Box(
                            modifier = Modifier.size(40.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = date.dayOfMonth.toString(),
                                style = MaterialTheme.typography.bodyLarge,
                                color = when {
                                    selected -> MaterialTheme.colorScheme.onPrimary
                                    hasExam -> MaterialTheme.colorScheme.onSurface
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                                },
                                fontWeight = if (hasExam) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShahafExamCards(exams: List<ShahafExamEvent>) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        exams.forEachIndexed { index, exam ->
            val position = when {
                exams.size == 1 -> MotionItemPosition.Alone
                index == 0 -> MotionItemPosition.Start
                index == exams.lastIndex -> MotionItemPosition.End
                else -> MotionItemPosition.Middle
            }
            val item = exam.toScheduleItem()
            ScheduleCardConnected(
                item = item,
                position = position,
                color = scheduleColor(item.optString("subject")),
                index = index,
            )
        }
    }
}

private fun parseShahafExamEvents(blocks: List<JSONObject>): List<ShahafExamEvent> {
    return blocks.flatMap { block ->
        block.optJSONArray("r").objects().mapNotNull { row ->
            val date = shahafExamDate(row.optJSONArray("h").objects().map { it.optString("t") })
                ?: return@mapNotNull null
            val texts = row.optJSONArray("i").objects()
                .map { it.optString("t").trim() }
                .filter { it.isNotBlank() }
            if (texts.isEmpty()) return@mapNotNull null

            val title = texts.firstOrNull { it.startsWith("מבחן") }.orEmpty()
            if (title.isBlank()) return@mapNotNull null
            val periods = texts.firstOrNull {
                it.startsWith("שעורים") || it.startsWith("שיעורים")
            }.orEmpty()
            val subjects = texts.indices.mapNotNull { index ->
                if (index == 0 || !texts[index - 1].contains("לתלמידי")) return@mapNotNull null
                texts[index].takeUnless {
                    it.startsWith("חדר:") || it.startsWith("Room:", ignoreCase = true)
                }
            }.filter { it.isNotBlank() }.distinct()
            val rooms = texts.mapNotNull { text ->
                text.takeIf {
                    it.startsWith("חדר:") || it.startsWith("Room:", ignoreCase = true)
                }?.substringAfter(':')?.trim()
            }.filter { it.isNotBlank() }.distinct()

            ShahafExamEvent(
                date = date,
                title = title,
                periods = periods,
                subjects = subjects,
                rooms = rooms,
            )
        }
    }.sortedBy { it.date }
}

private fun parseShahafPublishedEvents(blocks: List<JSONObject>): List<ShahafExamEvent> {
    val result = mutableListOf<ShahafExamEvent>()
    blocks.forEach { block ->
        var currentDate = shahafExamDate(listOf(block.optString("t")))
        block.optJSONArray("r").objects().forEach { row ->
            val headers = row.optJSONArray("h").objects()
                .map { it.optString("t").trim() }
                .filter { it.isNotBlank() }
            shahafExamDate(headers)?.let { currentDate = it }

            val texts = row.optJSONArray("i").objects()
                .map { it.optString("t").trim() }
                .filter { it.isNotBlank() }
            if (texts.isEmpty()) return@forEach

            val inlineDate = shahafExamDate(texts)
            val date = inlineDate ?: currentDate ?: return@forEach
            if (inlineDate != null) currentDate = inlineDate

            val content = texts.filterNot { text ->
                shahafExamDate(listOf(text)) == date
            }
            val title = content.firstOrNull().orEmpty()
            if (title.isBlank()) return@forEach
            result += ShahafExamEvent(
                date = date,
                title = title,
                periods = "",
                subjects = emptyList(),
                rooms = emptyList(),
                details = content.drop(1),
                isExam = false,
            )
        }
    }
    return result.distinctBy { event ->
        "${event.date}|${event.title}|${event.details.joinToString("|")}"
    }.sortedBy { it.date }
}

private fun shahafExamDate(headers: List<String>): LocalDate? {
    val cleaned = headers.map { it.replace('\u00A0', ' ').trim() }
    val joined = cleaned.joinToString(" ")
    Regex("""\b(\d{1,2})[./](\d{1,2})(?:[./](\d{2,4}))?\b""").find(joined)?.let { match ->
        val day = match.groupValues[1].toIntOrNull() ?: return@let
        val month = match.groupValues[2].toIntOrNull() ?: return@let
        val rawYear = match.groupValues[3].toIntOrNull()
        val now = Calendar.getInstance()
        val currentMonth = now.get(Calendar.MONTH) + 1
        val schoolYearStart = if (currentMonth >= 9) now.get(Calendar.YEAR) else now.get(Calendar.YEAR) - 1
        val inferredYear = if (month >= 9) schoolYearStart else schoolYearStart + 1
        val year = when {
            rawYear == null -> inferredYear
            rawYear < 100 -> 2000 + rawYear
            else -> rawYear
        }
        return runCatching { LocalDate.of(year, month, day) }.getOrNull()
    }
    val day = cleaned.firstNotNullOfOrNull { it.toIntOrNull()?.takeIf { value -> value in 1..31 } }
        ?: return null
    val monthNames = mapOf(
        "ינואר" to 1, "פברואר" to 2, "מרץ" to 3, "אפריל" to 4,
        "מאי" to 5, "יוני" to 6, "יולי" to 7, "אוגוסט" to 8,
        "ספטמבר" to 9, "אוקטובר" to 10, "נובמבר" to 11, "דצמבר" to 12,
    )
    val month = cleaned.firstNotNullOfOrNull { value -> monthNames.entries.firstOrNull { value.contains(it.key) }?.value }
        ?: return null
    val now = Calendar.getInstance()
    val currentMonth = now.get(Calendar.MONTH) + 1
    val schoolYearStart = if (currentMonth >= 9) now.get(Calendar.YEAR) else now.get(Calendar.YEAR) - 1
    val year = if (month >= 9) schoolYearStart else schoolYearStart + 1
    return runCatching { LocalDate.of(year, month, day) }.getOrNull()
}

private fun LocalDate.toUtcMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun changesForTab(
    changesView: JSONObject,
    tab: JSONObject?,
): Map<Int, ShahafScheduleChange> {
    val targetDate = tab?.let(::shahafTabDateKey) ?: return emptyMap()
    val result = mutableMapOf<Int, ShahafScheduleChange>()

    changesView.optJSONArray("d").objects().forEach { block ->
        var currentDate: Int? = null
        block.optJSONArray("r").objects().forEach { row ->
            val headers = row.optJSONArray("h").objects()
                .map { it.optString("t").trim() }
                .filter { it.isNotBlank() }
            val contents = row.optJSONArray("i").objects()
                .map { it.optString("t").trim() }
                .filter { it.isNotBlank() }

            val parsedDate = shahafDateKey(headers.joinToString(" "))
            if (parsedDate != null && contents.isEmpty()) {
                currentDate = parsedDate
                return@forEach
            }
            if (currentDate != targetDate || contents.isEmpty()) return@forEach

            val periods = shahafPeriodRange(headers.joinToString(" ")) ?: return@forEach
            val title = contents.firstOrNull().orEmpty()
            if (title.isBlank()) return@forEach
            val details = contents.drop(1)
                .filterNot { text ->
                    text.startsWith("לכיתות:") || text.startsWith("לשכבות:")
                }
                .joinToString("\n")
            val change = ShahafScheduleChange(title, details)
            periods.forEach { hour -> result[hour] = change }
        }
    }
    return result
}

private fun shahafTabDateKey(tab: JSONObject): Int? {
    val text = tab.optString("t").replace('\u00A0', ' ')
    val match = Regex("""\b(\d{1,2})\.(\d{1,2})\b""").find(text) ?: return null
    val day = match.groupValues[1].toIntOrNull() ?: return null
    val month = match.groupValues[2].toIntOrNull() ?: return null
    return month * 100 + day
}

private fun shahafDateKey(raw: String): Int? {
    val text = raw.replace('\u00A0', ' ').trim()
    Regex("""\b(\d{1,2})[./](\d{1,2})(?:[./]\d{2,4})?\b""").find(text)?.let { match ->
        val day = match.groupValues[1].toIntOrNull() ?: return@let
        val month = match.groupValues[2].toIntOrNull() ?: return@let
        return month * 100 + day
    }

    val months = mapOf(
        "ינואר" to 1,
        "פברואר" to 2,
        "מרץ" to 3,
        "אפריל" to 4,
        "מאי" to 5,
        "יוני" to 6,
        "יולי" to 7,
        "אוגוסט" to 8,
        "ספטמבר" to 9,
        "אוקטובר" to 10,
        "נובמבר" to 11,
        "דצמבר" to 12,
    )
    val monthPattern = months.keys.joinToString("|") { Regex.escape(it) }
    val match = Regex("""\b(\d{1,2})\s+($monthPattern)\b""").find(text) ?: return null
    val day = match.groupValues[1].toIntOrNull() ?: return null
    val month = months[match.groupValues[2]] ?: return null
    return month * 100 + day
}

private fun shahafPeriodRange(raw: String): IntRange? {
    Regex("""\b(\d{1,2})\s*-\s*(\d{1,2})\b""").find(raw)?.let { match ->
        val start = match.groupValues[1].toIntOrNull() ?: return@let
        val end = match.groupValues[2].toIntOrNull() ?: return@let
        if (start in 0..20 && end in start..20) return start..end
    }
    val single = Regex("""\b(\d{1,2})\b""").find(raw)?.groupValues?.get(1)?.toIntOrNull()
    return single?.takeIf { it in 0..20 }?.let { it..it }
}
