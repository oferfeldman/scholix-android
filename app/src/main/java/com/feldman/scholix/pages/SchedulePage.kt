import android.text.BidiFormatter
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.feldman.scholix.ui.components.ActionRow
import com.feldman.scholix.ui.components.SegmentedOption
import kotlinx.coroutines.launch
import org.json.JSONObject
import com.feldman.scholix.R
import androidx.compose.ui.text.intl.LocaleList

enum class ScheduleMode() { Original(), Updated(); }

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SchedulePage(
    modifier: Modifier = Modifier,
    fetchScheduleUpdated: suspend (dayIdx: Int) -> List<JSONObject>,
    fetchScheduleOriginal: suspend (dayIdx: Int) -> List<JSONObject>,
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
    var isUpdated by remember { mutableStateOf(true) }
    val today = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK)
    val initialPage = if (today == java.util.Calendar.SATURDAY) {
        0 // treat Saturday as Sunday
    } else {
        (today + 6) % 7
    }

    val pagerState = rememberPagerState(
        initialPage = initialPage,
        pageCount = { dayNames.size }
    )
    val coroutineScope = rememberCoroutineScope()

    var scheduleItemsUpdated by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var scheduleItemsOriginal by remember { mutableStateOf<List<JSONObject>>(emptyList()) }

    var isLoading by remember { mutableStateOf(false) }




    LaunchedEffect(pagerState.currentPage) {
        isLoading = true
        try {
            val updated = fetchScheduleUpdated(pagerState.currentPage)
            scheduleItemsUpdated = updated
            isLoading = false
            val original = fetchScheduleOriginal(pagerState.currentPage)
            scheduleItemsOriginal = original

        } catch (_: Exception) {
            scheduleItemsUpdated = emptyList()
            scheduleItemsOriginal = emptyList()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(start=16.dp, top=48.dp, end=16.dp, bottom=16.dp)
    ) {
        Text(
            text = stringResource(R.string.schedule),
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp
            ),
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )

        Spacer(modifier = Modifier.height(12.dp))

        val updatedState = remember { mutableStateOf(ScheduleMode.Updated) }

        ActionRow {
            addSegmentedToggleGroup(
                state = updatedState,
                SegmentedOption(
                    ScheduleMode.Original,
                    stringResource(R.string.original),
                    R.drawable.ic_raw,
                    selectedBackgroundColor = MaterialTheme.colorScheme.errorContainer,
                    textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    selectedTextColor = MaterialTheme.colorScheme.onSurface
                ),
                SegmentedOption(
                    ScheduleMode.Updated,
                    stringResource(R.string.updated),
                    R.drawable.ic_new,
                    selectedBackgroundColor = MaterialTheme.colorScheme.secondary,
                    textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    selectedTextColor = MaterialTheme.colorScheme.onSurface

                ),
                onSelectedChange = { newStyle ->
                    isUpdated = newStyle == ScheduleMode.Updated
                }
            )
        }


        Spacer(modifier = Modifier.height(12.dp))

        // Tabs
        PrimaryTabRow(
            selectedTabIndex = pagerState.currentPage,
            modifier = Modifier.fillMaxWidth()
        ) {
            dayNames.forEachIndexed { index, day ->
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = { coroutineScope.launch { pagerState.animateScrollToPage(index) } },
                    selectedContentColor = MaterialTheme.colorScheme.primary,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    content = {
                        Text(
                            text = day,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Clip,
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(horizontal = 3.dp, vertical = 16.dp)
                        )
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        val scheduleItems = if (isUpdated) scheduleItemsUpdated else scheduleItemsOriginal

        // Pager content (one page per day)
        HorizontalPager(state = pagerState) { _ ->
            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularWavyProgressIndicator()
                }
            } else if (scheduleItems.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(stringResource(R.string.noSchedule), color = Color.Gray)
                }
            } else {
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

@Composable
fun ScheduleCard(item: JSONObject) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = getColorFromClass(item.optString("colorClass"))
        )
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

// Wrap with Unicode bidi markers based on a Hebrew context
            val subjectBidi = remember(subjectRaw) {
                BidiFormatter.getInstance(java.util.Locale("he")).unicodeWrap(subjectRaw)
            }

            Text(
                text = subjectBidi,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    textDirection = TextDirection.ContentOrRtl,   // heuristics: use RTL if content starts RTL
                    localeList = LocaleList(
                        Locale("he"), // Hebrew
                        Locale("en")  // English
                    )
                ),
                color = reversedColor,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Start                     // or Center if you prefer
            )

            Text(
                text = item.optString("teacher"),
                fontSize = 14.sp,
                color = Color.DarkGray
            )
            if (item.optString("changes").isNotEmpty()) {
                Text(
                    text = item.optString("changes"),
                    fontSize = 14.sp,
                    color = Color.Red
                )
            } else if (item.optString("exams").isNotEmpty()) {
                Text(
                    text = item.optString("exams"),
                    fontSize = 14.sp,
                    color = Color.Blue
                )
            }
        }
    }
}

fun getColorFromClass(colorClass: String): Color {
    return when (colorClass) {
        "pink-cell" -> Color(0xFFFFCCFB)
        "lightgreen-cell" -> Color(0xFFC3FFC1)
        "lightyellow-cell" -> Color(0xFFFAFFB8)
        "lightblue-cell" -> Color(0xFFB6E1EE)
        "lightred-cell" -> Color(0xFFFFBAB3)
        "lightpurple-cell" -> Color(0xFFDBC2FF)
        "lightorange-cell" -> Color(0xFFFFCFA6)
        "blue-cell" -> Color(0xFFB5C2FF)
        "lime-cell" -> Color(0xffebffbc)
        "lightgrey-cell" -> Color(0xFFCFD5D9)
        "custom-pink-cell" -> Color(0xFFF8C8FA)
        "cancel-cell" -> Color(0xff7d5b5d)
        else -> Color.White
    }
}
