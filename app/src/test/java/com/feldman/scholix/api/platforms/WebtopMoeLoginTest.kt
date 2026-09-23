package com.feldman.scholix.api.platforms

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class WebtopMoeLoginTest {
    @Test
    fun followsCurrentSessionAndPreservesSignedXmlAndCallbackKey() {
        val requests = mutableListOf<Request>()
        val login = recordedFlow(requests)
        // This transport fixture intentionally issues no cookie. The client must reject it.
        val error = assertThrows(IOException::class.java) { login.login("moe-user", "p&=+word") }
        assertEquals("Webtop did not issue a session cookie", error.message)
        assertEquals(8, requests.size)
        assertEquals("37", requests[1].url.queryParameter("sid"))
        assertEquals("true", form(requests[2])["initiateLoginSequence"])
        assertEquals("p&=+word", form(requests[3])["Ecom_Password"])
        assertEquals(" ", form(requests[3])["g-recaptcha-response"])
        assertEquals("1234", requests[3].url.queryParameter("csrt"))
        assertEquals("", form(requests[4])["target"])
        assertEquals("GET", requests[5].method)
        assertEquals("37", requests[5].url.queryParameter("sid"))
        assertEquals("<Assertion><Name>A &amp; B</Name></Assertion>", form(requests[6])["wresult"])
        assertEquals(setOf("wa", "wresult", "wctx"), form(requests[6]).keys)
        val buffer = Buffer()
        requests[7].body!!.writeTo(buffer)
        val exchange = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
        assertEquals(JsonPrimitive("a b+c="), exchange["key"])
        assertEquals(JsonPrimitive("false"), exchange["rememberMe"])
        assertTrue((exchange["deviceDataJson"] as JsonPrimitive).isString)
    }

    @Test
    fun stopsWhenCaptchaIsRequired() {
        val requests = mutableListOf<Request>()
        val login = recordedFlow(requests, """{"isError":true,"isCaptchaNeeded":true}""")
        val error = assertThrows(IOException::class.java) { login.login("user", "password") }
        assertTrue(error.message!!.contains("CAPTCHA"))
        assertEquals(4, requests.size)
    }

    @Test
    fun requiresExplicitCredentialAcceptance() {
        for (result in listOf("{}", """{"isError":true}""", "<html>Login</html>")) {
            val requests = mutableListOf<Request>()
            val login = recordedFlow(requests, result)
            assertThrows(IOException::class.java) { login.login("user", "password") }
            assertEquals(4, requests.size)
        }
    }

    @Test
    fun stopsWhenPasswordUpdateIsRequired() {
        val requests = mutableListOf<Request>()
        val login = recordedFlow(requests, """{"isError":false,"isPasswordExpiring":true}""")
        val error = assertThrows(IOException::class.java) { login.login("user", "password") }
        assertTrue(error.message!!.contains("Update your password"))
        assertEquals(4, requests.size)
    }

    @Test
    fun stopsForSmsLoginBeforeSendingCredentials() {
        val requests = mutableListOf<Request>()
        val login = recordedFlow(requests, loginType = "usernamePasswordSMSOTP")
        val error = assertThrows(IOException::class.java) { login.login("user", "password") }
        assertTrue(error.message!!.contains("SMS"))
        assertEquals(2, requests.size)
    }

    private fun form(request: Request): Map<String, String> {
        val body = request.body as FormBody
        return (0 until body.size).associate { body.name(it) to body.value(it) }
    }

    // Redacted protocol fixtures; the interceptor replaces only external HTTP responses.
    private fun recordedFlow(
        requests: MutableList<Request>,
        credentialResponse: String = """{"isError":false,"isCaptchaNeeded":false}""",
        loginType: String = "usernamePassword"
    ): WebtopMoeLogin {
        val moeUrl = "https://lgn.edu.gov.il/nidp/wsfed/ep?sid=37"
        return WebtopMoeLogin(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests.add(request)
            val (url, body) = when (requests.size) {
                1 -> moeUrl to """<form action="ep?sid=37&amp;option=credential"></form><script>document.forms[0].submit();</script>"""
                2 -> moeUrl to """<script>var _serverUrl='$moeUrl'; var _loginType='$loginType'; var _target='';</script><a href="?csrt=1234"></a>"""
                3 -> moeUrl to """{"isError":true,"errorCode":"INVALID_INPUT"}"""
                4 -> moeUrl to credentialResponse
                5 -> moeUrl to """<script>top.location.href='ep?sid=37';</script>"""
                6 -> moeUrl to """
                    <input name="outside" value="ignored">
                    <form action="${WebtopMoeLogin.ENTRY_URL}">
                      <input name="wa" value="wsignin1.0">
                      <input name="wresult" value="&lt;Assertion&gt;&lt;Name&gt;A &amp;amp; B&lt;/Name&gt;&lt;/Assertion&gt;">
                      <input name="wctx" value="context">
                    </form><script>document.forms[0].submit();</script>
                """.trimIndent()
                7 -> "https://webtop.smartschool.co.il/account/loginMoe?key=a+b%2Bc%3D" to "<html></html>"
                8 -> request.url.toString() to """{"status":true,"data":{"userId":"test"}}"""
                else -> error("Unexpected request")
            }
            Response.Builder().request(request.newBuilder().url(url).build())
                .protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody()).build()
        }.build())
    }
}
