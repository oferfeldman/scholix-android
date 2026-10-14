package com.feldman.scholix.drive

/** A token lives only for this download, and is renewed once if a request rejects it. */
internal class DriveDownloadSession(
    private val authorize:suspend()->String,
    private val clear:suspend(String)->Unit
) {
    private var token:String?=null
    suspend fun <T> request(block:suspend(String)->T):T {
        val current=token ?: authorize().also {token=it}
        return try {block(current)} catch(e:DriveHttpError) {
            if(e.status!=401)throw e
            token=null
            clear(current)
            val renewed=authorize().also {token=it}
            block(renewed)
        }
    }
}
