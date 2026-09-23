package com.feldman.scholix.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import org.json.JSONTokener

class MessageEditorController {
    internal var view: WebView? = null
    fun flush(onContent: (String) -> Unit) {
        val editor = view ?: return
        editor.evaluateJavascript("window.Scholix && Scholix.content()") { result ->
            (runCatching { JSONTokener(result).nextValue() }.getOrNull() as? String)?.let(onContent)
        }
    }
    fun insertImage(url: String) { view?.evaluateJavascript("Scholix.image(${JSONObject.quote(url)})", null) }
    fun insertHtml(html: String) { view?.evaluateJavascript("Scholix.insert(${JSONObject.quote(MessageHtml.sanitize(html))})", null) }
}

private const val EDITOR_URL = "https://appassets.androidplatform.net/assets/editor/index.html"

private fun WebView.safeSettings() {
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    settings.setSupportMultipleWindows(false)
    isHorizontalScrollBarEnabled = false
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun RichMessageEditor(
    initialHtml: String,
    controller: MessageEditorController,
    onChange: (String) -> Unit,
    onPickImage: () -> Unit,
    modifier: Modifier = Modifier
) {
    val changed by rememberUpdatedState(onChange)
    val pickImage by rememberUpdatedState(onPickImage)
    val initial by rememberUpdatedState(initialHtml)
    val scheme = MaterialTheme.colorScheme
    val colors = JSONObject().put("background", "#%06X".format(0xFFFFFF and scheme.surfaceContainerLow.toArgb()))
        .put("foreground", "#%06X".format(0xFFFFFF and scheme.onSurface.toArgb()))
        .put("primary", "#%06X".format(0xFFFFFF and scheme.primary.toArgb()))
        .put("container", "#%06X".format(0xFFFFFF and scheme.surfaceContainerHigh.toArgb()))
        .put("outline", "#%06X".format(0xFFFFFF and scheme.outlineVariant.toArgb()))
        .put("muted", "#%06X".format(0xFFFFFF and scheme.onSurfaceVariant.toArgb()))
        .put("selected", "#%06X".format(0xFFFFFF and scheme.primaryContainer.toArgb()))
        .put("onSelected", "#%06X".format(0xFFFFFF and scheme.onPrimaryContainer.toArgb()))
    val currentColors by rememberUpdatedState(colors.toString())
    AndroidView(
        modifier = modifier.clip(MaterialTheme.shapes.large),
        factory = { context ->
            WebView(context).apply {
                safeSettings()
                setBackgroundColor(scheme.surfaceContainerLow.toArgb())
                settings.javaScriptEnabled = true
                val assets = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context)).build()
                addJavascriptInterface(object {
                    @JavascriptInterface fun changed(html: String) { post { changed(html) } }
                    @JavascriptInterface fun pickImage() { post { pickImage() } }
                    @JavascriptInterface fun print() { post { printMessage(context, this@apply) } }
                }, "ScholixNative")
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = assets.shouldInterceptRequest(request.url)
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                    override fun onPageFinished(view: WebView, url: String) {
                        if (url != EDITOR_URL) return
                        val config = JSONObject(currentColors).put("html", MessageHtml.sanitize(initial))
                        evaluateJavascript("Scholix.init($config)", null)
                    }
                }
                controller.view = this
                loadUrl(EDITOR_URL)
            }
        },
        update = { it.evaluateJavascript("window.Scholix && Scholix.theme($colors)", null) },
        onRelease = {
            controller.view = null
            it.removeJavascriptInterface("ScholixNative")
            it.destroy()
        }
    )
}

@Composable
fun RichMessageBody(html: String, modifier: Modifier = Modifier, onView: (WebView) -> Unit = {}) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val background = "#%06X".format(0xFFFFFF and scheme.surface.toArgb())
    val foreground = "#%06X".format(0xFFFFFF and scheme.onSurface.toArgb())
    val content = remember(html, background, foreground) {
        """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src https: data:; style-src 'unsafe-inline';"><style>body{margin:12px;font:16px sans-serif;background:$background;color:$foreground;overflow-wrap:anywhere}img{max-width:100%;height:auto}table{border-collapse:collapse;max-width:100%}td,th{border:1px solid #888;padding:5px}a{color:inherit;text-decoration:underline}blockquote{border-inline-start:3px solid #888;margin-inline:6px;padding-inline:10px}math{font-size:1.1em}pre{white-space:pre-wrap}</style></head><body dir="auto">${MessageHtml.sanitize(html)}</body></html>"""
    }
    AndroidView(modifier = modifier, factory = {
        WebView(context).apply {
            safeSettings()
            settings.javaScriptEnabled = false
            setBackgroundColor(scheme.surface.toArgb())
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (request.url.scheme in listOf("https", "http", "mailto", "tel")) {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                    }
                    return true
                }
            }
        }
    }, update = { view ->
        onView(view)
        if (view.tag != content) { view.tag = content; view.loadDataWithBaseURL("https://webtop.smartschool.co.il/", content, "text/html", "UTF-8", null) }
    }, onRelease = { it.destroy() })
}

fun printMessage(context: Context, view: WebView) {
    (context.getSystemService(Context.PRINT_SERVICE) as PrintManager).print(
        "Webtop message", view.createPrintDocumentAdapter("Webtop message"), PrintAttributes.Builder().build()
    )
}
