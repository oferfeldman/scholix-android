package com.feldman.scholix.drive

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.gms.auth.api.identity.Identity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriveMaterialsPage(searchQuery: String = "") {
    val context = LocalContext.current
    val repo = remember(context) { DriveRepository.get(context) }
    val state by repo.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current
    var tab by rememberSaveable { mutableStateOf("followed") }
    val stack = remember { mutableStateListOf<DriveItem>() }
    var query by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<DriveItem?>(null) }
    var preview by remember { mutableStateOf<File?>(null) }
    var disconnect by remember { mutableStateOf(false) }
    var starReading by remember { mutableStateOf(false) }
    val layoutPrefs=remember {context.getSharedPreferences("materials_layout",android.content.Context.MODE_PRIVATE)}
    var filters by remember {mutableStateOf(MaterialsFilters.read(layoutPrefs.getString("filters","").orEmpty()))}
    var arrangeFilters by remember {mutableStateOf(false)}
    val folder = stack.lastOrNull()
    fun run(block: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true; error = ""
            try { block() } catch (e: Exception) {
                if (e is CancellationException) throw e
                error = if(DriveConnection.unavailable(e) && repo.state.value.networkUnavailable)"" else DriveAuth.message(e)
            } finally { busy = false }
        }
    }
    fun refresh() = run { repo.refresh(folder, shared = folder == null && tab == "shared") }
    fun connected(token: String) = run {
        repo.connect(token)
        stack.clear(); tab = "shared"; selected = null; preview = null
        repo.refresh(shared = true)
        DriveSyncWorker.schedule(context)
    }
    val authorization = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        try {
            if (result.data?.hasExtra(ActivityResultContracts.StartIntentSenderForResult.EXTRA_SEND_INTENT_EXCEPTION) == true)
                throw java.io.IOException("Google sign-in could not open. Try connecting again.")
            val token = DriveAuth.complete(result.resultCode, result.data != null) {
                Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(result.data).accessToken
            }
            connected(token)
        } catch (e: Exception) {
            android.util.Log.i("ScholixDriveAuth", "Authorization result=${result.resultCode}, data=${result.data != null}, sdkStatus=${(e as? com.google.android.gms.common.api.ApiException)?.statusCode}")
            error = DriveAuth.message(e)
        }
    }
    fun connect() = run {
        val result = DriveAuth.authorize(context, state.account.takeIf { it.isNotBlank() })
        if (result.hasResolution()) {
            authorization.launch(IntentSenderRequest.Builder(requireNotNull(result.pendingIntent).intentSender).build())
        } else {
            repo.connect(result.accessToken ?: throw DriveNeedsConsent())
            stack.clear(); tab = "shared"; selected = null; preview = null
            repo.refresh(shared = true)
            DriveSyncWorker.schedule(context)
        }
    }
    fun openInDrive(item: DriveItem) {
        val uri = Uri.Builder().scheme("https").authority("drive.google.com")
            .appendPath("file").appendPath("d").appendPath(item.effectiveId).appendPath("view")
            .apply { if (item.effectiveKey.isNotEmpty()) appendQueryParameter("resourcekey", item.effectiveKey) }.build()
        try { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
        catch (_: android.content.ActivityNotFoundException) { error = "No browser or Drive viewer is available." }
    }
    fun open(item: DriveItem) {
        if (item.folder) { stack.add(item); return }
        if (item.pdf && item.canDownload) run { preview = repo.preview(item); selected = item }
        else if (repo.offlineFile(item).isFile) run {
            val copy = repo.shareCopy(item)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", copy)
            try { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, item.effectiveMime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
            catch (_: android.content.ActivityNotFoundException) { error = "No installed app can read this file. Open it in Google Drive." }
        } else openInDrive(item)
    }
    BackHandler(selected != null || stack.isNotEmpty()) {
        if (selected != null) { selected = null; preview = null } else stack.removeAt(stack.lastIndex)
    }
    LaunchedEffect(state.account) { stack.clear(); selected = null; preview = null }
    LaunchedEffect(state.account, state.followed, folder?.effectiveId, tab, lifecycle) {
        if (state.account.isBlank() || tab in listOf("offline", "star") && folder == null) return@LaunchedEffect
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                try {
                    if (folder != null || tab == "shared" || tab == "root")
                        repo.refresh(folder, shared = folder == null && tab == "shared")
                    else state.followed.forEach { repo.refresh(it) }
                } catch (e: Exception) { if (e is CancellationException) throw e }
                delay(60_000)
            }
        }
    }
    val listing = state.listings[repo.listingKey(folder, folder == null && tab == "shared")]
    val allItems = if (folder != null || tab in listOf("shared", "root")) listing?.items.orEmpty()
        else if (tab == "offline") state.savedFolders + state.offline else state.followed
    val items = allItems.distinctBy {it.id}.filter { it.name.contains(query, true) && it.name.contains(searchQuery, true) }
    Scaffold(topBar = {
        if(!starReading) {
        CenterAlignedTopAppBar(title = { Text(selected?.name ?: folder?.name ?: "Materials", maxLines = 1) },
            navigationIcon = { if (selected != null || stack.isNotEmpty()) IconButton(onClick = {
                if (selected != null) { selected = null; preview = null } else stack.removeAt(stack.lastIndex)
            }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                if (busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                if (state.account.isNotBlank() && selected == null) {
                    IconButton(onClick={arrangeFilters=true}) {Icon(Icons.Default.Tune,"Arrange filters")}
                    IconButton(onClick = { connect() }, enabled = !busy) { Icon(Icons.Default.Link, "Reconnect Google Drive") }
                    IconButton(onClick = { if (folder == null && tab == "followed") run {
                        state.followed.forEach { repo.refresh(it) }
                    } else refresh() }, enabled = !busy && tab != "offline") { Icon(Icons.Default.Refresh, "Refresh") }
                    IconButton(onClick = { disconnect = true }, enabled = !busy) { Icon(Icons.Default.LinkOff, "Disconnect Google Drive") }
                }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer))
        }
    }, containerColor = MaterialTheme.colorScheme.surfaceContainer) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).padding(bottom = 80.dp)) {
            val message = error.ifBlank { state.status }
            if (message.isNotEmpty()) Text(message, color = if(state.networkUnavailable&&error.isBlank())MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp))
            if (state.account.isBlank()) {
                Text("Your course folders, notes and past exams from Google Drive.", Modifier.padding(vertical = 12.dp))
                Text("Browse online. Save only the files you want to read offline.")
                Button(onClick = { connect() }, enabled = !busy) { Text("Connect Google Drive") }
                return@Column
            }
            if (state.needsConsent && !starReading) Button(onClick = { connect() }, enabled = !busy) { Text("Reconnect Google Drive") }
            if (selected != null && preview != null) {
                val item = selected!!
                Row {
                    TextButton(onClick = { run { repo.saveOffline(item) } }, enabled = !busy && item.downloadable) { Text("Save offline") }
                    TextButton(onClick = { openInDrive(item) }) { Text("Open in Drive") }
                }
                DrivePdfReader(preview!!, Modifier.weight(1f))
                return@Column
            }
            MaterialFilterLayout(filters,tab,visible=!starReading,onSelect={if(!busy){tab=it;stack.clear()}},modifier=Modifier.weight(1f)) {
            Column(Modifier.fillMaxSize()) {
            if (!starReading) {
            OutlinedTextField(query, { query = it }, label = { Text("Search this list") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            if (tab == "star") {
                key(state.account) { StarNotePage(repo, query, Modifier.weight(1f), onReader = { starReading = it }) }
                return@Column
            }
            if (folder != null) {
                val followed = state.followed.any { it.effectiveId == folder.effectiveId }
                TextButton(onClick = { run { repo.follow(folder) } }, enabled = !busy) {
                    Text(if (followed) "Unfollow folder" else "Follow as course folder")
                }
                FolderDownloadButton(folder,repo)
            }
            if (listing != null && (folder != null || tab in listOf("shared", "root"))) Text(
                "Last refreshed ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(listing.updated))}",
                style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(vertical = 6.dp))
            if (tab == "followed" && folder == null) Text("Open a folder from Shared with me or My Drive, then follow it for automatic updates.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (items.isEmpty()) item { Text(if (query.isNotBlank() || searchQuery.isNotBlank()) "No matching materials." else
                    if (tab == "offline") "No files saved offline." else "No folders or files to show yet.", Modifier.padding(vertical = 16.dp)) }
                items(items, key = { it.id }) { item ->
                    val saved = state.offline.firstOrNull { it.effectiveId == item.effectiveId }
                    ElevatedCard(onClick = { if (!busy) open(item) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(if (item.folder) Icons.Default.Folder else Icons.Default.Description, null)
                                Text(item.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            }
                            if (saved != null) Text(if (saved.modified != item.modified) "Saved offline · newer version online" else "Saved offline",
                                style = MaterialTheme.typography.labelSmall)
                            if(item.folder && state.savedFolders.any {it.effectiveId==item.effectiveId})Text("Downloaded locally",style=MaterialTheme.typography.labelSmall)
                            if (!item.folder) Row {
                                if (item.downloadable) TextButton(onClick = { run { repo.saveOffline(item) } }, enabled = !busy) {
                                    Text(if (saved == null) "Save offline" else "Update offline copy")
                                }
                                if (saved != null) TextButton(onClick = { run { repo.removeOffline(item) } }, enabled = !busy) { Text("Remove offline") }
                                TextButton(onClick = { openInDrive(item) }) { Text("Drive") }
                            }
                        }
                    }
                }
            }
            }
            }
        }
    }
    if(arrangeFilters)MaterialsFilterDialog(filters,onDismiss={arrangeFilters=false},onSave={
        filters=MaterialsFilters.normalize(it);layoutPrefs.edit().putString("filters",MaterialsFilters.encode(filters)).apply();arrangeFilters=false
    })
    if (disconnect) AlertDialog(onDismissRequest = { disconnect = false }, title = { Text("Disconnect Google Drive?") },
        text = { Text("This removes Scholix’s cached lists and offline copies. Your files in Google Drive stay intact.") },
        confirmButton = { TextButton(onClick = { disconnect = false; run { repo.disconnect() } }) { Text("Disconnect") } },
        dismissButton = { TextButton(onClick = { disconnect = false }) { Text("Cancel") } })
}

@Composable
private fun DrivePdfReader(file: File, modifier: Modifier = Modifier) {
    var page by remember(file) { mutableIntStateOf(0) }
    var count by remember(file) { mutableIntStateOf(0) }
    var bitmap by remember(file) { mutableStateOf<Bitmap?>(null) }
    var error by remember(file) { mutableStateOf("") }
    var zoom by remember(file, page) { mutableFloatStateOf(1f) }
    var offset by remember(file, page) { mutableStateOf(Offset.Zero) }
    var fit by remember(file) {mutableStateOf(ReaderFit.Page)}
    var fitRequest by remember(file) {mutableIntStateOf(0)}
    val transform = rememberTransformableState { scale, pan, _ ->
        zoom = (zoom * scale).coerceIn(1f, 5f)
        offset = if (zoom == 1f) Offset.Zero else Offset(
            (offset.x + pan.x).coerceIn(-1800f, 1800f), (offset.y + pan.y).coerceIn(-1800f, 1800f))
    }
    LaunchedEffect(file, page) {
        error = ""
        var rendered: Bitmap? = null
        try {
            val result = withContext(Dispatchers.IO) {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    PdfRenderer(descriptor).use { renderer ->
                        renderer.openPage(page).use { source ->
                            val scale = 1800.0 / maxOf(source.width, source.height)
                            val image = Bitmap.createBitmap(maxOf(1, (source.width * scale).toInt()),
                                maxOf(1, (source.height * scale).toInt()), Bitmap.Config.ARGB_8888)
                            rendered = image
                            image.eraseColor(android.graphics.Color.WHITE)
                            source.render(image, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            image to renderer.pageCount
                        }
                    }
                }
            }
            bitmap = result.first; count = result.second; rendered = null
        } catch (e: Exception) {
            rendered?.recycle()
            if (e is CancellationException) throw e
            error = "This PDF cannot be displayed here. Try opening it in Google Drive."
        }
    }
    DisposableEffect(bitmap) { val current = bitmap; onDispose { current?.recycle() } }
    Column(modifier) {
        if (error.isNotEmpty()) Text(error)
        bitmap?.let { image ->
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clipToBounds().transformable(transform)) {
                LaunchedEffect(page,fitRequest,maxWidth,maxHeight) {
                    zoom=if(fit==ReaderFit.Width)ReaderSizing.widthZoom(image.width.toFloat(),image.height.toFloat(),maxWidth.value,maxHeight.value) else 1f
                    offset=Offset.Zero
                }
                Image(image.asImageBitmap(), "Page ${page + 1}", Modifier.fillMaxSize().graphicsLayer {
                    scaleX = zoom; scaleY = zoom; translationX = offset.x; translationY = offset.y
                }, contentScale = ContentScale.Fit)
            }
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center) {
            TextButton(onClick={fit=ReaderFit.Page;fitRequest++}){Text("Fit page")}
            TextButton(onClick={fit=ReaderFit.Width;fitRequest++}){Text("Fit width")}
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { page-- }, enabled = page > 0) { Text("Previous") }
            Text("${page + 1} / ${count.coerceAtLeast(1)}", Modifier.padding(top = 12.dp))
            TextButton(onClick = { page++ }, enabled = page + 1 < count) { Text("Next") }
        }
    }
}
