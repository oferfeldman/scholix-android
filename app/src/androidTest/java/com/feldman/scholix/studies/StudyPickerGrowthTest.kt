package com.feldman.scholix.studies

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.feldman.motion.MotionDialog
import com.feldman.motion.MotionScaffold
import com.feldman.motion.MotionTheme
import org.junit.Rule
import org.junit.Test

/**
 * The Documents picker opens with "This device" and adds remote devices about a second later. The published Motion
 * section does not draw rows added to a section already on screen, so the picker keys the section by its rows; a
 * device that arrives late must then be visible, which it was not on the Pixel.
 */
class StudyPickerGrowthTest {
    @get:Rule val compose = createComposeRule()

    @Test fun remoteDeviceArrivingAfterThisDeviceIsDisplayed() {
        val remote = mutableStateOf(emptyList<String>())
        compose.setContent {
            MotionTheme {
                MotionDialog(onDismissRequest = {}, title = "Open in Documents", dismissText = "Cancel", onDismiss = {}) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        MotionScaffold(modifier = Modifier.heightIn(max = 400.dp), contentPadding = PaddingValues(0.dp), fitContentHeight = true) {
                            key(1, remote.value) { Section {
                                PageItem(title = "This device", icon = rememberVectorPainter(Icons.Outlined.Smartphone), onClick = {})
                                remote.value.forEach { name ->
                                    PageItem(title = name, icon = rememberVectorPainter(Icons.Outlined.Devices), onClick = {})
                                }
                            } }
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("This device").assertIsDisplayed()
        compose.runOnIdle { remote.value = listOf("SM-X610") }
        compose.waitForIdle()
        compose.onNodeWithText("SM-X610").assertIsDisplayed()
        compose.onNodeWithText("This device").assertIsDisplayed()
    }
}
