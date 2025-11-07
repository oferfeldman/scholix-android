import android.text.BidiFormatter
import android.util.Log
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import com.feldman.scholix.api.PlatformStorage
import com.feldman.scholix.pages.ChipPicker
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
                label = stringResource(R.string.grade), // "שכבה"
                options = listOf("7", "8", "9"),
                selected = grade,
                onSelectedChange = onGradeChange
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            ChipPicker(
                label = stringResource(R.string.classroom), // "כיתה"
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
        isLoading = true
        errorMessages[pagerState.currentPage] = null
        try {
            Log.d("SchedulePage", "Fetching schedule for $selectedValue")

            val platform = PlatformStorage.getPlatform(context, 0)
            if (platform != null) {
                // --- Updated schedule ---
                val updated = withContext(Dispatchers.IO) {
                    val schedule = platform.getSchedule(pagerState.currentPage, null, selectedValue)

                    // detect if an error object is returned
                    if (schedule.has("error")) {
                        val err = when (schedule.optString("error")) {
                            "server_unreachable" -> "Cannot reach the server.\nCheck your internet connection."
                            "login_failed" -> "Login failed.\nPlease re-login."
                            else -> "Unknown error occurred while loading schedule."
                        }
                        errorMessages[pagerState.currentPage] = err
                        emptyList()
                    } else {
                        schedule.keys().asSequence().map { schedule.getJSONObject(it) }.toList()
                    }
                }
                allSchedulesUpdated[pagerState.currentPage] = updated

                // --- Original schedule ---
                val original = withContext(Dispatchers.IO) {
                    val schedule = platform.getOriginalSchedule(pagerState.currentPage, null, selectedValue)
                    schedule.keys().asSequence().map { schedule.getJSONObject(it) }.toList()
                }
                allSchedulesOriginal[pagerState.currentPage] = original
            } else {
                errorMessages[pagerState.currentPage] = "No platform account found."
            }
        } catch (e: Exception) {
            Log.e("SchedulePage", "Error fetching schedule", e)
            errorMessages[pagerState.currentPage] = "Unknown error occurred while loading schedule."
        } finally {
            isLoading = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(start = 16.dp, top = 0.dp, end = 16.dp, bottom = 0.dp)
    ) {

        val scheduleMode = remember { mutableStateOf(ScheduleMode.Updated) }

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
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
            modifier = Modifier.fillMaxWidth()
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
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(scheduleItems) { item ->
                            ScheduleCard(item)
                        }
                    }
                }
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
    val darkTheme = !isSystemInDarkTheme()
    return when (colorClass) {
        "pink-cell" -> if (darkTheme) {
            ThemedColor(Color(0xFFD81B60), Color.White)     // dark bg, light text
        } else {
            ThemedColor(Color(0xFFFFCCFB), Color.Black)     // light bg, dark text
        }
        "lightgreen-cell" -> if (darkTheme) {
            ThemedColor(Color(0xFF2E7D32), Color.White)
        } else {
            ThemedColor(Color(0xFFC3FFC1), Color.Black)
        }
        "lightyellow-cell" -> if (darkTheme) {
            ThemedColor(Color(0xFFF9A825), Color.Black)    // yellow is bright → dark text
        } else {
            ThemedColor(Color(0xFFFAFFB8), Color.Black)
        }
        "lightblue-cell" -> if (darkTheme) {
            ThemedColor(Color(0xFF0277BD), Color.White)
        } else {
            ThemedColor(Color(0xFFB6E1EE), Color.Black)
        }
        "lightred-cell" -> if (darkTheme) {
            ThemedColor(Color(0xFFC62828), Color.White)
        } else {
            ThemedColor(Color(0xFFFFBAB3), Color.Black)
        }
        "lightpurple-cell" -> if (darkTheme) {
            ThemedColor(Color(0xFF6A1B9A), Color.White)
        } else {
            ThemedColor(Color(0xFFDBC2FF), Color.Black)
        }
        "lightorange-cell" -> if (darkTheme) {
            ThemedColor(Color(0xFFEF6C00), Color.White)
        } else {
            ThemedColor(Color(0xFFFFCFA6), Color.Black)
        }
        "blue-cell" -> if (darkTheme) {
            ThemedColor(Color(0xFF303F9F), Color.White)
        } else {
            ThemedColor(Color(0xFFB5C2FF), Color.Black)
        }
        "lime-cell" -> if (darkTheme) {
            ThemedColor(Color(0xFF827717), Color.White)
        } else {
            ThemedColor(Color(0xFFEBFFBC), Color.Black)
        }
        "lightgrey-cell" -> if (darkTheme) {
            ThemedColor(Color(0xFF37474F), Color.White)
        } else {
            ThemedColor(Color(0xFFCFD5D9), Color.Black)
        }
        "custom-pink-cell" -> if (darkTheme) {
            ThemedColor(Color(0xFFAD1457), Color.White)
        } else {
            ThemedColor(Color(0xFFF8C8FA), Color.Black)
        }
        "cancel-cell" -> if (darkTheme) {
            ThemedColor(Color(0xFF4E342E), Color.White)
        } else {
            ThemedColor(Color(0xFF7D5B5D), Color.White) // keep white text even in light
        }
        else -> if (darkTheme) {
            ThemedColor(Color.DarkGray, Color.White)
        } else {
            ThemedColor(Color.White, Color.Black)
        }
    }
}