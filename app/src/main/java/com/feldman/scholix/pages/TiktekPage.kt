package com.feldman.scholix.pages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import coil.compose.AsyncImage
import com.feldman.motion.MotionSectionDefaults
import com.feldman.motion.MotionItemPosition
import com.feldman.motion.MotionCard
import com.feldman.motion.MotionFonts
import com.feldman.scholix.R
import com.feldman.scholix.ui.components.ChipPicker
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST
import kotlinx.coroutines.launch
import kotlin.math.max

private data class TiktekEnvelope<T>(@SerializedName("d") val data: TiktekResponse<T>)
private data class TiktekResponse<T>(
    @SerializedName("Success") val success: Boolean,
    @SerializedName("ResultData") val resultData: T?
)
private data class TiktekBook(
    @SerializedName("ID") val id: String,
    @SerializedName("Title") val title: String = "",
    @SerializedName("Image") val image: String? = null,
    @SerializedName("BT1") val bt1: String? = null,
    @SerializedName("BT2") val bt2: String? = null,
    @SerializedName("BT3") val bt3: String? = null
)
private data class TiktekSolution(
    @SerializedName("ID") val id: String,
    @SerializedName("Image") val image: String,
    @SerializedName("BookID") val bookId: String,
    @SerializedName("Prefix") val prefix: String,
    @SerializedName("Page") val page: Int? = null,
    @SerializedName("Question") val question: Int? = null
)
private data class TiktekBooksRequest(
    @SerializedName("subjectID") val subjectId: String,
    @SerializedName("schoolID") val schoolId: String? = null,
    @SerializedName("locationID") val locationId: String? = null
)
private data class TiktekSolutionsRequest(
    @SerializedName("bookID") val bookId: String,
    @SerializedName("page") val page: String,
    @SerializedName("question") val question: String
)
private interface TiktekApi {
    @Headers(
        "Content-Type: application/json; charset=utf-8",
        "Accept: application/json, text/javascript, */*; q=0.01",
        "X-Requested-With: XMLHttpRequest"
    )
    @POST("il/services/SolutionSearch.asmx/GetBooks")
    suspend fun books(@Body request: TiktekBooksRequest): TiktekEnvelope<List<TiktekBook>>

    @Headers(
        "Content-Type: application/json; charset=utf-8",
        "Accept: application/json, text/javascript, */*; q=0.01",
        "X-Requested-With: XMLHttpRequest"
    )
    @POST("il/services/SolutionSearch.asmx/GetSolutionsEx")
    suspend fun solutions(@Body request: TiktekSolutionsRequest): TiktekEnvelope<List<TiktekSolution>>
}
private val tiktekApi: TiktekApi by lazy {
    Retrofit.Builder().baseUrl("https://tiktek.com/")
        .addConverterFactory(GsonConverterFactory.create(GsonBuilder().serializeNulls().create()))
        .build().create(TiktekApi::class.java)
}

private val tiktekSubjects = listOf(
    "ST2016" to "מתמטיקה", "ST2021" to "אנגלית", "ST2012" to "היסטוריה",
    "ST2005" to "תנ\"ך", "ST2009" to "לשון והבעה עברית", "ST2013" to "אזרחות",
    "ST2019" to "פיזיקה", "ST2032" to "ביולוגיה", "ST2036" to "כימיה",
    "ST2017" to "מדעי המחשב", "ST2039" to "חינוך גופני", "ST2014" to "גיאוגרפיה",
    "ST2010" to "סוציולוגיה ומדעי החברה", "ST2037" to "מדעי כדור הארץ והסביבה",
    "ST2018" to "מדעים וטכנולוגיה", "ST2041" to "ביו טכנולוגיה",
    "ST2042" to "חשמל, אלקטרוניקה", "ST2046" to "תעשייה וניהול",
    "ST2043" to "ניהול עסקי", "ST2047" to "טכנולוגיות מידע",
    "ST2045" to "טכנולוגיות תקשורת", "ST2015" to "תקשורת וקולנוע",
    "ST2033" to "פילוסופיה", "ST2048" to "פסיכולוגיה", "ST2029" to "אמנות חזותית",
    "ST2027" to "מוזיקה", "ST2030" to "מחול", "ST2028" to "תיאטרון", "ST2031" to "מכונות",
    "ST2044" to "מכונאות רכב ותחבורה", "ST2020" to "מדעי החקלאות",
    "ST2034" to "לימודי ארץ ישראל", "ST2007" to "מורשת ותרבות ישראל",
    "ST2008" to "מחשבת ישראל", "ST2006" to "תורה שבע\"פ ותלמוד",
    "ST2051" to "מורשת דרוזית", "ST2040" to "נושאים מיוחדים במדע",
    "ST2049" to "לימודי אסלאם", "ST2050" to "לימודי נצרות", "ST2022" to "ערבית",
    "ST2023" to "צרפתית", "ST2025" to "ספרדית", "ST2024" to "רוסית",
    "ST2053" to "סינית", "ST2026" to "אמהרית"
)

private object TiktekState {
    var selectedSubject by mutableStateOf(tiktekSubjects.first())
    val booksBySubject = mutableStateMapOf<String, List<TiktekBook>>()
    val favorites = mutableStateSetOf<String>()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TiktekBooksPage(onOpenBook: (String, String, String) -> Unit) {
    var subject by remember { mutableStateOf(TiktekState.selectedSubject) }
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(TiktekState.booksBySubject[subject.first] == null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(subject.first) {
        TiktekState.selectedSubject = subject
        if (TiktekState.booksBySubject.containsKey(subject.first)) {
            loading = false
            error = null
            return@LaunchedEffect
        }
        loading = true
        error = null
        try {
            TiktekState.booksBySubject[subject.first] = withContext(Dispatchers.IO) {
                tiktekApi.books(TiktekBooksRequest(subject.first)).data.let { response ->
                    check(response.success) { "Tiktek could not load books" }
                    response.resultData.orEmpty()
                }
            }
        } catch (exception: Exception) {
            error = "Could not load Tiktek books"
        } finally {
            loading = false
        }
    }
    val favorites = TiktekState.favorites.toSet()
    val visibleBooks = remember(TiktekState.booksBySubject[subject.first], query, favorites) {
        TiktekState.booksBySubject[subject.first].orEmpty()
            .filter { it.title.contains(query, ignoreCase = true) }
            .sortedWith(compareByDescending<TiktekBook> { favorites.contains(it.id) }.thenBy { it.title })
    }

    val searchIsRtl = if (query.isBlank()) subjectUsesRtl(subject.first) else isMostlyRtl(query)

    Scaffold(topBar = {
        CenterAlignedTopAppBar(title = {
            Text(
                "Tiktek",
                color = MaterialTheme.colorScheme.primary,
                fontFamily = MotionFonts.feldman(weight = 900, width = 120f),
                fontSize = 26.sp
            )
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            ChipPicker(
                label = "Subject",
                options = tiktekSubjects.map { it.second },
                selected = subject.second,
                onSelectedChange = { selectedName ->
                    subject = tiktekSubjects.first { it.second == selectedName }
                }
            )
            Spacer(Modifier.size(8.dp))
            CompositionLocalProvider(
                LocalLayoutDirection provides if (searchIsRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(50),
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    placeholder = { Text(if (searchIsRtl) "חיפוש ספרים" else "Search books") }
                )
            }
            when {
                loading -> GradesLoadingIndicator(Modifier.fillMaxSize())
                error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(error!!, color = MaterialTheme.colorScheme.error)
                }
                else -> LazyColumn(modifier = Modifier.fillMaxSize().padding(top = 12.dp)) {
                    itemsIndexed(visibleBooks, key = { _, book -> book.id }) { index, book ->
                        MotionCard(
                            position = when {
                                visibleBooks.size == 1 -> MotionItemPosition.Alone
                                index == 0 -> MotionItemPosition.Start
                                index == visibleBooks.lastIndex -> MotionItemPosition.End
                                else -> MotionItemPosition.Middle
                            },
                            contentPadding = 0.dp
                        ) {
                            CompositionLocalProvider(
                                LocalLayoutDirection provides if (isMostlyRtl(book.title)) LayoutDirection.Rtl else LayoutDirection.Ltr
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().clickable { onOpenBook(book.id, book.title, subject.first) }
                                        .padding(16.dp), verticalAlignment = Alignment.CenterVertically
                                ) {
                                    AsyncImage(
                                        model = "https://tiktek.com/il/tt-resources-unmanaged/books-covers/${book.image.orEmpty()}",
                                        contentDescription = book.title, modifier = Modifier.size(56.dp)
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        listOfNotNull(book.bt1, book.bt2, book.bt3).joinToString(" · ").takeIf { it.isNotBlank() }?.let {
                                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    IconButton(onClick = {
                                        if (!TiktekState.favorites.add(book.id)) TiktekState.favorites.remove(book.id)
                                    }) {
                                        Icon(
                                            painter = painterResource(if (favorites.contains(book.id)) R.drawable.ic_star else R.drawable.ic_star_border),
                                            contentDescription = "Favorite"
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.size(MotionSectionDefaults.ItemSpacing))
                    }
                }
            }
            Spacer(Modifier.height(180.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TiktekBookPage(bookId: String, bookName: String, subjectId: String, onBack: () -> Unit, onOpenSolution: (String) -> Unit) {
    var page by remember { mutableStateOf("") }
    var question by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var solutions by remember { mutableStateOf<List<TiktekSolution>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    fun load() {
        if (page.toIntOrNull() == null || question.toIntOrNull() == null) return
        loading = true
        error = null
    }
    LaunchedEffect(loading) {
        if (!loading) return@LaunchedEffect
        try {
            solutions = withContext(Dispatchers.IO) {
                tiktekApi.solutions(TiktekSolutionsRequest(bookId, page, question)).data.let { response ->
                    check(response.success) { "Tiktek could not load solutions" }; response.resultData.orEmpty()
                }
            }
        } catch (exception: Exception) { error = "Could not load solutions" } finally { loading = false }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text(bookName, maxLines = 1, overflow = TextOverflow.Ellipsis) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        })
    }, bottomBar = {
        val layoutDirection = if (subjectUsesRtl(subjectId)) LayoutDirection.Rtl else LayoutDirection.Ltr
        CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
            Row(
                modifier = Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = page,
                    onValueChange = { page = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("Page") },
                    singleLine = true,
                    shape = RoundedCornerShape(33.dp)
                )
                OutlinedTextField(
                    value = question,
                    onValueChange = { question = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("Exercise") },
                    singleLine = true,
                    shape = RoundedCornerShape(33.dp)
                )
                IconButton(
                    onClick = ::load,
                    modifier = Modifier.size(56.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50))
                ) {
                    Icon(Icons.Default.Search, "Go", tint = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            when {
                loading -> GradesLoadingIndicator(Modifier.fillMaxSize())
                error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Error: $error", color = MaterialTheme.colorScheme.error)
                }
                else -> LazyColumn(Modifier.fillMaxSize().padding(top = 12.dp)) {
                    itemsIndexed(solutions, key = { _, solution -> solution.id }) { index, solution ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                        ) {
                            val url = "https://tiktek.com/il/tt-resources/solution-images/${solution.prefix}_${solution.bookId}/${solution.image}"
                            Column(Modifier.clickable { onOpenSolution(url) }) {
                                AsyncImage(model = url, contentDescription = "Solution", modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp))
                                Text(
                                    text = "${solution.page ?: "?"} / ${solution.question ?: "?"}",
                                    modifier = Modifier.padding(8.dp),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                        Spacer(Modifier.size(MotionSectionDefaults.ItemSpacing))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TiktekSolutionPage(imageUrl: String, onBack: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("Solution") }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
    }) }) { padding ->
        var scale by remember { mutableStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        var containerWidth by remember { mutableStateOf(1f) }
        var containerHeight by remember { mutableStateOf(1f) }

        AsyncImage(
            model = imageUrl,
            contentDescription = "Solution",
            modifier = Modifier.fillMaxSize().padding(padding).onSizeChanged {
                containerWidth = it.width.toFloat()
                containerHeight = it.height.toFloat()
            }.pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(1f, 5f)
                    val maxX = max(0f, (containerWidth * newScale - containerWidth) / 2f)
                    val maxY = max(0f, (containerHeight * newScale - containerHeight) / 2f)
                    offset = (offset + pan).let { Offset(it.x.coerceIn(-maxX, maxX), it.y.coerceIn(-maxY, maxY)) }
                    scale = newScale
                }
            }.graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offset.x,
                translationY = offset.y
            )
        )
    }
}

private fun isMostlyRtl(text: String): Boolean {
    var rtl = 0
    var ltr = 0
    text.forEach { char ->
        when (Character.getDirectionality(char)) {
            Character.DIRECTIONALITY_RIGHT_TO_LEFT,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> rtl++
            Character.DIRECTIONALITY_LEFT_TO_RIGHT -> ltr++
        }
    }
    return rtl > ltr
}

private fun subjectUsesRtl(subjectId: String) = subjectId !in setOf(
    "ST2021", "ST2023", "ST2025", "ST2024", "ST2053", "ST2026"
)
