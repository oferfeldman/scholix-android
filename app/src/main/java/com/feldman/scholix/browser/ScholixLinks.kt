package com.feldman.scholix.browser

import java.net.URI
import java.util.Locale

/** Where a link opens. Web pages stay inside Scholix; Google's own apps keep their documents. */
sealed interface LinkRoute {
    /** Scholix's browser, which shares the app's university sign-ins. */
    data class InApp(val url: String) : LinkRoute
    /** Classroom/Drive links open in Google's app when it is installed, otherwise in [InApp]. */
    data class GoogleApp(val url: String, val packageName: String) : LinkRoute
    /** Email and phone links go to the apps that handle them; neither is a browser. */
    data class Contact(val uri: String) : LinkRoute
    data class Refused(val reason: String) : LinkRoute
}

object ScholixLinks {
    private val googleApps = mapOf(
        "classroom.google.com" to "com.google.android.apps.classroom",
        "drive.google.com" to "com.google.android.apps.docs",
        "docs.google.com" to "com.google.android.apps.docs",
    )

    fun route(raw: String): LinkRoute {
        val uri = runCatching { URI(raw.trim()) }.getOrNull()
            ?: return LinkRoute.Refused("This link is not a valid web address.")
        return when (uri.scheme?.lowercase(Locale.ROOT)) {
            "https", "http" -> {
                val host = uri.host?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() }
                    ?: return LinkRoute.Refused("This link is not a valid web address.")
                if (uri.rawUserInfo != null) return LinkRoute.Refused("This link hides its real address, so Scholix won't open it.")
                // Android blocks unencrypted pages; the same address over HTTPS is the only way to load it.
                val secure = "https://${uri.rawAuthority}${uri.rawPath.orEmpty()}" +
                    (uri.rawQuery?.let { "?$it" } ?: "") + (uri.rawFragment?.let { "#$it" } ?: "")
                googleApps[host]?.let { LinkRoute.GoogleApp(secure, it) } ?: LinkRoute.InApp(secure)
            }
            "mailto", "tel" -> LinkRoute.Contact(raw.trim())
            else -> LinkRoute.Refused("Scholix can't open this kind of link.")
        }
    }

    /** Pages the browser may load: HTTPS only, so a page cannot step it onto another scheme. */
    fun loadable(raw: String): Boolean = route(raw).let { it is LinkRoute.InApp || it is LinkRoute.GoogleApp } &&
        raw.trim().startsWith("https://", ignoreCase = true)
}
