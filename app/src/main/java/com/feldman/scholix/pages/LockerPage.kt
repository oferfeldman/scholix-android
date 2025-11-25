package com.feldman.scholix.pages


import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.material3.ToggleFloatingActionButtonDefaults.animateIcon
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.room.*
import com.feldman.lockerapp.ui.theme.darkColors
import com.feldman.scholix.BOTTOM_BAR_SPACING
import com.feldman.scholix.R
import com.feldman.scholix.TOP_BAR_SPACING
import com.feldman.scholix.api.Platform
import com.feldman.scholix.api.PlatformStorage
import com.feldman.scholix.ui.components.Title
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Entity(tableName = "locker_items")
data class LockerItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val label: String,
    val location: ItemLocation,
    val hidden: Boolean = false,
    val platformId: String,
    val userCreated: Boolean
)

enum class ItemLocation { LOCKER, HOME }

@Dao
interface LockerItemDao {
    @Query("SELECT * FROM locker_items")
    suspend fun getAllItems(): List<LockerItemEntity>

    @Insert
    suspend fun insert(item: LockerItemEntity)

    @Update
    suspend fun update(item: LockerItemEntity)

    @Delete
    suspend fun delete(item: LockerItemEntity)

    @Query("UPDATE locker_items SET hidden = :isHidden WHERE id = :id")
    suspend fun setHidden(id: Int, isHidden: Boolean)
}

class LockerViewModelFactory(
    private val repository: LockerRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(LockerViewModel::class.java)) {
            return LockerViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

@Database(
    entities = [LockerItemEntity::class, LockerTabEntity::class],
    version = 2,
)
@TypeConverters(ItemLocationConverter::class)
abstract class LockerDatabase : RoomDatabase() {
    abstract fun lockerItemDao(): LockerItemDao
    abstract fun lockerTabDao(): LockerTabDao
}

class ItemLocationConverter {
    @TypeConverter
    fun fromLocation(location: ItemLocation): String = location.name

    @TypeConverter
    fun toLocation(value: String): ItemLocation = ItemLocation.valueOf(value)
}
class LockerRepository(
    private val itemDao: LockerItemDao,
    private val tabDao: LockerTabDao
) {
    suspend fun getAllTabs() = tabDao.getAllTabs()
    suspend fun addTab(name: String) = tabDao.insert(LockerTabEntity(name = name))
    suspend fun deleteTab(tab: LockerTabEntity) = tabDao.delete(tab)

    suspend fun getAll() = itemDao.getAllItems()
    suspend fun addItem(name: String, platformId: String, userCreated: Boolean) = itemDao.insert(
        LockerItemEntity(
            name = name,
            label = name,
            location = ItemLocation.LOCKER,
            platformId = platformId,
            userCreated = userCreated
        )
    )

    suspend fun moveItem(item: LockerItemEntity) {
        val newLocation = if (item.location == ItemLocation.LOCKER) ItemLocation.HOME else ItemLocation.LOCKER
        itemDao.update(item.copy(location = newLocation))
    }
    suspend fun updateLabel(item: LockerItemEntity, newLabel: String) {
        itemDao.update(item.copy(label = newLabel))
    }
    suspend fun deleteItem(item: LockerItemEntity) = itemDao.delete(item)

    suspend fun setHidden(item: LockerItemEntity, hidden: Boolean) =
        itemDao.setHidden(item.id, hidden)

    suspend fun hideItem(item: LockerItemEntity) {
        itemDao.update(item.copy(hidden = true))
    }

    suspend fun unhideItem(item: LockerItemEntity) {
        itemDao.update(item.copy(hidden = false))
    }
}


class LockerViewModel(private val repository: LockerRepository) : ViewModel() {
    private val _tabs = MutableStateFlow<List<LockerTabEntity>>(emptyList())
    val tabs: StateFlow<List<LockerTabEntity>> = _tabs

    private val _items = MutableStateFlow<List<LockerItemEntity>>(emptyList())
    val items: StateFlow<List<LockerItemEntity>> = _items


    init {
        loadTabs()
        loadItems()
    }

    private fun loadTabs() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = repository.getAllTabs()
            withContext(Dispatchers.Main) {
                _tabs.value = list
            }
        }
    }

    fun addTab(name: String) {
        viewModelScope.launch {
            repository.addTab(name)
            loadTabs()
        }
    }

    fun deleteTab(tab: LockerTabEntity) {
        viewModelScope.launch {
            repository.deleteTab(tab)
            loadTabs()
        }
    }






    private fun loadItems() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = repository.getAll()
            withContext(Dispatchers.Main) {
                _items.value = list
            }
        }
    }

    fun setHidden(item: LockerItemEntity, hidden: Boolean) {
        viewModelScope.launch {
            repository.setHidden(item, hidden)
            _items.value = _items.value.map {
                if (it.id == item.id) it.copy(hidden = hidden) else it
            }
        }
    }

    fun updateLabel(item: LockerItemEntity, newLabel: String) {
        viewModelScope.launch {
            repository.updateLabel(item, newLabel)
            _items.value = _items.value.map {
                if (it.id == item.id) it.copy(label = newLabel) else it
            }
        }
    }

    fun addItem(name: String, platformId: String, userCreated: Boolean = true) {
        viewModelScope.launch {
            repository.addItem(name, platformId, userCreated)
            loadItems()
        }
    }


    fun moveItem(item: LockerItemEntity) {
        viewModelScope.launch {
            // Update local state instantly
            val updated = _items.value.map {
                if (it.id == item.id) it.copy(
                    location = if (it.location == ItemLocation.LOCKER)
                        ItemLocation.HOME else ItemLocation.LOCKER
                ) else it
            }
            _items.value = updated
            repository.moveItem(item)
        }
    }


    fun deleteItem(item: LockerItemEntity) {
        viewModelScope.launch {
            repository.deleteItem(item)
            loadItems()
        }
    }

    fun hideItem(item: LockerItemEntity) {
        viewModelScope.launch {
            repository.hideItem(item)
            _items.value = _items.value.map {
                if (it.id == item.id) it.copy(hidden = true) else it
            }
        }
    }

    fun unhideItem(item: LockerItemEntity) {
        viewModelScope.launch {
            repository.unhideItem(item)
            _items.value = _items.value.map {
                if (it.id == item.id) it.copy(hidden = false) else it
            }
        }
    }

    fun filterItemsByPlatform(platformId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val allItems = repository.getAll()
            val filtered = allItems.filter { it.platformId == platformId }
            withContext(Dispatchers.Main) {
                _items.value = filtered
            }
        }
    }

}


@Entity(tableName = "locker_tabs")
data class LockerTabEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val userCreated: Boolean = true
)

@Dao
interface LockerTabDao {
    @Query("SELECT * FROM locker_tabs")
    suspend fun getAllTabs(): List<LockerTabEntity>

    @Insert
    suspend fun insert(tab: LockerTabEntity)

    @Delete
    suspend fun delete(tab: LockerTabEntity)
}

@Composable
fun ManualTabContent(tab: LockerTabEntity, viewModel: LockerViewModel) {
    val items by viewModel.items.collectAsState()
    val tabItems = items.filter { it.platformId == "manual_${tab.id}" }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Manual Tab: ${tab.name}", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))

        Button(onClick = { viewModel.addItem("New Item", "manual_${tab.id}") }) {
            Text("Add Item")
        }

        Spacer(Modifier.height(16.dp))
        tabItems.forEach {
            Text("- ${it.label}")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LockerApp(
    modifier: Modifier,
    viewModel: LockerViewModel,
    platforms: List<Platform>
) {
    val items by viewModel.items.collectAsState()
    val customTabs by viewModel.tabs.collectAsState()

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var showNewTabDialog by remember { mutableStateOf(false) }
    var newTabName by remember { mutableStateOf("") }

    val allTabs = remember(platforms, customTabs) {
        val platformTabs = platforms.map { it.javaClass.simpleName.replace("Platform", "") }
        val manualTabs = customTabs.map { it.name }
        platformTabs + manualTabs
    }

    val selectedManualTab = customTabs.getOrNull(selectedTabIndex - platforms.size)


    var selectedPlatformIndex by remember { mutableIntStateOf(0) }
    var showDialog by remember { mutableStateOf(false) }
    var newItemName by remember { mutableStateOf("") }
    var showHidden by remember { mutableStateOf(false) }
    println(platforms)

    LaunchedEffect(platforms.getOrNull(selectedTabIndex)?.id) {
        val platform = platforms.getOrNull(selectedPlatformIndex) ?: return@LaunchedEffect
        if (platform.supportsSchedule) {
            withContext(Dispatchers.IO) {
                try {
                    val remoteSubjects = platform.getSubjectList()
                    val localItems = viewModel.items.value.filter { it.platformId == platform.id }
                    val localNames = localItems.map { it.name }.toSet()
                    val remoteNames = remoteSubjects.toSet()

                    val newSubjects = remoteNames - localNames
                    val removedSubjects = localNames - remoteNames

                    newSubjects.forEach { subject ->
                        viewModel.addItem(subject, platform.id, userCreated = false)
                    }

                    removedSubjects.forEach { name ->
                        val toHide = localItems.find { it.name == name }
                        if (toHide != null && !toHide.hidden) {
                            viewModel.hideItem(toHide)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        } else {
            viewModel.filterItemsByPlatform(platform.id)
        }
    }

    Scaffold(
        floatingActionButton = {
            var fabMenuExpanded by rememberSaveable { mutableStateOf(false) }
            BackHandler(fabMenuExpanded) { fabMenuExpanded = false }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .offset(y = 32.dp-BOTTOM_BAR_SPACING),
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
                    FloatingActionButtonMenuItem(
                        onClick = {
                            fabMenuExpanded = false
                            showDialog = true
                        },
                        icon = { Icon(Icons.Filled.Create, contentDescription = null) },
                        text = { Text("Add Subject") }
                    )

                    if (platforms.getOrNull(selectedPlatformIndex)?.supportsSchedule == true) {
                        FloatingActionButtonMenuItem(
                            onClick = {
                                fabMenuExpanded = false
                                showHidden = !showHidden
                            },
                            icon = {
                                Icon(
                                    if (showHidden) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = null
                                )
                            },
                            text = { Text(if (showHidden) "Hide Hidden Items" else "Show Hidden Items") }
                        )
                    }

                }
            }
        },
        modifier = modifier
    ) { padding ->

        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(TOP_BAR_SPACING))
            if (allTabs.isNotEmpty()) {
                PrimaryScrollableTabRow(
                    selectedTabIndex = selectedTabIndex,
                    containerColor = MaterialTheme.colorScheme.background
                ) {
                    allTabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTabIndex == index,
                            onClick = { selectedTabIndex = index },
                            text = {
                                Text(
                                    title,
                                    fontWeight = if (selectedTabIndex == index)
                                        FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        )
                    }

                    // Add new manual tab
                    Tab(
                        selected = false,
                        onClick = { showNewTabDialog = true },
                        text = { Icon(Icons.Default.Add, contentDescription = "Add Tab") }
                    )
                }
            }

            println("selectedTabIndex: $selectedTabIndex - platforms.size: ${platforms.size}")

            val isManualTab = selectedTabIndex >= platforms.size
            val platform = platforms.getOrNull(selectedTabIndex)

            if (isManualTab) {
                // Manual tab
                if (selectedManualTab != null) {
                    ManualTabContent(tab = selectedManualTab, viewModel = viewModel)
                } else {
                    Text("Manual tab not found", Modifier.padding(16.dp))
                }
            } else if (platform != null) {
                // 🔹 Existing platform-based view
                SharedTransitionLayout {
                    key(platforms.getOrNull(selectedTabIndex)?.id) {
                        if (selectedTabIndex < platforms.size) {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 8.dp),
                            ) {
                                val filteredItems = items.filter { it.platformId == platform.id }

                                val homeItems = filteredItems.filter { (showHidden || !it.hidden) && it.location == ItemLocation.HOME }
                                val lockerItems = filteredItems.filter { (showHidden || !it.hidden) && it.location == ItemLocation.LOCKER }

                                if (!platform.supportsSchedule) {
                                    item {
                                        Text(
                                            text = "This platform doesn’t support schedules.\nYou can add your own subjects manually.",
                                            style = MaterialTheme.typography.bodyLarge.copy(
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            ),
                                            modifier = Modifier.padding(16.dp)
                                        )
                                    }
                                }

                                if (homeItems.isNotEmpty()) {
                                    item {
                                        Text(
                                            text = "At Home",
                                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                            modifier = Modifier.padding(vertical = 8.dp)
                                        )
                                    }
                                    item {
                                        ItemList(
                                            items = homeItems,
                                            sharedTransitionScope = this@SharedTransitionLayout,
                                            viewModel = viewModel
                                        )
                                    }
                                    item { Spacer(Modifier.height(32.dp)) }
                                }

                                if (lockerItems.isNotEmpty()) {
                                    item {
                                        Text(
                                            text = "In Locker",
                                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                            modifier = Modifier.padding(vertical = 8.dp)
                                        )
                                    }
                                    item {
                                        ItemList(
                                            items = lockerItems,
                                            sharedTransitionScope = this@SharedTransitionLayout,
                                            viewModel = viewModel
                                        )
                                    }
                                    item { Spacer(Modifier.height(100.dp)) }
                                }

                                item { Spacer(Modifier.height(BOTTOM_BAR_SPACING)) }
                            }
                        }
                    }
                }
            }
        }

        if (showDialog) {
            AlertDialog(
                onDismissRequest = { showDialog = false },
                title = { Text("Add Subject") },
                text = {
                    OutlinedTextField(
                        value = newItemName,
                        onValueChange = { newItemName = it },
                        label = { Text("Subject name") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Done)
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        if (newItemName.isNotBlank()) {
                            viewModel.addItem(newItemName, platforms[selectedPlatformIndex].id)
                            newItemName = ""
                            showDialog = false
                        }
                    }) { Text("Add") }
                },
                dismissButton = {
                    OutlinedButton(onClick = { showDialog = false }) { Text("Cancel") }
                }
            )
        }

        if (showNewTabDialog) {
            AlertDialog(
                onDismissRequest = { showNewTabDialog = false },
                title = { Text("Create New Tab") },
                text = {
                    OutlinedTextField(
                        value = newTabName,
                        onValueChange = { newTabName = it },
                        label = { Text("Tab Name") }
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        if (newTabName.isNotBlank()) {
                            viewModel.addTab(newTabName)
                            newTabName = ""
                            showNewTabDialog = false
                        }
                    }) { Text("Create") }
                },
                dismissButton = {
                    OutlinedButton(onClick = { showNewTabDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun ItemList(
    items: List<LockerItemEntity>,
    sharedTransitionScope: SharedTransitionScope,
    viewModel: LockerViewModel
) {
    var editingItem by remember { mutableStateOf<LockerItemEntity?>(null) }
    var newLabel by remember { mutableStateOf("") }

    if (items.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items.forEachIndexed { index, item ->
            val isFirst = index == 0
            val isLast = index == items.lastIndex
            val isSingle = items.size == 1

            val shape = when {
                isSingle -> RoundedCornerShape(16.dp)
                isFirst -> RoundedCornerShape(
                    topStart = 16.dp, topEnd = 16.dp,
                    bottomStart = 6.dp, bottomEnd = 6.dp
                )
                isLast -> RoundedCornerShape(
                    topStart = 6.dp, topEnd = 6.dp,
                    bottomStart = 16.dp, bottomEnd = 16.dp
                )
                else -> RoundedCornerShape(4.dp)
            }

            var selected by remember { mutableStateOf(false) }

            with(sharedTransitionScope) {
                AnimatedVisibility(visible = true, enter = fadeIn(), exit = fadeOut()) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp)
                            .sharedBounds(
                                rememberSharedContentState(key = item.id),
                                animatedVisibilityScope = this,
                                resizeMode = SharedTransitionScope.ResizeMode.ScaleToBounds()
                            )
                            .combinedClickable(
                                onClick = { viewModel.moveItem(item) },
                                onLongClick = { editingItem = item; newLabel = item.label }
                            ),
                        shape = shape,
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = when {
                                selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                    .compositeOver(MaterialTheme.colorScheme.surface)

                                item.hidden -> MaterialTheme.colorScheme.surfaceVariant
                                    .copy(alpha = 0.7f)
                                    .compositeOver(MaterialTheme.colorScheme.surface)

                                else -> MaterialTheme.colorScheme.surfaceVariant
                            }
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                painter = if (item.location == ItemLocation.LOCKER)
                                    painterResource(id = R.drawable.ic_lock)
                                else painterResource(id = R.drawable.ic_home),
                                contentDescription = null,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = item.label,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "(${item.name})", // Original ID
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
//
//                            FilledIconButton(onClick = { viewModel.moveItem(item) }) {
//                                Icon(
//                                    painter = painterResource(id = R.drawable.ic_move),
//                                    contentDescription = "Move"
//                                )
//                            }
//                            if (item.hidden) {
//                                FilledIconButton(onClick = { viewModel.hideItem(item.copy(hidden = false)) }) {
//                                    Icon(
//                                        painter = painterResource(id = R.drawable.ic_visibility),
//                                        contentDescription = "Show"
//                                    )
//                                }
//                            }

                        }
                    }
                }
            }
        }
    }


// 🔹 Edit label dialog
    if (editingItem != null) {
        val isHidden = editingItem!!.hidden

        AlertDialog(
            onDismissRequest = { editingItem = null },
            title = { Text("Edit Subject") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = newLabel,
                        onValueChange = { newLabel = it },
                        label = { Text("Display Label") }
                    )

                    Text(
                        text = "Original name: ${editingItem!!.name}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (editingItem!!.userCreated) {
                        Button(
                            onClick = {
                                viewModel.deleteItem(editingItem!!)
                                editingItem = null
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                            )
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_delete),
                                contentDescription = "Delete",
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Delete Subject")
                        }
                    }
                    else {
                        Button(
                            onClick = {
                                if (editingItem!!.hidden) {
                                    viewModel.unhideItem(editingItem!!)
                                } else {
                                    viewModel.hideItem(editingItem!!)
                                }
                                editingItem = null
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp), // thick button
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isHidden)
                                    MaterialTheme.colorScheme.primaryContainer
                                else
                                    MaterialTheme.colorScheme.errorContainer,
                                contentColor = if (isHidden)
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                else
                                    MaterialTheme.colorScheme.onErrorContainer
                            )
                        ) {
                            Icon(
                                painter = painterResource(
                                    id = if (isHidden) R.drawable.ic_visibility else R.drawable.ic_visibility_off
                                ),
                                contentDescription = if (isHidden) "Show" else "Hide",
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(if (isHidden) "Show Subject" else "Hide Subject")
                        }
                    }

                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.updateLabel(editingItem!!, newLabel)
                    editingItem = null
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { editingItem = null }) {
                    Text("Cancel")
                }
            }
        )
    }

}
