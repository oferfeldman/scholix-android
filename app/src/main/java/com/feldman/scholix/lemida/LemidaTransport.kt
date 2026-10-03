package com.feldman.scholix.lemida

/** Browser transport boundary, also used for isolated expiry/recovery regressions. */
internal interface LemidaTransport {
    suspend fun prepare()
    suspend fun get(url: String): String
    suspend fun post(url: String, body: String): String
    suspend fun close()
}
