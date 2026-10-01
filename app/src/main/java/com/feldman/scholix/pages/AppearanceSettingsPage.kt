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
import com.feldman.motion.isMotionDarkTheme
import com.feldman.motion.rememberSymbolPainter
import com.feldman.motion.MotionThemeDefaults
import com.feldman.motion.MotionDynamicColorSwatch
import com.feldman.motion.MotionSectionDefaults
import com.materialkolor.PaletteStyle
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
    val paletteStyle by themeRepository.paletteStyle.collectAsState(initial = PaletteStyle.Vibrant)
    val motionLevel by themeRepository.motionLevel.collectAsState(initial = MotionLevel.MEDIUM)
    val orientationMode by context.orientationModeFlow().collectAsState(initial = OrientationMode.AUTO)
    val expressiveDesign by themeRepository.expressiveDesign.collectAsState(initial = true)
    val useDark = isMotionDarkTheme()
    val dynamicColorSeed =
        if (useDark) dynamicDarkColorScheme(context).primary else dynamicLightColorScheme(context).primary

    MotionScaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            SettingsTopBar(
                title = "Appearance",
                onBack = onBack,
                chromeColor = SettingsCategoryColor.APPEARANCE.container(useDark)
            )
        }
    ) {
        Title("Theme")
        Section {
            listOf(
                Triple(0, "System", MotionSymbols.ic_brightness_auto),
                Triple(1, "Light", MotionSymbols.ic_light_mode),
                Triple(2, "Dark", MotionSymbols.ic_dark_mode)
            ).forEach { (id, label, icon) ->
                ChoiceItem(
                    key = id,
                    title = label,
                    icon = rememberSymbolPainter(icon),
                    selected = themeMode == id,
                    // The item's selected content is always onPrimary, so the selected fill has to
                    // be primary for the pair to have any contrast.
                    containerColor = if (themeMode == id) colorScheme.primary else colorScheme.surfaceContainerHigh,
                    onClick = { scope.launch { themeRepository.setThemeMode(id) } }
                )
            }
        }

        Title("Theme color")
        Section {
            ColorPickerItem(
                colors = MotionThemeDefaults.SeedColors.map { pair -> if (useDark) pair.dark else pair.light },
                selectedIndex = if (dynamicColor) -1 else themeColor,
                onSelected = { index ->
                    scope.launch {
                        themeRepository.setThemeColor(index)
                        themeRepository.setDynamicColor(false)
                    }
                },
                dynamicColor = MotionDynamicColorSwatch(
                    color = dynamicColorSeed,
                    selected = dynamicColor,
                    onSelected = { scope.launch { themeRepository.setDynamicColor(true) } },
                    icon = rememberSymbolPainter(MotionSymbols.ic_hdr_auto)
                )
            )
            SwitchItem(
                title = "Vibrant palette",
                description = "Use higher saturation tones",
                icon = rememberSymbolPainter(MotionSymbols.ic_contrast),
                checked = paletteStyle == PaletteStyle.Vibrant,
                onCheckedChange = { checked ->
                    scope.launch { themeRepository.setPaletteStyle(if (checked) PaletteStyle.Vibrant else PaletteStyle.TonalSpot) }
                },
                visible = !dynamicColor
            )
        }

        Title("Design")
        Section {
            SwitchItem(
                title = "Expressive design",
                description = "Use expressive, bold UI",
                icon = rememberSymbolPainter(MotionSymbols.ic_animation),
                iconStyle = MotionSectionDefaults.iconStyle(
                    shape = if(expressiveDesign) MaterialShapes.Cookie12Sided else MaterialShapes.Circle,
                    morphShape = if(expressiveDesign) MaterialShapes.Circle else MaterialShapes.Cookie12Sided
                ),
                checked = expressiveDesign,
                onCheckedChange = { checked ->
                    scope.launch { themeRepository.setExpressiveDesign(checked) }
                }
            )
        }

        Title("Motion")
        Section {
            listOf(
                Triple(MotionLevel.NONE, "None", MotionSymbols.ic_stop_circle),
                Triple(MotionLevel.LOW, "Low", MotionSymbols.ic_trail_length_short),
                Triple(MotionLevel.MEDIUM, "Medium", MotionSymbols.ic_trail_length_medium),
                Triple(MotionLevel.HIGH, "High", MotionSymbols.ic_trail_length)
            ).forEach { (level, label, icon) ->
                ChoiceItem(
                    key = level.id,
                    title = label,
                    icon = rememberSymbolPainter(icon),
                    selected = motionLevel == level,
                    containerColor = if (motionLevel == level) colorScheme.primary else colorScheme.surfaceContainerHigh,
                    onClick = { scope.launch { themeRepository.setMotionLevel(level) } }
                )
            }
        }

        Title("Orientation")
        Section {
            SegmentedPickerItem(
                options = listOf("Auto", "Portrait", "Landscape"),
                icons = listOf(
                    rememberSymbolPainter(MotionSymbols.ic_screen_rotation),
                    rememberSymbolPainter(MotionSymbols.ic_screen_lock_portrait),
                    rememberSymbolPainter(MotionSymbols.ic_screen_lock_landscape)
                ),
                selectedIndex = OrientationMode.entries.indexOf(orientationMode),
                onSelected = { index ->
                    scope.launch { context.setOrientationMode(OrientationMode.entries[index]) }
                }
            )
        }

        Item {
            Spacer(Modifier.height(120.dp))
        }
    }
}
