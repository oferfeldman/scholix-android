package com.feldman.scholix.api.platforms

import android.net.Uri
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.util.concurrent.TimeUnit

internal class ShahafException(val authFailure: Boolean, message: String) : IOException(message)

/** Contract recovered from the ARM64 Dart snapshot of Shahaf 1.2.2850. */
internal class ShahafApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun get(path: String, parameters: Map<String, String> = emptyMap()): Any {
        val url = (BASE + path).toHttpUrl().newBuilder().apply {
            parameters.forEach { (name, value) -> addQueryParameter(name, value) }
        }.build()
        android.util.Log.d("ShahafApi", "GET $path (${parameters.keys.filterNot { it == "token" }.joinToString()})")
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            android.util.Log.d("ShahafApi", "-> HTTP ${response.code} for $path")
            val value = try { JSONTokener(response.body.string()).nextValue() } catch (_: Exception) {
                throw IOException("Shahaf returned an invalid response (HTTP ${response.code})")
            }
            if (value is JSONObject && value.optString("Status") == "failed") {
                val error = value.optString("Error")
                throw ShahafException(error == "NO_TOKEN" || error == "INVALID_TOKEN", when (error) {
                    "NO_TOKEN", "INVALID_TOKEN" -> "Your Shahaf session expired. Sign in again."
                    "BAD_CREDENTIALS", "NO_USER_OR_PASSWORD" -> "Incorrect Shahaf username or password"
                    else -> "Shahaf rejected the request: $error"
                })
            }
            if (!response.isSuccessful) throw IOException("Shahaf request failed (HTTP ${response.code})")
            return value ?: throw IOException("Shahaf returned an empty response")
        }
    }

    companion object {
        const val BASE = "https://view.shahaf.info/api/"
        const val SIGN_IN_PAGE = "https://timetable.shahaf.info/ShfSignIn.aspx?sid=shfapp&cid="

        fun authResponseFromUrl(url: String): JSONObject? {
            val uri = Uri.parse(url)
            if (uri.scheme != "https" || uri.host != "timetable.shahaf.info" ||
                !uri.path.orEmpty().endsWith("/SignInSuccess.aspx", ignoreCase = true)) return null
            val encoded = uri.getQueryParameter("s") ?: return null
            val bytes = Base64.decode(encoded.replace('.', '=').replace('_', '/').replace('-', '+'), Base64.DEFAULT)
            return JSONObject(String(bytes, Charsets.UTF_8))
        }
    }
}
