package com.feldman.scholix.api.platforms

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/** MOE credentials -> WS-Fed assertion -> Webtop session, without a WebView or JS engine. */
internal class WebtopMoeLogin(client: OkHttpClient = OkHttpClient()) {
    data class Session(val data: JsonObject, val cookieHeader: String)
    private data class Page(val url: HttpUrl, val body: String, val key: String?)

    private val cookies = mutableListOf<Cookie>()
    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookies.forEach { cookie ->
                this@WebtopMoeLogin.cookies.removeAll {
                    it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path
                }
                this@WebtopMoeLogin.cookies.add(cookie)
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            cookies.removeAll { it.expiresAt <= System.currentTimeMillis() }
            return cookies.filter { it.matches(url) }
        }
    }
    private val client = client.newBuilder()
        .cookieJar(cookieJar)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .followSslRedirects(false)
        .build()

    fun login(username: String, password: String): Session {
        if (username.isBlank() || password.isBlank()) {
            throw IOException("Enter your Ministry of Education username and password")
        }
        val loginPage = followHandoff(execute(Request.Builder().url(ENTRY_URL)))
        val server = literal(loginPage.body, "_serverUrl")
            ?: throw IOException("MOE did not return its credential page")
        var serverUrl = loginPage.url.resolve(server)
            ?: throw IOException("MOE returned an invalid login address")
        if (!serverUrl.isHttps || serverUrl.host != "lgn.edu.gov.il") {
            throw IOException("MOE returned an unexpected login address")
        }
        val csrt = Regex("csrt=([0-9]+)").find(loginPage.body)?.groupValues?.get(1)
        if (csrt != null && serverUrl.queryParameter("csrt") == null) {
            serverUrl = serverUrl.newBuilder().addQueryParameter("csrt", csrt).build()
        }
        val loginType = literal(loginPage.body, "_loginType").orEmpty()
        if (loginType.contains("SMSOTP")) {
            throw IOException("MOE requires SMS verification; complete sign-in on the MOE website")
        }

        // The MOE UI starts the sequence before submitting credentials, even if this returns an error.
        postForm(serverUrl, loginPage.url, mapOf(
            "option" to "credential", "initiateLoginSequence" to "true", "isAjax" to "true"
        ))
        val result = json(postForm(serverUrl, loginPage.url, mapOf(
            "option" to "credential", "isAjax" to "true", "HIN_USERID" to username,
            "Ecom_Password" to password,
            // Matches the MOE form's empty value. A required CAPTCHA is never solved or bypassed.
            "g-recaptcha-response" to " "
        )))
        if (result["isCaptchaNeeded"] == JsonPrimitive(true)) {
            throw IOException("MOE requires CAPTCHA verification; complete sign-in on the MOE website")
        }
        if (result["isPasswordExpiring"] == JsonPrimitive(true)) {
            throw IOException("Update your password on the MOE website before signing in")
        }
        if (result["isError"] != JsonPrimitive(false)) {
            throw IOException("MOE did not accept the username and password")
        }

        val callback = followHandoff(postForm(serverUrl, loginPage.url, mapOf(
            "option" to "credential", "target" to literal(loginPage.body, "_target").orEmpty()
        )))
        val key = callback.key ?: throw IOException("MOE did not complete the Webtop sign-in")
        val body = buildJsonObject {
            put("rememberMe", "false")
            put("key", key)
            put("UniqueId", UUID.randomUUID().toString())
            put("deviceDataJson", "{\"isMobile\":false,\"isTablet\":false,\"isDesktop\":true}")
        }
        val loginUrl = "https://webtopserver.smartschool.co.il/server/api/user/LoginMoe".toHttpUrl()
        val response = json(execute(Request.Builder().url(loginUrl)
            .header("Origin", "https://webtop.smartschool.co.il")
            .header("Referer", "https://webtop.smartschool.co.il/")
            .header("language", "he")
            .header("rememberMe", "0")
            .header("X-XSRF-TOKEN", "")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))))
        val data = response["data"] as? JsonObject
        if (response["status"] != JsonPrimitive(true) || data == null) {
            throw IOException("Webtop rejected the MOE sign-in")
        }
        val sessionCookies = cookieJar.loadForRequest(loginUrl)
        if (sessionCookies.none { it.name == "webToken" && it.value.isNotEmpty() }) {
            throw IOException("Webtop did not issue a session cookie")
        }
        return Session(data, sessionCookies.joinToString("; ") { "${it.name}=${it.value}" })
    }

    private fun followHandoff(initial: Page): Page {
        var page = initial
        repeat(6) {
            if (page.key != null || literal(page.body, "_serverUrl") != null) return page
            val redirect = literal(page.body, "top.location.href")
            if (redirect != null) {
                val url = page.url.resolve(redirect) ?: throw IOException("Invalid MOE redirect")
                page = execute(Request.Builder().url(url).header("Referer", page.url.toString()))
            } else {
                val form = Jsoup.parse(page.body).selectFirst("form") ?: return page
                if (!page.body.contains(".submit(")) return page
                val action = page.url.resolve(form.attr("action"))
                    ?: throw IOException("Invalid MOE handoff address")
                // Jsoup decodes HTML attributes once, preserving entities inside the signed XML.
                val fields = form.select("input[name]").associate { it.attr("name") to it.attr("value") }
                page = postForm(action, page.url, fields)
            }
        }
        throw IOException("MOE sign-in exceeded its redirect limit")
    }

    private fun postForm(url: HttpUrl, referer: HttpUrl, fields: Map<String, String>): Page {
        val body = FormBody.Builder().apply { fields.forEach { (name, value) -> add(name, value) } }.build()
        return execute(Request.Builder().url(url)
            .header("Referer", referer.toString())
            .header("Origin", "${referer.scheme}://${referer.host}")
            .post(body))
    }

    private fun execute(builder: Request.Builder): Page {
        val request = builder.header("User-Agent", USER_AGENT).build()
        if (!request.url.isHttps || request.url.host !in ALLOWED_HOSTS) {
            throw IOException("Unexpected address in the MOE sign-in flow")
        }
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Sign-in failed (HTTP ${response.code})")
            val key = generateSequence(response) { it.priorResponse }
                .mapNotNull { hop ->
                    val url = hop.request.url
                    if (url.isHttps && url.host == "webtop.smartschool.co.il" &&
                        url.encodedPath.equals("/account/loginMoe", ignoreCase = true)) {
                        // HttpUrl decodes '+' as a space, matching Webtop's Angular query parser.
                        url.queryParameter("key")?.takeIf { it.isNotBlank() }
                    } else null
                }.firstOrNull()
            Page(response.request.url, response.body.string(), key)
        }
    }

    private fun json(page: Page): JsonObject = try {
        Json.parseToJsonElement(page.body) as? JsonObject
            ?: throw IOException("The login service returned an unexpected response")
    } catch (_: IllegalArgumentException) {
        throw IOException("The login service returned an unexpected response")
    }

    private fun literal(html: String, name: String): String? =
        Regex("${Regex.escape(name)}\\s*=\\s*(['\"])(.*?)\\1").find(html)?.groupValues?.get(2)

    companion object {
        const val ENTRY_URL = "https://www.webtop.co.il/applications/loginMOENew/default.aspx"
        private val ALLOWED_HOSTS = setOf(
            "www.webtop.co.il", "lgn.edu.gov.il", "webtop.smartschool.co.il", "webtopserver.smartschool.co.il"
        )
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36"
    }
}
