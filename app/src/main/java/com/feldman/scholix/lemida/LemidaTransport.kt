package com.feldman.scholix.lemida

/** Browser transport boundary, also used for isolated expiry/recovery regressions. */
internal interface LemidaTransport {
    suspend fun prepare()
    suspend fun get(url: String): String
    suspend fun post(url: String, body: String): String
    suspend fun downloadPdf(url: String, file: java.io.File) { throw java.io.IOException("PDF download is unavailable.") }
    suspend fun close()
}
