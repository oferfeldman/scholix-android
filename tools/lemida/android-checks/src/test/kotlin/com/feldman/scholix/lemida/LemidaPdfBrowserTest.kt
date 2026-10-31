package com.feldman.scholix.lemida

import android.webkit.WebView
import android.webkit.ValueCallback
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/** Controlled callbacks exercise the actual native chunk writer, not WebView rendering/networking. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
@OptIn(ExperimentalCoroutinesApi::class)
class LemidaPdfBrowserTest {
    private val bytes = "%PDF-1.7\n".toByteArray() + ByteArray(70_000) { (it % 256).toByte() }
    private var response = JSONObject().put("size", bytes.size).toString()
    private var shortChunk = false
    private var cleaned = false
    private inner class ControlledView : WebView(RuntimeEnvironment.getApplication()) {
        init { webViewClient = android.webkit.WebViewClient() }
        override fun evaluateJavascript(script: String, callback: ValueCallback<String>?) {
            val result = when {
                "delete window[" in script -> { cleaned = true; "null" }
                "const state = {controller:" in script -> "true"
                "JSON.stringify" in script -> JSONObject.quote(response)
                "state.bytes.subarray" in script -> {
                    val offset = Regex("subarray\\((\\d+),").find(script)!!.groupValues[1].toInt()
                    val chunk = if (shortChunk) byteArrayOf(1) else bytes.copyOfRange(offset, minOf(offset + 32768, bytes.size))
                    JSONObject.quote(Base64.encodeToString(chunk, Base64.NO_WRAP))
                }
                else -> error("Unexpected script")
            }
            callback?.onReceiveValue(result)
        }
    }
    @Before fun mainDispatcher() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @After fun resetDispatcher() { Dispatchers.resetMain() }
    private fun download(assertion: suspend (LemidaBrowser, File) -> Unit) = runBlocking {
        val view = ControlledView()
        val browser = LemidaBrowser(RuntimeEnvironment.getApplication(), null, view)
        val file = File.createTempFile("lemida-pdf-native", ".pdf")
        try { assertion(browser, file) } finally { file.delete(); browser.close(); view.destroy() }
    }
    @Test fun actualNativeWriterReassemblesMultipleBinaryChunks() = download { browser, file ->
        browser.downloadPdf("${LemidaParser.BASE}/pluginfile.php/1/file.pdf", file)
        assertArrayEquals(bytes, file.readBytes()); assertTrue(cleaned)
    }
    @Test fun partialChunkFailsInsteadOfOpeningTruncatedPdf() = download { browser, file ->
        shortChunk = true
        try { browser.downloadPdf("${LemidaParser.BASE}/pluginfile.php/1/file.pdf", file); fail("Must reject truncation") }
        catch (_: IOException) { }
        assertTrue(cleaned)
    }
    @Test fun verificationResponseRequiresLoginAndCleansRequest() = download { browser, file ->
        response = "{\"verification\":true}"
        try { browser.downloadPdf("${LemidaParser.BASE}/pluginfile.php/1/file.pdf", file); fail("Must require sign-in") }
        catch (_: LemidaSessionExpired) { }
        assertTrue(cleaned); assertEquals(0L, file.length())
    }
}
