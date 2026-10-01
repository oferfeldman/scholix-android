package com.feldman.scholix.pages

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.feldman.motion.MotionScaffold
import com.feldman.motion.MotionSymbols
import com.feldman.motion.MotionFonts
import com.feldman.motion.isMotionDarkTheme
import com.feldman.scholix.R
import com.feldman.scholix.ui.components.SettingsCategoryColor
import com.feldman.scholix.ui.components.SettingsTopBar
import com.feldman.scholix.ui.components.SubjectIcon
import com.feldman.scholix.ui.components.rememberCachedSymbolPainter
import com.feldman.scholix.util.CrashHandler
import com.feldman.scholix.util.CrashRecord
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CrashLogsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    var crashReports by remember { mutableStateOf(CrashHandler.getCrashReports(context)) }

    val isDark = isMotionDarkTheme()
    val categoryColor = SettingsCategoryColor.SYSTEM.container(isDark)
    val categoryContentColor = SettingsCategoryColor.SYSTEM.content(isDark)
    val configuration = LocalConfiguration.current
    val emptyViewportHeight = remember(configuration.screenHeightDp) {
        (configuration.screenHeightDp.dp - 140.dp).coerceAtLeast(320.dp)
    }

    MotionScaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            SettingsTopBar(
                title = stringResource(R.string.crash_logs),
                onBack = onBack,
                chromeColor = categoryColor,
                chromeContentColor = categoryContentColor
            )
        }
    ) {
        if (crashReports.isEmpty()) {
            Item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(emptyViewportHeight),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(bottom = 24.dp)
                    ) {
                        SubjectIcon(
                            subject = "crashes",
                            assignment = "empty",
                            painter = rememberCachedSymbolPainter(name = MotionSymbols.ic_check),
                            containerColor = MaterialTheme.colorScheme.primary,
                            iconColor = MaterialTheme.colorScheme.onPrimary,
                            size = 128.dp,
                            iconSize = 68.dp,
                            polygon = MaterialShapes.Cookie12Sided
                        )
                        Spacer(Modifier.height(20.dp))
                        Text(
                            text = stringResource(R.string.no_crashes),
                            style = MaterialTheme.typography.headlineMedium,
                            fontFamily = MotionFonts.feldman(weight = 600, width = 140f),
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        } else {
            Item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${crashReports.size} ${stringResource(R.string.crash_logs)}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(
                        onClick = {
                            CrashHandler.clearCrashReports(context)
                            crashReports = emptyList()
                            Toast.makeText(context, "Logs cleared", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Text(stringResource(R.string.clear_logs), color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            Section {
                crashReports.forEach { report ->
                    Item(key = report.id, padding = 0.dp) {
                        CrashReportItem(
                            report = report,
                            onCopy = {
                                val text = buildCrashReportText(report)
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("Crash Report", text))
                                Toast.makeText(context, "Report copied to clipboard", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CrashReportItem(
    report: CrashRecord,
    onCopy: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    val formattedTime = remember(report.timestamp) { dateFormat.format(Date(report.timestamp)) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f, fill = false)
            ) {
                SubjectIcon(
                    subject = "crash",
                    assignment = "error",
                    painter = rememberCachedSymbolPainter(name = MotionSymbols.ic_bug_report),
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    iconColor = MaterialTheme.colorScheme.onErrorContainer,
                    size = 40.dp,
                    iconSize = 22.dp,
                    polygon = MaterialShapes.Cookie9Sided
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = report.exceptionClass.substringAfterLast('.'),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = formattedTime,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Icon(
                painter = rememberCachedSymbolPainter(
                    name = if (expanded) MotionSymbols.ic_keyboard_arrow_up else MotionSymbols.ic_keyboard_arrow_down
                ),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
        }

        if (report.message.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = report.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(top = 12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Device: ${report.deviceModel} | Android ${report.androidVersion} | Thread: ${report.threadName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    IconButton(onClick = onCopy, modifier = Modifier.size(32.dp)) {
                        Icon(
                            painter = rememberCachedSymbolPainter(name = MotionSymbols.ic_content_copy),
                            contentDescription = "Copy",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                        .padding(12.dp)
                        .horizontalScroll(rememberScrollState())
                ) {
                    Text(
                        text = report.stackTrace,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private fun buildCrashReportText(report: CrashRecord): String = buildString {
    appendLine("Scholix Crash Report")
    appendLine("Date: ${Date(report.timestamp)}")
    appendLine("Device: ${report.deviceModel} (Android ${report.androidVersion})")
    appendLine("Thread: ${report.threadName}")
    appendLine("Exception: ${report.exceptionClass}")
    if (report.message.isNotBlank()) {
        appendLine("Message: ${report.message}")
    }
    appendLine()
    appendLine("Stack Trace:")
    appendLine(report.stackTrace)
}
