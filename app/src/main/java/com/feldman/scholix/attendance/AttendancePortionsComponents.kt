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
import com.feldman.motion.*
import com.feldman.scholix.R
import com.feldman.scholix.pages.isRtlText
import com.feldman.scholix.ui.components.SubjectIcon
import kotlin.math.min

@Composable
fun PortionsSummaryBanner(
    summaries: List<SubjectPortionsSummary>,
    onOpenTable: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bonusCount = summaries.count { it.status == GradeImpactStatus.BONUS }
    val deductionCount = summaries.count { it.status == GradeImpactStatus.DEDUCTION }
    val failingCount = summaries.count { it.status == GradeImpactStatus.FAILING }
    val neutralCount = summaries.count { it.status == GradeImpactStatus.NEUTRAL }

    val hasCritical = failingCount > 0
    val hasDeduction = deductionCount > 0

    val bannerContainerColor = when {
        hasCritical -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
        hasDeduction -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
        else -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
    }

    MotionCard(
        modifier = modifier.fillMaxWidth(),
        position = MotionItemPosition.Alone,
        containerColor = bannerContainerColor
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = rememberSymbolPainter(
                            if (hasCritical) MotionSymbols.ic_warning else MotionSymbols.ic_calculate
                        ),
                        contentDescription = null,
                        tint = when {
                            hasCritical -> MaterialTheme.colorScheme.error
                            hasDeduction -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.attendance_portions_title),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontFamily = MotionFonts.feldman(weight = 700)
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${summaries.size} מקצועות מחושבים",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = onOpenTable,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Icon(
                            painter = rememberSymbolPainter(MotionSymbols.ic_table_chart),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.portions_school_table), style = MaterialTheme.typography.labelMedium)
                    }

                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            painter = rememberSymbolPainter(MotionSymbols.ic_settings),
                            contentDescription = stringResource(R.string.portions_settings),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            PortionsDistributionBar(
                bonusCount = bonusCount,
                neutralCount = neutralCount,
                deductionCount = deductionCount,
                failingCount = failingCount
            )
        }
    }
}

@Composable
fun PortionsDistributionBar(
    bonusCount: Int,
    neutralCount: Int,
    deductionCount: Int,
    failingCount: Int,
    modifier: Modifier = Modifier
) {
    val total = bonusCount + neutralCount + deductionCount + failingCount
    if (total == 0) return

    val bonusColor = Color(0xFF2E7D32)
    val neutralColor = Color(0xFF1976D2)
    val deductionColor = Color(0xFFE65100)
    val failingColor = MaterialTheme.colorScheme.error

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (bonusCount > 0) {
                Box(
                    modifier = Modifier
                        .weight(bonusCount.toFloat())
                        .fillMaxHeight()
                        .background(bonusColor)
                )
            }
            if (neutralCount > 0) {
                Box(
                    modifier = Modifier
                        .weight(neutralCount.toFloat())
                        .fillMaxHeight()
                        .background(neutralColor)
                )
            }
            if (deductionCount > 0) {
                Box(
                    modifier = Modifier
                        .weight(deductionCount.toFloat())
                        .fillMaxHeight()
                        .background(deductionColor)
                )
            }
            if (failingCount > 0) {
                Box(
                    modifier = Modifier
                        .weight(failingCount.toFloat())
                        .fillMaxHeight()
                        .background(failingColor)
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            LegendItem(color = bonusColor, label = "בונוס ($bonusCount)")
            LegendItem(color = neutralColor, label = "ללא שינוי ($neutralCount)")
            LegendItem(color = deductionColor, label = "הפחתה ($deductionCount)")
            LegendItem(color = failingColor, label = "ציון שלילי ($failingCount)")
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
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun SubjectPortionCard(
    summary: SubjectPortionsSummary,
    position: MotionItemPosition,
    onEditHours: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isRtl = isRtlText(summary.subject)

    val badgeColor = when (summary.status) {
        GradeImpactStatus.BONUS -> Color(0xFF2E7D32)
        GradeImpactStatus.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
        GradeImpactStatus.DEDUCTION -> Color(0xFFD84315)
        GradeImpactStatus.FAILING -> MaterialTheme.colorScheme.error
    }

    val badgeContainerColor = when (summary.status) {
        GradeImpactStatus.BONUS -> Color(0xFFE8F5E9)
        GradeImpactStatus.NEUTRAL -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
        GradeImpactStatus.DEDUCTION -> Color(0xFFFBE9E7)
        GradeImpactStatus.FAILING -> MaterialTheme.colorScheme.errorContainer
    }

    val badgeText = when (summary.status) {
        GradeImpactStatus.BONUS -> "+${summary.pointsDelta} נק' בונוס"
        GradeImpactStatus.NEUTRAL -> "0 נק' (ללא שינוי)"
        GradeImpactStatus.DEDUCTION -> "${summary.pointsDelta} נקודות"
        GradeImpactStatus.FAILING -> "ציון שלילי"
    }

    MotionCard(
        modifier = modifier.fillMaxWidth(),
        position = position
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SubjectIcon(
                        subject = summary.subject,
                        assignment = "חיסור"
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = summary.subject,
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontFamily = MotionFonts.feldman(weight = 700),
                                fontWeight = FontWeight.Bold
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${summary.unjustifiedHours} שעות לא מוצדקות • ${summary.weeklyHours} שעות שבועיות",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(Modifier.width(8.dp))

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = badgeContainerColor,
                        contentColor = badgeColor
                    ) {
                        Text(
                            text = badgeText,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontFamily = MotionFonts.feldman(weight = 700)
                            ),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                PortionsGaugeBar(
                    portions = summary.portions,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val portionsFormatted = "%.1f".format(summary.portions)
                    Text(
                        text = "נצברו $portionsFormatted מנות",
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    val hintText = when {
                        summary.isFailing -> "צברת 5 מנות ויותר - הציון שלילי"
                        summary.status == GradeImpactStatus.BONUS && summary.hoursUntilBonusLost != null ->
                            "נותרו ${summary.hoursUntilBonusLost} שעות לבונוס"
                        summary.hoursUntilNextPenalty != null ->
                            "נותרו ${summary.hoursUntilNextPenalty} שעות להפחתה הבאה"
                        else -> summary.detailsMessage
                    }

                    Text(
                        text = hintText,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (summary.isFailing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onEditHours,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Icon(
                            painter = rememberSymbolPainter(MotionSymbols.ic_edit),
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "הגדר שעות (${summary.weeklyHours} ש״ש)",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PortionsGaugeBar(
    portions: Float,
    modifier: Modifier = Modifier
) {
    val clampedPortions = portions.coerceIn(0f, 6f)
    val progressFraction = clampedPortions / 6f

    val bonusFraction = 1f / 6f
    val neutralFraction = 1f / 6f
    val deductionFraction = 3f / 6f
    val failingFraction = 1f / 6f

    val isDark = isMotionDarkTheme()
    val bonusColor = if (isDark) Color(0xFF4CAF50) else Color(0xFF2E7D32)
    val neutralColor = if (isDark) Color(0xFF64B5F6) else Color(0xFF1976D2)
    val deductionColor = if (isDark) Color(0xFFFFB74D) else Color(0xFFF57C00)
    val failingColor = if (isDark) Color(0xFFE57373) else Color(0xFFD32F2F)

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp)
                .clip(RoundedCornerShape(6.dp))
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .weight(bonusFraction)
                        .fillMaxHeight()
                        .background(bonusColor.copy(alpha = 0.35f))
                )
                Box(
                    modifier = Modifier
                        .weight(neutralFraction)
                        .fillMaxHeight()
                        .background(neutralColor.copy(alpha = 0.35f))
                )
                Box(
                    modifier = Modifier
                        .weight(deductionFraction)
                        .fillMaxHeight()
                        .background(deductionColor.copy(alpha = 0.35f))
                )
                Box(
                    modifier = Modifier
                        .weight(failingFraction)
                        .fillMaxHeight()
                        .background(failingColor.copy(alpha = 0.35f))
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(progressFraction)
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        when {
                            portions <= 1f -> bonusColor
                            portions <= 2f -> neutralColor
                            portions < 5f -> deductionColor
                            else -> failingColor
                        }
                    )
            )
        }

        Spacer(Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("0 מנות", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("1 מנה (בונוס)", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("2 מנות (ללא שינוי)", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("5 מנות (שלילי)", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Text(
                    text = "שעות שבועיות למקצוע",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontFamily = MotionFonts.feldman(weight = 700),
                        fontWeight = FontWeight.Bold
                    )
                )
                Text(
                    text = subject,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(16.dp))

                Text(
                    text = "בחר את מספר השעות השבועיות שנלמדות במקצוע זה. מנה אחת שווה למספר שעות זה.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    FilledTonalIconButton(
                        onClick = { if (hours > 1) hours-- },
                        enabled = hours > 1,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(painter = rememberSymbolPainter(MotionSymbols.ic_remove), contentDescription = "הפחת")
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
                            text = "שעות שבועיות",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(Modifier.width(20.dp))

                    FilledTonalIconButton(
                        onClick = { if (hours < 25) hours++ },
                        enabled = hours < 25,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(painter = rememberSymbolPainter(MotionSymbols.ic_add), contentDescription = "הוסף")
                    }
                }

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(2, 3, 4, 5, 6).forEach { preset ->
                        FilterChip(
                            selected = hours == preset,
                            onClick = { hours = preset },
                            label = { Text("$preset ש״ש") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "מדרגות היעדרות למקצוע זה:",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "• בונוס: עד $hours שעות חיסור",
                            style = MaterialTheme.typography.bodySmall
                        )
                        val neutralText = if (hours == 1) "2 שעות" else "${hours + 1}-${2 * hours - 1} שעות"
                        Text(
                            text = "• ללא שינוי: $neutralText",
                            style = MaterialTheme.typography.bodySmall
                        )
                        val minus5Start = if (hours == 1) 3 else 2 * hours
                        Text(
                            text = "• הפחתת 5 נק': מ-$minus5Start שעות חיסור",
                            style = MaterialTheme.typography.bodySmall
                        )
                        val failingStart = if (hours == 1) 6 else 5 * hours
                        Text(
                            text = "• ציון שלילי: מ-$failingStart שעות חיסור ומעלה",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("ביטול")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { onConfirm(hours) }) {
                        Text("שמור")
                    }
                }
            }
        }
    }
}

@Composable
fun SchoolPortionsTableDialog(
    currentWeeklyHours: Int? = null,
    onDismiss: () -> Unit
) {
    val tableRows = remember { AttendancePortionsCalculator.buildSchoolReferenceTable() }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "טבלת מנות היעדרות",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontFamily = MotionFonts.feldman(weight = 700),
                                fontWeight = FontWeight.Bold
                            )
                        )
                        Text(
                            text = "על פי הנחיות משרד החינוך",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(painter = rememberSymbolPainter(MotionSymbols.ic_close), contentDescription = "סגור")
                    }
                }

                Spacer(Modifier.height(12.dp))

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = "כללי חישוב מנת היעדרות:",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "1. מנת היעדרות = מספר השעות השבועיות במקצוע.\n" +
                                    "2. החישוב נעשה לכל מקצוע בנפרד, בכל מחצית.\n" +
                                    "3. בונוס 2 נק' יינתן אם נצברה עד מנה אחת ולא הוצדקו מעל 2 מנות.\n" +
                                    "4. צבירת 5 מנות ומעלה מביאה לציון שלילי במקצוע.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                val horizontalScroll = rememberScrollState()
                val verticalScroll = rememberScrollState()

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .horizontalScroll(horizontalScroll)
                ) {
                    Column(
                        modifier = Modifier
                            .width(620.dp)
                            .verticalScroll(verticalScroll)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    MaterialTheme.colorScheme.primaryContainer,
                                    RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
                                )
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TableHeaderCell("ש״ש", Modifier.width(55.dp))
                            TableHeaderCell("בונוס\n(+2)", Modifier.width(80.dp))
                            TableHeaderCell("ללא שינוי\n(0)", Modifier.width(95.dp))
                            TableHeaderCell("הפחתה\n(-5)", Modifier.width(95.dp))
                            TableHeaderCell("הפחתה\n(-7)", Modifier.width(95.dp))
                            TableHeaderCell("הפחתה\n(-9)", Modifier.width(95.dp))
                            TableHeaderCell("ציון שלילי\n(נכשל)", Modifier.width(105.dp))
                        }

                        tableRows.forEachIndexed { index, row ->
                            val isSelected = currentWeeklyHours == row.weeklyHours
                            val rowColor = when {
                                isSelected -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
                                index % 2 == 0 -> MaterialTheme.colorScheme.surface
                                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(rowColor)
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TableDataCell(
                                    "${row.weeklyHours}",
                                    Modifier.width(55.dp),
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                )
                                TableDataCell(row.bonusHoursText, Modifier.width(80.dp), color = Color(0xFF2E7D32))
                                TableDataCell(row.neutralHoursText, Modifier.width(95.dp))
                                TableDataCell(row.minus5HoursText, Modifier.width(95.dp), color = Color(0xFFD84315))
                                TableDataCell(row.minus7HoursText, Modifier.width(95.dp), color = Color(0xFFD84315))
                                TableDataCell(row.minus9HoursText, Modifier.width(95.dp), color = Color(0xFFD84315))
                                TableDataCell(row.failingHoursText, Modifier.width(105.dp), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("סגור")
                }
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
