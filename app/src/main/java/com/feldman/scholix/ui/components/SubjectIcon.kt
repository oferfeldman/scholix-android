package com.feldman.scholix.ui.components

import android.graphics.Matrix
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath
import com.feldman.motion.LocalMotionCardState
import com.feldman.motion.MotionLevel
import com.feldman.motion.MotionSymbols
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import com.feldman.motion.rememberMotionLevel
import com.feldman.motion.rememberSymbolPainter
import com.feldman.motion.rememberSymbolsFontFamily

/**
 * Keyword -> Material Symbol name used to pick an icon for a subject/course name.
 * Add new subjects here (Hebrew and English variants) to expand the list.
 */
private val subjectIconKeywords: List<Pair<String, String>> = listOf(
    "נחשון" to MotionSymbols.ic_podium,

    // Languages
    "אנגלית" to MotionSymbols.ic_language_gb_english,
    "עברית" to MotionSymbols.ic_translate,
    "ערבית" to MotionSymbols.ic_translate,
    "לשון" to MotionSymbols.ic_translate,
    "צרפתית" to MotionSymbols.ic_language_french,
    "english" to MotionSymbols.ic_language_gb_english,
    "language" to MotionSymbols.ic_language,

    // Math
    "מתמטיקה" to MotionSymbols.ic_function,
    "חשבון" to MotionSymbols.ic_calculate,
    "math" to MotionSymbols.ic_function,

    // Chemistry
    "כימיה" to MotionSymbols.ic_science,
    "chemistry" to MotionSymbols.ic_science,

    // Biology
    "ביולוגיה" to MotionSymbols.ic_biotech,
    "biology" to MotionSymbols.ic_biotech,

    // Physics
    "פיזיקה" to MotionSymbols.ic_orbit,
    "פיסיקה" to MotionSymbols.ic_orbit,
    "physics" to MotionSymbols.ic_orbit,

    // General science
    "מדעים" to MotionSymbols.ic_science,

    // History / civics
    "היסטוריה" to MotionSymbols.ic_history_edu,
    "history" to MotionSymbols.ic_history_edu,
    "אזרחות" to MotionSymbols.ic_gavel,

    // Bible / religion
    "תנך" to MotionSymbols.ic_menu_book,
    "תושבע" to MotionSymbols.ic_menu_book,

    // Geography
    "של\"ח" to MotionSymbols.ic_landscape,
    "של״ח" to MotionSymbols.ic_landscape,
    "שלח" to MotionSymbols.ic_landscape,
    "גאוגרפיה" to MotionSymbols.ic_public,
    "geography" to MotionSymbols.ic_public,

    // Art / music
    "ספרות" to MotionSymbols.ic_auto_stories,
    "literature" to MotionSymbols.ic_auto_stories,
    "literary" to MotionSymbols.ic_auto_stories,
    "אמנות" to MotionSymbols.ic_palette,
    "art" to MotionSymbols.ic_palette,
    "מוזיקה" to MotionSymbols.ic_music_note,
    "music" to MotionSymbols.ic_music_note,

    // Sport
    "חינוך גופני" to MotionSymbols.ic_sports_gymnastics,
    "חנ\"ג" to MotionSymbols.ic_sports_gymnastics,
    "חנ״ג" to MotionSymbols.ic_sports_gymnastics,
    "חנג" to MotionSymbols.ic_sports_gymnastics,
    "ספורט" to MotionSymbols.ic_sports_gymnastics,
    "physical education" to MotionSymbols.ic_sports_gymnastics,
    "physical ed" to MotionSymbols.ic_sports_gymnastics,
    "p.e." to MotionSymbols.ic_sports_gymnastics,
    "gymnastics" to MotionSymbols.ic_sports_gymnastics,
    "gym" to MotionSymbols.ic_sports_gymnastics,
    "sport" to MotionSymbols.ic_sports_gymnastics,

    // Education / homeroom
    "חינוך" to MotionSymbols.ic_person,

    // Computer science
    "מחשבים" to MotionSymbols.ic_computer,
    "מדעי המחשב" to MotionSymbols.ic_computer,
    "computer" to MotionSymbols.ic_computer,

    // Psychology
    "פסיכולוגיה" to MotionSymbols.ic_psychology,
    "psychology" to MotionSymbols.ic_psychology,


    // Psychology
    "systems engineering" to MotionSymbols.ic_precision_manufacturing,
    "system engineering" to MotionSymbols.ic_precision_manufacturing,
    "הנדסת מערכות" to MotionSymbols.ic_precision_manufacturing
)

/** Assignment keywords take priority so each grade's icon reflects what was graded. */
private val assignmentIconKeywords: List<Pair<String, String>> = listOf(
    "מבחן" to MotionSymbols.ic_quiz,
    "בוחן" to MotionSymbols.ic_quiz,
    "exam" to MotionSymbols.ic_quiz,
    "test" to MotionSymbols.ic_quiz,
    "quiz" to MotionSymbols.ic_quiz,
    "שיעורי בית" to MotionSymbols.ic_assignment,
    "עבודה" to MotionSymbols.ic_assignment,
    "מטלה" to MotionSymbols.ic_assignment,
    "homework" to MotionSymbols.ic_assignment,
    "assignment" to MotionSymbols.ic_assignment,
    "task" to MotionSymbols.ic_assignment,
    "פרויקט" to MotionSymbols.ic_account_tree,
    "project" to MotionSymbols.ic_account_tree,
    "מצגת" to MotionSymbols.ic_slideshow,
    "presentation" to MotionSymbols.ic_slideshow,
    "מעבדה" to MotionSymbols.ic_science,
    "lab" to MotionSymbols.ic_science,
    "חיבור" to MotionSymbols.ic_edit_note,
    "essay" to MotionSymbols.ic_edit_note
)

private val genericSubjectIcon = MotionSymbols.ic_school

/** Picks an assignment-specific icon first, then falls back to the icon for [subject]. */
fun subjectIconName(subject: String, assignment: String = ""): String {
    val normalizedSubject = subject.trim().lowercase()
    val normalizedAssignment = assignment.trim().lowercase()
    assignmentIconKeywords.firstOrNull { (keyword, _) ->
        normalizedAssignment.contains(keyword.lowercase())
    }?.let { return it.second }

    return subjectIconKeywords.firstOrNull { (keyword, _) -> normalizedSubject.contains(keyword.lowercase()) }
        ?.second
        ?: genericSubjectIcon
}

/**
 * Whether [subject] maps to an icon of its own rather than the generic fallback.
 *
 * Lets a caller substitute something more useful -- a course with no recognisable
 * subject shows its provider's logo instead of a stock school glyph.
 */
fun hasSubjectIcon(subject: String, assignment: String = ""): Boolean =
    subjectIconName(subject, assignment) != genericSubjectIcon

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private val subjectShapes = listOf(
    MaterialShapes.Square,
    MaterialShapes.Cookie6Sided,
    MaterialShapes.Cookie7Sided,
    MaterialShapes.Cookie9Sided,
    MaterialShapes.Cookie12Sided,
    MaterialShapes.Sunny
)

/** Deterministically picks a Material 3 shape for [subject] so the same subject always gets the same shape. */
private fun subjectShape(subject: String): RoundedPolygon {
    val parsed = subject.filter(Char::isLetterOrDigit)
    val index = Math.floorMod(parsed.hashCode(), subjectShapes.size)
    return subjectShapes[index]
}

/** A static (non-morphing) [Shape] tracing [polygon]'s outline. */
private class PolygonMorphShape(
    private val morph: Morph,
    private val progress: Float
) : Shape {
    private var lastWidth: Float = -1f
    private var lastHeight: Float = -1f
    private var lastProgress: Float = -1f
    private var cachedOutline: Outline? = null

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val w = size.width
        val h = size.height
        if (w == lastWidth && h == lastHeight && progress == lastProgress && cachedOutline != null) {
            return cachedOutline!!
        }

        val path = Path()
        morph.toPath(progress, path)

        val bounds = RectF()
        path.computeBounds(bounds, true)
        val matrix = Matrix().apply {
            setRectToRect(bounds, RectF(0f, 0f, w, h), Matrix.ScaleToFit.FILL)
        }
        path.transform(matrix)
        val outline = Outline.Generic(path.asComposePath())

        lastWidth = w
        lastHeight = h
        lastProgress = progress
        cachedOutline = outline
        return outline
    }
}

@OptIn(ExperimentalTextApi::class)
@Composable
fun rememberCachedSymbolPainter(
    name: String,
    fill: Float = 1f,
    grad: Float = 0f,
    opsz: Float = 24f,
    wght: Int = 400
): Painter {
    val textMeasurer = rememberTextMeasurer()
    val fontFamily = rememberSymbolsFontFamily(fill = fill, grad = grad, opsz = opsz, wght = wght)

    return remember(name, fill, grad, opsz, wght, fontFamily) {
        object : Painter() {
            override val intrinsicSize: Size = Size.Unspecified
            private var cachedWidth: Float = -1f
            private var cachedHeight: Float = -1f
            private var cachedLayout: TextLayoutResult? = null

            override fun DrawScope.onDraw() {
                val w = size.width
                val h = size.height
                if (w <= 0f || h <= 0f) return

                var layout = cachedLayout
                if (layout == null || cachedWidth != w || cachedHeight != h) {
                    val fontSizePx = size.minDimension
                    layout = textMeasurer.measure(
                        text = name,
                        style = TextStyle(
                            fontFamily = fontFamily,
                            fontSize = fontSizePx.toSp() * 0.92f,
                            platformStyle = PlatformTextStyle(includeFontPadding = false)
                        )
                    )
                    cachedLayout = layout
                    cachedWidth = w
                    cachedHeight = h
                }

                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(
                        (w - layout.size.width) / 2f,
                        (h - layout.size.height) / 2f
                    )
                )
            }
        }
    }
}

/** Same spring specs Motion's settings items use to animate their icon shape/rotation. */
private fun motionSpringSpec(level: MotionLevel) = when (level) {
    MotionLevel.NONE -> snap<Float>()
    MotionLevel.LOW -> spring(dampingRatio = 0.8f, stiffness = 700f)
    MotionLevel.MEDIUM -> spring(dampingRatio = 0.42f, stiffness = 500f)
    MotionLevel.HIGH -> spring(dampingRatio = 0.3f, stiffness = 420f)
}

/**
 * A grade icon drawn on top of a Material 3 shape background, picked from [assignment] and [subject].
 * Mirrors the look and feel of the icon shape used by Motion's settings items — same spring-driven
 * long-press rotation, reacting to the enclosing [com.feldman.motion.MotionCard]'s long press —
 * except the shape itself never morphs into a different one.
 */
@Composable
fun SubjectIcon(
    subject: String,
    assignment: String = "",
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.tertiaryContainer,
    iconColor: Color = MaterialTheme.colorScheme.onTertiaryContainer,
    size: androidx.compose.ui.unit.Dp = 56.dp,
    iconSize: androidx.compose.ui.unit.Dp = 36.dp,
    painter: Painter? = null,
    polygon: RoundedPolygon? = null,
    morphPolygon: RoundedPolygon? = null
) {
    val iconName = remember(subject, assignment) { subjectIconName(subject, assignment) }

    val cardState = LocalMotionCardState.current
    val isPressed = cardState?.isLongPressed == true
    val motionLevel = rememberMotionLevel()
    val defaultPolygon = polygon ?: subjectShape(subject)
    val targetPolygon = morphPolygon ?: defaultPolygon
    val morph = remember(defaultPolygon, targetPolygon) { Morph(defaultPolygon, targetPolygon) }
    val morphProgress by animateFloatAsState(
        targetValue = if (isPressed) 1f else 0f,
        animationSpec = motionSpringSpec(motionLevel),
        label = "SubjectIconShapeMorph"
    )
    val shape = remember(morph, morphProgress) { PolygonMorphShape(morph, morphProgress) }
    val rotation by animateFloatAsState(
        targetValue = if (isPressed) 14f else 0f,
        animationSpec = motionSpringSpec(motionLevel),
        label = "SubjectIconRotation"
    )

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 1.1f else 1f,
        animationSpec = motionSpringSpec(motionLevel),
        label = "SubjectIconScale"
    )

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer {
                    rotationZ = rotation
                    scaleX = scale
                    scaleY = scale
                }
                .background(containerColor, shape)
        )
        Icon(
            painter = painter ?: rememberCachedSymbolPainter(name = iconName),
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size(iconSize)
        )
    }
}
