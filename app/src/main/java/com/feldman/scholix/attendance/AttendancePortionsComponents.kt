package com.feldman.scholix.attendance

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.pluralStringResource
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import com.feldman.motion.*
import com.feldman.scholix.R
import com.feldman.scholix.pages.isRtlText
import com.feldman.scholix.ui.components.SubjectIcon

/** Semantic colors for a grade impact, so the chart and badges follow the dynamic theme. */
@Immutable
data class PortionsStatusColors(val accent: Color, val container: Color, val onContainer: Color)

@Composable
fun portionsStatusColors(status: GradeImpactStatus): PortionsStatusColors {
    val scheme = MaterialTheme.colorScheme
    return when (status) {
        GradeImpactStatus.BONUS -> PortionsStatusColors(scheme.primary, scheme.primaryContainer, scheme.onPrimaryContainer)
        GradeImpactStatus.NEUTRAL -> PortionsStatusColors(scheme.secondary, scheme.secondaryContainer, scheme.onSecondaryContainer)
        GradeImpactStatus.DEDUCTION -> PortionsStatusColors(scheme.tertiary, scheme.tertiaryContainer, scheme.onTertiaryContainer)
        GradeImpactStatus.FAILING -> PortionsStatusColors(scheme.error, scheme.errorContainer, scheme.onErrorContainer)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PortionsSummaryBanner(
    summaries: List<SubjectPortionsSummary>,
    onOpenTable: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val counts = remember(summaries) { summaries.groupingBy { it.status }.eachCount() }
    val netPoints = remember(summaries) { summaries.filterNot { it.isFailing }.sumOf { it.pointsDelta } }
    val failing = counts[GradeImpactStatus.FAILING] ?: 0
    val netColors = portionsStatusColors(
        when {
            failing > 0 -> GradeImpactStatus.FAILING
            netPoints > 0 -> GradeImpactStatus.BONUS
            netPoints < 0 -> GradeImpactStatus.DEDUCTION
            else -> GradeImpactStatus.NEUTRAL
        }
    )

    MotionCard(
        modifier = modifier.fillMaxWidth(),
        position = MotionItemPosition.Alone,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentPadding = 0.dp
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(top = 12.dp)) {
                    Text(
                        text = stringResource(R.string.attendance_portions_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontFamily = MotionFonts.feldman(weight = 700),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.portions_calculated_subjects, summaries.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onOpenTable) {
                    Icon(
                        painter = rememberSymbolPainter(MotionSymbols.ic_table_chart),
                        contentDescription = stringResource(R.string.portions_school_table),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(
                        painter = rememberSymbolPainter(MotionSymbols.ic_settings),
                        contentDescription = stringResource(R.string.portions_settings),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 12.dp)) {
                Surface(shape = MaterialShapes.Cookie9Sided.toShape(), color = netColors.container) {
                    Box(Modifier.size(88.dp), contentAlignment = Alignment.Center) {
                        Text(
                            text = if (netPoints > 0) "+$netPoints" else "$netPoints",
                            style = MaterialTheme.typography.headlineMedium,
                            fontFamily = MotionFonts.feldman(weight = 800),
                            color = netColors.onContainer,
                            maxLines = 1
                        )
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.portions_net_impact),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    PortionsDistributionBar(
                        bonusCount = counts[GradeImpactStatus.BONUS] ?: 0,
                        neutralCount = counts[GradeImpactStatus.NEUTRAL] ?: 0,
                        deductionCount = counts[GradeImpactStatus.DEDUCTION] ?: 0,
                        failingCount = failing
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PortionsDistributionBar(
    bonusCount: Int,
    neutralCount: Int,
    deductionCount: Int,
    failingCount: Int,
    modifier: Modifier = Modifier
) {
    if (bonusCount + neutralCount + deductionCount + failingCount == 0) return
    val segments = listOf(
        Triple(GradeImpactStatus.BONUS, bonusCount, R.string.portions_legend_bonus),
        Triple(GradeImpactStatus.NEUTRAL, neutralCount, R.string.portions_legend_neutral),
        Triple(GradeImpactStatus.DEDUCTION, deductionCount, R.string.portions_legend_deduction),
        Triple(GradeImpactStatus.FAILING, failingCount, R.string.portions_legend_failing),
    ).filter { it.second > 0 }

    Column(modifier = modifier.fillMaxWidth()) {
        // Separate rounded segments with a gap, as Material's segmented indicators do.
        Row(
            modifier = Modifier.fillMaxWidth().height(12.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            segments.forEach { (status, count, _) ->
                Box(
                    Modifier
                        .weight(count.toFloat())
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(portionsStatusColors(status).accent)
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            segments.forEach { (status, count, label) ->
                LegendItem(color = portionsStatusColors(status).accent, label = stringResource(label, count))
            }
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Where the subject sits on the portion scale: the bonus, no-change, deduction and failing zones
 * drawn as a track, filled up to the portions used so far.
 */
@Composable
fun PortionsGauge(
    portions: Float,
    status: GradeImpactStatus,
    config: PortionsConfig,
    modifier: Modifier = Modifier
) {
    val bonusEnd = config.bonusThresholdPortions
    val neutralEnd = max(bonusEnd, config.neutralThresholdPortions)
    val failStart = max(neutralEnd, config.failingThresholdPortions)
    val scaleMax = max(failStart + 1f, ceil(portions + 0.25f))
    val zoneColors = listOf(
        portionsStatusColors(GradeImpactStatus.BONUS).accent,
        portionsStatusColors(GradeImpactStatus.NEUTRAL).accent,
        portionsStatusColors(GradeImpactStatus.DEDUCTION).accent,
        portionsStatusColors(GradeImpactStatus.FAILING).accent,
    )
    val fillColor = portionsStatusColors(status).accent
    val markerColor = MaterialTheme.colorScheme.onSurface
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val bounds = listOf(0f, bonusEnd, neutralEnd, failStart, scaleMax)
    val ticks = listOf(0f, bonusEnd, neutralEnd, failStart).distinct()

    Column(modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(14.dp)) {
            val trackHeight = 8.dp.toPx()
            val top = (size.height - trackHeight) / 2f
            val gap = 2.dp.toPx()
            fun x(value: Float): Float {
                val px = size.width * (value / scaleMax).coerceIn(0f, 1f)
                return if (rtl) size.width - px else px
            }
            for (i in 0 until 4) {
                val from = x(bounds[i])
                val to = x(bounds[i + 1])
                val left = minOf(from, to) + if (i > 0) gap / 2 else 0f
                val right = maxOf(from, to) - if (i < 3) gap / 2 else 0f
                if (right <= left) continue
                drawRoundRect(
                    color = zoneColors[i].copy(alpha = 0.22f),
                    topLeft = Offset(left, top),
                    size = Size(right - left, trackHeight),
                    cornerRadius = CornerRadius(trackHeight / 2)
                )
            }
            val start = x(0f)
            val end = x(portions)
            if (portions > 0f) {
                drawRoundRect(
                    color = fillColor,
                    topLeft = Offset(minOf(start, end), top),
                    size = Size(abs(end - start).coerceAtLeast(trackHeight), trackHeight),
                    cornerRadius = CornerRadius(trackHeight / 2)
                )
            }
            // Where the subject is now.
            val markerX = end.coerceIn(2.dp.toPx(), size.width - 2.dp.toPx())
            drawLine(
                color = markerColor,
                start = Offset(markerX, 0f),
                end = Offset(markerX, size.height),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
        Spacer(Modifier.height(4.dp))
        // Threshold labels under the zone boundaries.
        Layout(
            content = {
                ticks.forEach { tick ->
                    Text(
                        text = formatPortions(tick),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { measurables, constraints ->
            val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0)) }
            val height = placeables.maxOfOrNull { it.height } ?: 0
            layout(constraints.maxWidth, height) {
                placeables.forEachIndexed { i, p ->
                    val center = constraints.maxWidth * (ticks[i] / scaleMax)
                    val x = (center - p.width / 2f).coerceIn(0f, (constraints.maxWidth - p.width).toFloat())
                    // Mirrored in RTL, matching the track above.
                    p.placeRelative(x.roundToInt(), 0)
                }
            }
        }
    }
}

fun formatPortions(value: Float): String =
    if (value % 1f == 0f) value.toInt().toString() else String.format(java.util.Locale.US, "%.1f", value)

@Composable
fun SubjectPortionCard(
    summary: SubjectPortionsSummary,
    config: PortionsConfig,
    position: MotionItemPosition,
    onEditHours: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isRtl = isRtlText(summary.subject)
    val colors = portionsStatusColors(summary.status)

    val badgeText = when (summary.status) {
        GradeImpactStatus.BONUS -> stringResource(R.string.bonus_pts_badge, summary.pointsDelta)
        GradeImpactStatus.NEUTRAL -> stringResource(R.string.neutral_pts_badge)
        GradeImpactStatus.DEDUCTION -> stringResource(R.string.deduction_pts_badge, summary.pointsDelta)
        GradeImpactStatus.FAILING -> stringResource(R.string.failing_grade_badge)
    }
    val hint = when (summary.status) {
        GradeImpactStatus.BONUS -> summary.hoursUntilBonusLost?.let {
            if (it == 0) stringResource(R.string.portions_hint_bonus_last)
            else pluralStringResource(R.plurals.portions_hint_bonus_left, it, it)
        }
        GradeImpactStatus.NEUTRAL -> summary.hoursUntilNextPenalty?.let {
            pluralStringResource(R.plurals.portions_hint_until_deduction, it, it)
        }
        GradeImpactStatus.DEDUCTION -> summary.hoursUntilNextPenalty?.let {
            pluralStringResource(R.plurals.portions_hint_until_next_deduction, it, it)
        }
        GradeImpactStatus.FAILING -> stringResource(R.string.portions_hint_failing)
    }

    MotionCard(
        modifier = modifier.fillMaxWidth().clickable(onClick = onEditHours),
        position = position,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentPadding = 0.dp
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SubjectIcon(
                        subject = summary.subject,
                        assignment = "חיסור",
                        size = 44.dp,
                        iconSize = 26.dp
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = summary.subject,
                            style = MaterialTheme.typography.titleMedium,
                            fontFamily = MotionFonts.feldman(weight = 700),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.absences_count, summary.unjustifiedHours) +
                                " · " + stringResource(R.string.weekly_hours_suffix, summary.weeklyHours) +
                                " · " + stringResource(R.string.portions_value, formatPortions(summary.portions)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Surface(shape = CircleShape, color = colors.container, contentColor = colors.onContainer) {
                        Text(
                            text = badgeText,
                            style = MaterialTheme.typography.labelLarge,
                            fontFamily = MotionFonts.feldman(weight = 800),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                PortionsGauge(portions = summary.portions, status = summary.status, config = config)
                if (hint != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (summary.status == GradeImpactStatus.FAILING) colors.accent
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun EditSubjectHoursDialog(
    subject: String,
    initialHours: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var hours by remember { mutableIntStateOf(initialHours.coerceIn(1, 20)) }
    val isRtl = isRtlText(subject)

    MotionDialog(
        onDismissRequest = onDismiss,
        icon = "schedule",
        title = stringResource(R.string.portions_subject_hours_title),
        text = subject + "\n" + stringResource(R.string.portions_subject_hours_desc),
        confirmText = stringResource(R.string.save),
        onConfirm = { onConfirm(hours) },
        dismissText = stringResource(R.string.cancel),
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
            Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                FilledTonalIconButton(
                    onClick = { if (hours > 1) hours-- },
                    enabled = hours > 1,
                    modifier = Modifier.size(46.dp)
                ) {
                    Icon(painter = rememberSymbolPainter(MotionSymbols.ic_remove), contentDescription = null)
                }

                Spacer(Modifier.width(20.dp))

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "$hours",
                        style = MaterialTheme.typography.displayMedium.copy(
                            fontWeight = FontWeight.Black,
                            fontFamily = MotionFonts.feldman(weight = 900)
                        ),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = stringResource(R.string.weekly_hours),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.width(20.dp))

                FilledTonalIconButton(
                    onClick = { if (hours < 25) hours++ },
                    enabled = hours < 25,
                    modifier = Modifier.size(46.dp)
                ) {
                    Icon(painter = rememberSymbolPainter(MotionSymbols.ic_add), contentDescription = null)
                }
            }

            Spacer(Modifier.height(16.dp))

            // Preset chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(2, 3, 4, 5, 6).forEach { preset ->
                    MotionButton(
                        text = stringResource(R.string.weekly_hours_suffix, preset),
                        onClick = { hours = preset },
                        modifier = Modifier.weight(1f),
                        sizes = MotionButtonDefaults.sizes(height = 36.dp, fontSize = 12.sp),
                        states = MotionButtonDefaults.states(
                            default = if (hours == preset) {
                                MotionButtonState(
                                    backgroundColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                MotionButtonState(
                                    backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        )
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = stringResource(R.string.portions_subject_thresholds_title),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "• " + stringResource(R.string.portions_threshold_bonus, hours),
                        style = MaterialTheme.typography.bodySmall
                    )
                    val neutralText = if (hours == 1) {
                        stringResource(R.string.hours_suffix, 2)
                    } else {
                        "${hours + 1}-${2 * hours - 1} " + stringResource(R.string.hours_suffix, 1).replace("1 ", "")
                    }
                    Text(
                        text = "• " + stringResource(R.string.portions_threshold_neutral, neutralText),
                        style = MaterialTheme.typography.bodySmall
                    )
                    val minus5Start = if (hours == 1) 3 else 2 * hours
                    Text(
                        text = "• " + stringResource(R.string.portions_threshold_minus5, minus5Start),
                        style = MaterialTheme.typography.bodySmall
                    )
                    val failingStart = if (hours == 1) 6 else 5 * hours
                    Text(
                        text = "• " + stringResource(R.string.portions_threshold_failing, failingStart),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            }
        }
    }
}

/**
 * Bottom Sheet content for the School Reference Table.
 * Designed to fit smoothly within MotionBottomSheetScene / MotionScaffold(fitContentHeight = true).
 */
@Composable
fun SchoolPortionsTableDialog(
    onDismiss: () -> Unit,
    currentWeeklyHours: Int? = null,
    modifier: Modifier = Modifier
) {
    SchoolPortionsTableSheet(
        onClose = onDismiss,
        currentWeeklyHours = currentWeeklyHours,
        modifier = modifier
    )
}

@Composable
fun SchoolPortionsTableSheet(
    onClose: () -> Unit,
    currentWeeklyHours: Int? = null,
    modifier: Modifier = Modifier
) {
    val tableRows = remember { AttendancePortionsCalculator.buildSchoolReferenceTable() }
    val isAppRtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    MotionScaffold(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(0.dp),
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0),
        fitContentHeight = true
    ) {
        Item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 14.dp)
            ) {
                // Top Header Row with Title and Close Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.portions_school_table),
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontFamily = MotionFonts.feldman(weight = 700),
                                fontWeight = FontWeight.Bold
                            ),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = stringResource(R.string.portions_school_table_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    MotionButton(
                        icon = MotionSymbols.ic_close,
                        onClick = onClose,
                        sizes = MotionButtonDefaults.sizes(width = 38.dp, height = 38.dp, iconSize = 20.dp),
                        states = MotionButtonDefaults.states(
                            default = MotionButtonState(
                                backgroundColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        )
                    )
                }

                Spacer(Modifier.height(12.dp))

                // Guidelines summary card
                MotionCard(
                    modifier = Modifier.fillMaxWidth(),
                    position = MotionItemPosition.Alone,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    contentPadding = 12.dp
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.portions_rules_title),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.portions_rule_1) + "\n" +
                                    stringResource(R.string.portions_rule_2) + "\n" +
                                    stringResource(R.string.portions_rule_3) + "\n" +
                                    stringResource(R.string.portions_rule_4),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Scrollable Table
                val horizontalScroll = rememberScrollState()

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(horizontalScroll)
                ) {
                    Column(
                        modifier = Modifier
                            .width(620.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                    ) {
                        // Header Row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TableHeaderCell(stringResource(R.string.portions_table_header_weekly), Modifier.width(60.dp))
                            TableHeaderCell(stringResource(R.string.portions_table_header_bonus), Modifier.width(85.dp))
                            TableHeaderCell(stringResource(R.string.portions_table_header_neutral), Modifier.width(95.dp))
                            TableHeaderCell(stringResource(R.string.portions_table_header_minus_5), Modifier.width(95.dp))
                            TableHeaderCell(stringResource(R.string.portions_table_header_minus_7), Modifier.width(95.dp))
                            TableHeaderCell(stringResource(R.string.portions_table_header_minus_9), Modifier.width(95.dp))
                            TableHeaderCell(stringResource(R.string.portions_table_header_failing), Modifier.width(95.dp))
                        }

                        tableRows.forEachIndexed { index, row ->
                            val isSelected = currentWeeklyHours == row.weeklyHours
                            val rowColor = when {
                                isSelected -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.8f)
                                index % 2 == 0 -> MaterialTheme.colorScheme.surface
                                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(rowColor)
                                    .padding(vertical = 9.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TableDataCell(
                                    "${row.weeklyHours}",
                                    Modifier.width(60.dp),
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                )
                                TableDataCell(row.bonusHoursText, Modifier.width(85.dp), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                TableDataCell(row.neutralHoursText, Modifier.width(95.dp))
                                TableDataCell(row.minus5HoursText, Modifier.width(95.dp), color = MaterialTheme.colorScheme.tertiary)
                                TableDataCell(row.minus7HoursText, Modifier.width(95.dp), color = MaterialTheme.colorScheme.tertiary)
                                TableDataCell(row.minus9HoursText, Modifier.width(95.dp), color = MaterialTheme.colorScheme.tertiary)
                                TableDataCell(row.failingHoursText, Modifier.width(95.dp), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))

                MotionButton(
                    text = stringResource(R.string.close),
                    onClick = onClose,
                    modifier = Modifier.fillMaxWidth(),
                    sizes = MotionButtonDefaults.sizes(height = 46.dp, fontSize = 15.sp),
                    states = MotionButtonDefaults.states(
                        default = MotionButtonState(
                            backgroundColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    )
                )

                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun TableHeaderCell(text: String, modifier: Modifier) {
    Text(
        text = text,
        modifier = modifier,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onPrimaryContainer
    )
}

@Composable
private fun TableDataCell(
    text: String,
    modifier: Modifier,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight = FontWeight.Normal
) {
    Text(
        text = text,
        modifier = modifier,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodySmall.copy(fontWeight = fontWeight),
        color = color
    )
}
