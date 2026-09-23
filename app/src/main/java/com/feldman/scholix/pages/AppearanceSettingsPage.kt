package com.feldman.scholix.pages

import android.os.Build
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.feldman.motion.MotionLevel
import com.feldman.motion.MotionScaffold
import com.feldman.motion.MotionSymbols
import com.feldman.motion.MotionThemeRepository
import com.feldman.motion.isDarkTheme
import com.feldman.motion.symbolPainter
import com.feldman.motion.themeColors
import com.feldman.scholix.storage.OrientationMode
import com.feldman.scholix.storage.orientationModeFlow
import com.feldman.scholix.storage.setOrientationMode
import com.feldman.scholix.ui.components.SettingsCategoryColor
import com.feldman.scholix.ui.components.SettingsTopBar
import kotlinx.coroutines.launch

/**
 * How the app looks and moves: theme, color and motion level. All of them are Motion's own
 * settings, written straight to [MotionThemeRepository], so they take effect on the next frame
 * with no save step.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppearanceSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val themeRepository = remember(context) { MotionThemeRepository(context) }
    val scope = rememberCoroutineScope()

    val themeMode by themeRepository.themeMode.collectAsState(initial = 0)
    val themeColor by themeRepository.themeColor.collectAsState(initial = 0)
    val dynamicColor by themeRepository.dynamicColor.collectAsState(initial = true)
    val tintPalette by themeRepository.tintPalette.collectAsState(initial = false)
    val motionLevel by themeRepository.motionLevel.collectAsState(initial = MotionLevel.MEDIUM)
    val orientationMode by context.orientationModeFlow().collectAsState(initial = OrientationMode.AUTO)
    val expressiveDesign by themeRepository.expressiveDesign.collectAsState(initial = true)
    val useDark = isDarkTheme()
    val dynamicColorSeed =
        if (useDark) dynamicDarkColorScheme(context).primary else dynamicLightColorScheme(context).primary

    MotionScaffold(
        scaffoldModifier = Modifier.fillMaxSize(),
        expressiveDesign = expressiveDesign,
        topBar = {
            SettingsTopBar(
                title = "Appearance",
                onBack = onBack,
                chromeColor = SettingsCategoryColor.APPEARANCE.container(useDark)
            )
        }
    ) {
        title("Theme")
        section {
            listOf(
                Triple(0, "System", MotionSymbols.ic_brightness_auto),
                Triple(1, "Light", MotionSymbols.ic_light_mode),
                Triple(2, "Dark", MotionSymbols.ic_dark_mode)
            ).forEach { (id, label, icon) ->
                choiceItem(
                    key = id,
                    title = label,
                    icon = symbolPainter(icon),
                    selected = themeMode == id,
                    // The item's selected content is always onPrimary, so the selected fill has to
                    // be primary for the pair to have any contrast.
                    containerColor = if (themeMode == id) colorScheme.primary else colorScheme.surfaceContainerHigh,
                    onClick = { scope.launch { themeRepository.setThemeMode(id) } }
                )
            }
        }

        title("Theme color")
        section {
            colorPickerItem(
                colors = themeColors.map { pair -> if (useDark) pair.dark else pair.light },
                selectedIndex = if (dynamicColor) -1 else themeColor,
                onSelected = { index ->
                    scope.launch {
                        themeRepository.setThemeColor(index)
                        themeRepository.setDynamicColor(false)
                    }
                },
                dynamicColor = dynamicColorSeed,
                dynamicColorSelected = dynamicColor,
                onDynamicColorSelected = {
                    scope.launch { themeRepository.setDynamicColor(true) }
                },
                dynamicColorIcon = symbolPainter(MotionSymbols.ic_hdr_auto)
            )
            switchItem(
                title = "Vibrant palette",
                description = "Use higher saturation tones",
                icon = symbolPainter(MotionSymbols.ic_contrast),
                checked = tintPalette,
                onCheckedChange = { checked ->
                    scope.launch { themeRepository.setTintPalette(checked) }
                },
                visible = !dynamicColor
            )
        }

        title("Design")
        section {
            switchItem(
                title = "Expressive design",
                description = "Use expressive, bold UI",
                icon = symbolPainter(MotionSymbols.ic_animation),
                iconShape = if(expressiveDesign) MaterialShapes.Cookie12Sided else MaterialShapes.Circle,
                iconMorphShape = if(expressiveDesign) MaterialShapes.Circle else MaterialShapes.Cookie12Sided,
                iconMorphOnSelection = false,
                checked = expressiveDesign,
                onCheckedChange = { checked ->
                    scope.launch { themeRepository.setExpressiveDesign(checked) }
                }
            )
        }

        title("Motion")
        section {
            listOf(
                Triple(MotionLevel.NONE, "None", MotionSymbols.ic_stop_circle),
                Triple(MotionLevel.LOW, "Low", MotionSymbols.ic_trail_length_short),
                Triple(MotionLevel.MEDIUM, "Medium", MotionSymbols.ic_trail_length_medium),
                Triple(MotionLevel.HIGH, "High", MotionSymbols.ic_trail_length)
            ).forEach { (level, label, icon) ->
                choiceItem(
                    key = level.id,
                    title = label,
                    icon = symbolPainter(icon),
                    selected = motionLevel == level,
                    containerColor = if (motionLevel == level) colorScheme.primary else colorScheme.surfaceContainerHigh,
                    onClick = { scope.launch { themeRepository.setMotionLevel(level) } }
                )
            }
        }

        title("Orientation")
        section {
            segmentedPickerItem(
                options = listOf("Auto", "Portrait", "Landscape"),
                icons = listOf(
                    symbolPainter(MotionSymbols.ic_screen_rotation),
                    symbolPainter(MotionSymbols.ic_screen_lock_portrait),
                    symbolPainter(MotionSymbols.ic_screen_lock_landscape)
                ),
                selectedIndex = OrientationMode.entries.indexOf(orientationMode),
                onSelected = { index ->
                    scope.launch { context.setOrientationMode(OrientationMode.entries[index]) }
                }
            )
        }

        item {
            Spacer(Modifier.height(120.dp))
        }
    }
}
