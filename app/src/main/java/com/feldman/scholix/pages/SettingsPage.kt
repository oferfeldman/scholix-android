package com.feldman.scholix.pages
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.combinedClickable
import com.feldman.scholix.R
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.material3.ToggleFloatingActionButtonDefaults.animateIcon
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.feldman.scholix.BottomBarSpacing
import com.feldman.scholix.api.LoginFields
import com.feldman.scholix.api.Platform
import com.feldman.scholix.api.PlatformInfo
import com.feldman.scholix.api.PlatformStorage
import com.feldman.scholix.api.applyLoginFields
import com.feldman.scholix.api.platformOptions
import com.feldman.scholix.Dest
import com.feldman.scholix.TopBarSpacing
import com.feldman.scholix.ui.components.ActionRow
import com.feldman.scholix.ui.components.FloatingTopAppBar
import com.feldman.scholix.ui.components.SegmentedOption
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlin.reflect.full.companionObjectInstance

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PlatformsPage(
    modifier: Modifier = Modifier,
    onPlatformsChanged: () -> Unit,
    onLogout: () -> Unit,
    navController: NavController
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var refreshKey by remember { mutableIntStateOf(0) }
    val currentPlatforms by remember(refreshKey) { mutableStateOf(PlatformStorage.loadPlatforms(context)) }

    var reorderMode by rememberSaveable { mutableStateOf(false) }
    var showAddSheet by rememberSaveable { mutableStateOf(false) }
    var editIndex by remember { mutableStateOf<Int?>(null) }
    var confirmDeleteIndex by remember { mutableStateOf<Int?>(null) }

    val backdrop = rememberLayerBackdrop()

    Scaffold(
        floatingActionButton = {
            var fabMenuExpanded by rememberSaveable { mutableStateOf(false) }
            BackHandler(fabMenuExpanded) { fabMenuExpanded = false }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .offset(y = 32.dp- BottomBarSpacing()),
                contentAlignment = Alignment.BottomEnd
            ) {
                FloatingActionButtonMenu(
                    expanded = fabMenuExpanded,
                    modifier = Modifier.align(Alignment.BottomEnd),
                    button = {
                        ToggleFloatingActionButton(
                            checked = fabMenuExpanded,
                            onCheckedChange = { fabMenuExpanded = !fabMenuExpanded },
                            modifier = Modifier.animateFloatingActionButton(
                                visible = true,
                                alignment = Alignment.BottomEnd
                            )
                        ) {
                            val imageVector by remember {
                                derivedStateOf {
                                    if (checkedProgress > 0.5f) Icons.Filled.Close else Icons.Filled.Add
                                }
                            }
                            Icon(
                                painter = rememberVectorPainter(imageVector),
                                contentDescription = null,
                                modifier = Modifier.animateIcon({ checkedProgress })
                            )
                        }
                    }
                ) {
                    // 🟦 Add Platform
                    FloatingActionButtonMenuItem(
                        onClick = {
                            fabMenuExpanded = false
                            showAddSheet = true
                        },
                        icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = "Add Platform") },
                        text = { Text("Add Platform") }
                    )

                    // 🟨 Toggle Reorder Mode
                    FloatingActionButtonMenuItem(
                        onClick = {
                            fabMenuExpanded = false
                            reorderMode = !reorderMode
                            Log.d("FAB", "Reorder mode: $reorderMode")
                        },
                        icon = {
                            Icon(
                                painterResource(R.drawable.ic_drag_indicator),
                                contentDescription = "Reorder Platforms"
                            )
                        },
                        text = {
                            Text(if (reorderMode) "Done Reordering" else "Reorder Platforms")
                        }
                    )

                    // 🟥 Logout
                    FloatingActionButtonMenuItem(
                        onClick = {
                            fabMenuExpanded = false
                            onLogout()
                        },
                        icon = {
                            Icon(
                                painterResource(R.drawable.ic_logout),
                                contentDescription = "Logout"
                            )
                        },
                        text = { Text("Logout") },
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                }
            }
        },
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding()
    ) { innerPadding ->
        FloatingTopAppBar(
            title = {
                Text(
                    text = "Platforms",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            },
            navigationIcon = {
                IconButton(onClick = { navController.navigateUp() }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 8.dp)
        ) {
            if (currentPlatforms.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    Text("No platforms yet")
                }
            } else {
                // 🟩 Pass reorderMode into the list
                ReorderablePlatformsList(
                    platforms = currentPlatforms,
                    reorderEnabled = reorderMode,
                    onReorder = { newList ->
                        refreshKey++
                        scope.launch(Dispatchers.IO) {
                            PlatformStorage.savePlatforms(context, newList)
                        }
                    },
                    onEdit = { editIndex = it },
                    onDelete = { confirmDeleteIndex = it },
                    onMakePrimary = { index ->
                        scope.launch(Dispatchers.IO) {
                            val mutable = currentPlatforms.toMutableList()
                            val item = mutable.removeAt(index)
                            mutable.add(0, item)
                            PlatformStorage.savePlatforms(context, mutable)
                            withContext(Dispatchers.Main) { refreshKey++ }
                        }
                    }
                )
            }
        }
    }


    // --- Edit Modal ---
    if (editIndex != null) {
        val idx = editIndex!!
        val current = currentPlatforms.getOrNull(idx)
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val backdrop = rememberLayerBackdrop()

        if (current != null) {
            val infoByClassName = remember {
                platformOptions.associateBy { it.factory()::class.java.name }
            }
            val selectedPlatformInfo = infoByClassName[current.javaClass.name]

            if (selectedPlatformInfo != null) {
                var loginFields by remember {
                    mutableStateOf(selectedPlatformInfo.factory().getLoginFields().apply {
                        loadFrom(current)
                    })
                }
                var busy by remember { mutableStateOf(false) }
                var errorMessage by remember { mutableStateOf<String?>(null) }

                ModalBottomSheet(
                    onDismissRequest = { editIndex = null },
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "Edit ${selectedPlatformInfo.name} Account",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(16.dp))
                        var name by remember { mutableStateOf(current.platformDisplayName.ifBlank { selectedPlatformInfo.name }) }

                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("Account name") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Spacer(Modifier.height(12.dp))
                        DynamicLoginFields(
                            fields = loginFields,
                            onFieldsChanged = { loginFields = it },
                            isLoading = busy,
                            errorMessage = errorMessage,
                            onSubmit = {
                                busy = true
                                errorMessage = null

                                scope.launch {
                                    try {
                                        busy = true
                                        errorMessage = null

                                        val platformClass = current.javaClass
                                        val companion = platformClass.kotlin.companionObjectInstance

                                        val isCorrect = withContext(Dispatchers.IO) {
                                            if (companion is Platform.Companion) {
                                                // call static credential checker
                                                companion.checkCredentials(loginFields)
                                            } else {
                                                Log.w("EditPlatform", "No credential checker for ${platformClass.simpleName}")
                                                false
                                            }
                                        }

                                        if (isCorrect) {
                                            withContext(Dispatchers.IO) {
                                                current.applyLoginFields(loginFields)
                                                PlatformStorage.updatePlatform(context, idx, current)
                                            }

                                            withContext(Dispatchers.Main) {
                                                busy = false
                                                editIndex = null
                                                refreshKey++
                                                Log.d("EditPlatform", "Credentials verified and updated for ${platformClass.simpleName}")
                                            }

                                        } else {
                                            withContext(Dispatchers.Main) {
                                                busy = false
                                                errorMessage = "Invalid username or password"
                                                Log.w("EditPlatform", "Credential check failed for ${platformClass.simpleName}")
                                            }
                                        }
                                    } catch (e: Exception) {
                                        busy = false
                                        errorMessage = "Error checking credentials: ${e.localizedMessage}"
                                        Log.e("EditPlatform", "Error updating credentials", e)
                                    }
                                }

                            },
                            onCancel = { editIndex = null },
                            buttonText = "Update"
                        )
                    }
                }
            }
        }
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    if (showAddSheet) {
        var selectedPlatform by remember { mutableStateOf<PlatformInfo?>(null) }
        var loginFields by remember { mutableStateOf<LoginFields?>(null) }
        var isLoading by remember { mutableStateOf(false) }
        var errorMessage by remember { mutableStateOf<String?>(null) }

        ModalBottomSheet(
            onDismissRequest = { showAddSheet = false },
            sheetState = sheetState
        ) {
            if (selectedPlatform == null) {
                // 🟦 Step 1: platform selection
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Choose a platform",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(12.dp))

                    ActionRow {
                        addVerticalActionList(
                            options = platformOptions.map { option ->
                                SegmentedOption(
                                    option,
                                    text = option.name,
                                    iconRes = option.iconRes
                                )
                            },
                            onClick = { option ->
                                selectedPlatform = option
                                loginFields = option.factory().getLoginFields()
                                errorMessage = null
                            },
                            isGlass = false,
                            backdrop = backdrop
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = { showAddSheet = false }) {
                        Text("Cancel")
                    }
                }
            } else {
                // 🟩 Step 2: login fields for the chosen platform
                val fields = loginFields ?: selectedPlatform!!.factory().getLoginFields()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        IconButton(
                            onClick = {
                                selectedPlatform = null
                                errorMessage = null
                            },
                            modifier = Modifier.align(Alignment.CenterStart)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                        Text(
                            text = selectedPlatform!!.name,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    DynamicLoginFields(
                        fields = fields,
                        onFieldsChanged = { loginFields = it },
                        isLoading = isLoading,
                        errorMessage = errorMessage,
                        onSubmit = {
                            val missing = fields.getFields().any { it.value.isNullOrBlank() }
                            if (missing) {
                                errorMessage = "Please fill in all fields"
                                return@DynamicLoginFields
                            }

                            isLoading = true
                            errorMessage = null

                            scope.launch {
                                try {
                                    val created = withContext(Dispatchers.IO) {
                                        val info = selectedPlatform!!
                                        val platformClass = info.factory()::class.java

                                        // Try to find a constructor that accepts LoginFields
                                        val constructor = platformClass.constructors.find { ctor ->
                                            ctor.parameterTypes.size == 1 && ctor.parameterTypes[0] == LoginFields::class.java
                                        }

                                        val instance = if (constructor != null) {
                                            // Platform supports direct LoginFields constructor — will auto-login
                                            constructor.newInstance(fields) as Platform
                                        } else {
                                            // Fallback: create blank, then apply fields manually
                                            info.factory().apply {
                                                applyLoginFields(fields)
                                            }
                                        }

                                        instance
                                    }

                                    val ok = withContext(Dispatchers.IO) {
                                        try {
                                            created.isLoggedIn()
                                        } catch (e: Exception) {
                                            Log.e("AddPlatform", "Login check failed", e)
                                            false
                                        }
                                    }


                                    if (ok) {
                                        withContext(Dispatchers.IO) {
                                            val list = PlatformStorage.loadPlatforms(context).toMutableList()
                                            list.add(created)
                                            PlatformStorage.savePlatforms(context, list)
                                        }

                                        withContext(Dispatchers.Main) {
                                            refreshKey++   // force recomposition + reload
                                            showAddSheet = false
                                            Log.d("AddPlatform", "Platform added successfully")
                                        }

                                    } else {
                                        errorMessage = "Invalid credentials"
                                    }
                                } catch (e: Exception) {
                                    errorMessage = "Error: ${e.localizedMessage}"
                                } finally {
                                    isLoading = false
                                }
                            }
                        },
                        onCancel = { showAddSheet = false },
                        buttonText = "Add"
                    )
                }
            }
        }
    }
// --- Delete confirmation ---
    if (confirmDeleteIndex != null) {
        val idx = confirmDeleteIndex!!
        val platform = PlatformStorage.loadPlatforms(context).getOrNull(idx)

        if (platform != null) {
            AlertDialog(
                onDismissRequest = { confirmDeleteIndex = null },
                title = { Text("Remove Platform") },
                text = { Text("Are you sure you want to delete ${platform.javaClass.simpleName}?") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    val list = PlatformStorage.loadPlatforms(context).toMutableList()
                                    if (idx in list.indices) {
                                        list.removeAt(idx)
                                        PlatformStorage.savePlatforms(context, list)
                                    }
                                }
                                withContext(Dispatchers.Main) {
                                    confirmDeleteIndex = null
                                    refreshKey++  // force refresh of UI
                                    Log.d("PlatformsPage", "Deleted ${platform.javaClass.simpleName}")
                                }
                            }
                        }
                    ) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDeleteIndex = null }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

@Composable
fun ReorderablePlatformsList(
    platforms: List<Platform>,
    reorderEnabled: Boolean,
    onReorder: (List<Platform>) -> Unit,
    onEdit: (Int) -> Unit,
    onDelete: (Int) -> Unit,
    onMakePrimary: (Int) -> Unit
) {
    val lazyListState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(
        lazyListState = lazyListState,
        onMove = { from, to ->
            val mutable = platforms.toMutableList().apply {
                add(to.index, removeAt(from.index))
            }
            onReorder(mutable)
        }
    )

    LazyColumn(
        state = lazyListState,
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
    ) {
        item {
            Spacer(Modifier.height(TopBarSpacing() +12.dp))
        }
        itemsIndexed(platforms, key = { _, platform -> platform.hashCode() }) { index, platform ->
            val isPrimary = index == 0
            val infoByClassName = remember {
                platformOptions.associateBy { it.factory()::class.java.name }
            }
            val selectedPlatformInfo = infoByClassName[platform.javaClass.name]

            val shape = when (index) {
                0 ->
                    if (platforms.lastIndex == 0)
                        RoundedCornerShape(16.dp)
                    else
                        RoundedCornerShape(
                            topStart = 16.dp,
                            topEnd = 16.dp,
                            bottomStart = 4.dp,
                            bottomEnd = 4.dp
                        )
                platforms.lastIndex ->
                    RoundedCornerShape(
                        bottomStart = 16.dp,
                        bottomEnd = 16.dp,
                        topStart = 4.dp,
                        topEnd = 4.dp
                    )
                else ->
                    RoundedCornerShape(4.dp)
            }

            ReorderableItem(reorderState, key = platform.hashCode()) { isDragging ->
                Card(
                    shape = shape,
                    modifier = Modifier
                        .fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = platform.platformDisplayName.ifBlank { selectedPlatformInfo?.name ?: platform.javaClass.name },
                                style = MaterialTheme.typography.titleLarge
                            )
                            Text(
                                if (isPrimary) "Primary" else if (reorderEnabled) "Drag to reorder" else "",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            IconButton(onClick = { onEdit(index) }) {
                                Icon(painterResource(R.drawable.ic_edit), contentDescription = "Edit")
                            }

                            IconButton(onClick = { onDelete(index) }) {
                                Icon(
                                    painterResource(R.drawable.ic_delete),
                                    contentDescription = "Remove",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }

                            if (reorderEnabled) {
                                IconButton(
                                    onClick = {},
                                    modifier = Modifier.draggableHandle()
                                ) {
                                    Icon(
                                        painterResource(R.drawable.ic_drag_indicator),
                                        contentDescription = "Drag handle"
                                    )
                                }
                            }

                        }
                    }
                }
            }

            // small spacing between cards
            if (index != platforms.lastIndex) {
                Spacer(Modifier.height(2.dp))
            }
        }
        item {
            Spacer(Modifier.height(BottomBarSpacing()))
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPage(
    navController: NavController,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val backdrop = rememberLayerBackdrop()

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(TopBarSpacing()))
        ActionRow {
            addVerticalActionList(
                options = listOf(
                    SegmentedOption("platforms", text = "Platforms", desc = "Add, Remove Or Edit Platforms", iconRes = R.drawable.ic_account),
                ),
                onClick = { option ->
                    when (option) {
                        "platforms" -> navController.navigate(Dest.Platforms.name)
                    }
                },
                isGlass = false,
                backdrop = backdrop
            )

        }
        Spacer(Modifier.height(BottomBarSpacing()))
    }

}