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
import androidx.compose.ui.viewinterop.AndroidView
import com.feldman.motion.*
import com.feldman.scholix.*
import com.feldman.scholix.api.PlatformStorage
import com.feldman.scholix.api.platforms.OpenAUPlatform
import com.feldman.scholix.api.platforms.WebtopDownloadedAttachment
import com.feldman.scholix.courses.CourseSectionKind
import com.feldman.scholix.drive.DrivePdfReader
import com.feldman.scholix.openu.OpenUAssignmentsPage
import com.feldman.scholix.ui.components.SettingsTopBar
import com.feldman.scholix.ui.components.openMessageAttachment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

object OpenUCourseSections {
    fun available(course: JSONObject): List<CourseSectionKind> = listOf(
        CourseSectionKind.OVERVIEW, CourseSectionKind.UNITS, CourseSectionKind.ASSIGNMENTS,
        CourseSectionKind.RECORDINGS, CourseSectionKind.FORUMS, CourseSectionKind.ARCHIVE, CourseSectionKind.BOOKS,
    ) + (if (course.optBoolean("hasBooklet")) listOf(CourseSectionKind.BOOKLET) else emptyList()) +
        listOf(CourseSectionKind.GRADES, CourseSectionKind.STUDIES)
}

@Composable
fun OpenUCourseSection(platform: OpenAUPlatform, courseKey: String, section: CourseSectionKind,
                       onNavigate: MotionNavigator, onBack: () -> Unit, term: String = "") {
    if (section == CourseSectionKind.ASSIGNMENTS) {
        MotionScaffold(scrollable = false, contentPadding = PaddingValues(0.dp),
            topBar = { SettingsTopBar(section.label, onBack) }) {
            Item {
                OpenUAssignmentsPage(platform, "", object : MotionNavigator {
                    override fun invoke(destination: MotionDest, resetStack: Boolean) {
                        onNavigate(if (destination is AppDest.OpenUAssignment) destination.copy(courseKey = courseKey, term = term) else destination, resetStack)
                    }
                }, courseKey, {}, {}, includeTopInset = false)
            }
        }
        return
    }
    val context = LocalContext.current
    val repo = remember(platform, platform.getUsername()) { OpenUCourseRepository(context, platform) }
    val onCourseCacheChanged = LocalAppState.current.onCourseCacheChanged
    val kind = section.name.lowercase()
    val cacheKey = if (section in listOf(CourseSectionKind.OVERVIEW, CourseSectionKind.UNITS)) "overview" else kind
    var data by remember(repo, courseKey, section) { mutableStateOf(repo.cached(courseKey, cacheKey)) }
    var error by remember(repo, courseKey, section) { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var bookConsent by remember { mutableStateOf<JSONObject?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(repo, courseKey, section, retry) {
        if (section == CourseSectionKind.ARCHIVE) return@LaunchedEffect
        loading = true; error = null
        try {
            data = if (cacheKey == "overview") repo.course(courseKey) else repo.list(courseKey, kind)
            if (cacheKey == "overview") onCourseCacheChanged()
        }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "Could not update this OpenU section." }
        finally { loading = false }
    }
    MotionScaffold(topBar = { SettingsTopBar(section.label, onBack, actions = {
        IconButton(onClick = { retry++ }, enabled = !loading && section != CourseSectionKind.ARCHIVE) {
            Icon(rememberSymbolPainter(MotionSymbols.ic_refresh), contentDescription = "Refresh")
        }
    }) }) {
        if (loading) Item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (error != null) Item { Column { Text(error!!, color = MaterialTheme.colorScheme.error)
            if (data != null) Text("Showing saved course content.") } }
        when (section) {
            CourseSectionKind.OVERVIEW -> {
                val course = data ?: LocalAppState.current.courseCatalog.singleOrNull {
                    it.optString("platformId") == platform.id && it.optString("courseKey") == courseKey && it.optString("term") == term
                }
                if (course != null) {
                    Item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(course.optString("name"), style = MaterialTheme.typography.headlineMedium)
                            Text(listOf(course.optString("id"), course.optString("term"), course.optString("status")).filter(String::isNotBlank).joinToString(" · "))
                            if (course.optString("finalGrade").isNotBlank()) Text("Final grade: ${course.optString("finalGrade")}")
                        }
                    }
                    Section {
                        OpenUCourseSections.available(course).filter { it != section }.forEach { target ->
                            PageItem(title = target.label, onClick = { onNavigate(CourseSection(platform.id, courseKey, term, target)) })
                        }
                    }
                }
            }
            CourseSectionKind.UNITS -> {
                val units = data?.optJSONArray("sections")?.objects().orEmpty()
                if (!loading && units.isEmpty()) Item { Text("No learning units were returned for this course.") }
                Section {
                    units.forEach { unit -> PageItem(title = unit.optString("title"), onClick = {
                        onNavigate(OpenUCourseItem(platform.id, courseKey, term, section, "unit", unit.getString("id"), unit.getString("title")))
                    }) }
                }
            }
            CourseSectionKind.ARCHIVE -> Item {
                Text("The OpenU app's exam and assignment archive uses its separately configured Google Drive collection. That collection is not connected in Scholix.")
            }
            else -> {
                val items = data?.optJSONArray("items")?.objects().orEmpty()
                if (!loading && items.isEmpty()) Item { Text("No ${section.label.lowercase()} were returned for this course.") }
                if (section == CourseSectionKind.RECORDINGS) items.map { it.optString("collection") }.distinct().forEach { collection ->
                    Title(collection)
                    Section { items.filter { it.optString("collection") == collection }.forEach { item ->
                        PageItem(title = item.optString("title"), description = item.optString("meta"), onClick = {
                            onNavigate(OpenUCoursePlayer(platform.id, courseKey, term, section, item.optString("title"), item.toString()))
                        })
                    } }
                } else Section {
                    items.forEach { item -> PageItem(title = item.optString("title"), description = item.optString("description"), onClick = {
                        when (section) {
                            CourseSectionKind.BOOKS -> if (item.optBoolean("requiresConsent")) bookConsent = item
                                else onNavigate(OpenUCourseDocument(platform.id, courseKey, term, section, item.optString("title"), item.getString("url")))
                            CourseSectionKind.BOOKLET -> onNavigate(OpenUCourseDocument(platform.id, courseKey, term, section, item.optString("title"), item.getString("url")))
                            else -> onNavigate(OpenUCourseItem(platform.id, courseKey, term, section, "activity", item.optString("id"), item.optString("title"), item.optString("url")))
                        }
                    }) }
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
                catch (e: Exception) { error = e.message ?: "Could not accept book terms." }
                finally { loading = false }
            }
        }, onDismiss = { bookConsent = null }) }
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
        TextButton(onClick = { retry++ }, enabled = !loading) { Text("Refresh") }
    }) }) {
        if (loading) Item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        error?.let { Item { Text(it, color = MaterialTheme.colorScheme.error) } }
        val value = data
        if (route.kind == "unit") Section {
            value?.optJSONArray("items")?.objects().orEmpty().forEach { item ->
                PageItem(title = item.optString("title"), description = if (item.optBoolean("completed")) "Completed" else item.optString("module"), onClick = {
                    onNavigate(route.copy(kind = if (item.optString("module") == "quiz") "quiz" else "activity", itemId = item.getString("id"), title = item.optString("title"), url = item.getString("url"), unitId = route.itemId, unitTitle = route.title))
                })
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
                value.optJSONArray("media")?.objects().orEmpty().forEach { media -> PageItem(title = media.optString("title").ifBlank { "Open media" }, onClick = {
                    val target = media.optString("url")
                    if (OpenUCourseParser.documentUrl(target)) onNavigate(OpenUCourseDocument(route.providerId, route.courseKey, route.term, route.section, route.title, target, origin = route))
                    else if (OpenUCourseParser.hlsUrl(target)) onNavigate(OpenUCoursePlayer(route.providerId, route.courseKey, route.term, route.section, route.title, playerUrl = target, origin = route))
                    else runCatching { uri.openUri(target) }.onFailure { error = it.message }
                }) }
                value.optJSONArray("links")?.objects().orEmpty().filter { link ->
                    val path = runCatching { java.net.URI(link.optString("url")).path }.getOrNull().orEmpty()
                    !path.contains("/mod/quiz/") || path.endsWith("view.php") || path.endsWith("review.php")
                }.forEach { link -> PageItem(title = link.optString("title"), onClick = { open(link) }) }
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
