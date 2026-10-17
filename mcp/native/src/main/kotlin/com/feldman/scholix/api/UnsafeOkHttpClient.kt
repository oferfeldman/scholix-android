package com.feldman.scholix.api

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Legacy provider factory name; server transport uses normal TLS validation. */
object UnsafeOkHttpClient {
    private val client = OkHttpClient.Builder().retryOnConnectionFailure(false).callTimeout(120, TimeUnit.SECONDS).build()
    fun getUnsafeOkHttpClient() = client
}
