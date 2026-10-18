package com.feldman.scholix.lockerim

import com.feldman.scholix.api.platforms.LockerimPlatform
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LockerimTest {
    @Test fun smsFlowMatchesRecoveredPayloadsAndRetainsOtpAsText() {
        val requests = mutableListOf<Request>()
        val api = api(requests, """{"content":{"otpToken":"challenge"}}""",
            """{"content":{"otpToken":"resent"}}""",
            """{"content":{"token":"access","refreshToken":"refresh"}}""")
        assertEquals("challenge", api.sendCode("0500000000", "captcha-send").getString("otpToken"))
        assertEquals("resent", api.resendCode("challenge", "captcha-resend").getString("otpToken"))
        assertEquals("access", api.verifyCode("resent", "0012", "captcha-verify").getString("token"))
        assertEquals(listOf("/Account/Login", "/Account/ResendLoginOtp", "/Account/ValidateLoginOtp"), requests.map { it.url.encodedPath })
        assertEquals("0500000000", body(requests[0]).getString("phoneNumber"))
        assertEquals("challenge", body(requests[1]).getString("otpToken"))
        assertEquals("0012", body(requests[2]).getString("otp"))
        assertEquals("captcha-verify", body(requests[2]).getString("RecaptchaKey"))
        assertTrue(requests.all { it.header("Authorization") == null })
    }

    @Test fun expiredSessionRefreshesOnceAndRetriesWithRotatedBearerToken() {
        val requests = mutableListOf<Request>()
        val transport = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            val (status, content) = when (requests.size) {
                1 -> 401 to ""
                2 -> 200 to """{"content":{"token":"new-token","refreshToken":"new-refresh"}}"""
                else -> 200 to """{"content":{"subscriptions":[{"subscriptionId":7,"cellNumber":12}]}}"""
            }
            response(request, status, content)
        }.build()
        val account = LockerimPlatform(api = LockerimApi(client = transport)).apply {
            acceptSession(JSONObject("""{"token":"old-token","refreshToken":"old-refresh"}"""))
        }
        assertEquals(12, account.refreshLockers().single().getInt("cellNumber"))
        assertEquals(listOf("/Subscription/GetMySubscriptions", "/Account/RefreshToken", "/Subscription/GetMySubscriptions"), requests.map { it.url.encodedPath })
        assertEquals("old-refresh", body(requests[1]).getString("refreshToken"))
        assertEquals("Bearer new-token", requests.last().header("Authorization"))
        assertEquals("new-refresh", LockerimPlatform.fromJson(account.toJson()).refreshToken)
        assertEquals(7, LockerimPlatform.fromJson(account.toJson()).subscriptions.getJSONObject(0).getInt("subscriptionId"))
    }

    @Test fun serviceValidationErrorsDoNotCreateASession() {
        // The production API returns HTTP 200 with this error envelope for an empty login.
        val api = api(mutableListOf(), """{"content":null,"isSucceeded":false,"errors":[{"code":2,"message":"Validation failed"}]}""")
        assertEquals("Validation failed", assertThrows(LockerimApiException::class.java) {
            api.sendCode("", "")
        }.message)
        assertFalse(LockerimPlatform().isLoggedIn())
    }

    @Test fun bluetoothUnlockMatchesIndependentAesReferenceVector() {
        // Synthetic IDs; calculated independently with Node crypto's AES-128-ECB.
        val frame = LockerimProtocol.command("123456", 0x01020304L, 1, unlock = true)
        assertEquals("5b6e5a30a9b5833168bddbead8f63d184d5d", LockerimProtocol.hex(frame))
        assertEquals("00010102030401123456000000000000", LockerimProtocol.decrypt(frame, "123456"))
        assertFalse(frame.contentEquals(LockerimProtocol.command("123456", 0x01020304L, 1, unlock = false)))
        assertFalse(frame.contentEquals(LockerimProtocol.command("123456", 0x01020304L, 2, unlock = true)))
        assertEquals("3c0123abcd3e", LockerimProtocol.hex(LockerimProtocol.enrollment("0123abcd")))
    }

    private fun body(request: Request): JSONObject = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())

    private fun api(requests: MutableList<Request>, vararg contents: String): LockerimApi {
        val responses = contents.iterator()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            response(chain.request(), 200, responses.next())
        }.build()
        return LockerimApi(client = client)
    }

    private fun response(request: Request, status: Int, content: String) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(status).message("Lockerim fixture")
        .body(content.toResponseBody()).build()
}
