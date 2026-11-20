package com.feldman.scholix.api.platforms

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebtopBrowserIdentityTest {
    private val sent = mutableListOf<Request>()
    private val client = OkHttpClient.Builder().withWebtopBrowserIdentity().withWebtopBrowserIdentity()
        .addInterceptor { chain ->
            sent += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("{}".toResponseBody()).build()
        }.build()

    private fun send(url: String, userAgent: String? = null): Request {
        val request = Request.Builder().url(url).apply { userAgent?.let { header("User-Agent", it) } }.build()
        client.newCall(request).execute().close()
        return sent.last()
    }

    @Test fun webtopRequestsLookLikeAMobileBrowser() {
        val request = send("https://webtopserver.smartschool.co.il/server/api/user/LoginByUserNameAndPassword")
        assertEquals(WebtopBrowserIdentity.USER_AGENT, request.header("User-Agent"))
        assertEquals(1, client.interceptors.count { it === WebtopBrowserIdentity })
    }

    @Test fun anExplicitUserAgentAndOtherSitesAreLeftAlone() {
        assertEquals("desktop", send("https://webtopserver.smartschool.co.il/server/api/user/LoginMoe", "desktop").header("User-Agent"))
        assertNull(send("https://lgn.edu.gov.il/").header("User-Agent"))
    }
}
