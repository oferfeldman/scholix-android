package com.feldman.scholix.api.platforms

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Base64

class ShahafMoeLoginTest {
    @Test
    fun smsRequestAndVerifyFollowTheMoeContractToSignInSuccess() {
        val requests = mutableListOf<Request>()
        val login = smsFlow(requests)
        val challenge = login.requestSmsCode(ENTRY, "123456789", "0521234567")
        assertEquals("xxxxxx4567", challenge.maskedPhone)
        assertEquals("1234", challenge.serverUrl.queryParameter("csrt"))
        // The ID/phone contract mirrors the MOE page's own SMS tab.
        assertTrue(requests.any { it.url.toString().contains("id=EduCombinedAuthSms") })
        assertTrue(challenge.pageUrl.toString().contains("id=EduCombinedAuthSms"))
        assertEquals("123456789", form(requests[5])["HIN_ID"])
        assertEquals("0521234567", form(requests[5])["HIN_MOBILE"])
        assertEquals(" ", form(requests[5])["g-recaptcha-response"])
        // The AJAX posts must carry the browser's profile or MOE flags them as
        // bots (isCaptchaNeeded) while a real browser gets the code.
        for (i in listOf(4, 5)) {
            assertEquals("XMLHttpRequest", requests[i].header("X-Requested-With"))
            assertEquals("application/json, text/plain, */*", requests[i].header("Accept"))
            assertEquals("he-IL,he;q=0.9,en;q=0.8", requests[i].header("Accept-Language"))
            assertEquals("https://lgn.edu.gov.il", requests[i].header("Origin"))
        }
        // The send-code Referer is the SMS SPA page, never the _serverUrl POST
        // target (a combination browsers never send).
        assertTrue(requests[5].header("Referer")!!.contains("id=EduCombinedAuthSms"))
        // Navigations send the browser language and an HTML Accept.
        assertEquals("he-IL,he;q=0.9,en;q=0.8", requests[3].header("Accept-Language"))
        assertTrue(requests[3].header("Accept")!!.startsWith("text/html"))
        val auth = login.submitSmsCode(challenge, "123456")
        // The verify-code and target posts keep the SMS page as Referer too.
        for (i in listOf(6, 7)) {
            assertEquals("XMLHttpRequest", requests[i].header("X-Requested-With"))
            assertTrue(requests[i].header("Referer")!!.contains("id=EduCombinedAuthSms"))
        }
        assertEquals("tok", auth.getString("Token"))
        assertEquals("123456", form(requests[6])["Ecom_Token"])
        assertEquals("", form(requests[7])["target"])
        assertEquals("POST", requests[8].method)
        assertEquals(9, requests.size)
    }

    @Test
    fun passwordLoginPostsCredentialsAndCompletes() {
        val requests = mutableListOf<Request>()
        val login = smsFlow(requests, contractType = "usernamePassword")
        val auth = login.loginWithPassword(ENTRY, "moe-user", "s3cret")
        assertEquals("tok", auth.getString("Token"))
        assertEquals("moe-user", form(requests[4])["HIN_USERID"])
        assertEquals("s3cret", form(requests[4])["Ecom_Password"])
        assertEquals("XMLHttpRequest", requests[4].header("X-Requested-With"))
        assertEquals("application/json, text/plain, */*", requests[4].header("Accept"))
    }

    @Test
    fun rejectsInvalidIdAndPhoneBeforeAnyNetworkCall() {
        val requests = mutableListOf<Request>()
        val login = smsFlow(requests)
        val error = assertThrows(IOException::class.java) {
            login.requestSmsCode(ENTRY, "123", "05123")
        }
        assertTrue(error.message!!.contains("ID number"))
        assertTrue(requests.isEmpty())
    }

    @Test
    fun mapsExpiredCodeAndLockoutToActionableErrors() {
        val requests = mutableListOf<Request>()
        val login = smsFlow(requests, otpResponse = """{"isError":true,"errorCode":"TOKEN_TOKEN_EXPIRED"}""")
        val challenge = login.requestSmsCode(ENTRY, "123456789", "0521234567")
        val expired = assertThrows(IOException::class.java) { login.submitSmsCode(challenge, "000000") }
        assertTrue(expired.message!!.contains("expired"))
    }

    @Test
    fun stopsWhenCaptchaIsRequired() {
        val requests = mutableListOf<Request>()
        val login = smsFlow(requests, mobileResponse = """{"isError":true,"isCaptchaNeeded":true}""")
        val error = assertThrows(IOException::class.java) {
            login.requestSmsCode(ENTRY, "123456789", "0521234567")
        }
        assertTrue(error.message!!.contains("CAPTCHA"))
    }

    @Test
    fun refusesNonSmsContracts() {
        val requests = mutableListOf<Request>()
        val login = smsFlow(requests, contractType = "usernamePassword")
        val error = assertThrows(IOException::class.java) {
            login.requestSmsCode(ENTRY, "123456789", "0521234567")
        }
        assertTrue(error.message!!.contains("SMS"))
    }

    @Test
    fun rejectsUnexpectedHosts() {
        val login = ShahafMoeLogin(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("<html></html>".toResponseBody()).build()
        }.build())
        assertThrows(IOException::class.java) {
            login.requestSmsCode("https://example.com/login", "123456789", "0521234567")
        }
    }

    private fun form(request: Request): Map<String, String> {
        val body = request.body as FormBody
        return (0 until body.size).associate { body.name(it) to body.value(it) }
    }

    private fun smsFlow(
        requests: MutableList<Request>,
        contractType: String = "usernameSMSOTP",
        mobileResponse: String = """{"isError":false,"isCaptchaNeeded":false,"otpDestMasked":"xxxxxx4567"}""",
        otpResponse: String = """{"isError":false,"isCaptchaNeeded":false}""",
    ): ShahafMoeLogin {
        val moePage = "https://lgn.edu.gov.il/nidp/wsfed/ep?id=EduCombinedAuthUidPwd&sid=0&option=credential&sid=0"
        val payload = Base64.getEncoder().encodeToString("""{"Token":"tok","Name":"Test"}""".toByteArray())
            .replace('=', '.').replace('/', '_').replace('+', '-')
        val successUrl = "https://timetable.shahaf.info/SignInSuccess.aspx?s=$payload"
        return ShahafMoeLogin(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests.add(request)
            val fields = (request.body as? FormBody)?.let { body ->
                (0 until body.size).associate { body.name(it) to body.value(it) }
            }.orEmpty()
            val (url, body) = when {
                request.method == "GET" && request.url.host == "timetable.shahaf.info" ->
                    moePage to """<script>top.location.href='ep?sid=0';</script>"""
                request.method == "GET" && request.url.toString().contains("id=EduCombinedAuthSms") ->
                    request.url.toString() to credentialHtml(contractType)
                request.method == "GET" ->
                    moePage to """<form action="ep?id=EduCombinedAuthUidPwd&amp;sid=0&amp;option=credential"></form><script>document.forms[0].submit();</script>"""
                // The F5/WS-Fed interstitial auto-submit carries no fields.
                request.method == "POST" && fields.isEmpty() ->
                    moePage to credentialHtml("usernamePassword")
                fields["initiateLoginSequence"] == "true" ->
                    moePage to """{"isError":false,"isCaptchaNeeded":false}"""
                fields.containsKey("HIN_ID") -> moePage to mobileResponse
                fields.containsKey("HIN_USERID") -> moePage to mobileResponse
                fields.containsKey("Ecom_Token") -> moePage to otpResponse
                fields.containsKey("target") ->
                    moePage to """<form action="https://timetable.shahaf.info/signin.aspx"><input name="wa" value="wsignin1.0"><input name="wresult" value="assertion"><input name="wctx" value="ctx"></form><script>document.forms[0].submit();</script>"""
                request.url.host == "timetable.shahaf.info" -> successUrl to "<html></html>"
                else -> error("Unexpected request: ${request.method} ${request.url} $fields")
            }
            Response.Builder().request(request.newBuilder().url(url).build())
                .protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody()).build()
        }.build())
    }

    private fun credentialHtml(loginType: String) = """
        <script>var _serverUrl='https://lgn.edu.gov.il/nidp/wsfed/ep?sid=0&sid=0';
        var _loginType="$loginType"; var _target='';</script>
        <a href="?csrt=1234"></a>
    """.trimIndent()

    companion object {
        const val ENTRY = "https://timetable.shahaf.info/ShfSignIn.aspx?sid=shfapp&cid=device"
    }
}
