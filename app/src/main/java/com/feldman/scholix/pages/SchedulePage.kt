package com.feldman.scholix.pages

import android.text.BidiFormatter
import android.util.Log
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONObject
import com.feldman.scholix.R
import androidx.compose.ui.text.intl.LocaleList
import com.feldman.scholix.BottomBarSpacing
import com.feldman.scholix.TopBarSpacing
import com.feldman.scholix.api.Platform
import com.feldman.scholix.api.PlatformStorage
import com.feldman.scholix.api.platformOptions
import com.feldman.scholix.api.platforms.MashovPlatform
import com.feldman.scholix.api.platforms.WebtopPlatform
import com.feldman.scholix.api.platforms.InbarPlatform
import com.feldman.scholix.ui.HiddenInbarLogin
import com.feldman.scholix.ui.HiddenWebtopMoeLogin
import com.feldman.scholix.ui.components.ChipPicker
import com.feldman.scholix.ui.components.ProviderPickerBar
import com.feldman.scholix.ui.components.SubjectIcon
import com.feldman.motion.MotionIconBackground
import com.feldman.motion.MotionItemPosition
import com.feldman.motion.MotionCard
import com.feldman.motion.MotionSymbols
import com.feldman.motion.MotionFonts
import com.feldman.motion.rememberSymbolPainter
import com.feldman.motion.MotionThemeDefaults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import kotlin.sequences.asSequence

enum class ScheduleMode() { Original(), Updated(); }
@Composable
fun ClassFiltersRow(
    grade: String,
    onGradeChange: (String) -> Unit,
    clazz: String,
    onClassChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Box(modifier = Modifier.weight(1f)) {
            ChipPicker(
                label = stringResource(R.string.grade),
                options = (1..12).map(Int::toString),
                selected = grade,
                onSelectedChange = onGradeChange
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            ChipPicker(
                label = stringResource(R.string.classroom),
                options = (1..9).map { it.toString() },
                selected = clazz,
                onSelectedChange = onClassChange
            )
        }
    }
}


@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SchedulePage(
    platforms: List<Platform>,
    modifier: Modifier = Modifier,
    inbarAccessRequested: Boolean = false,
    isActive: Boolean = true,
) {
    var inbarRequestedByUser by remember { mutableStateOf(false) }
    val active by rememberUpdatedState(isActive)
    val navigationRequested by rememberUpdatedState(inbarAccessRequested)
    fun inbarAllowed() = active && (navigationRequested || inbarRequestedByUser)
    val canAccessInbar = inbarAllowed()
    val schedulePlatforms = platforms.filter { it.supportsSchedule }
    var selectedPlatformIndex by remember(schedulePlatforms.size) { mutableIntStateOf(0) }
    var platformPickerExpanded by remember { mutableStateOf(false) }
    var recoveredProviders by remember { mutableStateOf<Map<String, Platform>>(emptyMap()) }
    val storedPlatform = schedulePlatforms.getOrNull(selectedPlatformIndex)
    val platform = storedPlatform?.let { recoveredProviders[it.id] ?: it }
    val scheduleInfo = platform?.getInfo()
    val includesSaturday = scheduleInfo?.optBoolean("supportsSaturdaySchedule", false) == true
    val dayNames = listOf(
        stringResource(R.string.sunday),
        stringResource(R.string.monday),
        stringResource(R.string.tuesday),
        stringResource(R.string.wednesday),
        stringResource(R.string.thursday),
        stringResource(R.string.friday)
    ) + if (includesSaturday) listOf(stringResource(R.string.saturday)) else emptyList()
    val fullDayNames = listOf(
        stringResource(R.string.sunday_full),
        stringResource(R.string.monday_full),
        stringResource(R.string.tuesday_full),
        stringResource(R.string.wednesday_full),
        stringResource(R.string.thursday_full),
        stringResource(R.string.friday_full)
    ) + if (includesSaturday) listOf(stringResource(R.string.saturday_full)) else emptyList()

    val allSchedulesUpdated = remember { mutableStateMapOf<Int, List<JSONObject>>() }
    val allSchedulesOriginal = remember { mutableStateMapOf<Int, List<JSONObject>>() }
    val errorMessages = remember { mutableStateMapOf<Int, String?>() }
    var loadedSelection by remember { mutableStateOf<String?>(null) }

    val todayCalendar = Calendar.getInstance()
    val today = todayCalendar.get(Calendar.DAY_OF_WEEK)
    val currentHour = todayCalendar.get(Calendar.HOUR_OF_DAY)
    val todayPage = if (today == Calendar.SATURDAY && !includesSaturday) -1 else (today + 6) % 7

    val tomorrowPage = when (today) {
        Calendar.SATURDAY -> 0
        else -> if (todayPage in 0 until dayNames.lastIndex) todayPage + 1 else 0
    }

    val initialPage = when {
        today == Calendar.SATURDAY && !includesSaturday -> 0
        currentHour >= 20 -> tomorrowPage
        else -> todayPage
    }

    val pagerState = rememberPagerState(
        initialPage = initialPage,
        pageCount = { dayNames.size }
    )
    val coroutineScope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(false) }
    var webtopRelogin by remember { mutableStateOf<WebtopPlatform?>(null) }
    var inbarRelogin by remember { mutableStateOf<InbarPlatform?>(null) }
    var reloginAttempted by remember { mutableStateOf(false) }
    var scheduleReloadTrigger by remember { mutableIntStateOf(0) }
    LaunchedEffect(isActive) {
        if (!isActive) {
            inbarRequestedByUser = false
            inbarRelogin = null
            reloginAttempted = false
        }
    }

    val context = LocalContext.current
    // Every provider that can supply a schedule, so the page can switch between
    // them the same way Grades switches between courses.
    // Subjects the user marked as free periods for this provider.
    val windowSubjects = remember(platform?.id) {
        platform?.let { PlatformStorage.loadWindowSubjects(context, it.id) } ?: emptySet()
    }
    // Providers differ: Webtop publishes a separate "original" timetable and lets
    // any grade/class be read, Mashov has one timetable for the signed-in student
    // only. Show a control only where the provider backs it.
    val supportsOriginal = scheduleInfo?.optBoolean("supportsOriginalSchedule", true) ?: true
    val supportsSelection = scheduleInfo?.optBoolean("supportsScheduleSelection", true) ?: true
    val supportsAcademicSelection = scheduleInfo?.optBoolean("supportsAcademicScheduleSelection", false) == true
    val numberedPeriods = scheduleInfo?.optBoolean("numberedLessonPeriods", true) ?: true
    val academicYears = scheduleInfo?.optJSONArray("scheduleYears")?.let { years ->
        (0 until years.length()).map { years.getInt(it).toString() }
    }.orEmpty()
    val academicPeriods = scheduleInfo?.optJSONArray("schedulePeriods")?.let { periods ->
        (0 until periods.length()).associate { index ->
            periods.getJSONObject(index).let { it.getString("id") to it.getString("label") }
        }
    }.orEmpty()
    var academicYear by remember(platform?.id) { mutableStateOf(scheduleInfo?.optInt("scheduleYear")?.toString().orEmpty()) }
    var academicPeriod by remember(platform?.id) { mutableStateOf(scheduleInfo?.optString("schedulePeriod", "1").orEmpty()) }
    val rawScheduleSelection = platform?.getInfo()?.optString("scheduleSelection")
    val accountSelection = remember(platform, rawScheduleSelection) {
        rawScheduleSelection
            ?.split('|', limit = 2)
            ?.takeIf { it.size == 2 && it.all(String::isNotBlank) }
    }
    val accountSelectionKey = "${platform?.id}:${accountSelection?.joinToString("|")}"
    var selectedGrade by remember(accountSelectionKey) {
        mutableStateOf(accountSelection?.get(0) ?: "9")
    }
    var selectedClass by remember(accountSelectionKey) {
        mutableStateOf(accountSelection?.get(1) ?: "6")
    }
    val selectedValue = if (supportsAcademicSelection) "$academicYear|$academicPeriod" else "$selectedGrade|$selectedClass"

    LaunchedEffect(accountSelection) {
        if (accountSelection != null && accountSelection.size == 2) {
            selectedGrade = accountSelection[0]
            selectedClass = accountSelection[1]
        }
    }

    LaunchedEffect(pagerState.currentPage, platform?.id, selectedValue, scheduleReloadTrigger, canAccessInbar, isActive) {
        if (!isActive) return@LaunchedEffect
        if (inbarRelogin != null) return@LaunchedEffect
        val page = pagerState.currentPage
        if (page !in dayNames.indices) return@LaunchedEffect
        if (platform is InbarPlatform && !canAccessInbar) {
            isLoading = false
            errorMessages[page] = "Tap Load Inbar schedule to view your timetable."
            return@LaunchedEffect
        }
        val platformSelection = "${platform?.id}:$selectedValue"
        if (loadedSelection != platformSelection) {
            allSchedulesUpdated.clear()
            allSchedulesOriginal.clear()
            errorMessages.clear()
            loadedSelection = platformSelection
        }

        // 1. Check if we already have the data for the current page and selected filters.
        // If the data is already present, skip the network request.
        val isUpdatedDataPresent = allSchedulesUpdated.containsKey(page)
        val isOriginalDataPresent = allSchedulesOriginal.containsKey(page)

        if (isUpdatedDataPresent && isOriginalDataPresent) {
            // Data is already cached and loaded for this page/filter combination.
            // If you want to force a refresh when switching, remove this block.
            // For now, let's keep it to prevent unnecessary fetches.
            isLoading = false
            return@LaunchedEffect
        }

        // 2. Start loading and clear any previous error for this page
        isLoading = allSchedulesUpdated.isEmpty()
        errorMessages[page] = null

        try {
            Log.d("SchedulePage", "Fetching schedule for $selectedValue and page $page")

            if (platform != null) {
                val requestPlatform = if (platform is InbarPlatform) withContext(Dispatchers.IO) {
                    PlatformStorage.loadPlatforms(context).firstOrNull { it.id == platform.id } ?: platform
                } else platform
                if (requestPlatform is InbarPlatform) recoveredProviders = recoveredProviders + (requestPlatform.id to requestPlatform)
                // Check if the page is STILL the current page before starting.
                // This is a minimal guard, but the cancellation handling is more important.

                // --- Updated schedule ---
                // Use Dispatchers.Default for the blocking network call instead of IO,
                // but the original IO is fine if the function handles IO internally.
                // The main thing is that the LaunchedEffect's coroutine is still prone to cancellation.

                val updated = withContext(Dispatchers.IO) {
                    // IMPORTANT: The fetch function itself must be cancellable
                    // (e.g., using coroutineScope.ensureActive() inside the low-level logic,
                    // or using a cancellable HTTP client). Assuming the `platform.getSchedule` is blocking:

                    val schedule = requestPlatform.getSchedule(page, null, selectedValue)

                    // detect if an error object is returned
                    if (schedule.has("error")) {
                        val errorCode = schedule.optString("error")
                        val inbar = requestPlatform as? InbarPlatform
                        if (errorCode == "login_failed" && inbar != null &&
                            inbarAllowed() && inbar.hasSavedLoginDetails() && !reloginAttempted && inbarRelogin == null) {
                            reloginAttempted = true
                            inbarRelogin = inbar.forSmsLogin()
                            return@withContext emptyList()
                        }
                        val webtop = platform as? WebtopPlatform
                        if (errorCode == "login_failed" && webtop != null &&
                            webtop.needsInteractiveRelogin() && !reloginAttempted
                        ) {
                            Log.d("SchedulePage", "Webtop session expired; re-signing in off-screen")
                            reloginAttempted = true
                            webtopRelogin = webtop
                            return@withContext emptyList()
                        }
                        val err = when (errorCode) {
                            "server_unreachable" -> "Cannot reach the server.\nCheck your internet connection."
                            "login_failed" -> "Login failed.\nPlease re-login."
                            else -> "Unknown error occurred while loading schedule."
                        }
                        errorMessages[page] = err
                        emptyList()
                    } else {
                        schedule.keys().asSequence().map { schedule.getJSONObject(it) }.toList()
                    }
                }
                if (inbarRelogin != null) return@LaunchedEffect
                // Update the state *after* the blocking call, back on the main thread (which LaunchedEffect runs on).
                allSchedulesUpdated[page] = updated

                // --- Original schedule ---
                val original = if (!supportsOriginal) updated else withContext(Dispatchers.IO) {
                    val schedule = requestPlatform.getOriginalSchedule(page, null, selectedValue)
                    schedule.keys().asSequence().map { schedule.getJSONObject(it) }.toList()
                }
                allSchedulesOriginal[page] = original

                if ((platform is MashovPlatform || platform is WebtopPlatform ||
                        scheduleInfo?.optString("scheduleKind") == "weekly") && errorMessages[page] == null) {
                    val remainingDays = withContext(Dispatchers.IO) {
                        dayNames.indices
                            .filter { it != page }
                            .associateWith { day ->
                                val updatedSchedule = requestPlatform.getSchedule(day, null, selectedValue)
                                val originalSchedule = if (supportsOriginal) requestPlatform.getOriginalSchedule(day, null, selectedValue)
                                    else updatedSchedule
                                val updatedItems = updatedSchedule.keys().asSequence()
                                    .map { updatedSchedule.getJSONObject(it) }
                                    .toList()
                                val originalItems = originalSchedule.keys().asSequence()
                                    .map { originalSchedule.getJSONObject(it) }
                                    .toList()
                                updatedItems to originalItems
                            }
                    }
                    val newUpdated = remainingDays.mapValues { it.value.first }
                    val newOriginal = remainingDays.mapValues { it.value.second }
                    androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
                        allSchedulesUpdated.putAll(newUpdated)
                        allSchedulesOriginal.putAll(newOriginal)
                    }
                }

            } else {
                errorMessages[page] = "No platform account found."
            }
        } catch (e: Exception) {
        // 1. Check for the standard Kotlin Coroutines CancellationException.
        // LeftCompositionCancellationException inherits from CancellationException.
        if (e is kotlinx.coroutines.CancellationException) {
            // This is an expected signal from Compose/Coroutines when the tab is switched.
            // Ignore this and do not log it as a critical error or show a message to the user.
            Log.d("SchedulePage", "Coroutine cancelled (expected on tab switch)")
        } else {
            // 2. Handle all other unexpected errors
            Log.e("SchedulePage", "Error fetching schedule", e)
            errorMessages[pagerState.currentPage] = "Unknown error occurred while loading schedule."
        }
    } finally {
        // This finally block always executes, whether cancelled or not,
        // ensuring the loading state is reset.
        isLoading = inbarRelogin != null || webtopRelogin != null
    }
    }

    val weekTimesByHour = remember(allSchedulesUpdated.size, allSchedulesOriginal.size, platform?.id) {
        buildMap {
            platform?.getInfo()?.optJSONObject("lessonTimes")?.let { bells ->
                bells.keys().forEach { key ->
                    val hour = key.toIntOrNull() ?: return@forEach
                    val time = bells.optString(key)
                    if (time.isNotBlank()) put(hour, time)
                }
            }
            (allSchedulesUpdated.values + allSchedulesOriginal.values)
                .flatten()
                .forEach { lesson ->
                    val hour = hourOf(lesson)
                    val time = lesson.optString("time")
                    if (hour >= 0 && time.isNotBlank()) putIfAbsent(hour, time)
                }
        }
    }

    val fetchesAllDaysTogether = remember(platform?.id) {
        platform is WebtopPlatform || platform is MashovPlatform ||
                platform?.getInfo()?.optString("scheduleKind") == "weekly"
    }

    var autoAdvancedToTomorrow by remember(platform?.id, selectedValue) {
        mutableStateOf(false)
    }

    LaunchedEffect(allSchedulesUpdated[todayPage], fetchesAllDaysTogether) {
        if (!fetchesAllDaysTogether || autoAdvancedToTomorrow) return@LaunchedEffect
        if (todayPage == -1 || pagerState.currentPage != todayPage) return@LaunchedEffect

        val todayLessons = allSchedulesUpdated[todayPage] ?: return@LaunchedEffect
        if (todayLessons.isEmpty()) return@LaunchedEffect

        val realLessons = todayLessons.filter {
            !it.optBoolean(WINDOW_FLAG, false) &&
                    !PlatformStorage.isWindowSubject(it.optString("subject"), windowSubjects) &&
                    it.optString("colorClass") != "cancel-cell"
        }

        val lastLesson = realLessons.maxByOrNull { hourOf(it) } ?: todayLessons.maxByOrNull { hourOf(it) }
        if (lastLesson != null) {
            val lastHour = hourOf(lastLesson)
            val timeStr = lastLesson.optString("time").ifBlank {
                weekTimesByHour[lastHour].orEmpty()
            }
            val endTimeMinutes = parseLessonEndTimeMinutes(timeStr, lastHour)
            val nowCal = Calendar.getInstance()
            val currentMins = nowCal.get(Calendar.HOUR_OF_DAY) * 60 + nowCal.get(Calendar.MINUTE)

            if (currentMins >= endTimeMinutes) {
                autoAdvancedToTomorrow = true
                pagerState.animateScrollToPage(tomorrowPage)
            }
        }
    }

    inbarRelogin?.takeIf { canAccessInbar }?.let { pending ->
        HiddenInbarLogin(account = pending,
            reuseSavedSession = { withContext(Dispatchers.IO) { PlatformStorage.restoreVerifiedInbarSession(context, pending) } },
            onSmsRequested = { account -> withContext(Dispatchers.IO) { PlatformStorage.addPlatforms(context, listOf(account)) } },
            onResult = { account, error ->
                if (account != null) {
                    withContext(Dispatchers.IO) { PlatformStorage.addPlatforms(context, listOf(account)) }
                    inbarRelogin = null
                    recoveredProviders = recoveredProviders + (account.id to account)
                    allSchedulesUpdated.clear()
                    allSchedulesOriginal.clear()
                    errorMessages.clear()
                    loadedSelection = null
                    reloginAttempted = false
                    scheduleReloadTrigger++
                } else {
                    inbarRelogin = null
                    errorMessages[pagerState.currentPage] = error ?: "Login failed.\nPlease re-login."
                    isLoading = false
                }
            })
    }

    webtopRelogin?.let { webtop ->
        HiddenWebtopMoeLogin(
            username = webtop.getUsername().orEmpty(),
            password = webtop.getPassword().orEmpty()
        ) { key, error ->
            webtopRelogin = null
            if (key == null) {
                Log.w("SchedulePage", "Webtop re-login failed: " + (error ?: "no key"))
                errorMessages[pagerState.currentPage] = "Login failed.\nPlease re-login."
                return@HiddenWebtopMoeLogin
            }
            coroutineScope.launch {
                val ok = withContext(Dispatchers.IO) {
                    webtop.adoptSession(key).also { success ->
                        if (success) {
                            val saved = PlatformStorage.loadPlatforms(context).toMutableList()
                            val idx = saved.indexOfFirst { it.id == webtop.id }
                            if (idx >= 0) saved[idx] = webtop else saved += webtop
                            PlatformStorage.savePlatforms(context, saved)
                        }
                    }
                }
                if (ok) {
                    reloginAttempted = false
                    errorMessages.remove(pagerState.currentPage)
                    allSchedulesUpdated.remove(pagerState.currentPage)
                    allSchedulesOriginal.remove(pagerState.currentPage)
                    scheduleReloadTrigger++
                } else {
                    errorMessages[pagerState.currentPage] = "Login failed.\nPlease re-login."
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
    ) {
        // Same top inset as Grades and Attendance, so the provider picker lands in
        // the same place on all three pages.
        Spacer(Modifier.height(TopBarSpacing()))
        val scheduleMode = remember { mutableStateOf(ScheduleMode.Updated) }
        LaunchedEffect(supportsOriginal) {
            if (!supportsOriginal) scheduleMode.value = ScheduleMode.Updated
        }

        if (schedulePlatforms.size > 1) {
            ProviderPickerBar(
                providers = schedulePlatforms,
                selectedIndex = selectedPlatformIndex,
                onSelected = {
                    if (schedulePlatforms.getOrNull(it) is InbarPlatform) inbarRequestedByUser = true
                    selectedPlatformIndex = it
                },
                expanded = platformPickerExpanded,
                onExpandedChange = { platformPickerExpanded = it },
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(Modifier.height(8.dp))
        }

        if (supportsAcademicSelection) Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        ) {
            Box(Modifier.weight(1f)) {
                ChipPicker(label = stringResource(R.string.year), options = (academicYears + academicYear).distinct(),
                    selected = academicYear, onSelectedChange = { inbarRequestedByUser = true; academicYear = it; reloginAttempted = false })
            }
            Box(Modifier.weight(1f)) {
                ChipPicker(label = stringResource(R.string.semester), options = academicPeriods.values.toList(),
                    selected = academicPeriods[academicPeriod].orEmpty(),
                    onSelectedChange = { label ->
                        inbarRequestedByUser = true
                        academicPeriods.entries.firstOrNull { it.value == label }?.let { academicPeriod = it.key }
                        reloginAttempted = false
                    })
            }
        }
        if (supportsOriginal || supportsSelection) Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            if (supportsOriginal) Box(modifier = Modifier.weight(1f)) {
                val original = stringResource(R.string.original)
                val updated = stringResource(R.string.updated)
                ChipPicker(
                    label = "Version",
                    options = listOf(
                        original,
                        updated
                    ),
                    selected = when (scheduleMode.value) {
                        ScheduleMode.Original -> original
                        ScheduleMode.Updated -> updated
                    },
                    onSelectedChange = { newValue ->
                        scheduleMode.value = if (newValue == original)
                            ScheduleMode.Original
                        else
                            ScheduleMode.Updated
                    }
                )
            }
            if (supportsSelection) Box(modifier = Modifier.weight(1f)) {
                ChipPicker(
                    label = stringResource(R.string.grade), // "שכבה"
                    options = ((1..12).map(Int::toString) + selectedGrade).distinct(),
                    selected = selectedGrade,
                    onSelectedChange = { selectedGrade = it }
                )
            }

            if (supportsSelection) Box(modifier = Modifier.weight(1f)) {
                ChipPicker(
                    label = stringResource(R.string.classroom), // "כיתה"
                    options = ((1..9).map(Int::toString) + selectedClass).distinct(),
                    selected = selectedClass,
                    onSelectedChange = { selectedClass = it }
                )
            }

        }

        Spacer(modifier = Modifier.height(12.dp))

        PrimaryTabRow(
            selectedTabIndex = pagerState.currentPage,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth(),
            containerColor = MaterialTheme.colorScheme.background
        ) {
            dayNames.forEachIndexed { index, day ->
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = { coroutineScope.launch { pagerState.animateScrollToPage(index) } },
                    selectedContentColor = MaterialTheme.colorScheme.primary,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                ) {
                    Text(
                        text = day,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(horizontal = 3.dp, vertical = 16.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        HorizontalPager(state = pagerState) { page ->
            val rawItems = when (scheduleMode.value) {
                ScheduleMode.Updated -> allSchedulesUpdated[page] ?: emptyList()
                ScheduleMode.Original -> allSchedulesOriginal[page] ?: emptyList()
            }
            val scheduleItems = remember(rawItems, windowSubjects, weekTimesByHour, numberedPeriods) {
                withFreePeriods(rawItems, windowSubjects, weekTimesByHour, synthesizeGaps = numberedPeriods)
            }

            val errMessage = errorMessages[page]
            val pageFetched = allSchedulesUpdated.containsKey(page)

            when {
                errMessage == null && (!pageFetched || (isLoading && scheduleItems.isEmpty())) -> {
                    GradesLoadingIndicator(modifier = Modifier.fillMaxSize())
                }
                errMessage != null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = errMessage,
                            color = Color.Red,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontWeight = FontWeight.Medium,
                                fontSize = 18.sp
                            )
                        )
                        val inbar = platform as? InbarPlatform
                        if (inbar != null && inbar.hasSavedLoginDetails()) {
                            TextButton(onClick = {
                                inbarRequestedByUser = true
                                errorMessages[page] = null
                                isLoading = true
                                if (canAccessInbar) inbarRelogin = inbar.forSmsLogin()
                                else scheduleReloadTrigger++
                            }) { Text(if (canAccessInbar) "Retry login" else "Load Inbar schedule") }
                        }
                        }
                    }
                }
                scheduleItems.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        NoScheduleState(
                            dayName = fullDayNames[page],
                            isToday = page == todayPage
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .fillMaxSize(),
                    ) {
                        itemsIndexed(
                            scheduleItems,
                            key = { hourIndex, lessonsInHour ->
                                val first = lessonsInHour.firstOrNull()
                                "hour_${hourIndex}_${first?.let { hourOf(it) }}_${first?.optString("subject")}_${lessonsInHour.size}"
                            }
                        ) { hourIndex, lessonsInHour ->
                            val firstLesson = lessonsInHour.first()
                            val hourNum = hourOf(firstLesson)
                            val time = lessonsInHour.firstOrNull { it.optString("time").isNotBlank() }?.optString("time").orEmpty()

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    lessonsInHour.forEachIndexed { subIndex, item ->
                                        key(item.optString("subject") + "_" + item.optInt("hour", subIndex) + "_" + subIndex) {
                                            ScheduleCardConnected(
                                                item = item,
                                                position = if (lessonsInHour.size == 1) {
                                                    MotionItemPosition.Alone
                                                } else {
                                                    when (subIndex) {
                                                        0 -> MotionItemPosition.Start
                                                        lessonsInHour.lastIndex -> MotionItemPosition.End
                                                        else -> MotionItemPosition.Middle
                                                    }
                                                },
                                                color = scheduleColor(item.optString("subject")),
                                                index = hourIndex * 10 + subIndex
                                            )
                                        }
                                    }
                                }

                                HourLabel(hour = if (numberedPeriods) hourNum else -1, time = time)
                            }

                            Spacer(Modifier.height(2.dp))
                        }

                        item {
                            Spacer(Modifier.height(180.dp))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ScheduleCardConnected(
    item: JSONObject,
    position: MotionItemPosition,
    color: MotionIconBackground,
    index: Int
) {
    // A free period is drawn in the plain surface colour so it reads as a gap
    // between lessons rather than a lesson of its own.
    if (item.optBoolean(WINDOW_FLAG, false)) {
        FreePeriodCard(position = position, replacedSubject = item.optString("subject"))
        return
    }

    val isCancel = item.optString("colorClass") == "cancel-cell"
    val actualColor = if (isCancel) {
        MotionIconBackground(
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            onColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        color
    }

    MotionCard(
        position = position,
        containerColor = actualColor.color,
        contentPadding = 12.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {

            val subjectRaw = item.optString("subject")
            val subjectLevel = item.optString("subjectLevel")
            val subjectFormatted = remember(subjectRaw, subjectLevel) {
                val locale = java.util.Locale.forLanguageTag("he")
                val cleanLevel = subjectLevel.replace("``", "\"").trim()
                val combined = if (cleanLevel.isNotBlank() && !subjectRaw.contains(cleanLevel)) {
                    "\u200F$subjectRaw\u200F • \u200F$cleanLevel\u200F"
                } else {
                    subjectRaw
                }
                val fixed = formatBidiHebrewWithLatin(combined)
                BidiFormatter.getInstance(locale).unicodeWrap(fixed)
            }
            val changes = item.optString("changes")
            val exams = item.optString("exams")
            val teacher = item.optString("teacher")
            val room = item.optString("room")
            val roomLabel = stringResource(R.string.room)
            val details = remember(teacher, room) {
                val raw = when {
                    teacher.isNotBlank() && room.isNotBlank() -> "$teacher, $roomLabel: $room"
                    teacher.isNotBlank() -> teacher
                    room.isNotBlank() -> "$roomLabel: $room"
                    else -> ""
                }
                if (raw.isNotBlank()) {
                    val locale = java.util.Locale.forLanguageTag("he")
                    BidiFormatter.getInstance(locale).unicodeWrap(formatBidiHebrewWithLatin(raw))
                } else ""
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = subjectFormatted,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Start,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        textDirection = TextDirection.ContentOrRtl,
                        localeList = LocaleList(Locale("he"), Locale("en"))
                    ),
                    color = actualColor.onColor
                )
                if (details.isNotBlank()) {
                    Text(
                        text = details,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Start,
                        fontSize = 14.sp,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            textDirection = TextDirection.ContentOrRtl,
                            localeList = LocaleList(Locale("he"), Locale("en"))
                        ),
                        color = actualColor.onColor
                    )
                }

                val extraText = when {
                    changes.isNotEmpty() -> changes
                    exams.isNotEmpty() -> exams
                    else -> null
                }
                if (extraText != null) {
                    Text(
                        text = extraText,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Start,
                        fontSize = 14.sp,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            textDirection = TextDirection.ContentOrRtl,
                            localeList = LocaleList(Locale("he"), Locale("en"))
                        ),
                        color = actualColor.onColor
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            SubjectIcon(
                subject = subjectRaw,
                assignment = if (exams.isNotEmpty()) "exam" else "",
                containerColor = Color.White.copy(alpha = 0.36f),
                iconColor = actualColor.onColor,
                size = 52.dp,
                iconSize = 30.dp,
                polygon = scheduleIconShapes[index % scheduleIconShapes.size],
                morphPolygon = scheduleIconShapes[(index + 3) % scheduleIconShapes.size]
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private val scheduleIconShapes = listOf(
    MaterialShapes.Circle,
    MaterialShapes.Gem,
    MaterialShapes.Cookie6Sided,
    MaterialShapes.Cookie9Sided,
    MaterialShapes.Sunny,
    MaterialShapes.Cookie12Sided
)

private fun scheduleColor(subject: String): MotionIconBackground {
    val normalized = subject.trim().lowercase().filter(Char::isLetterOrDigit)
    val index = Math.floorMod(normalized.hashCode(), MotionThemeDefaults.VibrantIconBackgrounds.size)
    return MotionThemeDefaults.VibrantIconBackgrounds[index]
}

@Composable
fun ScheduleCard(item: JSONObject) {
    val colors = getColorFromClass(item.optString("colorClass"))

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp),
        colors = CardDefaults.cardColors(containerColor = colors.background)

    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            val onSurface = MaterialTheme.colorScheme.onSurface
            // Reverse color (invert RGB values)
            val reversedColor = Color(
                red = 1f - onSurface.red,
                green = 1f - onSurface.green,
                blue = 1f - onSurface.blue,
                alpha = onSurface.alpha
            )

            val subjectRaw = item.optString("subject")

            val subjectBidi = remember(subjectRaw) {
                val locale = java.util.Locale.forLanguageTag("he")
                BidiFormatter.getInstance(locale).unicodeWrap(subjectRaw)
            }


            Text(
                text = subjectBidi,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    textDirection = TextDirection.ContentOrRtl,
                    localeList = LocaleList(
                        Locale("he"),
                        Locale("en")
                    )
                ),
                color = reversedColor,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Start                     // or Center if you prefer
            )

            Text(
                text = item.optString("teacher"),
                fontSize = 14.sp,
                color = reversedColor
            )
            if (item.optString("changes").isNotEmpty()) {
                Text(
                    text = item.optString("changes"),
                    fontSize = 14.sp,
                    color = reversedColor
                )
            } else if (item.optString("exams").isNotEmpty()) {
                Text(
                    text = item.optString("exams"),
                    fontSize = 14.sp,
                    color = reversedColor
                )
            }
        }
    }
}

data class ThemedColor(val background: Color, val content: Color)

@Composable
fun getColorFromClass(colorClass: String): ThemedColor {
    val isDark = isSystemInDarkTheme()
    if (isDark) {

        return when (colorClass) {
            "pink-cell" -> ThemedColor(Color(0xffd5a7d1), Color.Black)
            "lightgreen-cell" -> ThemedColor(Color(0xff8bc58a), Color.Black)
            "lightyellow-cell" -> ThemedColor(Color(0xffd5da94), Color.Black)
            "lightblue-cell" -> ThemedColor(Color(0xff8ec3d8), Color.Black)
            "lightred-cell" -> ThemedColor(Color(0xffe4958b), Color.Black)
            "lightpurple-cell" -> ThemedColor(Color(0xffb198d3), Color.Black)
            "lightorange-cell" -> ThemedColor(Color(0xffdaba90), Color.Black)
            "blue-cell" -> ThemedColor(Color(0xffb0bdff), Color.Black)
            "lime-cell" -> ThemedColor(Color(0xffaacd8d), Color.Black)
            "lightgrey-cell" -> ThemedColor(Color(0xff93999e), Color.Black)
            "custom-pink-cell" -> ThemedColor(Color(0xffd791dc), Color.Black)
            "cancel-cell" -> ThemedColor(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurfaceVariant)
            else -> ThemedColor(Color.White, Color.Black)
        }
    }
    else {

        return when (colorClass) {
            "pink-cell" -> ThemedColor(Color(0xffffc7fa), Color.Black)
            "lightgreen-cell" -> ThemedColor(Color(0xffb8ffb4), Color.Black)
            "lightyellow-cell" -> ThemedColor(Color(0xffe1e880), Color.Black)
            "lightblue-cell" -> ThemedColor(Color(0xffbaeaff), Color.Black)
            "lightred-cell" -> ThemedColor(Color(0xffffaaa0), Color.Black)
            "lightpurple-cell" -> ThemedColor(Color(0xffd2afff), Color.Black)
            "lightorange-cell" -> ThemedColor(Color(0xffffd599), Color.Black)
            "blue-cell" -> ThemedColor(Color(0xffb0bdff), Color.Black)
            "lime-cell" -> ThemedColor(Color(0xffd8ffa9), Color.Black)
            "lightgrey-cell" -> ThemedColor(Color(0xffb6bcc1), Color.Black)
            "custom-pink-cell" -> ThemedColor(Color(0xfffab5ff), Color.Black)
            "cancel-cell" -> ThemedColor(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurfaceVariant)
            else -> ThemedColor(Color.White, Color.Black)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NoScheduleState(
    dayName: String,
    isToday: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        SubjectIcon(
            subject = "Schedule",
            containerColor = MaterialTheme.colorScheme.primary,
            iconColor = MaterialTheme.colorScheme.onPrimary,
            size = 128.dp,
            iconSize = 68.dp,
            painter = rememberSymbolPainter(MotionSymbols.ic_schedule),
            polygon = MaterialShapes.Cookie12Sided
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = if (isToday) {
                stringResource(R.string.noSchedule)
            } else {
                stringResource(R.string.noScheduleForDay, dayName)
            },
            style = MaterialTheme.typography.headlineMedium,
            fontFamily = MotionFonts.feldman(weight = 600, width = 140f),
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
    }
}

/** Marks a synthesised free period ("חלון") rather than a real lesson. */
const val WINDOW_FLAG = "isWindow"

/**
 * Expand a day's lessons into a continuous run of hours.
 *
 * Two kinds of free period ("חלון") end up in the list:
 *  * gaps — hours with no lesson at all, including the ones before the first
 *    lesson, so a day that starts at hour 3 still shows hours 0-2; and
 *  * subjects the user marked as free in provider settings (e.g. a bagrut
 *    already completed), which are kept in place but shown as a window.
 *
 * The run always starts at hour 0 and ends at the last hour that has a real
 * lesson, so trailing empty hours are not invented.
 */
fun withFreePeriods(
    items: List<JSONObject>,
    windowSubjects: Set<String>,
    /**
     * Hour -> clock time gathered from every loaded day. A gap has no lesson of
     * its own to take a time from, and schools run a fixed bell schedule, so the
     * time hour 3 has on other days is the time hour 3 has today.
     */
    weekTimesByHour: Map<Int, String> = emptyMap(),
    synthesizeGaps: Boolean = true,
): List<List<JSONObject>> {
    if (items.isEmpty()) return emptyList()

    val byHour = HashMap<Int, MutableList<JSONObject>>()
    val timesByHour = HashMap<Int, String>(weekTimesByHour)
    for (item in items) {
        val h = hourOf(item)
        if (h >= 0) {
            byHour.getOrPut(h) { mutableListOf() }.add(item)
            item.optString("time").takeIf { it.isNotBlank() }?.let { timesByHour[h] = it }
        }
    }
    if (byHour.isEmpty()) return emptyList()

    // Only extend to the last REAL lesson: a trailing window is not a window.
    val lastRealHour = byHour.entries
        .filter { entry ->
            entry.value.any {
                !it.optBoolean(WINDOW_FLAG, false) &&
                        !PlatformStorage.isWindowSubject(it.optString("subject"), windowSubjects) &&
                        it.optString("colorClass") != "cancel-cell"
            }
        }
        .maxOfOrNull { it.key } ?: (byHour.keys.maxOrNull() ?: 0)

    val out = ArrayList<List<JSONObject>>()
    val hours = if (synthesizeGaps) (0..lastRealHour).toList() else byHour.keys.filter { it <= lastRealHour }.sorted()
    for (hour in hours) {
        val lessons = byHour[hour]
        when {
            lessons.isNullOrEmpty() ->
                out.add(
                    listOf(
                        JSONObject()
                            .put("num", hour)
                            .put("hour", hour)
                            .put(WINDOW_FLAG, true)
                            .put("subject", "")
                            .put("time", timesByHour[hour].orEmpty())
                    )
                )

            lessons.all { PlatformStorage.isWindowSubject(it.optString("subject"), windowSubjects) } -> {
                out.add(
                    lessons.map { lesson ->
                        JSONObject(lesson.toString())
                            .put("num", hour)
                            .put("hour", hour)
                            .put(WINDOW_FLAG, true)
                            .apply {
                                if (optString("time").isBlank()) {
                                    put("time", timesByHour[hour].orEmpty())
                                }
                            }
                    }
                )
            }

            else -> {
                out.add(
                    lessons.map { lesson ->
                        val isWindow = PlatformStorage.isWindowSubject(lesson.optString("subject"), windowSubjects)
                        if (isWindow) {
                            JSONObject(lesson.toString())
                                .put("num", hour)
                                .put("hour", hour)
                                .put(WINDOW_FLAG, true)
                                .apply {
                                    if (optString("time").isBlank()) {
                                        put("time", timesByHour[hour].orEmpty())
                                    }
                                }
                        } else {
                            lesson.apply {
                                if (optString("time").isBlank()) {
                                    put("time", timesByHour[hour].orEmpty())
                                }
                            }
                        }
                    }
                )
            }
        }
    }
    out.sortBy { list -> list.firstOrNull()?.let { hourOf(it) } ?: -1 }
    return out
}

fun formatBidiHebrewWithLatin(text: String): String {
    val rlm = "\u200F"
    return text
        .replace(Regex("([A-Za-z0-9])(\\s*[•\\-–—\\(\\)\\[\\]:,/])")) { match ->
            "${match.groupValues[1]}$rlm${match.groupValues[2]}"
        }
        .replace(Regex("([•\\-–—\\(\\)\\[\\]:,/]\\s*)([A-Za-z0-9])")) { match ->
            "${match.groupValues[1]}$rlm${match.groupValues[2]}"
        }
}

fun hourOf(o: JSONObject): Int =
    o.optInt("num", o.optInt("hour", o.optString("num").toIntOrNull() ?: o.optString("hour").toIntOrNull() ?: -1))

fun parseLessonEndTimeMinutes(timeStr: String, hourNum: Int): Int {
    val times = Regex("""\d{1,2}:\d{2}""").findAll(timeStr).map { it.value }.toList()
    if (times.isNotEmpty()) {
        val latest = times.maxByOrNull { t ->
            val parts = t.split(":").mapNotNull { it.toIntOrNull() }
            if (parts.size == 2) parts[0] * 60 + parts[1] else 0
        }
        if (latest != null) {
            val p = latest.split(":").mapNotNull { it.toIntOrNull() }
            if (p.size == 2) return p[0] * 60 + p[1]
        }
    }
    return when (hourNum) {
        0 -> 8 * 60
        1 -> 8 * 60 + 45
        2 -> 9 * 60 + 30
        3 -> 10 * 60 + 30
        4 -> 11 * 60 + 15
        5 -> 12 * 60 + 15
        6 -> 13 * 60
        7 -> 14 * 60 + 10
        8 -> 14 * 60 + 55
        9 -> 15 * 60 + 50
        10 -> 16 * 60 + 35
        else -> 8 * 60 + (hourNum * 55)
    }
}

/**
 * The hour number shown beside every row, including hour zero, so gaps in the
 * day stay countable at a glance.
 */
@Composable
fun HourLabel(hour: Int, time: String = "") {
    // A fixed width, always: sizing it to the content made every card in a day
    // shift sideways depending on whether that hour happened to have a time.
    Column(
        modifier = Modifier.width(52.dp).padding(start = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (hour >= 0) {
            Text(
                text = hour.toString(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (time.isNotBlank()) {
            time.split(" - ").forEach { part ->
                Text(
                    text = part,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * A free period ("חלון"): an hour with no lesson, or one whose subject the user
 * marked as free in provider settings.
 *
 * Deliberately built like a normal lesson card -- same height, same icon and
 * text layout -- so the day reads as one continuous column. Only the teacher /
 * room line is dropped, since there is nobody to name; `replacedSubject` is the
 * lesson that was dropped, shown so it is clear why the hour is free.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FreePeriodCard(position: MotionItemPosition, replacedSubject: String = "") {
    val container = MaterialTheme.colorScheme.surfaceVariant
    val onContainer = MaterialTheme.colorScheme.onSurfaceVariant
    MotionCard(
        position = position,
        containerColor = container,
        contentPadding = 12.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.free_period),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Start,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        textDirection = TextDirection.ContentOrRtl,
                        localeList = LocaleList(Locale("he"), Locale("en"))
                    ),
                    color = onContainer
                )
                // Second line keeps the card the same height as a lesson card.
                Text(
                    text = replacedSubject,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Start,
                    fontSize = 14.sp,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        textDirection = TextDirection.ContentOrRtl,
                        localeList = LocaleList(Locale("he"), Locale("en"))
                    ),
                    color = onContainer.copy(alpha = 0.7f)
                )
            }
            Spacer(Modifier.width(12.dp))
            SubjectIcon(
                subject = "",
                assignment = "free",
                painter = rememberSymbolPainter(MotionSymbols.ic_weekend, fill = 1f),
                containerColor = onContainer.copy(alpha = 0.14f),
                iconColor = onContainer,
                size = 52.dp,
                iconSize = 30.dp,
            )
        }
    }
}
