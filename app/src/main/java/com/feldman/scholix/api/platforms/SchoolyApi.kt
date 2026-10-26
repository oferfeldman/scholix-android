package com.feldman.scholix.api.platforms

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

internal class SchoolySessionExpired : IOException("Schooly session expired. Sign in again to renew it.")

internal class SchoolyApi(
    private val base: HttpUrl = "https://api.schooly.co.il/".toHttpUrl(),
    transport: OkHttpClient = OkHttpClient(),
) : CookieJar {
    private val cookies = mutableListOf<Cookie>()
    var token = ""
    private val client = transport.newBuilder().cookieJar(this).callTimeout(45, TimeUnit.SECONDS).build()

    @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookies.forEach { cookie ->
            this.cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
            if (cookie.expiresAt > System.currentTimeMillis()) this.cookies += cookie
        }
    }

    @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> {
        cookies.removeAll { it.expiresAt <= System.currentTimeMillis() }
        return cookies.filter { it.matches(url) }
    }

    @Synchronized fun session(): JSONObject = JSONObject().put("token", token)
        .put("cookies", JSONArray(cookies.map { it.toString() }))

    @Synchronized fun restore(session: JSONObject) {
        token = session.optString("token")
        cookies.clear()
        session.optJSONArray("cookies")?.let { array ->
            for (index in 0 until array.length()) Cookie.parse(base, array.getString(index))?.let {
                if (it.domain == base.host || base.host.endsWith(".${it.domain}")) cookies += it
            }
        }
    }

    fun request(path: String, params: Map<String, String> = emptyMap(), body: JSONObject? = null): JSONObject {
        val url = base.newBuilder().addPathSegments(path.trimStart('/')).apply {
            params.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        val request = Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("Origin", "https://app.schooly.co.il")
            .header("X-Client-Platform", "android")
            .apply {
                if (token.isNotBlank()) header("Authorization", "Bearer $token")
                if (body != null) post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            }.build()
        client.newCall(request).execute().use { response ->
            if (response.code == 401 && !path.startsWith("api/auth/")) throw SchoolySessionExpired()
            val json = runCatching { JSONObject(response.body.string()) }.getOrElse {
                throw IOException("Schooly returned an unreadable response (HTTP ${response.code}).")
            }
            if (!response.isSuccessful || !json.optBoolean("success", true)) {
                throw IOException(json.optString("message").ifBlank { json.optString("error").ifBlank { "Schooly request failed (HTTP ${response.code})." } })
            }
            return json
        }
    }
}

internal fun JSONObject.schoolyData(): JSONObject = optJSONObject("data") ?: this
internal fun JSONArray?.schoolyObjects(): List<JSONObject> = if (this == null) emptyList()
    else (0 until length()).mapNotNull { optJSONObject(it) }
