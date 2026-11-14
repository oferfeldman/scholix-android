package com.feldman.scholix.pages

import com.feldman.scholix.attendance.EditSubjectHoursDialog
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
import com.feldman.scholix.AppDest
import com.feldman.scholix.R
import com.feldman.scholix.attendance.AttendancePortionsCalculator
import com.feldman.scholix.attendance.AttendancePortionsStorage
import com.feldman.scholix.attendance.PortionsConfig
import com.feldman.scholix.ui.components.SettingsCategoryColor
import com.feldman.scholix.ui.components.SettingsTopBar

@Composable
fun PortionsSettingsPage(providerId: String, subject: String, onNavigate: MotionNavigator? = null, onBack: () -> Unit, initialWeeklyHours: Int? = null) {
    val context = LocalContext.current
    val configFlow = remember(context, providerId, subject) { AttendancePortionsStorage.configFlow(context, providerId, subject) }
    val initialConfig = remember(context, providerId, subject) { AttendancePortionsStorage.getPortionsConfig(context, providerId, subject) }
    val config by configFlow.collectAsState(initial = initialConfig)

    fun saveConfig(context: android.content.Context, updated: PortionsConfig) {
        AttendancePortionsStorage.savePortionsConfig(context, updated, providerId, subject)
    }
    val isDark = isMotionDarkTheme()
    val categoryColor = SettingsCategoryColor.PORTIONS.container(isDark)
    val categoryContentColor = SettingsCategoryColor.PORTIONS.content(isDark)

    MotionScaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            SettingsTopBar(
                title = subject,
                onBack = onBack,
                chromeColor = categoryColor,
                chromeContentColor = categoryContentColor
            )
        }
    ) {
        Title("הפעלה")
        Section {
            SwitchItem(
                title = "הפעל שיטת מנות",
                description = "הצגת חישוב מנות היעדרות, מדדים והתראות במסך הנוכחות",
                checked = config.enabled,
                onCheckedChange = {
                    saveConfig(context, config.copy(enabled = it))
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
                        saveConfig(context, config.copy(bonusPoints = it))
                    }
                )

                NumberSettingItem(
                    title = "הפחתת נקודות (מ-2 מנות)",
                    description = "כמות הנקודות המופחתות בצבירת 2 מנות היעדרות",
                    value = config.deductionAtFirstThreshold,
                    min = 1,
                    max = 20,
                    onValueChange = {
                        saveConfig(
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
                        saveConfig(
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
                        saveConfig(
                            context,
                            config.copy(failingThresholdPortions = it.toFloat())
                        )
                    }
                )

                NumberSettingItem(
                    title = "שעות שבועיות",
                    description = "$subject",
                    value = config.subjectWeeklyHours[subject] ?: initialWeeklyHours ?: config.defaultWeeklyHours,
                    min = 1,
                    max = 10,
                    onValueChange = {
                        saveConfig(
                            context,
                            config.copy(defaultWeeklyHours = it, subjectWeeklyHours = mapOf(subject to it))
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
                        saveConfig(
                            context,
                            config.copy(latenessesPerAbsence = it)
                        )
                    }
                )
            }

            Title("טבלה והנחיות")
            Section {
                PageItem(
                    title = "טבלת מנות משרד החינוך",
                    description = "צפייה בטבלה המלאה של מדרגות ההיעדרות לפי שעות שבועיות",
                    icon = rememberSymbolPainter(MotionSymbols.ic_table_chart),
                    onClick = { onNavigate?.invoke(AppDest.SchoolPortionsTable()) }
                )
            }
        }

        Section {
            PageItem(
                title = stringResource(R.string.restore_defaults),
                description = "איפוס כל כללי המנות והשעות להגדרות ברירת המחדל של בית הספר",
                onClick = {
                    AttendancePortionsStorage.resetToDefaults(context, providerId, subject)
                }
            )
        }

    }
}

@Composable
private fun MotionSectionScope.NumberSettingItem(
    title: String,
    description: String,
    value: Int,
    min: Int,
    max: Int,
    onValueChange: (Int) -> Unit
) {
    DropdownItem(label = title, options = (min..max).toList(), selected = value,
        onSelected = onValueChange)
}
