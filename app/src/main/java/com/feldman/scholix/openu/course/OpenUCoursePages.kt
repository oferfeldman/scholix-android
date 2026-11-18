package com.feldman.scholix.openu.course

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import com.feldman.motion.*
import com.feldman.scholix.*
import com.feldman.scholix.api.PlatformStorage
import com.feldman.scholix.api.platforms.OpenAUPlatform
import com.feldman.scholix.api.platforms.WebtopDownloadedAttachment
import com.feldman.scholix.courses.CourseSectionKind
import com.feldman.scholix.drive.DrivePdfReader
import com.feldman.scholix.ui.components.SettingsTopBar
import com.feldman.scholix.ui.components.openMessageAttachment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

object OpenUCourseSections {
    /** Units live on the course Overview; Assignments live in Grades. */
    fun available(course: JSONObject): List<CourseSectionKind> = listOf(
        CourseSectionKind.OVERVIEW, CourseSectionKind.RECORDINGS, CourseSectionKind.GRADES,
        CourseSectionKind.BOOKS, CourseSectionKind.FORUMS,
    ) + (if (course.optBoolean("hasBooklet")) listOf(CourseSectionKind.BOOKLET) else emptyList()) +
        listOf(CourseSectionKind.STUDIES)

    /** Where a saved route to a section that no longer has its own page now lives. */
    fun redirect(section: CourseSectionKind): CourseSectionKind? = when (section) {
        CourseSectionKind.UNITS, CourseSectionKind.ARCHIVE -> CourseSectionKind.OVERVIEW
        CourseSectionKind.ASSIGNMENTS -> CourseSectionKind.GRADES
        else -> null
    }

    /** "2027a" as OpenU writes semesters: 2027A. */
    fun termLabel(term: String): String = Regex("^(\\d{4})([a-c])$", RegexOption.IGNORE_CASE).matchEntire(term)
        ?.let { "${it.groupValues[1]}${it.groupValues[2].uppercase()}" } ?: term

    /** The course facts the Overview states: number, semester, credits and a known exam date. */
    fun facts(course: JSONObject): List<Pair<String, String>> {
        val exam = course.optJSONArray("assignments")?.objects().orEmpty()
            .firstNotNullOfOrNull { it.optString("examDate").takeIf(String::isNotBlank) }
        return listOfNotNull(
            course.optString("id").takeIf(String::isNotBlank)?.let { "Course" to it },
            course.optString("term").takeIf(String::isNotBlank)?.let { "Semester" to termLabel(it) },
            course.optString("points").takeIf(String::isNotBlank)?.let { "Credits" to it },
            exam?.let { "Exam" to it },
        )
    }

    /** Progress of a unit already opened on this device, from its saved activity list. */
    fun unitProgress(unit: JSONObject?): Pair<Int, Int>? {
        val items = unit?.optJSONArray("items")?.objects() ?: return null
        val tracked = items.filter { it.has("completed") }
        if (tracked.isEmpty()) return null
        return tracked.count { it.optBoolean("completed") } to tracked.size
    }
}

@Composable
fun OpenUCourseSection(platform: OpenAUPlatform, courseKey: String, section: CourseSectionKind,
                       onNavigate: MotionNavigator, onBack: () -> Unit, term: String = "",
                       coursePicker: (@Composable () -> Unit)? = null) {
    val context = LocalContext.current
    val repo = remember(platform, platform.getUsername()) { OpenUCourseRepository(context, platform) }
    val onCourseCacheChanged = LocalAppState.current.onCourseCacheChanged
    val kind = section.name.lowercase()
    val cacheKey = if (section == CourseSectionKind.OVERVIEW) "overview" else kind
    var data by remember(repo, courseKey, section) { mutableStateOf(repo.cached(courseKey, cacheKey)) }
    var error by remember(repo, courseKey, section) { mutableStateOf<String?>(null) }
    var loading by remember(repo, courseKey, section) { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var bookConsent by remember { mutableStateOf<JSONObject?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(repo, courseKey, section, retry) {
        loading = true; error = null
        try {
            data = if (cacheKey == "overview") repo.course(courseKey) else repo.list(courseKey, kind)
            if (cacheKey == "overview") onCourseCacheChanged()
        }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "Could not update this course." }
        finally { loading = false }
    }
    val refresh = @Composable {
        IconButton(onClick = { retry++ }, enabled = !loading) {
            Icon(rememberSymbolPainter(MotionSymbols.ic_refresh), contentDescription = "Refresh",
                tint = if (loading) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    MotionScaffold(topBar = {
        if (coursePicker != null) Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { coursePicker() }
            refresh()
        } else SettingsTopBar(section.label, onBack, actions = { refresh() })
    }) {
        Item(visible = loading) { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        error?.let { message -> Item {
            Text(if (data != null) "$message Showing saved content." else message,
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        } }
        when (section) {
            CourseSectionKind.OVERVIEW -> {
                val course = data ?: LocalAppState.current.courseCatalog.singleOrNull {
                    it.optString("platformId") == platform.id && it.optString("courseKey") == courseKey && it.optString("term") == term
                }
                val facts = course?.let(OpenUCourseSections::facts).orEmpty()
                if (facts.isNotEmpty()) Item { CourseFacts(facts) }
                val units = data?.optJSONArray("sections")?.objects().orEmpty()
                if (data != null && !loading && units.isEmpty()) Item { Text("This course has no units yet.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (units.isNotEmpty()) {
                    Title("Units")
                    Section {
                        units.forEach { unit ->
                            val id = unit.optString("id")
                            val title = unit.optString("title")
                            val target = OpenUCourseItem(platform.id, courseKey, term, section, "unit", id, title)
                            val progress = OpenUCourseSections.unitProgress(repo.cached(courseKey, "unit:$id"))
                            val done = progress != null && progress.first == progress.second
                            CourseRow(title = title, key = "unit:$id", destination = target, accent = done,
                                icon = rememberSymbolPainter(if (done) MotionSymbols.ic_task_alt else MotionSymbols.ic_view_module),
                                description = progress?.let { (complete, total) ->
                                    if (complete == total) "Done" else "$complete of $total done" },
                                onClick = { onNavigate(target) })
                        }
                    }
                }
            }
            CourseSectionKind.RECORDINGS -> {
                val items = data?.optJSONArray("items")?.objects().orEmpty()
                if (data != null && !loading && items.isEmpty()) Item { Text("No recordings for this course.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
                val collections = items.groupBy { it.optString("collection") }
                collections.forEach { (collection, recordings) ->
                    if (collections.size > 1 && collection.isNotBlank()) Title(collection)
                    Section {
                        recordings.forEach { item ->
                            val target = OpenUCoursePlayer(platform.id, courseKey, term, section, item.optString("title"), item.toString())
                            CourseRow(title = item.optString("title"), key = "recording:${item.optString("cmid")}:${item.optString("id")}",
                                destination = target, icon = rememberSymbolPainter(MotionSymbols.ic_play_circle),
                                description = listOf(item.optString("date"), item.optString("instructor"))
                                    .filter(String::isNotBlank).joinToString("\n")
                                    .ifBlank { item.optString("meta") },
                                onClick = { onNavigate(target) })
                        }
                    }
                }
            }
            else -> {
                val items = data?.optJSONArray("items")?.objects().orEmpty()
                if (data != null && !loading && items.isEmpty()) Item {
                    Text(when (section) {
                        CourseSectionKind.BOOKS -> "No digital books for this course."
                        CourseSectionKind.FORUMS -> "No forums for this course."
                        CourseSectionKind.BOOKLET -> "The booklet has no files."
                        else -> "No files for this course."
                    }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Section {
                    items.forEach { item ->
                        val title = item.optString("title")
                        val target: MotionDest? = when (section) {
                            CourseSectionKind.BOOKS -> item.optString("url").takeIf(String::isNotBlank)
                                ?.let { OpenUCourseDocument(platform.id, courseKey, term, section, title, it) }
                            CourseSectionKind.BOOKLET -> OpenUCourseDocument(platform.id, courseKey, term, section, title, item.optString("url"))
                            else -> OpenUCourseItem(platform.id, courseKey, term, section, "activity", item.optString("id"), title, item.optString("url"))
                        }
                        CourseRow(title = title, key = "${section.name}:${item.optString("id")}", destination = target,
                            icon = rememberSymbolPainter(when (section) {
                                CourseSectionKind.BOOKS -> MotionSymbols.ic_menu_book
                                CourseSectionKind.FORUMS -> MotionSymbols.ic_forum
                                else -> MotionSymbols.ic_book
                            }),
                            description = item.optString("description"),
                            onClick = {
                                if (section == CourseSectionKind.BOOKS && item.optBoolean("requiresConsent")) bookConsent = item
                                else target?.let { onNavigate(it) }
                            })
                    }
                }
            }
        }
        BottomBarSpacer(minMargin = 24.dp)
    }
    bookConsent?.let { book -> MotionDialog(onDismissRequest = { bookConsent = null }, title = "Open digital book?",
        text = "${book.optString("title")}\n\nThe book is for personal study only. The file may contain visible and hidden identifying information. Do not distribute or sell it. Accepting sends your consent to OpenU and downloads the book.",
        confirmText = "Accept terms and open", dismissText = "Cancel", onConfirm = {
            bookConsent = null
            scope.launch {
                loading = true
                try { val url = repo.consent(courseKey, book.getString("id"))
                    onNavigate(OpenUCourseDocument(platform.id, courseKey, term, section, book.optString("title"), url)) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = e.message ?: "Could not accept the book terms." }
                finally { loading = false }
            }
        }, onDismiss = { bookConsent = null }) }
}

/** Course number, semester, credits and exam date as plain label and value pairs. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CourseFacts(facts: List<Pair<String, String>>) {
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        facts.forEach { (label, value) ->
            Column(Modifier.semantics(mergeDescendants = true) {}) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.titleLarge.copy(
                    fontFamily = MotionFonts.feldman(weight = 600, width = 80f)),
                    color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun rememberCourseRepository(providerId: String): OpenUCourseRepository? {
    val context = LocalContext.current
    val provider = LocalAppState.current.platforms.filterIsInstance<OpenAUPlatform>().singleOrNull { it.id == providerId }
    return remember(provider, provider?.getUsername()) { provider?.let { OpenUCourseRepository(context, it) } }
}

@Composable
internal fun OpenUCourseItemPage(route: OpenUCourseItem, onNavigate: MotionNavigator, onBack: () -> Unit) {
    val repo = rememberCourseRepository(route.providerId)
    var data by remember(repo, route) { mutableStateOf(repo?.cached(route.courseKey, if (route.kind == "unit") "unit:${route.itemId}" else "detail:${route.url}")) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    val uri = LocalUriHandler.current
    LaunchedEffect(repo, route, retry) {
        error = null; loading = true
        try { data = if (route.kind == "unit") repo?.section(route.courseKey, route.itemId) else repo?.detail(route.courseKey, route.url)
            if (repo == null) error = "The OpenU account was removed." }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "Could not update course content." }
        finally { loading = false }
    }
    fun open(link: JSONObject) {
        val target = link.optString("url")
        when {
            OpenUCourseParser.documentUrl(target) || com.feldman.scholix.openu.OpenUAssignmentParser.fileUrl(target) -> onNavigate(OpenUCourseDocument(route.providerId, route.courseKey, route.term, route.section, link.optString("title"), target, origin = route))
            OpenUCourseParser.nativeActivityUrl(target) -> onNavigate(route.copy(kind = if (java.net.URI(target).path.contains("/quiz/")) "quiz" else "activity", title = link.optString("title"), url = target,
                forumUrl = if (route.section == CourseSectionKind.FORUMS && route.forumUrl.isBlank()) route.url else route.forumUrl,
                forumTitle = if (route.section == CourseSectionKind.FORUMS && route.forumTitle.isBlank()) route.title else route.forumTitle))
            else -> runCatching { uri.openUri(target) }.onFailure { error = it.message }
        }
    }
    MotionScaffold(topBar = { SettingsTopBar(route.title, onBack, actions = {
        IconButton(onClick = { retry++ }, enabled = !loading) {
            Icon(rememberSymbolPainter(MotionSymbols.ic_refresh), contentDescription = "Refresh")
        }
    }) }) {
        Item(visible = loading) { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        error?.let { Item { Text(it, color = MaterialTheme.colorScheme.error) } }
        val value = data
        if (route.kind == "unit") {
            val items = value?.optJSONArray("items")?.objects().orEmpty()
            if (value != null && !loading && items.isEmpty()) Item { Text("This unit has no activities yet.",
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Section {
                items.forEach { item ->
                    val module = item.optString("module")
                    val target = route.copy(kind = if (module == "quiz") "quiz" else "activity", itemId = item.optString("id"),
                        title = item.optString("title"), url = item.optString("url"), unitId = route.itemId, unitTitle = route.title)
                    val done = item.optBoolean("completed")
                    CourseRow(title = item.optString("title"), key = "activity:${item.optString("id")}", destination = target,
                        accent = done, icon = rememberSymbolPainter(if (done) MotionSymbols.ic_task_alt else moduleSymbol(module)),
                        description = if (done) "Done" else moduleLabel(module), onClick = { onNavigate(target) })
                }
            }
        } else if (value != null) {
            val quiz = value.optJSONObject("quiz")
            if (quiz != null) {
                Item { Text(quiz.optString("info")) }
                Item { MotionButton(text = "Open official quiz", onClick = {
                    runCatching { uri.openUri(route.url) }.onFailure { error = it.message }
                }) }
                Item { Text("Quiz attempts and answers use OpenU's original controls in Scholix's built-in browser. Opening this overview does not start or submit an attempt.") }
            } else {
                Item { OpenUCourseHtml(value.optString("html"), repo, route.courseKey, { target -> open(JSONObject().put("url", target).put("title", "Course resource")) }, Modifier.fillMaxWidth().height(560.dp)) }
                Item { TextButton(onClick = { runCatching { uri.openUri(route.url) }.onFailure { error = it.message } }) { Text("Open original page") } }
            }
            Section {
                value.optJSONArray("media")?.objects().orEmpty().forEach { media ->
                    val target = media.optString("url")
                    CourseRow(title = media.optString("title").ifBlank { "Play media" }, key = "media:$target",
                        icon = rememberSymbolPainter(MotionSymbols.ic_play_circle), onClick = {
                        if (OpenUCourseParser.documentUrl(target)) onNavigate(OpenUCourseDocument(route.providerId, route.courseKey, route.term, route.section, route.title, target, origin = route))
                        else if (OpenUCourseParser.hlsUrl(target)) onNavigate(OpenUCoursePlayer(route.providerId, route.courseKey, route.term, route.section, route.title, playerUrl = target, origin = route))
                        else runCatching { uri.openUri(target) }.onFailure { error = it.message }
                    })
                }
                value.optJSONArray("links")?.objects().orEmpty().filter { link ->
                    val path = runCatching { java.net.URI(link.optString("url")).path }.getOrNull().orEmpty()
                    !path.contains("/mod/quiz/") || path.endsWith("view.php") || path.endsWith("review.php")
                }.forEach { link ->
                    val target = link.optString("url")
                    val module = Regex("/mod/([a-z][a-z0-9_]*)/").find(runCatching { java.net.URI(target).path }.getOrNull().orEmpty())?.groupValues?.get(1)
                    CourseRow(title = link.optString("title"), key = "link:$target", onClick = { open(link) },
                        icon = rememberSymbolPainter(when {
                            OpenUCourseParser.documentUrl(target) -> MotionSymbols.ic_description
                            module != null -> moduleSymbol(module)
                            else -> MotionSymbols.ic_link
                        }))
                }
            }
        }
        BottomBarSpacer(minMargin = 24.dp)
    }

}

@Composable
internal fun OpenUCourseDocumentPage(route: OpenUCourseDocument, onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = rememberCourseRepository(route.providerId)
    var file by remember(route) { mutableStateOf<WebtopDownloadedAttachment?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(file?.mimeType ?: "application/octet-stream")) { uri ->
        val downloaded = file
        if (uri != null && downloaded != null) scope.launch { runCatching { withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri)?.use { output -> downloaded.file.inputStream().use { it.copyTo(output) } }
                ?: error("Could not save the document.")
        } }.onFailure { error = it.message } }
    }
    LaunchedEffect(repo, route) {
        file = null; error = null
        try { file = repo?.file(route.courseKey, route.title, route.url); if (repo == null) error = "The OpenU account was removed." }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "Could not open this course document." }
    }
    MotionScaffold(scrollable = false, topBar = { SettingsTopBar(route.title, onBack, actions = {
        TextButton(onClick = { file?.let { save.launch(it.file.name) } }, enabled = file != null) { Text("Save a copy") }
    }) }) {
        Item { Column(Modifier.fillMaxSize()) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            val downloaded = file
            if (downloaded != null) {
                if (downloaded.mimeType == "application/pdf" || downloaded.file.inputStream().use { input ->
                        val header = ByteArray(5); input.read(header) == 5 && header.contentEquals("%PDF-".toByteArray()) })
                    DrivePdfReader(downloaded.file, Modifier.weight(1f))
                else {
                    Text("${downloaded.mimeType} · ${downloaded.file.length()} bytes")
                    TextButton(onClick = { runCatching { openMessageAttachment(context, downloaded) }.onFailure { error = it.message } }) { Text("Open with…") }
                }
            }
            else if (error == null) CircularProgressIndicator()
        } }
    }
}

@Composable
internal fun OpenUCoursePlayerPage(route: OpenUCoursePlayer, onBack: () -> Unit) {
    val repo = rememberCourseRepository(route.providerId)
    var stream by remember(route) { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var video by remember { mutableStateOf<VideoView?>(null) }
    LaunchedEffect(repo, route) {
        try {
            if (repo == null) error("The OpenU account was removed.")
            stream = if (route.recording.isNotBlank()) repo.stream(route.courseKey, JSONObject(route.recording))
                else repo.playerStream(route.courseKey, route.playerUrl)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "Could not open the recording." }
    }
    DisposableEffect(Unit) { onDispose { video?.stopPlayback() } }
    MotionScaffold(scrollable = false, topBar = { SettingsTopBar(route.title, onBack) }) {
        Item { Column(Modifier.fillMaxSize()) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            val source = stream
            if (source != null) AndroidView(modifier = Modifier.fillMaxWidth().weight(1f), factory = { context ->
                VideoView(context).also { view ->
                    video = view
                    view.setMediaController(MediaController(context).apply { setAnchorView(view) })
                    view.setVideoURI(Uri.parse(source), mapOf("Referer" to "https://opal.openu.ac.il/"))
                    view.setOnErrorListener { _, _, _ -> error = "This recording could not be played. Refresh the recording list and try again."; true }
                    view.setOnPreparedListener { view.start() }
                }
            }) else if (error == null) CircularProgressIndicator()
        } }
    }
}

/** Read-only rich-content viewer, following the original OpenU HtmlWebView. */
@Composable
private fun OpenUCourseHtml(html: String, repo: OpenUCourseRepository?, courseKey: String, onUrl: (String) -> Unit, modifier: Modifier = Modifier) {
    val background = MaterialTheme.colorScheme.surface
    val foreground = MaterialTheme.colorScheme.onSurface
    val primary = MaterialTheme.colorScheme.primary
    fun color(value: androidx.compose.ui.graphics.Color) = "#%06X".format(value.toArgb() and 0xffffff)
    val document = remember(html, background, foreground, primary) {
        """<!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
            <style>body{background:${color(background)};color:${color(foreground)};font:16px sans-serif;line-height:1.6;overflow-wrap:anywhere;}
            a{color:${color(primary)}}img{max-width:100%;height:auto}table{max-width:100%;border-collapse:collapse}td,th{padding:8px}
            .ouilforum_post{padding:12px;border-bottom:1px solid currentColor}.indent{margin-inline-start:18px}</style></head><body dir="auto">$html</body></html>"""
    }
    val currentOnUrl by rememberUpdatedState(onUrl)
    var browser by remember { mutableStateOf<WebView?>(null) }
    DisposableEffect(Unit) { onDispose { browser?.destroy() } }
    AndroidView(modifier = modifier, factory = { context -> WebView(context).apply {
        browser = this
        settings.javaScriptEnabled = false
        settings.domStorageEnabled = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                request?.url?.toString()?.let(currentOnUrl)
                return true
            }
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse {
                val target = request?.url?.toString().orEmpty()
                if (repo != null && request?.method == "GET" && OpenUCourseParser.documentUrl(target)) {
                    val image = runCatching { kotlinx.coroutines.runBlocking { repo.file(courseKey, "image", target) } }.getOrNull()
                    if (image != null && image.mimeType.startsWith("image/")) return WebResourceResponse(image.mimeType, null, image.file.inputStream())
                }
                return WebResourceResponse("text/plain", "UTF-8", java.io.ByteArrayInputStream(ByteArray(0)))
            }
        }
    } }, update = { view ->
        if (view.tag != document) { view.tag = document; view.loadDataWithBaseURL(OpenUCourseParser.BASE, document, "text/html", "UTF-8", null) }
    })
}
