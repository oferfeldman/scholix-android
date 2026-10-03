package com.feldman.scholix.lemida

import android.Manifest
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.feldman.motion.MotionScaffold
import com.feldman.motion.MotionSectionDefaults
import com.feldman.motion.MotionThemeDefaults
import com.feldman.scholix.R
import com.feldman.scholix.ui.components.SettingsTopBar
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LemidaPage(searchQuery: String = "") {
    val context = LocalContext.current
    val repo = remember(context) { LemidaRepository(context) }
    val lifecycle = LocalLifecycleOwner.current
    val syncing by repo.syncing.collectAsStateWithLifecycle()
    var homework by remember { mutableStateOf(repo.cached()) }
    var status by remember { mutableStateOf(repo.status()) }
    var updated by remember { mutableLongStateOf(repo.lastSync()) }
    var enabled by remember { mutableStateOf(repo.enabled()) }
    var needsLogin by remember { mutableStateOf(repo.needsLogin()) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var courseFilter by rememberSaveable { mutableStateOf<Int?>(null) }
    var typeFilter by rememberSaveable { mutableStateOf("all") }
    var query by rememberSaveable { mutableStateOf("") }
    var options by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val login = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        homework = repo.cached(); status = repo.status(); updated = repo.lastSync()
        needsLogin = repo.needsLogin(); enabled = repo.enabled()
    }
    LaunchedEffect(homework, courseFilter, selectedId) {
        if (courseFilter != null && homework.none { it.courseId == courseFilter }) courseFilter = null
        if (selectedId != null && homework.none { it.id == selectedId }) selectedId = null
    }
    homework.firstOrNull { it.id == selectedId }?.let { item ->
        LemidaHomeworkDetail(item, repo) { selectedId = null }; return
    }
    LaunchedEffect(lifecycle) {
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (repo.enabled() && !repo.needsLogin() && !repo.syncing.value) LemidaSyncWorker.refresh(context)
            while (true) {
                homework = repo.cached(); status = repo.status(); updated = repo.lastSync(); needsLogin = repo.needsLogin()
                enabled = repo.enabled()
                delay(2000)
            }
        }
    }
    val visible = homework.filter { (courseFilter == null || it.courseId == courseFilter) &&
        (typeFilter == "all" || it.type == typeFilter) &&
        it.matchesSearch(query, searchQuery) }
    val courses = homework.distinctBy { it.courseId }.sortedBy { it.course }
    MotionScaffold(modifier = Modifier.fillMaxSize(), topBar = {
        CenterAlignedTopAppBar(title = { Text("Homework", fontWeight = FontWeight.Bold) }, actions = {
            IconButton(onClick = { LemidaSyncWorker.refresh(context, manual = true) }, enabled = updated > 0L && !syncing && !needsLogin) {
                if (syncing) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.Refresh, "Refresh")
            }
            Box {
                IconButton(onClick = { options = true }) { Icon(Icons.Default.MoreVert, "Homework settings") }
                DropdownMenu(expanded = options, onDismissRequest = { options = false }) {
                    DropdownMenuItem(text = { Text("Reconnect Lemida") }, enabled = !syncing, onClick = {
                        options = false
                        login.launch(Intent(context, LemidaLoginActivity::class.java))
                    })
                    DropdownMenuItem(text = { Text(if (enabled) "Pause automatic updates" else "Resume automatic updates") }, onClick = {
                        enabled = !enabled; repo.setEnabled(enabled); options = false
                        if (enabled) { permission.launch(Manifest.permission.POST_NOTIFICATIONS); LemidaSyncWorker.schedule(context); LemidaSyncWorker.refresh(context, manual = true) }
                    })
                }
            }
        }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer))
    }) {
        Item {
            LemidaHomeworkFilters(
                count = visible.size, updated = updated, courses = courses,
                query = query, onQueryChange = { query = it },
                courseFilter = courseFilter, onCourseChange = { courseFilter = it },
                typeFilter = typeFilter, onTypeChange = { typeFilter = it },
                automaticUpdates = enabled,
            )
        }
        if (updated == 0L || needsLogin) Section {
            PageItem(title = "Sign in to Lemida", description = "Connect to resume homework updates", icon = painterResource(R.drawable.ic_docs), onClick = {
                permission.launch(Manifest.permission.POST_NOTIFICATIONS); login.launch(Intent(context, LemidaLoginActivity::class.java))
            })
        }
        if (status.startsWith("Update failed")) Item { Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        if (visible.isEmpty()) Item { Text(when {
            updated == 0L -> "Connect Lemida to see your homework."
            homework.isEmpty() -> "No homework available yet. New activities will appear after an update."
            else -> "No homework matches. Try another search or choose All courses and All."
        }, modifier = Modifier.padding(24.dp)) }
        visible.groupBy { it.courseId }.toSortedMap().forEach { (courseId, group) ->
            val color = MotionThemeDefaults.VibrantIconBackgrounds[Math.floorMod(courseId, MotionThemeDefaults.VibrantIconBackgrounds.size)]
            Title(group.first().course)
            listOf("assign" to "Assignments", "quiz" to "Quizzes", "workshop" to "Workshops").forEach { (type, label) ->
                val items = group.filter { it.type == type }.sortedWith(compareBy<Homework> {
                    Regex("\\d+").find(it.title)?.value?.toIntOrNull() ?: Int.MAX_VALUE
                }.thenBy { it.title })
                if (items.isNotEmpty()) {
                    Item { Text("$label · ${items.size}", style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Section { items.forEach { item ->
                        PageItem(key = item.id, title = item.title, description = item.dates.takeIf { it.isNotBlank() },
                            icon = painterResource(if (type == "quiz") R.drawable.ic_schedule else R.drawable.ic_docs),
                            iconStyle = MotionSectionDefaults.iconStyle(containerColor = color.color, contentColor = color.onColor), onClick = { selectedId = item.id })
                    } }
                }
            }
        }
        BottomBarSpacer(minMargin = 24.dp)
    }
}

/** Motion Item is a Box; this Column gives every control its own measured row. */
@Composable
internal fun LemidaHomeworkFilters(
    count: Int, updated: Long, courses: List<Homework>,
    query: String, onQueryChange: (String) -> Unit,
    courseFilter: Int?, onCourseChange: (Int?) -> Unit,
    typeFilter: String, onTypeChange: (String) -> Unit,
    automaticUpdates: Boolean = true,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("$count items", style = MaterialTheme.typography.labelLarge)
            Text(if (updated > 0) "Updated ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(updated))}" else "Not synced",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (updated > 0 && !automaticUpdates) Text("Automatic updates paused",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedTextField(value = query, onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth().testTag("homework-search"),
            singleLine = true, placeholder = { Text("Search homework") }, shape = MaterialTheme.shapes.large,
            trailingIcon = if (query.isNotEmpty()) {
                { IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Default.Close, "Clear homework search") } }
            } else null)
        Row(Modifier.fillMaxWidth().testTag("homework-course-filters").horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = courseFilter == null, onClick = { onCourseChange(null) }, label = { Text("All courses") })
            courses.forEach { c -> FilterChip(selected = courseFilter == c.courseId,
                onClick = { onCourseChange(c.courseId) }, label = { Text(c.course, maxLines = 1) }) }
        }
        Row(Modifier.fillMaxWidth().testTag("homework-type-filters").horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("all" to "All", "assign" to "Assignments", "quiz" to "Quizzes", "workshop" to "Workshops").forEach { (type, label) ->
                FilterChip(selected = typeFilter == type, onClick = { onTypeChange(type) }, label = { Text(label) })
            }
        }
    }
}

@Composable
private fun LemidaHomeworkDetail(item: Homework, repo: LemidaRepository, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    var detail by remember(item.id) { mutableStateOf(repo.cachedDetail(item)) }
    var loading by remember(item.id) { mutableStateOf(true) }
    var error by remember(item.id) { mutableStateOf<String?>(null) }
    var retry by remember(item.id) { mutableIntStateOf(0) }
    LaunchedEffect(item.id, retry) {
        loading = true
        error = null
        try { detail = repo.detail(item) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "Could not update homework details." }
        finally { loading = false }
    }
    MotionScaffold(modifier = Modifier.fillMaxSize(), topBar = { SettingsTopBar("Homework details", onBack = onBack) }) {
        Item { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(item.course, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(item.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        } }
        error?.let { message -> Section {
            Item { Text(message, color = MaterialTheme.colorScheme.error) }
            PageItem(title = "Retry loading homework", icon = rememberVectorPainter(Icons.Default.Refresh), onClick = { retry++ })
        } }
        detail?.let { data ->
            if (data.notices.isNotEmpty()) {
                Title("Activity notices")
                Section { data.notices.forEach { notice -> Item { Text(notice, style = MaterialTheme.typography.bodyMedium) } } }
            }
            if (data.dates.isNotBlank()) { Title("Dates"); Section { Item { Text(data.dates, style = MaterialTheme.typography.bodyMedium) } } }
            if (data.description.isNotBlank()) { Title("Instructions"); Section { Item { Text(data.description, style = MaterialTheme.typography.bodyMedium) } } }
            if (data.tables.isNotEmpty()) Title("Submission and grading")
            data.tables.forEachIndexed { index, table -> Section {
                data.tableCaptions.getOrNull(index)?.takeIf { it.isNotBlank() }?.let { caption ->
                    Item { Text(caption, style = MaterialTheme.typography.titleSmall) }
                }
                table.forEach { cells -> Item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(cells.first(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        if (cells.size > 1) Text(cells.drop(1).joinToString("\n"), style = MaterialTheme.typography.bodyMedium)
                    }
                } }
            } }
            if (data.description.isBlank() && data.tables.isEmpty() && (data.text.isNotBlank() || data.notices.isEmpty())) Section { Item {
                Text(data.text.ifBlank { "This activity has no instructions yet." }, style = MaterialTheme.typography.bodyMedium)
            } }
        }
        BottomBarSpacer(minMargin = 24.dp)
    }
}
