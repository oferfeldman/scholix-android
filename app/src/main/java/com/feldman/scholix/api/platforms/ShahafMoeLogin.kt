package com.feldman.scholix.api.platforms

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Headless Ministry-of-Education sign-in for Shahaf: OkHttp only, no WebView or JS engine.
 *
 * The MOE login page (lgn.edu.gov.il) is an Angular SPA whose SMS protocol was recovered
 * from its own served bundle (main-es2015.js):
 *
 *  1. Entry: the relying-party URL (Shahaf ShfSignIn.aspx) redirects to
 *     `https://lgn.edu.gov.il/nidp/wsfed/ep?...`, which serves an auto-submitting
 *     form to `.../ep?id=<contract>&sid=0&option=credential&sid=0`. The served page
 *     carries `_serverUrl`, `_loginType` and `_target` script literals.
 *  2. SMS contract: the SPA's SMS tab navigates to the same URL with
 *     `id=EduCombinedAuthSms` (`href.replace(/id=\w+/, "id=EduCombinedAuthSms")`);
 *     that page reports `_loginType="usernameSMSOTP"`.
 *  3. Send code: `POST _serverUrl {option=credential, isAjax=true, HIN_ID=<ID>,
 *     HIN_MOBILE=<full 10-digit mobile>, g-recaptcha-response=" "}` answers JSON
 *     with `isError`, `isCaptchaNeeded`, `isPasswordExpiring`, `otpDestMasked`.
 *  4. Verify code: `POST _serverUrl {option=credential, isAjax=true,
 *     Ecom_Token=<code>, g-recaptcha-response=" "}`; `isError=false` means accepted.
 *  5. Complete: `POST _serverUrl {option=credential, target=<_target>}`, then follow
 *     the WS-Fed handoff (`top.location.href` / auto-submitting wa+wresult+wctx form)
 *     back to the relying party. Shahaf lands on
 *     `.../SignInSuccess.aspx?s=<payload>`, decoded the same way as
 *     [ShahafApi.authResponseFromUrl] but without android.net.Uri so this class
 *     stays unit-testable on the JVM.
 *
 * The MOE password flow uses the same shape (`loginWithIdAndPassword`: HIN_USERID +
 * Ecom_Password), so it is headless here too. A required CAPTCHA is never solved or
 * bypassed: it surfaces as an error telling the user to use the MOE website.
 *
 * Every request carries a browser-identical profile (desktop Chrome User-Agent,
 * Accept-Language, navigation Accept on GETs, Angular AJAX headers plus
 * page-URL Referer/Origin on POSTs). MOE's SMS endpoint flags requests missing
 * that profile with `isCaptchaNeeded` even when no CAPTCHA is shown in a real
 * browser, so the profile is load-bearing, not cosmetic.
 */
internal class ShahafMoeLogin(client: OkHttpClient = OkHttpClient()) {
    /**
     * Pending SMS sign-in between [requestSmsCode] and [submitSmsCode].
     * [pageUrl] is the SMS SPA page (the browser's AJAX `Referer`); [serverUrl]
     * is the `_serverUrl` POST target. They differ (`?id=...` vs `?sid=...`),
     * so both are kept: posting with `Referer == serverUrl` never happens in a
     * browser and trips MOE's bot detection (`isCaptchaNeeded`).
     */
    data class SmsChallenge(
        val serverUrl: HttpUrl,
        val pageUrl: HttpUrl,
        val target: String,
        val maskedPhone: String,
    )

    private data class Page(val url: HttpUrl, val body: String)
    private data class CredentialPage(val pageUrl: HttpUrl, val serverUrl: HttpUrl, val target: String)

    private val cookies = mutableListOf<Cookie>()
    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookies.forEach { cookie ->
                this@ShahafMoeLogin.cookies.removeAll {
                    it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path
                }
                this@ShahafMoeLogin.cookies.add(cookie)
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
        .build()

    /** MOE username + password sign-in, returning the Shahaf auth response. */
    fun loginWithPassword(entryUrl: String, username: String, password: String): JSONObject {
        if (username.isBlank() || password.isBlank()) {
            throw IOException("Enter your Ministry of Education username and password")
        }
        val credential = reachCredentialPage(entryUrl)
        // The MOE UI starts the sequence before submitting credentials, even if this returns an error.
        postForm(credential.serverUrl, credential.pageUrl, mapOf(
            "option" to "credential", "initiateLoginSequence" to "true", "isAjax" to "true"
        ))
        val result = json(postForm(credential.serverUrl, credential.pageUrl, mapOf(
            "option" to "credential", "isAjax" to "true", "HIN_USERID" to username,
            "Ecom_Password" to password,
            // Matches the MOE form's empty value. A required CAPTCHA is never solved or bypassed.
            "g-recaptcha-response" to " "
        )))
        checkCredentialResult(result, "MOE did not accept the username and password")
        return completeSignIn(credential)
    }

    /**
     * Sends the SMS code to [phone] and returns the pending challenge for [submitSmsCode].
     * Follows the same entry chain as the password flow, then switches to the
     * `EduCombinedAuthSms` contract exactly like the MOE page's own SMS tab.
     */
    fun requestSmsCode(entryUrl: String, id: String, phone: String): SmsChallenge {
        if (id.isBlank() || phone.length != 10 || !phone.startsWith("05")) {
            throw IOException("Enter your ID number and registered mobile number")
        }
        val credential = reachCredentialPage(entryUrl)
        val sms = openSmsContract(credential)
        // The MOE UI starts the sequence before submitting credentials, even if this returns an error.
        postForm(sms.serverUrl, sms.pageUrl, mapOf(
            "option" to "credential", "initiateLoginSequence" to "true", "isAjax" to "true"
        ))
        val result = json(postForm(sms.serverUrl, sms.pageUrl, mapOf(
            "option" to "credential", "isAjax" to "true", "HIN_ID" to id,
            "HIN_MOBILE" to phone,
            // Matches the MOE form's empty value. A required CAPTCHA is never solved or bypassed.
            "g-recaptcha-response" to " "
        )))
        if (result.optBoolean("isCaptchaNeeded", false)) {
            throw IOException("MOE requires CAPTCHA verification; complete sign-in on the MOE website")
        }
        if (result.optBoolean("isPasswordExpiring", false)) {
            throw IOException("Update your password on the MOE website before signing in")
        }
        if (result.optBoolean("isError", true)) {
            throw IOException("MOE did not accept the ID and mobile number" +
                result.optString("errorCode").takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty())
        }
        return SmsChallenge(sms.serverUrl, sms.pageUrl, sms.target, result.optString("otpDestMasked"))
    }

    /** Submits the SMS [code] for a pending [challenge], returning the Shahaf auth response. */
    fun submitSmsCode(challenge: SmsChallenge, code: String): JSONObject {
        if (code.isBlank()) {
            throw IOException("Enter the SMS code sent to your mobile number")
        }
        val result = json(postForm(challenge.serverUrl, challenge.pageUrl, mapOf(
            "option" to "credential", "isAjax" to "true", "Ecom_Token" to code,
            // Matches the MOE form's empty value. A required CAPTCHA is never solved or bypassed.
            "g-recaptcha-response" to " "
        )))
        if (result.optBoolean("isCaptchaNeeded", false)) {
            throw IOException("MOE requires CAPTCHA verification; complete sign-in on the MOE website")
        }
        if (result.optBoolean("isError", true)) {
            throw IOException(when (result.optString("errorCode")) {
                "TOKEN_TOKEN_EXPIRED", "TOKEN_NOT_FOUND" ->
                    "The SMS code expired. Request a new code and try again."
                "INTRUDER_DETECTED" ->
                    "MOE locked the sign-in after too many attempts. Try again later."
                else -> "MOE did not accept the SMS code" +
                    result.optString("errorCode").takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
            })
        }
        return completeSignIn(CredentialPage(challenge.pageUrl, challenge.serverUrl, challenge.target))
    }

    // -- flow steps -----------------------------------------------------------

    private fun checkCredentialResult(result: JSONObject, rejection: String) {
        if (result.optBoolean("isCaptchaNeeded", false)) {
            throw IOException("MOE requires CAPTCHA verification; complete sign-in on the MOE website")
        }
        if (result.optBoolean("isPasswordExpiring", false)) {
            throw IOException("Update your password on the MOE website before signing in")
        }
        if (result.optBoolean("isError", true)) {
            throw IOException(rejection)
        }
    }

    /** Final `target` POST plus the WS-Fed handoff back to Shahaf. */
    private fun completeSignIn(credential: CredentialPage): JSONObject {
        val callback = followShahafHandoff(postForm(credential.serverUrl, credential.pageUrl, mapOf(
            "option" to "credential", "target" to credential.target
        )))
        return authResponseFromUrl(callback.url)
            ?: throw IOException("MOE did not complete the Shahaf sign-in")
    }

    /**
     * Entry chain up to the MOE credential page (the one carrying `_serverUrl`).
     * The chain is an HTTP redirect to lgn.edu.gov.il followed by F5/WS-Fed
     * auto-submitting interstitials, the same shape [WebtopMoeLogin] follows.
     */
    private fun reachCredentialPage(entryUrl: String): CredentialPage {
        var page = execute(Request.Builder().url(entryUrl))
        repeat(6) {
            parseCredentialPage(page)?.let { return it }
            page = followInterstitial(page)
        }
        throw IOException("MOE sign-in exceeded its redirect limit")
    }

    /** Same page with the `EduCombinedAuthSms` contract, like the SPA's SMS tab. */
    private fun openSmsContract(credential: CredentialPage): CredentialPage {
        val raw = credential.pageUrl.toString()
        val smsUrl = if (Regex("[?&]id=[^&]*").containsMatchIn(raw)) {
            raw.replaceFirst(Regex("([?&])id=[^&]*"), "$1id=$SMS_CONTRACT")
        } else {
            credential.pageUrl.newBuilder().addQueryParameter("id", SMS_CONTRACT).build().toString()
        }
        val page = execute(Request.Builder().url(smsUrl).header("Referer", credential.pageUrl.toString()))
        val parsed = parseCredentialPage(page)
            ?: throw IOException("MOE did not return its SMS sign-in page")
        val loginType = literal(page.body, "_loginType").orEmpty()
        if (!loginType.contains("sms", ignoreCase = true) && !loginType.contains("otp", ignoreCase = true)) {
            throw IOException("MOE did not offer SMS sign-in for this account")
        }
        return parsed
    }

    private fun parseCredentialPage(page: Page): CredentialPage? {
        val server = literal(page.body, "_serverUrl") ?: return null
        var serverUrl = page.url.resolve(server)
            ?: throw IOException("MOE returned an invalid login address")
        if (!serverUrl.isHttps || serverUrl.host != MOE_HOST) {
            throw IOException("MOE returned an unexpected login address")
        }
        val csrt = Regex("csrt=([0-9]+)").find(page.body)?.groupValues?.get(1)
        if (csrt != null && serverUrl.queryParameter("csrt") == null) {
            serverUrl = serverUrl.newBuilder().addQueryParameter("csrt", csrt).build()
        }
        return CredentialPage(page.url, serverUrl, literal(page.body, "_target").orEmpty())
    }

    private fun followShahafHandoff(initial: Page): Page {
        var page = initial
        repeat(8) {
            if (authResponseFromUrl(page.url) != null) return page
            page = followInterstitial(page)
        }
        throw IOException("MOE did not complete the Shahaf sign-in")
    }

    /** One F5/WS-Fed interstitial hop: `top.location.href` or an auto-submitting form. */
    private fun followInterstitial(page: Page): Page {
        val redirect = literal(page.body, "top.location.href")
        if (redirect != null) {
            val url = page.url.resolve(redirect) ?: throw IOException("Invalid MOE redirect")
            return execute(Request.Builder().url(url).header("Referer", page.url.toString()))
        }
        val form = Jsoup.parse(page.body).selectFirst("form")
            ?: throw IOException("MOE sign-in exceeded its redirect limit")
        if (!page.body.contains(".submit(")) {
            throw IOException("MOE sign-in exceeded its redirect limit")
        }
        val action = page.url.resolve(form.attr("action"))
            ?: throw IOException("Invalid MOE handoff address")
        // Jsoup decodes HTML attributes once, preserving entities inside the signed XML.
        val fields = form.select("input[name]").associate { it.attr("name") to it.attr("value") }
        return postForm(action, page.url, fields)
    }

    private fun postForm(url: HttpUrl, referer: HttpUrl, fields: Map<String, String>): Page {
        val body = FormBody.Builder().apply { fields.forEach { (name, value) -> add(name, value) } }.build()
        return execute(Request.Builder().url(url)
            .header("Referer", referer.toString())
            .header("Origin", "${referer.scheme}://${referer.host}")
            // The SPA posts via Angular $http, which sends these on every AJAX
            // call. Without them the request does not look browser-issued and
            // MOE answers the SMS endpoint with isCaptchaNeeded=true, while a
            // real browser gets the code. Same values as the live-verified
            // StudentsPortalPlatform AJAX calls.
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept", "application/json, text/plain, */*")
            .post(body))
    }

    private fun execute(builder: Request.Builder): Page {
        val tmp = builder.build()
        if (tmp.header("Accept") == null) {
            builder.header("Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        }
        val request = builder
            // Browsers always send Accept-Language; its absence is a bot signal.
            .header("Accept-Language", "he-IL,he;q=0.9,en;q=0.8")
            .header("User-Agent", USER_AGENT)
            .build()
        if (!request.url.isHttps || request.url.host !in ALLOWED_HOSTS) {
            throw IOException("Unexpected address in the MOE sign-in flow")
        }
        return client.newCall(request).execute().use { response ->
            // The Shahaf payload can arrive on an intermediate redirect; catch it
            // before OkHttp's follower moves past it.
            val payloadUrl = generateSequence(response) { it.priorResponse }
                .map { it.request.url }
                .firstOrNull { authResponseFromUrl(it) != null }
            if (payloadUrl != null) return Page(payloadUrl, "")
            if (!response.isSuccessful) throw IOException("Sign-in failed (HTTP ${response.code})")
            Page(response.request.url, response.body.string())
        }
    }

    private fun json(page: Page): JSONObject = try {
        JSONObject(page.body)
    } catch (_: Exception) {
        throw IOException("The login service returned an unexpected response")
    }

    private fun literal(html: String, name: String): String? =
        Regex("${Regex.escape(name)}\\s*=\\s*(['\"])(.*?)\\1").find(html)?.groupValues?.get(2)

    companion object {
        const val SMS_CONTRACT = "EduCombinedAuthSms"
        private const val MOE_HOST = "lgn.edu.gov.il"
        private val ALLOWED_HOSTS = setOf(MOE_HOST, "timetable.shahaf.info")
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36"

        /**
         * The Shahaf auth response encoded in a `SignInSuccess.aspx?s=...` URL.
         * Same contract as [ShahafApi.authResponseFromUrl], decoded with JVM-only
         * APIs so headless login stays unit-testable. Returns null when the URL
         * is not a success redirect; throws [IOException] on a corrupt payload.
         */
        internal fun authResponseFromUrl(url: HttpUrl): JSONObject? {
            if (url.scheme != "https" || url.host != "timetable.shahaf.info" ||
                !url.encodedPath.endsWith("/SignInSuccess.aspx", ignoreCase = true)) return null
            val encoded = url.queryParameter("s") ?: return null
            return try {
                val standard = encoded.replace('.', '=').replace('_', '/').replace('-', '+')
                val padded = standard + "=".repeat((4 - standard.length % 4) % 4)
                JSONObject(String(java.util.Base64.getDecoder().decode(padded), Charsets.UTF_8))
            } catch (_: Exception) {
                throw IOException("Shahaf returned an invalid sign-in response")
            }
        }
    }
}
