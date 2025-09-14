package com.feldman.scholix.pages


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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
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
import com.feldman.scholix.R
import com.feldman.scholix.ui.components.Title
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@Entity(tableName = "locker_items")
data class LockerItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val location: ItemLocation
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

@Database(entities = [LockerItemEntity::class], version = 1)
@TypeConverters(ItemLocationConverter::class)
abstract class LockerDatabase : RoomDatabase() {
    abstract fun lockerItemDao(): LockerItemDao
}

class ItemLocationConverter {
    @TypeConverter
    fun fromLocation(location: ItemLocation): String = location.name

    @TypeConverter
    fun toLocation(value: String): ItemLocation = ItemLocation.valueOf(value)
}
class LockerRepository(private val dao: LockerItemDao) {
    suspend fun getAll() = dao.getAllItems()
    suspend fun addItem(name: String) = dao.insert(
        LockerItemEntity(name = name, location = ItemLocation.LOCKER)
    )
    suspend fun moveItem(item: LockerItemEntity) {
        val newLocation = if (item.location == ItemLocation.LOCKER) ItemLocation.HOME else ItemLocation.LOCKER
        dao.update(item.copy(location = newLocation))
    }
    suspend fun deleteItem(item: LockerItemEntity) = dao.delete(item)
}

class LockerViewModel(private val repository: LockerRepository) : ViewModel() {
    private val _items = MutableStateFlow<List<LockerItemEntity>>(emptyList())
    val items: StateFlow<List<LockerItemEntity>> = _items

    init {
        loadItems()
    }

    private fun loadItems() {
        viewModelScope.launch {
            _items.value = repository.getAll()
        }
    }

    fun addItem(name: String) {
        viewModelScope.launch {
            repository.addItem(name)
            loadItems()
        }
    }

    fun moveItem(item: LockerItemEntity) {
        viewModelScope.launch {
            repository.moveItem(item)
            delay(50)
            loadItems()
        }
    }

    fun deleteItem(item: LockerItemEntity) {
        viewModelScope.launch {
            repository.deleteItem(item)
            loadItems()
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun LockerApp(modifier: Modifier, viewModel: LockerViewModel) {
    val items by viewModel.items.collectAsState()
    var showDialog by remember { mutableStateOf(false) }
    var newItemName by remember { mutableStateOf("") }
    SharedTransitionLayout {
        Scaffold(
            floatingActionButton = {
                FloatingActionButton(
                    onClick = { showDialog = true },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.surface
                ) {
                    Icon(painterResource(R.drawable.ic_add), contentDescription = "Add Item")
                }
            },
            modifier = modifier
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        start = padding.calculateStartPadding(LayoutDirection.Ltr),
                        top = padding.calculateTopPadding(),
                        end = padding.calculateEndPadding(LayoutDirection.Ltr)
                    )
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(bottom = padding.calculateBottomPadding()), // ✅ this scrolls too
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Title(stringResource(R.string.locker))
                }

//                item {
//                    Text("In Locker", style = MaterialTheme.typography.titleMedium)
//                }
                item {
                    ItemList(
                        items.filter { it.location == ItemLocation.LOCKER },
                        onMove = { viewModel.moveItem(it) },
                        onDelete = { viewModel.deleteItem(it) },
                        sharedTransitionScope = this@SharedTransitionLayout
                    )
                }
//
//                item {
//                    Text("At Home", style = MaterialTheme.typography.titleMedium)
//                }
                item {
                    ItemList(
                        items.filter { it.location == ItemLocation.HOME },
                        onMove = { viewModel.moveItem(it) },
                        onDelete = { viewModel.deleteItem(it) },
                        sharedTransitionScope = this@SharedTransitionLayout

                        )
                }
                item {
                    Spacer(modifier = Modifier.height(80.dp))
                }
            }
        }

        if (showDialog) {
            AlertDialog(
                onDismissRequest = { showDialog = false },
                title = { Text("Add Item") },
                text = {
                    OutlinedTextField(
                        value = newItemName,
                        onValueChange = { newItemName = it },
                        label = { Text("Book/Notebook Name") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Done)
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        if (newItemName.isNotBlank()) {
                            viewModel.addItem(newItemName)
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
    }


}


@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun ItemList(
    items: List<LockerItemEntity>,
    onMove: (LockerItemEntity) -> Unit,
    onDelete: (LockerItemEntity) -> Unit,
    sharedTransitionScope: SharedTransitionScope,
) {
    if (items.isEmpty()) {
//        Text("No items here.", style = MaterialTheme.typography.bodySmall)
    } else {
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items.forEachIndexed { index, item ->
                val isFirst = index == 0
                val isLast = index == items.lastIndex
                var selected by remember { mutableStateOf(false) }

                val shape = when {
                    isFirst && isLast -> RoundedCornerShape(16.dp)
                    isFirst -> RoundedCornerShape(
                        topStart = 16.dp, topEnd = 16.dp,
                        bottomStart = 2.dp, bottomEnd = 2.dp
                    )
                    isLast -> RoundedCornerShape(
                        topStart = 2.dp, topEnd = 2.dp,
                        bottomStart = 16.dp, bottomEnd = 16.dp
                    )
                    else -> RoundedCornerShape(2.dp)
                }
                val selectedColor = MaterialTheme.colorScheme.primary
                    .copy(alpha = 0.2f)
                    .compositeOver(MaterialTheme.colorScheme.surface)
                with(sharedTransitionScope) {
                    AnimatedVisibility(
                        visible = items.contains(item),
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(100.dp)
                                .sharedBounds(
                                    rememberSharedContentState(key = item.id),
                                    animatedVisibilityScope = this,
                                    enter = fadeIn(),
                                    exit = fadeOut(),
                                    resizeMode = SharedTransitionScope.ResizeMode.ScaleToBounds()
                                )
                                .combinedClickable(
                                    onClick = { if (selected) selected = false },
                                    onLongClick = { selected = !selected }
                                ),
                            shape = shape,
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (selected) selectedColor
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxSize(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Big icon on the left
                                    Icon(
                                        painter = if (item.location == ItemLocation.LOCKER) painterResource(id = R.drawable.ic_lock) else painterResource(id = R.drawable.ic_home),
                                        contentDescription = null,
                                        modifier = Modifier.size(48.dp)
                                    )

                                    Spacer(Modifier.width(16.dp))

                                    // Text in the middle, expands to take space
                                    Text(
                                        text = item.name,
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.weight(1f)
                                    )

                                    FilledIconButton(
                                        onClick = { onMove(item) },
                                        colors = IconButtonDefaults.filledIconButtonColors(
                                            containerColor = MaterialTheme.colorScheme.secondary
                                        )
                                    ) {
                                        Icon(painter = painterResource(id = R.drawable.ic_move), contentDescription = "Move Home")
                                    }

                                    if (selected) {
                                        Spacer(Modifier.width(8.dp))
                                        FilledIconButton(
                                            onClick = {
                                                onDelete(item)
                                                selected = false
                                            },
                                            colors = IconButtonDefaults.filledIconButtonColors(
                                                containerColor = MaterialTheme.colorScheme.error,
                                                contentColor = MaterialTheme.colorScheme.onError
                                            )
                                        ) {
                                            Icon(painterResource(R.drawable.ic_delete), contentDescription = "Delete")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
