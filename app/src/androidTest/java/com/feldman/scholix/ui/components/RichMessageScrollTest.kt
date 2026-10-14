package com.feldman.scholix.ui.components

import android.webkit.WebView
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class RichMessageScrollTest {
    @get:Rule val compose = createComposeRule()

    @Test fun inlineEmojiScalesWithItsSurroundingFont() {
        val reader = AtomicReference<WebView>()
        val dimensions = AtomicReference<JSONObject>()
        val image = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jVqkAAAAASUVORK5CYII="
        compose.setContent {
            MaterialTheme {
                RichMessageBody("<span style='font-size:20px'>Text<img class='an1' src='$image' width='72' height='72'></span>", Modifier.fillMaxWidth(), onView = {
                    it.settings.javaScriptEnabled = true // Only to inspect this synthetic test document.
                    reader.set(it)
                })
            }
        }
        compose.waitUntil(10_000) { reader.get()?.height?.let { it > 1 } == true }
        compose.runOnIdle {
            reader.get().evaluateJavascript("(function(){var i=document.querySelector('img'),r=i.getBoundingClientRect();return {width:r.width,height:r.height,font:parseFloat(getComputedStyle(i).fontSize)}})()") {
                dimensions.set(JSONObject(it))
            }
        }
        compose.waitUntil(10_000) { dimensions.get() != null }
        val result = dimensions.get()
        assertEquals(result.getDouble("font") * 1.2, result.getDouble("width"), 0.5)
        assertEquals(result.getDouble("font") * 1.2, result.getDouble("height"), 0.5)
    }

    @Test fun draggingMessageScrollsTheWholePage() {
        val reader = AtomicReference<WebView>()
        lateinit var scroll: ScrollState
        compose.setContent {
            scroll = rememberScrollState()
            MaterialTheme {
                Column(Modifier.fillMaxSize().verticalScroll(scroll).testTag("page")) {
                    Text("Message header")
                    RichMessageBody("<p>Long message paragraph</p>".repeat(100), Modifier.fillMaxWidth(), onView = reader::set)
                    Text("Attachments")
                }
            }
        }
        compose.waitUntil(10_000) { reader.get()?.height?.let { it > 3000 } == true }
        compose.onNodeWithTag("page").performTouchInput { swipeUp() }
        compose.runOnIdle {
            assertTrue("Dragging the body should scroll the page", scroll.value > 0)
            assertEquals("The body should not scroll independently", 0, reader.get().scrollY)
            assertFalse(reader.get().canScrollVertically(1))
        }
    }
}
