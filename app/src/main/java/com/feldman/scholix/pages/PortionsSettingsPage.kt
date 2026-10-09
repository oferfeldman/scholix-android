package com.feldman.scholix.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.feldman.motion.*
import com.feldman.scholix.R
import com.feldman.scholix.BottomBarSpacing
import com.feldman.scholix.attendance.AttendancePortionsCalculator
import com.feldman.scholix.attendance.AttendancePortionsStorage
import com.feldman.scholix.attendance.PortionsConfig
import com.feldman.scholix.attendance.SchoolPortionsTableDialog
import com.feldman.scholix.ui.components.SettingsCategoryColor
import com.feldman.scholix.ui.components.SettingsTopBar

@Composable
fun PortionsSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val config by AttendancePortionsStorage.configFlow(context)
        .collectAsState(initial = AttendancePortionsStorage.getPortionsConfig(context))

    val isDark = isMotionDarkTheme()
    val categoryColor = SettingsCategoryColor.PORTIONS.container(isDark)
    val categoryContentColor = SettingsCategoryColor.PORTIONS.content(isDark)

    var showTableDialog by remember { mutableStateOf(false) }
    var editingSubject by remember { mutableStateOf<String?>(null) }
    var editingSubjectHours by remember { mutableIntStateOf(2) }

    if (showTableDialog) {
        SchoolPortionsTableDialog(onDismiss = { showTableDialog = false })
    }

    if (editingSubject != null) {
        Dialog(onDismissRequest = { editingSubject = null }) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "שעות שבועיות: ${editingSubject.orEmpty()}",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontFamily = MotionFonts.feldman(weight = 700),
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Spacer(Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilledTonalIconButton(
                            onClick = { if (editingSubjectHours > 1) editingSubjectHours-- },
                            enabled = editingSubjectHours > 1
                        ) {
                            Icon(painter = rememberSymbolPainter(MotionSymbols.ic_remove), contentDescription = null)
                        }
                        Spacer(Modifier.width(20.dp))
                        Text(
                            text = "$editingSubjectHours שעות",
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontFamily = MotionFonts.feldman(weight = 700)
                            )
                        )
                        Spacer(Modifier.width(20.dp))
                        FilledTonalIconButton(
                            onClick = { if (editingSubjectHours < 25) editingSubjectHours++ },
                            enabled = editingSubjectHours < 25
                        ) {
                            Icon(painter = rememberSymbolPainter(MotionSymbols.ic_add), contentDescription = null)
                        }
                    }

                    Spacer(Modifier.height(20.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { editingSubject = null }) {
                            Text("ביטול")
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = {
                            editingSubject?.let { sub ->
                                AttendancePortionsStorage.setSubjectWeeklyHours(context, sub, editingSubjectHours)
                            }
                            editingSubject = null
                        }) {
                            Text("שמור")
                        }
                    }
                }
            }
        }
    }

    MotionScaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            SettingsTopBar(
                title = stringResource(R.string.attendance_portions_title),
                onBack = onBack,
                chromeColor = categoryColor,
                chromeContentColor = categoryContentColor
            )
        }
    ) {
        Item {
            Text(
                text = "הגדרת חישוב מנות היעדרות, בונוסים והפחתות ציון על פי כללי בית הספר ומשרד החינוך.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Title("הפעלה")
        Section {
            SwitchItem(
                title = "הפעל שיטת מנות",
                description = "הצגת חישוב מנות היעדרות, מדדים והתראות במסך הנוכחות",
                checked = config.enabled,
                onCheckedChange = {
                    AttendancePortionsStorage.savePortionsConfig(context, config.copy(enabled = it))
                }
            )
        }

        if (config.enabled) {
            Title("כללי ניקוד והפחתה")
            Section {
                NumberSettingItem(
                    title = "נקודות בונוס (עד מנה 1)",
                    description = "תוספת לציון אם לא נצברה יותר ממנת היעדרות אחת",
                    value = config.bonusPoints,
                    min = 1,
                    max = 10,
                    onValueChange = {
                        AttendancePortionsStorage.savePortionsConfig(context, config.copy(bonusPoints = it))
                    }
                )

                NumberSettingItem(
                    title = "הפחתת נקודות (מ-2 מנות)",
                    description = "כמות הנקודות המופחתות בצבירת 2 מנות היעדרות",
                    value = config.deductionAtFirstThreshold,
                    min = 1,
                    max = 20,
                    onValueChange = {
                        AttendancePortionsStorage.savePortionsConfig(
                            context,
                            config.copy(deductionAtFirstThreshold = it)
                        )
                    }
                )

                NumberSettingItem(
                    title = "הפחתה לכל מנה נוספת",
                    description = "תוספת הפחתה לכל מנת היעדרות מעבר ל-2 מנות",
                    value = config.deductionPerExtraPortion,
                    min = 1,
                    max = 10,
                    onValueChange = {
                        AttendancePortionsStorage.savePortionsConfig(
                            context,
                            config.copy(deductionPerExtraPortion = it)
                        )
                    }
                )

                NumberSettingItem(
                    title = "סף לציון שלילי (מנות)",
                    description = "מספר מנות שמעליו נקבע ציון שלילי במקצוע",
                    value = config.failingThresholdPortions.toInt(),
                    min = 3,
                    max = 10,
                    onValueChange = {
                        AttendancePortionsStorage.savePortionsConfig(
                            context,
                            config.copy(failingThresholdPortions = it.toFloat())
                        )
                    }
                )

                NumberSettingItem(
                    title = "שעות שבועיות ברירת מחדל",
                    description = "ערך ברירת המחדל למקצוע שבו לא הוגדרו שעות ידנית",
                    value = config.defaultWeeklyHours,
                    min = 1,
                    max = 10,
                    onValueChange = {
                        AttendancePortionsStorage.savePortionsConfig(
                            context,
                            config.copy(defaultWeeklyHours = it)
                        )
                    }
                )
            }

            Title("איחורים")
            Section {
                NumberSettingItem(
                    title = "המרה מאיחורים לחיסור",
                    description = if (config.latenessesPerAbsence == 0) "מושבת (איחורים אינם נספרים כחיסור)" else "${config.latenessesPerAbsence} איחורים שקולים לשעת חיסור אחת",
                    value = config.latenessesPerAbsence,
                    min = 0,
                    max = 5,
                    onValueChange = {
                        AttendancePortionsStorage.savePortionsConfig(
                            context,
                            config.copy(latenessesPerAbsence = it)
                        )
                    }
                )
            }

            Title("שעות שבועיות לפי מקצוע")
            if (config.subjectWeeklyHours.isEmpty()) {
                Item {
                    Text(
                        text = "טרם הוגדרו שעות ספציפיות. ניתן לערוך שעות ישירות מכרטיס המקצוע במסך הנוכחות.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Section {
                    config.subjectWeeklyHours.forEach { (subject, hours) ->
                        PageItem(
                            title = subject,
                            description = "$hours שעות שבועיות",
                            icon = rememberSymbolPainter(MotionSymbols.ic_school),
                            onClick = {
                                editingSubject = subject
                                editingSubjectHours = hours
                            }
                        )
                    }
                }
            }

            Title("טבלה והנחיות")
            Section {
                PageItem(
                    title = "טבלת מנות משרד החינוך",
                    description = "צפייה בטבלה המלאה של מדרגות ההיעדרות לפי שעות שבועיות",
                    icon = rememberSymbolPainter(MotionSymbols.ic_table_chart),
                    onClick = { showTableDialog = true }
                )
            }
        }

        Section {
            PageItem(
                title = stringResource(R.string.restore_defaults),
                description = "איפוס כל כללי המנות והשעות להגדרות ברירת המחדל של בית הספר",
                onClick = {
                    AttendancePortionsStorage.resetToDefaults(context)
                }
            )
        }

        Item {
            Spacer(Modifier.height(BottomBarSpacing()))
        }
    }
}

@Composable
private fun NumberSettingItem(
    title: String,
    description: String,
    value: Int,
    min: Int,
    max: Int,
    onValueChange: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.width(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(
                onClick = { if (value > min) onValueChange(value - 1) },
                enabled = value > min,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    painter = rememberSymbolPainter(MotionSymbols.ic_remove),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            }

            Text(
                text = "$value",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontFamily = MotionFonts.feldman(weight = 700)
                ),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 12.dp)
            )

            FilledTonalIconButton(
                onClick = { if (value < max) onValueChange(value + 1) },
                enabled = value < max,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    painter = rememberSymbolPainter(MotionSymbols.ic_add),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
