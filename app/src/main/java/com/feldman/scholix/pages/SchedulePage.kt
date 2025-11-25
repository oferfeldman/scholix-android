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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import com.feldman.scholix.BOTTOM_BAR_SPACING
import com.feldman.scholix.TOP_BAR_SPACING
import com.feldman.scholix.api.PlatformStorage
import com.feldman.scholix.ui.components.ChipPicker
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
                options = listOf("7", "8", "9"),
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
    modifier: Modifier = Modifier,
) {
    val dayNames = listOf(
        stringResource(R.string.sunday),
        stringResource(R.string.monday),
        stringResource(R.string.tuesday),
        stringResource(R.string.wednesday),
        stringResource(R.string.thursday),
        stringResource(R.string.friday)
    )

    val allSchedulesUpdated = remember { mutableStateMapOf<Int, List<JSONObject>>() }
    val allSchedulesOriginal = remember { mutableStateMapOf<Int, List<JSONObject>>() }
    val errorMessages = remember { mutableStateMapOf<Int, String?>() }

    val today = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
    val initialPage = if (today == Calendar.SATURDAY) 0 else (today + 6) % 7

    val pagerState = rememberPagerState(
        initialPage = initialPage,
        pageCount = { dayNames.size }
    )
    val coroutineScope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(false) }

    var selectedGrade by remember { mutableStateOf("9") }
    var selectedClass by remember { mutableStateOf("6") }
    val selectedValue = "${selectedGrade}|${selectedClass}"
    val context = LocalContext.current

    LaunchedEffect(pagerState.currentPage, selectedValue) {
        val page = pagerState.currentPage

        // 1. Check if we already have the data for the current page and selected filters.
        // If the data is already present, skip the network request.
        val isUpdatedDataPresent = allSchedulesUpdated.containsKey(page) && allSchedulesUpdated[page]?.isNotEmpty() == true
        val isOriginalDataPresent = allSchedulesOriginal.containsKey(page) && allSchedulesOriginal.get(page) != null

        if (isUpdatedDataPresent && isOriginalDataPresent) {
            // Data is already cached and loaded for this page/filter combination.
            // If you want to force a refresh when switching, remove this block.
            // For now, let's keep it to prevent unnecessary fetches.
            isLoading = false
            return@LaunchedEffect
        }

        // 2. Start loading and clear any previous error for this page
        isLoading = true
        errorMessages[page] = null

        try {
            Log.d("SchedulePage", "Fetching schedule for $selectedValue and page $page")

            val platform = PlatformStorage.getPlatform(context, 0)

            if (platform != null) {
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

                    val schedule = platform.getSchedule(page, null, selectedValue)

                    // detect if an error object is returned
                    if (schedule.has("error")) {
                        // ... (error handling logic remains the same)
                        val err = when (schedule.optString("error")) {
                            "server_unreachable" -> "Cannot reach the server.\nCheck your internet connection."
                            "login_failed" -> "Login failed.\nPlease re-login."
                            else -> "Unknown error occurred while loading schedule."
                        }
                        errorMessages[page] = err // Note: updating state in IO is usually fine if it's a MutableStateMap or similar thread-safe structure, but it's safer to do it after withContext. For now, let's keep it clean.
                        emptyList()
                    } else {
                        schedule.keys().asSequence().map { schedule.getJSONObject(it) }.toList()
                    }
                }
                // Update the state *after* the blocking call, back on the main thread (which LaunchedEffect runs on).
                allSchedulesUpdated[page] = updated

                // --- Original schedule ---
                val original = withContext(Dispatchers.IO) {
                    val schedule = platform.getOriginalSchedule(page, null, selectedValue)
                    schedule.keys().asSequence().map { schedule.getJSONObject(it) }.toList()
                }
                allSchedulesOriginal[page] = original

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
        isLoading = false
    }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
    ) {
        Spacer(Modifier.height(TOP_BAR_SPACING+40.dp))
        val scheduleMode = remember { mutableStateOf(ScheduleMode.Updated) }

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
        ) {
            Box(modifier = Modifier.weight(1f)) {
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
            Box(modifier = Modifier.weight(1f)) {
                ChipPicker(
                    label = stringResource(R.string.grade), // "שכבה"
                    options = listOf("7", "8", "9"),
                    selected = selectedGrade,
                    onSelectedChange = { selectedGrade = it }
                )
            }

            Box(modifier = Modifier.weight(1f)) {
                ChipPicker(
                    label = stringResource(R.string.classroom), // "כיתה"
                    options = (1..9).map { it.toString() },
                    selected = selectedClass,
                    onSelectedChange = { selectedClass = it }
                )
            }

        }

        Spacer(modifier = Modifier.height(12.dp))

        PrimaryTabRow(
            selectedTabIndex = pagerState.currentPage,
            modifier = Modifier
                .padding(horizontal = 8.dp)
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
            val scheduleItems = when (scheduleMode.value) {
                ScheduleMode.Updated -> allSchedulesUpdated[page] ?: emptyList()
                ScheduleMode.Original -> allSchedulesOriginal[page] ?: emptyList()
            }

            val errMessage = errorMessages[page]

            when {
                isLoading && scheduleItems.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularWavyProgressIndicator()
                    }
                }
                errMessage != null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = errMessage,
                            color = Color.Red,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontWeight = FontWeight.Medium,
                                fontSize = 18.sp
                            )
                        )
                    }
                }
                scheduleItems.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(R.string.noSchedule),
                            color = Color.Gray,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .padding(horizontal = 8.dp)
                            .fillMaxSize(),
//                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
//                        items(scheduleItems) { item ->
//                            ScheduleCard(item)
//                        }
                        itemsIndexed(scheduleItems) { index, item ->
                            val isFirst = index == 0
                            val isLast = index == scheduleItems.lastIndex

                            val shape = when {
                                isFirst && isLast -> RoundedCornerShape(16.dp)
                                isFirst -> RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 4.dp)
                                isLast -> RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = 16.dp, bottomEnd = 16.dp)
                                else -> RoundedCornerShape(4.dp)
                            }

                            ScheduleCardConnected(
                                item = item,
                                shape = shape
                            )

                            Spacer(Modifier.height(2.dp))
                        }

                        item {
                            Spacer(Modifier.height(BOTTOM_BAR_SPACING))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ScheduleCardConnected(
    item: JSONObject,
    shape: RoundedCornerShape
) {
    val colors = getColorFromClass(item.optString("colorClass"))

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = colors.background)

    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.End   // Hebrew natural right side
        ) {

            val subjectRaw = item.optString("subject")
            val subjectFormatted = remember(subjectRaw) {
                val locale = java.util.Locale.forLanguageTag("he")
                BidiFormatter.getInstance(locale).unicodeWrap(subjectRaw)
            }
            val onSurface = MaterialTheme.colorScheme.onSurface

            val reversedColor = Color(
                red = 1f - onSurface.red,
                green = 1f - onSurface.green,
                blue = 1f - onSurface.blue,
                alpha = onSurface.alpha
            )

            Text(
                text = subjectFormatted,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Start,   // Hebrew RTL makes this RIGHT
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    textDirection = TextDirection.ContentOrRtl,
                    localeList = LocaleList(Locale("he"), Locale("en"))
                ),
                color = colors.content
            )

            Text(
                text = item.optString("teacher"),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Start,
                fontSize = 14.sp,
                style = MaterialTheme.typography.bodyMedium.copy(
                    textDirection = TextDirection.ContentOrRtl,
                    localeList = LocaleList(Locale("he"), Locale("en"))
                ),
                color = colors.content
            )

            val changes = item.optString("changes")
            val exams = item.optString("exams")

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
                    color = colors.content
                )
            }
        }
    }
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

    return when (colorClass) {
        "pink-cell" -> ThemedColor(Color(0xffd5a7d1), Color.Black)
        "lightgreen-cell" -> ThemedColor(Color(0xff8bc58a), Color.Black)
        "lightyellow-cell" -> ThemedColor(Color(0xffd5da94), Color.Black)
        "lightblue-cell" -> ThemedColor(Color(0xff92bac8), Color.Black)
        "lightred-cell" -> ThemedColor(Color(0xffcd9189), Color.Black)
        "lightpurple-cell" -> ThemedColor(Color(0xffb198d3), Color.Black)
        "lightorange-cell" -> ThemedColor(Color(0xffffd1b0), Color.Black)
        "blue-cell" -> ThemedColor(Color(0xffb0bdff), Color.Black)
        "lime-cell" -> ThemedColor(Color(0xffaacd8d), Color.Black)
        "lightgrey-cell" -> ThemedColor(Color(0xff93999e), Color.Black)
        "custom-pink-cell" -> ThemedColor(Color(0xffc294c5), Color.Black)
        "cancel-cell" -> ThemedColor(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurfaceVariant)
        else -> ThemedColor(Color.White, Color.Black)
    }
}