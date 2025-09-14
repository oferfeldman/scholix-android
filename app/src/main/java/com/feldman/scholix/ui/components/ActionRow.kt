package com.feldman.scholix.ui.components
import com.feldman.scholix.R
import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType

@Composable
fun CircleCheckbox(selected: Boolean, enabled: Boolean = true, onChecked: () -> Unit) {
    val color = MaterialTheme.colorScheme
    val iconRes = if (selected) R.drawable.ic_checked_circle else R.drawable.ic_unchecked_circle
    val tint = MaterialTheme.colorScheme.onSurface
    val background = if (selected) color.surface else Color.Transparent

    IconButton(
        onClick = { onChecked() },
        modifier = Modifier.offset(x = 4.dp, y = 4.dp),
        enabled = enabled
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = "checkbox",
            tint = tint,
            modifier = Modifier
                .background(background, shape = CircleShape)
                .size(30.dp)
        )
    }
}

data class SegmentedOption<T>(
    val value: T,
    val text: String,
    val iconRes: Int? = null,
    val enabled: Boolean = true,
    val title: String? = null,
    val font: FontFamily? = null,
    val autoFitText: Boolean = false,
    val colorSwatch: Color? = null,
    val backgroundColor: Color? = null,
    val selectedBackgroundColor: Color? = null,
    val textColor: Color? = null,
    val selectedTextColor: Color? = null
)
@DslMarker
annotation class ActionRowDsl

sealed class RowItem {
    data class ButtonItem(
        val text: String,
        val iconRes: Int? = null,
        val color: Color? = null,
        val onClick: () -> Unit,
        val enabled: () -> Boolean = { true }
    ) : RowItem()


    data class OutlinedButtonItem(
        val text: String,
        val iconRes: Int? = null,
        val onClick: () -> Unit,
        val enabled: () -> Boolean = { true }
    ) : RowItem()

    data class TextItem(
        val text: String,
        val style: TextStyle? = null,
        val fontSize: Int? = null,
        val color: Color? = null,
        val align: TextAlign? = null,
        val weight: FontWeight? = null
    ) : RowItem()

    data class DropdownItem(
        val label: String,
        val options: List<String>,
        val selected: String,
        val onSelectedChange: (String) -> Unit
    ) : RowItem()

    data class BackButtonItem(
        val onClick: () -> Unit
    ) : RowItem()

    data class OutlinedTextFieldItem(
        val state: MutableState<String>,
        val label: String,
        val onDone: () -> Unit,
        val leadingIcon: (@Composable (() -> Unit))? = null,
        val trailingIcon: (@Composable (() -> Unit))? = null,
        val weight: Float = 1f,
        val isNumeric: Boolean = false,
        val onValueChange: (String) -> Unit = {}
    ) : RowItem()

    data class KeyValueDropdownItem(
        val label: String,
        val options: List<Pair<String, String>>,
        val selectedId: String,
        val onSelectedChange: (String) -> Unit
    ) : RowItem()

    data class CircleCheckboxItem(
        val selected: Boolean,
        val enabled: Boolean = true,
        val onChecked: () -> Unit
    ) : RowItem()


    data class SegmentedToggleGroupItem<T>(
        val state: MutableState<T>,
        val options: List<SegmentedOption<T>>,
        val weight: Float = 1f,
        val onSelectedChange: ((T) -> Unit)? = null
    ) : RowItem()

    data class ComposableItem(
        val weight: Float = 1f,
        val content: @Composable () -> Unit
    ) : RowItem()

    data class Spacer(
        val width: Dp = 1.dp,
    ) : RowItem()

    data class VerticalPickerItem<T>(
        val state: MutableState<T>,
        val options: List<SegmentedOption<T>>,
        val onSelectedChange: ((T) -> Unit)? = null
    ) : RowItem()

    data class GridPickerItem<T>(
        val state: MutableState<T>,
        val options: List<SegmentedOption<T>>,
        val perRow: Int = 3,
        val onSelectedChange: ((T) -> Unit)? = null
    ) : RowItem()
}

@Suppress("unused")
@ActionRowDsl
class ActionRowScope {
    internal val items = mutableListOf<RowItem>()

    fun addButton(
        text: String,
        icon: Int? = null,
        color: Color? = null,
        onClick: () -> Unit,
        enabled: () -> Boolean = { true }
    ) {
        items.add(RowItem.ButtonItem(text, icon, color, onClick, enabled))
    }

    fun addOutlinedButton(
        text: String,
        icon: Int? = null,
        onClick: () -> Unit,
        enabled: () -> Boolean = { true }
    ) {
        items.add(RowItem.OutlinedButtonItem(text, icon, onClick ,enabled))
    }

    fun addText(
        text: String,
        style: TextStyle? = null,
        fontSize: Int? = null,
        color: Color? = null,
        align: TextAlign? = null,
        weight: FontWeight? = null
    ) {
        items.add(RowItem.TextItem(text, style, fontSize, color, align, weight))
    }
    @Composable
    fun addTitle(
        text: String,
    ) {
        items.add(RowItem.TextItem(text, MaterialTheme.typography.titleLarge, 24, null, TextAlign.Center, FontWeight.Bold))
    }
    @Composable
    fun addSmallTitle(
        text: String,
    ) {
        items.add(RowItem.TextItem(text, MaterialTheme.typography.titleSmall, 20, null, TextAlign.Start, FontWeight.Medium))
    }
    fun addDropdown(
        label: String = "Select",
        options: List<String>,
        selected: String,
        onSelectedChange: (String) -> Unit
    ) {
        items.add(RowItem.DropdownItem(label, options, selected, onSelectedChange))
    }
    fun addBackButton(onClick: () -> Unit) {
        items.add(RowItem.BackButtonItem(onClick))
    }

    fun addOutlinedTextField(
        state: MutableState<String>,
        label: String,
        onDone: () -> Unit,
        leadingIcon: (@Composable (() -> Unit))? = null,
        trailingIcon: (@Composable (() -> Unit))? = null,
        weight: Float = 1f,
        isNumeric: Boolean = false,
        onValueChange: (String) -> Unit = {}
    ) {
        items.add(RowItem.OutlinedTextFieldItem(state, label, onDone, leadingIcon, trailingIcon, weight, isNumeric, onValueChange))
    }


    fun addKeyValueDropdown(
        label: String = "Select",
        options: List<Pair<String, String>>,
        selectedId: String,
        onSelectedChange: (String) -> Unit
    ) {
        items.add(RowItem.KeyValueDropdownItem(label, options, selectedId, onSelectedChange))
    }

    fun addCircleCheckbox(
        selected: Boolean,
        enabled: Boolean = true,
        onChecked: () -> Unit
    ) {
        items.add(RowItem.CircleCheckboxItem(selected, enabled, onChecked))
    }

    fun <T> ActionRowScope.addSegmentedToggleGroup(
        state: MutableState<T>,
        vararg options: SegmentedOption<T>,
        weight: Float = 1f,
        onSelectedChange: ((T) -> Unit)? = null
    ) {
        require(options.isNotEmpty())
        items.add(
            RowItem.SegmentedToggleGroupItem(
                state = state,
                options = options.toList(),
                weight = weight,
                onSelectedChange = onSelectedChange
            )
        )
    }
    fun addComposable(
        weight: Float = 1f,
        content: @Composable () -> Unit
    ) {
        items.add(RowItem.ComposableItem(weight, content))

    }


    fun addSpacer(
        width: Dp = 1.dp,
    ) {
        items.add(RowItem.Spacer(width))
    }

    fun <T> ActionRowScope.addVerticalPicker(
        state: MutableState<T>,
        vararg options: SegmentedOption<T>,
        onSelectedChange: ((T) -> Unit)? = null
    ) {
        require(options.isNotEmpty())
        items.add(RowItem.VerticalPickerItem(state, options.toList(), onSelectedChange))
    }

    fun <T> ActionRowScope.addGridPicker(
        state: MutableState<T>,
        vararg options: SegmentedOption<T>,
        perRow: Int = 3,
        onSelectedChange: ((T) -> Unit)? = null
    ) {
        require(options.isNotEmpty())
        items.add(RowItem.GridPickerItem(state, options.toList(), perRow, onSelectedChange))
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ActionRow(
    spacing: Dp = 8.dp,
    content: @Composable ActionRowScope.() -> Unit
) {
    val scope = ActionRowScope().apply { content() }
    val hasBackButton = scope.items.any { it is RowItem.BackButtonItem }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalAlignment = Alignment.CenterVertically
    ) {
        scope.items.filterIsInstance<RowItem.BackButtonItem>().forEach { item ->
            IconButton(onClick = item.onClick) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        }

        scope.items.filter { it !is RowItem.BackButtonItem }.forEach { item ->
            when (item) {
                is RowItem.ButtonItem -> {
                    val resolvedColor = item.color ?: MaterialTheme.colorScheme.primary
                    Button(
                        onClick = item.onClick,
                        colors = ButtonDefaults.buttonColors(containerColor = resolvedColor),
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp),
                        enabled = item.enabled()
                    ) {
                        item.iconRes?.let { iconId ->
                            Icon(
                                painter = painterResource(id = iconId),
                                contentDescription = item.text
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(item.text, fontSize = 16.sp)
                    }
                }

                is RowItem.OutlinedButtonItem -> {
                    OutlinedButton(
                        onClick = item.onClick,
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp),
                        enabled = item.enabled()
                    ) {
                        item.iconRes?.let { iconId ->
                            Icon(
                                painter = painterResource(id = iconId),
                                contentDescription = item.text
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(item.text, fontSize = 16.sp)
                    }
                }

                is RowItem.TextItem -> {
                    val baseStyle = item.style ?: MaterialTheme.typography.bodyLarge
                    val resolvedStyle = baseStyle.copy(
                        fontSize = item.fontSize?.sp ?: baseStyle.fontSize,
                        color = item.color ?: MaterialTheme.colorScheme.onSurface,
                        fontWeight = item.weight ?: baseStyle.fontWeight
                    )

                    Text(
                        text = item.text,
                        style = resolvedStyle,
                        textAlign = item.align ?: TextAlign.Center,
                        modifier = Modifier
                            .weight(1f)
                            .let { if (hasBackButton) it.padding(end = 48.dp) else it }
                    )
                }

                is RowItem.DropdownItem -> {
                    var expanded by remember { mutableStateOf(false) }
                    Box(modifier = Modifier.weight(1f)) {
                        ExposedDropdownMenuBox(
                            expanded = expanded,
                            onExpandedChange = { expanded = !expanded },
                        ) {
                            val interactionSource = remember { MutableInteractionSource() }
                            OutlinedTextField(
                                value = item.selected,
                                onValueChange = {},
                                readOnly = true,
                                enabled = true,
                                label = { Text(item.label) },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                                singleLine = true,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier
                                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                                    .fillMaxWidth()
                                    .clickable(
                                        interactionSource = interactionSource,
                                        indication = null
                                    ) { expanded = !expanded },
                                textStyle = LocalTextStyle.current.copy(
                                    color = MaterialTheme.colorScheme.onSurface
                                ),
                                interactionSource = interactionSource
                            )

                            ExposedDropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                                    shape = RoundedCornerShape(12.dp)
                                )
                            ) {
                                item.options.forEachIndexed { index, option ->
                                    DropdownMenuItem(
                                        text = { Text(option) },
                                        leadingIcon = {
                                            if (option == item.selected) {
                                                Box(
                                                    modifier = Modifier.fillMaxHeight(),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Check,
                                                        contentDescription = "Selected",
                                                        tint = MaterialTheme.colorScheme.primary,
                                                    )
                                                }
                                            }
                                        },
                                        onClick = {
                                            item.onSelectedChange(option)
                                            expanded = false
                                        },
                                    )
                                    if (index < item.options.lastIndex) {
                                        HorizontalDivider(
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                is RowItem.OutlinedTextFieldItem -> {
                    val focusManager = LocalFocusManager.current
                    val context = LocalContext.current
                    val view = LocalView.current
                    val imm = remember {
                        context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    }
                    OutlinedTextField(
                        value = item.state.value,
                        onValueChange = { newValue ->
                            if (item.isNumeric) {
                                if (newValue.all { it.isDigit() }) {
                                    item.state.value = newValue
                                    item.onValueChange(newValue)
                                }
                            } else {
                                item.state.value = newValue
                                item.onValueChange(newValue)
                            }
                        },
                        label = { Text(item.label) },
                        singleLine = true,
                        maxLines = 1,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .weight(item.weight)
                            .fillMaxWidth(),
                        keyboardOptions = if (item.isNumeric) {
                            KeyboardOptions.Default.copy(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Done
                            )
                        } else {
                            KeyboardOptions.Default.copy(imeAction = ImeAction.Done)
                        },
                        keyboardActions = KeyboardActions(
                            onDone = {
                                imm.hideSoftInputFromWindow(view.windowToken, 0)
                                focusManager.clearFocus()
                                item.onDone()
                            }
                        ),
                        leadingIcon = item.leadingIcon,
                        trailingIcon = item.trailingIcon
                    )
                }
                is RowItem.KeyValueDropdownItem -> {
                    var expanded by remember { mutableStateOf(false) }
                    Box(modifier = Modifier.weight(1f)) {
                        ExposedDropdownMenuBox(
                            expanded = expanded,
                            onExpandedChange = { expanded = !expanded }
                        ) {
                            OutlinedTextField(
                                value = item.options.find { it.second == item.selectedId }?.first ?: "None",
                                onValueChange = {},
                                readOnly = true,
                                enabled = true,
                                label = { Text(item.label) },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                                singleLine = true,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier
                                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                                    .fillMaxWidth()
                                    .clickable { expanded = !expanded },
                                textStyle = LocalTextStyle.current.copy(
                                    color = MaterialTheme.colorScheme.onSurface
                                ),
                            )
                            ExposedDropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                                    shape = RoundedCornerShape(12.dp)
                                )
                            ) {
                                DropdownMenuItem(
                                    text = { Text("None") },
                                    leadingIcon = {
                                        if (item.selectedId.isEmpty()) {
                                            Box(
                                                modifier = Modifier.fillMaxHeight(),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = "Selected",
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                    },
                                    onClick = {
                                        item.onSelectedChange("")
                                        expanded = false
                                    }
                                )
                                item.options.forEachIndexed { index, (name, id) ->
                                    HorizontalDivider(
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
                                    )
                                    DropdownMenuItem(
                                        text = { Text(name) },
                                        leadingIcon = {
                                            if (id == item.selectedId) {
                                                Box(
                                                    modifier = Modifier.fillMaxHeight(),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Check,
                                                        contentDescription = "Selected",
                                                        tint = MaterialTheme.colorScheme.primary
                                                    )
                                                }
                                            }
                                        },
                                        onClick = {
                                            item.onSelectedChange(id)
                                            expanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
                is RowItem.CircleCheckboxItem -> {
                    CircleCheckbox(
                        selected = item.selected,
                        enabled = item.enabled,
                        onChecked = item.onChecked
                    )
                }

                is RowItem.SegmentedToggleGroupItem<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    val group = item as RowItem.SegmentedToggleGroupItem<Any?>

                    var selectedIndex by remember {
                        mutableStateOf(group.options.indexOfFirst { it.value == group.state.value })
                    }

                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier
                            .weight(group.weight)
                            .fillMaxWidth()
                            .height(70.dp)
                    ) {
                        group.options.forEachIndexed { index, opt ->
                            SegmentedButton(
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = group.options.size
                                ),
                                modifier = Modifier.height(70.dp),
                                onClick = {
                                    selectedIndex = index
                                    if (group.state.value != opt.value) {
                                        group.state.value = opt.value
                                        group.onSelectedChange?.invoke(opt.value)
                                    }
                                },
                                selected = index == selectedIndex,
                                enabled = opt.enabled,
                                colors = SegmentedButtonDefaults.colors(
                                    activeContainerColor = opt.selectedBackgroundColor
                                        ?: MaterialTheme.colorScheme.primary,
                                    activeContentColor = opt.selectedTextColor
                                        ?: MaterialTheme.colorScheme.onPrimary,
                                    inactiveContainerColor = opt.backgroundColor
                                        ?: MaterialTheme.colorScheme.surfaceVariant,
                                    inactiveContentColor = opt.textColor
                                        ?: MaterialTheme.colorScheme.onSurfaceVariant,
                                    disabledActiveContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                    disabledActiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                                    disabledInactiveContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                    disabledInactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                                ),
                                label = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center,
                                        modifier = Modifier.fillMaxHeight()
                                    ) {
                                        opt.iconRes?.let {
                                            Icon(
                                                painter = painterResource(it),
                                                contentDescription = opt.text,
                                                modifier = Modifier.size(36.dp)
                                            )
                                            Spacer(Modifier.width(8.dp))
                                        }
                                        Text(
                                            text = opt.text,
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            )
                        }
                    }
                }



                is RowItem.ComposableItem -> {
                    Box(Modifier.weight(item.weight)) {
                        item.content()
                    }
                }

                is RowItem.Spacer -> {
                    Spacer(modifier = Modifier.width(item.width))
                }
                is RowItem.VerticalPickerItem<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    val picker = item as RowItem.VerticalPickerItem<Any?>

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        picker.options.forEachIndexed { index, opt ->
                            val selected = picker.state.value == opt.value

                            val containerColor by animateColorAsState(
                                if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant,
                                label = "pickerContainer"
                            )
                            val contentColor by animateColorAsState(
                                if (selected) Color.White
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                label = "pickerContent"
                            )

                            val shape = when (index) {
                                0 -> RoundedCornerShape(
                                    topStart = 16.dp, topEnd = 16.dp,
                                    bottomStart = 4.dp, bottomEnd = 4.dp
                                )
                                picker.options.lastIndex -> RoundedCornerShape(
                                    topStart = 4.dp, topEnd = 4.dp,
                                    bottomStart = 16.dp, bottomEnd = 16.dp
                                )
                                else -> RoundedCornerShape(4.dp)
                            }

                            Card(
                                onClick = {
                                    if (picker.state.value != opt.value) {
                                        picker.state.value = opt.value
                                        picker.onSelectedChange?.invoke(opt.value)
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(60.dp),
                                shape = shape,
                                colors = CardDefaults.cardColors(containerColor),

                                ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(start=20.dp),
                                    verticalArrangement = Arrangement.Center,
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        opt.iconRes?.let {
                                            Icon(
                                                painter = painterResource(it),
                                                contentDescription = opt.text,
                                                tint = contentColor,
                                                modifier = Modifier.size(24.dp)
                                            )
                                            Spacer(Modifier.width(8.dp))
                                        }

                                        Box(Modifier.align(Alignment.CenterVertically)) {
                                            Text(
                                                text = opt.text,
                                                style = MaterialTheme.typography.bodyMedium.copy(
                                                    fontFamily = opt.font ?: MaterialTheme.typography.bodyMedium.fontFamily,
                                                    fontWeight = FontWeight.Medium,
                                                    fontSize = 16.sp
                                                ),
                                                fontSize = 20.sp,
                                                color = contentColor
                                            )
                                        }
                                    }
                                }

                            }
                        }
                    }
                }
                is RowItem.GridPickerItem<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    val picker = item as RowItem.GridPickerItem<Any?>

                    val rows = picker.options.chunked(picker.perRow)
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        rows.forEachIndexed { rowIndex, row ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                row.forEachIndexed { colIndex, opt ->
                                    val selected = picker.state.value == opt.value

                                    val containerColor by animateColorAsState(
                                        if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.surfaceVariant,
                                        label = "gridPickerContainer"
                                    )
                                    val contentColor by animateColorAsState(
                                        if (selected) Color.White
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        label = "gridPickerContent"
                                    )

                                    val shape = when {
                                        rowIndex == 0 && colIndex == 0 ->
                                            RoundedCornerShape(topStart = 16.dp, topEnd = 4.dp, bottomStart = 4.dp, bottomEnd = 4.dp)
                                        rowIndex == 0 && colIndex == row.lastIndex ->
                                            RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 4.dp)
                                        rowIndex == rows.lastIndex && colIndex == 0 ->
                                            RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = 16.dp, bottomEnd = 4.dp)
                                        rowIndex == rows.lastIndex && colIndex == row.lastIndex ->
                                            RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = 4.dp, bottomEnd = 16.dp)
                                        else -> RoundedCornerShape(4.dp)
                                    }

                                    Card(
                                        onClick = {
                                            if (picker.state.value != opt.value) {
                                                picker.state.value = opt.value
                                                picker.onSelectedChange?.invoke(opt.value)
                                            }
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(100.dp),
                                        shape = shape,
                                        colors = CardDefaults.cardColors(containerColor)
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(12.dp),
                                            verticalArrangement = Arrangement.Center,
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            when {
                                                opt.iconRes != null -> {
                                                    Icon(
                                                        painter = painterResource(opt.iconRes),
                                                        contentDescription = opt.text,
                                                        tint = contentColor,
                                                        modifier = Modifier.size(36.dp)
                                                    )
                                                }
                                                opt.colorSwatch != null -> {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(36.dp)
                                                            .background(opt.colorSwatch, CircleShape)
                                                            .border(
                                                                width = 2.dp,
                                                                color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface,
                                                                shape = CircleShape
                                                            )
                                                    )
                                                }
                                            }
                                            if (opt.title != null) {
                                                Spacer(Modifier.height(10.dp))
                                                Text(
                                                    text = opt.title ?: "",
                                                    style = MaterialTheme.typography.titleLarge.copy(
                                                        fontSize = 24.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        fontFamily = opt.font ?: MaterialTheme.typography.titleLarge.fontFamily
                                                    ),
                                                    textAlign = TextAlign.Center,
                                                    color = contentColor,
                                                    autoSize = if (opt.autoFitText) {
                                                        TextAutoSize.StepBased(
                                                            minFontSize = 4.sp,
                                                            maxFontSize = 20.sp,
                                                            stepSize = 1.sp,
                                                        )
                                                    } else {
                                                        null
                                                    }
                                                )
                                            }
                                            Spacer(Modifier.height(8.dp))
                                            Text(
                                                text = opt.text,
                                                style = MaterialTheme.typography.bodyMedium.copy(
                                                    fontFamily = opt.font ?: MaterialTheme.typography.bodyMedium.fontFamily,
                                                    fontWeight = FontWeight.Medium,
                                                    fontSize = 16.sp
                                                ),
                                                fontWeight = FontWeight.Medium,
                                                textAlign = TextAlign.Center,
                                                color = contentColor
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                else -> {}
            }
        }

    }
}

@Composable
fun Title(text: String) {
    ActionRow(spacing = 8.dp) {
        addTitle(
            text = text
        )
    }
    Spacer(modifier = Modifier.height(24.dp))
}


@Composable
fun Subtitle(text: String) {
    ActionRow(spacing = 8.dp) {
        addSmallTitle(
            text = text
        )
    }
    Spacer(modifier = Modifier.height(4.dp))
}