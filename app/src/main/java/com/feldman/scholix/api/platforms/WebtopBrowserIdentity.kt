package com.feldman.scholix.api.platforms

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * Webtop's CloudFront answers OkHttp's default User-Agent with 403 "Request blocked" (seen on
 * 20 November 2026), which Scholix reported as invalid credentials. Requests to Webtop identify as
 * the mobile Chrome that the sign-in's deviceDataJson already describes; a request that sets its own
 * User-Agent, such as the Ministry sign-in, keeps it.
 */
internal object WebtopBrowserIdentity : Interceptor {
    const val USER_AGENT = "Mozilla/5.0 (Linux; Android 16; K) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/152.0.0.0 Mobile Safari/537.36"

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header("User-Agent") != null || !request.url.host.endsWith("smartschool.co.il")) {
            return chain.proceed(request)
        }
        return chain.proceed(request.newBuilder().header("User-Agent", USER_AGENT).build())
    }
}

internal fun OkHttpClient.Builder.withWebtopBrowserIdentity(): OkHttpClient.Builder =
    apply { if (WebtopBrowserIdentity !in interceptors()) addInterceptor(WebtopBrowserIdentity) }
