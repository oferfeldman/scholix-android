package com.feldman.scholix.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.viewinterop.AndroidView
import com.feldman.motion.MotionTheme
import com.feldman.scholix.ui.components.SettingsTopBar

/**
 * Scholix's own browser. It uses the app's WebView cookie store, so pages on Lemida and the other
 * providers open already signed in. It never hands a page to another browser.
 */
class ScholixBrowserActivity : ComponentActivity() {
    private var web: WebView? = null
    private var pendingFiles: ValueCallback<Array<Uri>>? = null
    private val fileChooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        pendingFiles?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
        pendingFiles = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val start = intent.getStringExtra(EXTRA_URL)?.takeIf(ScholixLinks::loadable) ?: run { finish(); return }
        val fallbackTitle = Uri.parse(start).host.orEmpty()
        enableEdgeToEdge()
        setContent {
            MotionTheme {
                var title by remember { mutableStateOf(intent.getStringExtra(EXTRA_TITLE) ?: fallbackTitle) }
                var progress by remember { mutableStateOf(0) }
                var canGoBack by remember { mutableStateOf(false) }
                var blocked by remember { mutableStateOf<String?>(null) }
                BackHandler(canGoBack) { web?.goBack() }
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize()) {
                        SettingsTopBar(title, onBack = { finish() }, actions = {
                            IconButton(onClick = { web?.reload() }) { Icon(Icons.Default.Refresh, "Reload") }
                        })
                        if (progress in 1..99) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                        AndroidView(modifier = Modifier.weight(1f).fillMaxWidth().navigationBarsPadding(), factory = { context ->
                            WebView(context).apply {
                                web = this
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.allowFileAccess = false
                                settings.allowContentAccess = false
                                CookieManager.getInstance().setAcceptCookie(true)
                                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                        if (!request.isForMainFrame || request.url.scheme.equals("https", ignoreCase = true)) return false
                                        return when (val route = ScholixLinks.route(request.url.toString())) {
                                            is LinkRoute.InApp -> { view.loadUrl(route.url); true }
                                            is LinkRoute.GoogleApp -> { view.loadUrl(route.url); true }
                                            is LinkRoute.Contact -> { openContact(this@ScholixBrowserActivity, route.uri) { blocked = it }; true }
                                            is LinkRoute.Refused -> { blocked = route.reason; true }
                                        }
                                    }
                                    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                                        canGoBack = view.canGoBack()
                                    }
                                }
                                webChromeClient = object : WebChromeClient() {
                                    override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress }
                                    override fun onReceivedTitle(view: WebView, value: String?) {
                                        value?.takeIf { it.isNotBlank() }?.let { title = it }
                                    }
                                    override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>,
                                        params: FileChooserParams): Boolean {
                                        pendingFiles?.onReceiveValue(null)
                                        pendingFiles = callback
                                        return try { fileChooser.launch(params.createIntent()); true }
                                        catch (_: ActivityNotFoundException) {
                                            pendingFiles = null
                                            blocked = "No app on this device can choose a file."
                                            false
                                        }
                                    }
                                }
                                setDownloadListener { url, userAgent, disposition, mime, _ ->
                                    download(url, userAgent, disposition, mime)?.let { blocked = it }
                                }
                                if (savedInstanceState == null || restoreState(savedInstanceState) == null) loadUrl(start)
                            }
                        })
                    }
                }
                blocked?.let { message ->
                    AlertDialog(onDismissRequest = { blocked = null }, title = { Text("Could not open this") },
                        text = { Text(message) }, confirmButton = { TextButton(onClick = { blocked = null }) { Text("OK") } })
                }
            }
        }
    }

    /** Files from a signed-in page download with the same cookies, into the device's Downloads. */
    private fun download(url: String, userAgent: String?, disposition: String?, mime: String?): String? {
        if (!url.startsWith("https://", ignoreCase = true)) return "Only secure (HTTPS) files can be downloaded."
        val name = URLUtil.guessFileName(url, disposition, mime)
        return runCatching {
            val request = DownloadManager.Request(Uri.parse(url))
                .addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url).orEmpty())
                .apply { userAgent?.let { addRequestHeader("User-Agent", it) } }
                .setMimeType(mime)
                .setTitle(name)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            getSystemService(DownloadManager::class.java).enqueue(request)
            Toast.makeText(this, "Downloading $name", Toast.LENGTH_SHORT).show()
        }.exceptionOrNull()?.let { "This file could not be downloaded." }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web?.saveState(outState)
    }

    override fun onPause() {
        web?.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        web?.onResume()
    }

    override fun onDestroy() {
        pendingFiles?.onReceiveValue(null)
        pendingFiles = null
        web?.destroy()
        web = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_URL = "com.feldman.scholix.browser.URL"
        private const val EXTRA_TITLE = "com.feldman.scholix.browser.TITLE"

        fun intent(context: Context, url: String, title: String? = null): Intent {
            require(ScholixLinks.loadable(url)) { "Only secure web pages open in Scholix." }
            return Intent(context, ScholixBrowserActivity::class.java).putExtra(EXTRA_URL, url).apply {
                title?.let { putExtra(EXTRA_TITLE, it) }
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }
}

internal fun openContact(context: Context, uri: String, onFailure: (String) -> Unit) {
    try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri))) }
    catch (_: ActivityNotFoundException) { onFailure("No app on this device can open this email or phone link.") }
}

/** Opens links the way [ScholixLinks] routes them: never in an outside browser. */
class ScholixWebLinks(private val context: Context, private val onFailure: (String) -> Unit) : UriHandler {
    override fun openUri(uri: String) {
        when (val route = ScholixLinks.route(uri)) {
            is LinkRoute.InApp -> context.startActivity(ScholixBrowserActivity.intent(context, route.url))
            is LinkRoute.GoogleApp -> try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(route.url)).setPackage(route.packageName)
                    .apply { if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            } catch (_: ActivityNotFoundException) {
                context.startActivity(ScholixBrowserActivity.intent(context, route.url))
            }
            is LinkRoute.Contact -> openContact(context, route.uri, onFailure)
            is LinkRoute.Refused -> onFailure(route.reason)
        }
    }
}

/** Every `LocalUriHandler.openUri` inside [content] opens through [ScholixWebLinks]. */
@Composable
fun ScholixWebLinkScope(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    val links = remember(context) { ScholixWebLinks(context) { error = it } }
    CompositionLocalProvider(LocalUriHandler provides links, content = content)
    error?.let { message ->
        AlertDialog(onDismissRequest = { error = null }, title = { Text("Could not open link") },
            text = { Text(message) }, confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } })
    }
}
