package com.feldman.scholix.lemida

import com.feldman.scholix.courses.courseScope
import com.feldman.motion.MotionDropdownMenuItem
import com.feldman.motion.MotionDropdown
import com.feldman.motion.MotionDropdownDefaults
import com.feldman.motion.MotionDropdownAnimation
import android.Manifest
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.togetherWith
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
import com.feldman.scholix.LocalAppState
import com.feldman.scholix.api.platforms.GoogleClassroomPlatform
import com.feldman.scholix.api.platforms.OpenAUPlatform
import com.feldman.scholix.openu.OpenUAssignmentsRepository
import com.feldman.scholix.ui.components.CoursePickerBar
import com.feldman.scholix.ui.components.SettingsTopBar
import com.feldman.scholix.openu.course.CourseRow
import com.feldman.scholix.openu.course.moduleLabel
import com.feldman.scholix.openu.course.moduleSymbol
import com.feldman.motion.rememberSymbolPainter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
fun LemidaPage(searchQuery: String = "", onNavigate: com.feldman.motion.MotionNavigator,
               courseProviderId: String? = null, courseKey: String? = null, filesOnly: Boolean = false,
               onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    val repo = remember(context) { LemidaRepository(context) }
    val app = LocalAppState.current
    val providers = (if (courseProviderId == null) app.scopedPlatforms else app.platforms).filter { it is GoogleClassroomPlatform || it is OpenAUPlatform }
    val snapshot by repo.snapshots.collectAsStateWithLifecycle(initialValue = remember(repo) { repo.cachedSnapshot() })
    if (courseProviderId != null) {
        if (courseProviderId.startsWith("lemida:")) {
            if (!snapshot.loaded) {
                Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) { CircularProgressIndicator() }
            } else if (courseProviderId != "lemida:${snapshot.account}" ||
                snapshot.courses.none { it.id.toString() == courseKey }) {
                Text("This course is no longer available for the saved Lemida account.", Modifier.padding(24.dp))
            } else key(courseProviderId, courseKey, filesOnly) {
                LemidaPageContent(repo, searchQuery, coursePicker = {}, selectedCourseId = courseKey?.toIntOrNull(),
                    filesOnly = filesOnly, onBack = onBack, onOpenItem = { item ->
                        onNavigate(com.feldman.scholix.AppDest.LemidaAssignment(item.id, courseProviderId, courseKey.orEmpty()))
                    })
            }
        } else when (val provider = providers.firstOrNull { it.id == courseProviderId }) {
            is OpenAUPlatform -> com.feldman.scholix.openu.OpenUAssignmentsPage(provider, searchQuery, onNavigate,
                courseKey, coursePicker = {}, onCoursesChanged = {})
            is GoogleClassroomPlatform -> com.feldman.scholix.classroom.ClassroomHomeworkPage(provider, searchQuery,
                coursePicker = {}, selectedCourseId = courseKey, materialsOnly = filesOnly, onBack = onBack)
            else -> Text("This course account is no longer available.", Modifier.padding(24.dp))
        }
        return
    }
    val refreshedCourses = remember { mutableStateMapOf<String, List<JSONObject>>() }
    val cachedCourses = remember(app.preloadedCourses, app.platforms, app.selectedProviderId) {
        providers.associate { provider ->
            val saved = if (provider is OpenAUPlatform) OpenUAssignmentsRepository(context, provider).cachedCourses()
                else (provider as GoogleClassroomPlatform).coursesList.toList()
            provider.id to saved.ifEmpty { app.preloadedCourses.filter { it.optString("platformId") == provider.id } }
        }
    }
    val courses = providers.flatMap { provider ->
        (refreshedCourses[provider.id] ?: cachedCourses[provider.id]).orEmpty().map { course ->
            JSONObject(course.toString()).put("platformId", provider.id)
                .put("courseKey", course.optString("courseKey").ifBlank { course.optString("id") })
        }.ifEmpty { listOf(JSONObject().put("platformId", provider.id).put("courseKey", "")
            .put("name", provider.getName()).put("status", "Refresh to load courses")) }
    } + (if (app.selectedProviderId.isBlank() || app.selectedProviderId == "lemida") snapshot.courses.map { course ->
        JSONObject().put("platformId", "lemida").put("platformName", "Lemida")
            .put("courseKey", course.id.toString()).put("name", course.name)
    }.ifEmpty { listOf(JSONObject().put("platformId", "lemida").put("courseKey", "")
        .put("name", "Lemida").put("status", "Sign in to load courses")) } else emptyList())
    if (courses.isEmpty()) return
    var selectedKey by rememberSaveable { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    val selectedIndex = courses.indexOfFirst { "${it.optString("platformId")}|${it.optString("courseKey")}" == selectedKey }.coerceAtLeast(0)
    val selectedCourse = courses[selectedIndex]
    val providerId = selectedCourse.optString("platformId")
    val courseKey = selectedCourse.optString("courseKey").takeIf { it.isNotBlank() }
    LaunchedEffect(providerId, courseKey) {
        selectedKey = "$providerId|${courseKey.orEmpty()}"
    }
    // On a wide screen this list stays beside an open course: follow that Lemida course instead of the first one.
    val openCourse = app.activePage?.courseScope()?.takeIf { it.providerId.startsWith("lemida:") }?.courseKey
    LaunchedEffect(openCourse) {
        if (openCourse != null && courses.any { it.optString("platformId") == "lemida" && it.optString("courseKey") == openCourse })
            selectedKey = "lemida|$openCourse"
    }
    val requestedAssignment by HomeworkLinks.pending.collectAsStateWithLifecycle()
    LaunchedEffect(requestedAssignment) {
        val request = requestedAssignment ?: return@LaunchedEffect
        val saved = repo.snapshots.first { it.loaded }
        val match = HomeworkLinks.match(request, saved.homework, saved.account)
        // Show the Lemida course of the requested assignment; LemidaPageContent then opens it.
        if (match != null) selectedKey = "lemida|${saved.courses.firstOrNull { it.id == match.courseId }?.id
            ?: saved.courses.firstOrNull()?.id ?: ""}"
        else HomeworkLinks.consume(request) // Not a Lemida assignment of this account: show the homework list.
    }
    val coursePicker: @Composable () -> Unit = {
        if (!com.feldman.scholix.LocalProviderHeaderVisible.current) CoursePickerBar(courses, selectedIndex, onSelected = { index ->
            courses.getOrNull(index)?.let { selectedKey = "${it.optString("platformId")}|${it.optString("courseKey")}" }
        }, expanded = expanded, onExpandedChange = { expanded = it }, providersById = providers.associateBy { it.id })
    }
    key(providerId) {
        when (val selected = providers.firstOrNull { it.id == providerId }) {
            is OpenAUPlatform -> if (filesOnly && courseKey != null) {
                com.feldman.scholix.openu.course.OpenUCourseSection(selected, courseKey,
                    com.feldman.scholix.courses.CourseSectionKind.FILES, onNavigate, onBack ?: {},
                    coursePicker = coursePicker)
            } else com.feldman.scholix.openu.OpenUAssignmentsPage(selected, searchQuery, onNavigate,
                courseKey, coursePicker, onCoursesChanged = { refreshedCourses[providerId] = it; app.onCourseCacheChanged() })
            is GoogleClassroomPlatform -> com.feldman.scholix.classroom.ClassroomHomeworkPage(selected, searchQuery,
                coursePicker, selectedCourseId = courseKey, materialsOnly = filesOnly, onCoursesChanged = { refreshedCourses[providerId] = it; app.onCourseCacheChanged() })
            // Under the provider bar this page's own picker is hidden, and it silently kept the first course.
            // Lemida's main page lists every course, with its course chips to narrow it down.
            else -> if (com.feldman.scholix.LocalProviderHeaderVisible.current)
                LemidaPageContent(repo, searchQuery, coursePicker = null, selectedCourseId = app.lemidaCourse,
                    filesOnly = filesOnly, onCourseFilterChange = app.onLemidaCourseChanged,
                    onOpenItem = { item -> onNavigate(com.feldman.scholix.AppDest.LemidaAssignment(item.id)) })
            else LemidaPageContent(repo, searchQuery, coursePicker, selectedCourseId = courseKey?.toIntOrNull(), filesOnly = filesOnly)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LemidaPageContent(repo: LemidaRepository, searchQuery: String = "", coursePicker: (@Composable () -> Unit)? = null,
                               selectedCourseId: Int? = null, filesOnly: Boolean = false, onBack: (() -> Unit)? = null,
                               /** Shares the course chips' choice with the provider bar above the page. */
                               onCourseFilterChange: ((Int?) -> Unit)? = null,
                               /** Opens an activity's details as a page of its own; inline when absent. */
                               onOpenItem: ((Homework) -> Unit)? = null) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val syncing by repo.syncing.collectAsStateWithLifecycle()
    val snapshot by repo.snapshots.collectAsStateWithLifecycle(initialValue = remember(repo) { repo.cachedSnapshot() })
    val homework = snapshot.homework
    val courses = snapshot.courses
    val status = snapshot.status
    val updated = snapshot.updated
    val enabled = snapshot.enabled
    val needsLogin = snapshot.needsLogin
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var courseFilter by rememberSaveable(selectedCourseId) { mutableStateOf(selectedCourseId) }
    var typeFilter by rememberSaveable { mutableStateOf("all") }
    var query by rememberSaveable { mutableStateOf("") }
    var options by remember { mutableStateOf(false) }
    val exportHomework = rememberHomeworkExportAction()
    var materialFile by remember { mutableStateOf<java.io.File?>(null) }
    var materialName by remember { mutableStateOf("") }
    var openingMaterial by remember { mutableStateOf<String?>(null) }
    var materialError by remember { mutableStateOf<String?>(null) }
    val materialScope = rememberCoroutineScope()
    DisposableEffect(materialFile) { val file = materialFile; onDispose { file?.delete() } }
    fun openMaterial(material: LemidaMaterial) {
        fun onLemida() = context.startActivity(com.feldman.scholix.browser.ScholixBrowserActivity.intent(context, material.url, material.title))
        if (material.type != "resource") { onLemida(); return }
        if (openingMaterial != null) return
        materialScope.launch {
            openingMaterial = material.id; materialError = null
            try { materialFile = repo.openMaterial(material); materialName = material.title }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: LemidaSessionExpired) { materialError = e.message ?: "Sign in to Lemida again to open this file." }
            // Slides and documents aren't PDFs; Lemida's own page offers them.
            catch (e: Exception) { onLemida() }
            finally { openingMaterial = null }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val login = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        // A completed sign-in may change accounts. Return to the freshly loaded list.
        if (result.resultCode == android.app.Activity.RESULT_OK) selectedId = null
    }
    val signInScope = rememberCoroutineScope()
    fun reconnect() {
        signInScope.launch {
            val automatic = withContext(Dispatchers.IO) { LemidaSignInStore(context).canRecover() } &&
                LemidaBackgroundSignIn.available(context) && !repo.status().startsWith("Open Sign in to Lemida")
            if (automatic) LemidaSyncWorker.refresh(context, manual = true)
            else login.launch(Intent(context, LemidaLoginActivity::class.java))
        }
    }
    LaunchedEffect(homework, courses, snapshot.loaded, courseFilter, selectedId) {
        if (!snapshot.loaded) return@LaunchedEffect
        if (courseFilter != null && courses.none { it.id == courseFilter }) { courseFilter = null; onCourseFilterChange?.invoke(null) }
        if (selectedId != null && homework.none { it.id == selectedId }) selectedId = null
    }
    val requestedAssignment by HomeworkLinks.pending.collectAsStateWithLifecycle()
    LaunchedEffect(requestedAssignment, homework, snapshot.loaded, snapshot.account) {
        val request = requestedAssignment ?: return@LaunchedEffect
        if (!snapshot.loaded) return@LaunchedEffect
        HomeworkLinks.match(request, homework, snapshot.account)?.let { if (onOpenItem != null) onOpenItem(it) else selectedId = it.id }
        HomeworkLinks.consume(request)
    }
    homework.firstOrNull { it.id == selectedId }?.let { item ->
        key(snapshot.account, item.id) {
            LemidaHomeworkDetail(item, repo, snapshot.account, onBack = { selectedId = null }, onReconnect = {
                reconnect()
            })
        }
        return
    }
    LaunchedEffect(lifecycle) {
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val canRecover = withContext(Dispatchers.IO) { LemidaSignInStore(context).canRecover() } && LemidaBackgroundSignIn.available(context)
            if (repo.enabled() && (!repo.needsLogin() || canRecover) && !repo.syncing.value) LemidaSyncWorker.refresh(context)
        }
    }
    if (!snapshot.loaded) {
        Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val visible = remember(homework, courseFilter, typeFilter, query, searchQuery, filesOnly, snapshot.updated) { homework.filter { (courseFilter == null || it.courseId == courseFilter) &&
        (typeFilter == "all" || LemidaActivities.group(it.type) == typeFilter) &&
        (!filesOnly || repo.cachedDetail(it)?.pdfs?.isNotEmpty() == true) &&
        it.matchesSearch(query, searchQuery) } }
    val materials = remember(snapshot.materials, courseFilter, query, searchQuery, filesOnly) {
        if (!filesOnly) emptyList() else snapshot.materials.filter {
            (courseFilter == null || it.courseId == courseFilter) && it.matchesSearch(query, searchQuery)
        }
    }
    Box(Modifier.fillMaxSize()) {
    MotionScaffold(modifier = Modifier.fillMaxSize(), topBar = { if (filesOnly && !com.feldman.scholix.LocalProviderHeaderVisible.current) SettingsTopBar("Files", onBack) }) {
        if (!filesOnly && onBack != null) Item { com.feldman.motion.MotionBackButton(onClick = onBack) }
        if (coursePicker != null && !com.feldman.scholix.LocalProviderHeaderVisible.current) Item { coursePicker() }
        Item {
            LemidaHomeworkFilters(
                count = visible.size, updated = updated, courses = courses,
                query = query, onQueryChange = { query = it },
                courseFilter = courseFilter, onCourseChange = { courseFilter = it; onCourseFilterChange?.invoke(it) },
                typeFilter = typeFilter, onTypeChange = { typeFilter = it },
                groups = homework.filter { courseFilter == null || it.courseId == courseFilter }
                    .map { LemidaActivities.group(it.type) }.toSet()
                    .let { present -> LemidaActivities.groups.filter { it in present } },
                automaticUpdates = enabled,
                showCourseFilters = coursePicker == null,
                searchLabel = if (filesOnly) "Search files" else "Search assignments",
                showTypeFilters = !filesOnly,
                // Refresh and Lemida's options sit beside the search box rather than on a row of their own.
                actions = {
            IconButton(onClick = { LemidaSyncWorker.refresh(context, manual = true) }, enabled = updated > 0L && !syncing && !needsLogin) {
                if (syncing) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.Refresh, "Refresh")
            }
            Box {
                MotionDropdown<Unit>(options = emptyList(), selected = null, onSelected = {},
                    expanded = options, onExpandedChange = { options = it },
                    menu = MotionDropdownDefaults.menu(animation = MotionDropdownAnimation.Transform, matchAnchorWidth = false),
                    sizes = MotionDropdownDefaults.sizes(menuMinWidth = 240.dp, menuMaxWidth = 320.dp),
                    anchorContent = { _, toggle -> IconButton(onClick = toggle) {
                        Icon(Icons.Default.MoreVert, "Assignment settings")
                    } }, menuHeader = {
                    MotionDropdownMenuItem(text = { Text("Save homework for PC") }, onClick = {
                        options = false
                        exportHomework()
                    })
                    MotionDropdownMenuItem(text = { Text(if (LemidaSignInStore(context).enabled()) "Pause automatic sign-in" else "Resume automatic sign-in") }, onClick = {
                        LemidaSignInStore(context).apply { setEnabled(!enabled()) }
                        options = false
                    })
                    MotionDropdownMenuItem(text = { Text("Automatic sign-in") }, onClick = {
                        options = false
                        login.launch(Intent(context, LemidaLoginActivity::class.java).putExtra("edit_sign_in_details", true))
                    })
                    MotionDropdownMenuItem(text = { Text("Reconnect Lemida") }, enabled = !syncing, onClick = {
                        options = false
                        reconnect()
                    })
                    MotionDropdownMenuItem(text = { Text("Open sign-in screen") }, onClick = {
                        options = false
                        login.launch(Intent(context, LemidaLoginActivity::class.java))
                    })
                    MotionDropdownMenuItem(text = { Text(if (enabled) "Pause automatic updates" else "Resume automatic updates") }, onClick = {
                        val next = !enabled; repo.setEnabled(next); options = false
                        if (next) { permission.launch(Manifest.permission.POST_NOTIFICATIONS); LemidaSyncWorker.schedule(context); LemidaSyncWorker.refresh(context, manual = true) }
                    })
                })
            }
                },
            )
        }
        if (updated == 0L || needsLogin) Section {
            PageItem(title = "Sign in to Lemida", description = "Connect to resume assignment updates", icon = painterResource(R.drawable.ic_docs), onClick = {
                permission.launch(Manifest.permission.POST_NOTIFICATIONS); reconnect()
            })
        }
        if (status.startsWith("Update failed")) Item { Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        if (filesOnly) {
            if (materials.isEmpty()) Item {
                Column(Modifier.padding(12.dp)) {
                    Text(when {
                        updated == 0L -> "Connect Lemida to see your course files."
                        snapshot.materials.isEmpty() -> "Course files appear after the next Lemida update. Tap Refresh to update now."
                        else -> "No course files match."
                    })
                    if (selectedCourseId != null) TextButton(onClick = {
                        context.startActivity(com.feldman.scholix.browser.ScholixBrowserActivity.intent(context,
                            "${LemidaParser.BASE}/course/view.php?id=$selectedCourseId", "Course files"))
                    }) { Text("Browse course files on Lemida") }
                }
            }
            materialError?.let { message -> Item { Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) } }
            // Lemida's own course sections ("הרצאות", "תרגולים", "לוח"), in the course's order.
            materials.groupBy { it.courseId }.entries
                .sortedByDescending { (_, items) -> items.maxOf { it.id.substringAfterLast(':').toLongOrNull() ?: 0L } }
                .forEach { (_, inCourse) ->
                if (courseFilter == null) Title(inCourse.first().course)
                inCourse.groupBy { it.sectionIndex to it.section }.forEach { (section, items) ->
                    Item { Text("${section.second.ifBlank { "General" }} · ${items.size}", style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Section { items.forEach { material ->
                        CourseRow(title = material.title, key = material.id, onClick = { openMaterial(material) },
                            icon = rememberSymbolPainter(moduleSymbol(material.type)),
                            description = if (openingMaterial == material.id) "Opening…" else moduleLabel(material.type))
                    } }
                }
            }
            if (visible.isNotEmpty()) Title("Assignment attachments")
        }
        if (visible.isEmpty() && !(filesOnly && materials.isNotEmpty())) Item { Text(when {
            updated == 0L -> "Connect Lemida to see your assignments."
            filesOnly -> "No saved PDF attachments in this course yet."
            homework.isEmpty() -> "No assignments available yet. New activities will appear after an update."
            courseFilter != null && homework.none { it.courseId == courseFilter } -> "No assignments in this course yet. New activities will appear automatically."
            else -> "No assignments matches. Try another search or choose All courses and All."
        }, modifier = Modifier.padding(24.dp)) }
        // Courses with the newest activity first, so this semester's courses lead and finished ones follow.
        visible.groupBy { it.courseId }.entries.sortedByDescending { (_, items) -> items.maxOf { LemidaActivities.recency(it) } }
            .forEach { (courseId, group) ->
            val color = MotionThemeDefaults.VibrantIconBackgrounds[Math.floorMod(courseId, MotionThemeDefaults.VibrantIconBackgrounds.size)]
            Title(group.first().course)
            LemidaActivities.groups.forEach { label ->
                val items = group.filter { LemidaActivities.group(it.type) == label }.sortedWith(compareBy<Homework> {
                    Regex("\\d+").find(it.title)?.value?.toIntOrNull() ?: Int.MAX_VALUE
                }.thenBy { it.title })
                if (items.isNotEmpty()) {
                    Item { Text("$label · ${items.size}", style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Section { items.forEach { item ->
                        PageItem(key = item.id, title = item.title, description = item.dates.takeIf { it.isNotBlank() },
                            icon = painterResource(if (item.type == "quiz") R.drawable.ic_schedule else R.drawable.ic_docs),
                            iconStyle = MotionSectionDefaults.iconStyle(containerColor = color.color, contentColor = color.onColor),
                            onClick = { if (onOpenItem != null) onOpenItem(item) else selectedId = item.id })
                    } }
                }
            }
        }
        BottomBarSpacer(minMargin = 24.dp)
    }
    // A course file grows in over its list and settles back into it, rather than replacing the page.
    androidx.compose.animation.AnimatedContent(targetState = materialFile, label = "course file",
        transitionSpec = {
            val spring = androidx.compose.animation.core.spring<Float>(dampingRatio = 0.9f, stiffness = 380f)
            (androidx.compose.animation.fadeIn(spring) + androidx.compose.animation.scaleIn(spring, initialScale = 0.94f)) togetherWith
                (androidx.compose.animation.fadeOut(spring) + androidx.compose.animation.scaleOut(spring, targetScale = 0.94f))
        }) { file ->
        if (file != null) Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            LemidaPdfScreen(file, materialName, onBack = { materialFile = null })
        }
    }
    }
}

/** Motion Item is a Box; this Column gives every control its own measured row. */
@Composable
internal fun LemidaHomeworkFilters(
    count: Int, updated: Long, courses: List<LemidaCourse>,
    query: String, onQueryChange: (String) -> Unit,
    courseFilter: Int?, onCourseChange: (Int?) -> Unit,
    typeFilter: String, onTypeChange: (String) -> Unit,
    /** Kinds of work present, in display order; the type chips offer only these. */
    groups: List<String> = LemidaActivities.groups,
    automaticUpdates: Boolean = true,
    showCourseFilters: Boolean = true,
    /** What the search box searches; Files searches course material by its title and section. */
    searchLabel: String = "Search assignments",
    showTypeFilters: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (updated > 0 && !automaticUpdates) Text("Automatic updates paused",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        OutlinedTextField(value = query, onValueChange = onQueryChange,
            modifier = Modifier.weight(1f).testTag("homework-search"),
            singleLine = true, placeholder = { Text(searchLabel) }, shape = MaterialTheme.shapes.large,
            trailingIcon = if (query.isNotEmpty()) {
                { IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Default.Close, "Clear search") } }
            } else null)
        actions()
        }
        if (showCourseFilters) {
            // The chosen course may come from the bar above; bring its chip into view.
            val chips = androidx.compose.foundation.lazy.rememberLazyListState()
            LaunchedEffect(courseFilter, courses) {
                val index = courses.indexOfFirst { it.id == courseFilter } + 1
                if (index >= 0) chips.animateScrollToItem(index.coerceAtLeast(0))
            }
            androidx.compose.foundation.lazy.LazyRow(Modifier.fillMaxWidth().testTag("homework-course-filters"),
                state = chips, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item(key = "all") { FilterChip(selected = courseFilter == null, onClick = { onCourseChange(null) }, label = { Text("All courses") }) }
                items(courses.size, key = { courses[it].id }) { i -> val c = courses[i]
                    FilterChip(selected = courseFilter == c.id, onClick = { onCourseChange(c.id) }, label = { Text(c.name, maxLines = 1) }) }
            }
        }
        if (showTypeFilters) Row(Modifier.fillMaxWidth().testTag("homework-type-filters").horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (listOf("all" to "All") + groups.map { it to it }).forEach { (type, label) ->
                FilterChip(selected = typeFilter == type, onClick = { onTypeChange(type) }, label = { Text(label) })
            }
        }
    }
}

/** [LemidaHomeworkDetail] as a page of its own, for [com.feldman.scholix.AppDest.LemidaAssignment]. */
@Composable
internal fun LemidaAssignmentPage(itemId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember(context) { LemidaRepository(context) }
    val snapshot by repo.snapshots.collectAsStateWithLifecycle(initialValue = remember(repo) { repo.cachedSnapshot() })
    val login = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    val scope = rememberCoroutineScope()
    if (!snapshot.loaded) {
        Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val item = snapshot.homework.firstOrNull { it.id == itemId }
    if (item == null) {
        MotionScaffold(topBar = { SettingsTopBar("Assignment details", onBack) }) {
            Item { Text("This activity is no longer on Lemida for the signed-in account.", Modifier.padding(12.dp)) }
        }
        return
    }
    key(snapshot.account, item.id) {
        LemidaHomeworkDetail(item, repo, snapshot.account, onBack = onBack, interceptBack = false, onReconnect = {
            scope.launch {
                val automatic = withContext(Dispatchers.IO) { LemidaSignInStore(context).canRecover() } &&
                    LemidaBackgroundSignIn.available(context) && !repo.status().startsWith("Open Sign in to Lemida")
                if (automatic) LemidaSyncWorker.refresh(context, manual = true)
                else login.launch(Intent(context, LemidaLoginActivity::class.java))
            }
        })
    }
}

@Composable
internal fun LemidaHomeworkDetail(item: Homework, repo: LemidaRepository, account: String = "",
    onBack: () -> Unit, onReconnect: () -> Unit,
    /** False as a navigation page, whose Back (and predictive back) the navigation host owns. */
    interceptBack: Boolean = true) {
    BackHandler(enabled = interceptBack, onBack = onBack)
    var detail by remember(item.id) { mutableStateOf(repo.cachedDetail(item)) }
    var detailCheckedAt by remember(item.id) { mutableLongStateOf(0) }
    var loading by remember(item.id) { mutableStateOf(true) }
    var error by remember(item.id) { mutableStateOf<String?>(null) }
    var requiresSignIn by remember(item.id) { mutableStateOf(false) }
    var retry by remember(item.id) { mutableIntStateOf(0) }
    var pdfFile by remember(item.id) { mutableStateOf<java.io.File?>(null) }
    var pdfName by remember(item.id) { mutableStateOf("") }
    var openingPdf by remember(item.id) { mutableStateOf<String?>(null) }
    var pdfError by remember(item.id) { mutableStateOf<String?>(null) }
    val pdfScope = rememberCoroutineScope()
    val context = LocalContext.current
    val downloadMessages = remember(item.id) { SnackbarHostState() }
    var savingPdf by remember(item.id) { mutableStateOf(false) }
    DisposableEffect(pdfFile) { val file = pdfFile; onDispose { file?.delete() } }
    LaunchedEffect(item.id, retry) {
        // A posted exercise file or link has no instructions page to read; it opens on Lemida.
        if (!LemidaActivities.hasDetailPage(item.type)) { loading = false; return@LaunchedEffect }
        loading = true
        error = null
        requiresSignIn = false
        try { detail = repo.detail(item); detailCheckedAt = System.currentTimeMillis() }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            error = e.message ?: "Could not update assignment details."
            requiresSignIn = e is LemidaSessionExpired
        }
        finally { loading = false }
    }
    pdfFile?.let { file ->
        BackHandler { pdfFile = null }
        Scaffold(snackbarHost = { SnackbarHost(downloadMessages) }, topBar = {
            SettingsTopBar(pdfName, onBack = { pdfFile = null }, actions = {
                TextButton(enabled = !savingPdf, onClick = {
                    if (!savingPdf) pdfScope.launch {
                        savingPdf = true
                        val message = try {
                            LemidaPdfDownloads.save(context, file, pdfName)
                            "Saved to Downloads/Scholix"
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (e: Exception) { e.message ?: "Could not save the PDF. Try again." }
                        finally { savingPdf = false }
                        downloadMessages.showSnackbar(message)
                    }
                }) { Text(if (savingPdf) "Saving…" else "Download") }
            })
        }) { padding ->
            com.feldman.scholix.drive.DrivePdfReader(file,
                Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp).padding(bottom = 80.dp),
                errorMessage = "This PDF could not be displayed. Go back and try opening it again.")
        }
        return
    }
    MotionScaffold(modifier = Modifier.fillMaxSize(), topBar = { SettingsTopBar("Assignment details", onBack = onBack) }) {
        Item { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(item.course, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(item.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (loading) com.feldman.scholix.pages.GradesLoadingIndicator(Modifier.fillMaxWidth().height(80.dp))
        } }
        if (account.isNotBlank()) Item {
            val assignment = item.studyAssignment(account,
                status = detail?.tables?.flatten()?.filter { row -> row.firstOrNull()?.contains("status", ignoreCase = true) == true }
                    ?.joinToString("; ") { it.joinToString(" · ") }.orEmpty().take(2000),
                checkedAt = detailCheckedAt)
            com.feldman.scholix.studies.StudyActions(assignment, prepareContent = {
                val current = detail ?: repo.detail(item)
                val sources = current.pdfs.map { pdf ->
                    com.feldman.scholix.studies.AssignmentStudySource(pdf.name, repo.openPdf(item, pdf),
                        Regex("solution|answer|\u05e4\u05ea\u05e8\u05d5\u05df|\u05e4\u05ea\u05e8\u05d5\u05e0\u05d5\u05ea", RegexOption.IGNORE_CASE).containsMatchIn(pdf.name))
                }
                com.feldman.scholix.studies.AssignmentStudyBundle.build(context, assignment, sources,
                    org.jsoup.Jsoup.parse(current.description).text().ifBlank { current.text })
            })
        }
        // The official hand-in: Lemida's own page in Scholix's browser, already signed in. Scholix never submits.
        if (item.url.startsWith("${LemidaParser.BASE}/") && com.feldman.scholix.browser.ScholixLinks.loadable(item.url)) Section {
            val label = LemidaActivities.openLabel(item.type)
            PageItem(title = label,
                description = when {
                    item.type == "quiz" -> "Opens this quiz on Lemida's website inside Scholix."
                    label == "Hand in on Lemida" -> "Opens this assignment on Lemida's website inside Scholix. Upload your exported PDF there."
                    else -> "Opens it on Lemida's website inside Scholix."
                },
                icon = painterResource(R.drawable.ic_docs), onClick = {
                    context.startActivity(com.feldman.scholix.browser.ScholixBrowserActivity.intent(context, item.url, item.title))
                })
        }
        error?.let { message -> Section {
            Item { Text(message, color = MaterialTheme.colorScheme.error) }
            if (requiresSignIn) PageItem(title = "Sign in to Lemida", description = "Reconnect to update this assignment",
                icon = painterResource(R.drawable.ic_docs), onClick = onReconnect)
            else PageItem(title = "Retry loading assignments", icon = rememberVectorPainter(Icons.Default.Refresh), onClick = { retry++ })
        } }
        detail?.let { data ->
            if (data.pdfs.isNotEmpty()) {
                Title("PDF attachments")
                Section {
                    data.pdfs.forEach { pdf -> PageItem(title = pdf.name,
                        description = if (openingPdf == pdf.url) "Opening PDF…" else "Open PDF",
                        icon = painterResource(R.drawable.ic_docs), onClick = {
                            if (openingPdf == null) pdfScope.launch {
                                openingPdf = pdf.url; pdfError = null
                                try { pdfFile = repo.openPdf(item, pdf); pdfName = pdf.name }
                                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                                catch (e: Exception) {
                                    pdfError = e.message ?: "Could not open the PDF. Try again."
                                    if (e is LemidaSessionExpired) { error = pdfError; requiresSignIn = true }
                                } finally { openingPdf = null }
                            }
                        }) }
                    pdfError?.let { message -> Item { Text(message, color = MaterialTheme.colorScheme.error) } }
                }
            }
            if (data.notices.isNotEmpty()) {
                Title("Activity notices")
                Section { data.notices.forEach { notice -> Item { Text(notice, style = MaterialTheme.typography.bodyMedium) } } }
            }
            if (data.dates.isNotBlank()) { Title("Dates"); Section { Item { Text(data.dates, style = MaterialTheme.typography.bodyMedium) } } }
            // Lemida repeats each attached file (name and upload time) in the instructions and the status
            // table; those are already listed under PDF attachments, so only other text is shown again.
            val attachments = data.pdfs.map { it.name }
            val instructions = LemidaDetailText.withoutAttachments(data.description, attachments)
            val tables = data.tables.map { table -> table.filterNot { cells ->
                cells.first().isBlank() && LemidaDetailText.withoutAttachments(cells.drop(1).joinToString("\n"), attachments).isBlank()
            } }
            if (instructions.isNotBlank()) { Title("Instructions"); Section { Item { Text(instructions, style = MaterialTheme.typography.bodyMedium) } } }
            if (tables.any { it.isNotEmpty() }) Title("Submission and grading")
            tables.forEachIndexed { index, table -> if (table.isNotEmpty()) Section {
                data.tableCaptions.getOrNull(index)?.takeIf { it.isNotBlank() && table.none { cells -> cells.first() == it } }?.let { caption ->
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
