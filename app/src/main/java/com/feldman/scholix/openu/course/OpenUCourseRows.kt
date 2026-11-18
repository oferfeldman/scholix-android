package com.feldman.scholix.openu.course

import android.text.BidiFormatter
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.feldman.motion.MotionDest
import com.feldman.motion.MotionPageSettingsItem
import com.feldman.motion.MotionSectionDefaults
import com.feldman.motion.MotionSectionScope
import com.feldman.motion.MotionSymbols
import com.feldman.scholix.LocalAppState
import com.feldman.scholix.pages.isRtlText

/** The direction of the first strongly directional character, so Hebrew rows mirror and English rows do not. */
internal fun contentDirection(text: String): LayoutDirection =
    if (isRtlText(text)) LayoutDirection.Rtl else LayoutDirection.Ltr

/**
 * A row of course content laid out in its own title's direction. It is highlighted while
 * [destination] is open, including when that page sits in the neighbouring pane.
 */
@Composable
internal fun MotionSectionScope.CourseRow(
    title: String,
    onClick: () -> Unit,
    key: Any,
    icon: Painter,
    description: String? = null,
    destination: MotionDest? = null,
    accent: Boolean = false,
) {
    val activePage = LocalAppState.current.activePage
    val isSelected = destination != null && generateSequence(activePage) { it.parent }.any { it == destination }
    val direction = contentDirection(title)
    val bidi = BidiFormatter.getInstance(direction == LayoutDirection.Rtl)
    val supportingText = description?.takeIf(String::isNotBlank)?.lines()?.joinToString("\n") { bidi.unicodeWrap(it) }
    val style = if (accent) MotionSectionDefaults.iconStyle(colorScheme.tertiaryContainer, colorScheme.onTertiaryContainer)
        else MotionSectionDefaults.iconStyle(colorScheme.secondaryContainer, colorScheme.onSecondaryContainer)
    Item(key = key, padding = 0.dp, containerColor = if (isSelected) colorScheme.primary else null,
        iconStyle = style, selected = isSelected) {
        CompositionLocalProvider(LocalLayoutDirection provides direction) {
            MotionPageSettingsItem(title = title, onClick = onClick, description = supportingText,
                modifier = Modifier.semantics { if (destination != null) selected = isSelected },
                icon = icon, iconStyle = style, selected = isSelected)
        }
    }
}

/** Opal activity modules mapped to the symbol that names what opening them shows. */
internal fun moduleSymbol(module: String): String = when (module) {
    "resource" -> MotionSymbols.ic_description
    "url" -> MotionSymbols.ic_link
    "page", "book" -> MotionSymbols.ic_article
    "folder" -> MotionSymbols.ic_folder
    "quiz" -> MotionSymbols.ic_quiz
    "forum", "ouilforum" -> MotionSymbols.ic_forum
    "ouilvideocollection", "video", "ouil_video" -> MotionSymbols.ic_video_library
    "assign", "ouilassign" -> MotionSymbols.ic_assignment
    "label" -> MotionSymbols.ic_notes
    else -> MotionSymbols.ic_open_in_new
}

/** Short readable name for a module when the row has nothing better to say. */
internal fun moduleLabel(module: String): String = when (module) {
    "resource" -> "File"
    "url" -> "Link"
    "page", "book" -> "Page"
    "folder" -> "Folder"
    "quiz" -> "Quiz"
    "forum", "ouilforum" -> "Forum"
    "ouilvideocollection", "video", "ouil_video" -> "Recordings"
    "assign", "ouilassign" -> "Assignment"
    else -> ""
}
