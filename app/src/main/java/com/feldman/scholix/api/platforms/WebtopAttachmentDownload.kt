package com.feldman.scholix.api.platforms

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Call
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class WebtopDownloadedAttachment(val file: File, val mimeType: String)

/** Foreground attachment reads: bounded, streamed and cached separately for each account. */
internal class WebtopAttachmentDownload(
    private val cookie: () -> String,
    client: OkHttpClient = OkHttpClient()
) {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS).build()

    suspend fun download(directory: File, accountId: String, link: String, name: String): WebtopDownloadedAttachment =
        suspendCancellableCoroutine { continuation ->
            val activeCall = AtomicReference<Call?>()
            continuation.invokeOnCancellation { activeCall.get()?.cancel() }
            Dispatchers.IO.asExecutor().execute {
                try {
                    val result = downloadBlocking(directory, accountId, link, name,
                        checkCancelled = { if (!continuation.isActive) throw CancellationException("Attachment opening cancelled") },
                        onCall = { call -> activeCall.set(call); if (!continuation.isActive) call.cancel() })
                    continuation.resume(result)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        }

    private fun downloadBlocking(directory: File, accountId: String, link: String, name: String,
                                 checkCancelled: () -> Unit, onCall: (Call) -> Unit): WebtopDownloadedAttachment {
        var url = link.toHttpUrl()
        require(url.isHttps) { "Webtop did not provide a secure download link." }
        val safeName = name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\p{Cntrl}:*?\"<>|]"), "_").take(120)
            .takeUnless { it.isBlank() || it in setOf(".", "..", ".mime") } ?: "attachment.bin"
        val key = MessageDigest.getInstance("SHA-256")
            .digest("$accountId\n$link\n$name".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val folder = File(directory, key)
        if (!folder.isDirectory && !folder.mkdirs()) throw IOException("Cannot save the attachment.")
        val target = File(folder, safeName)
        val mimeFile = File(folder, ".mime")
        checkCancelled()
        if (target.length() > 0 && mimeFile.isFile &&
            System.currentTimeMillis() - target.lastModified() in 0 until TimeUnit.HOURS.toMillis(24)) {
            return WebtopDownloadedAttachment(target, mimeFile.readText())
        }
        repeat(6) {
            checkCancelled()
            require(url.isHttps) { "Webtop redirected to an insecure download link." }
            val request = Request.Builder().url(url).apply {
                // Signed storage URLs need no session cookie. Never forward Webtop
                // credentials to an attachment host or cross-host redirect.
                if (url.host in setOf("webtopserver.smartschool.co.il", "webtop.smartschool.co.il")) {
                    header("Cookie", cookie())
                }
            }.build()
            val call = client.newCall(request)
            onCall(call)
            call.execute().use { response ->
                if (response.code in listOf(301, 302, 303, 307, 308)) {
                    url = response.header("Location")?.let(url::resolve)
                        ?: throw IOException("Webtop returned an invalid attachment redirect.")
                    return@use
                }
                if (response.code in listOf(401, 403)) throw IOException("This attachment link expired. Refresh the message and try again.")
                if (!response.isSuccessful) throw IOException("Cannot download attachment (HTTP ${response.code}). Try again.")
                val body = response.body
                if (body.contentLength() > MAX_BYTES) throw IOException("The attachment is larger than 50 MB.")
                val type = body.contentType()?.let { "${it.type}/${it.subtype}" }
                if (type == "text/html") throw IOException("Webtop returned a login page instead of the attachment. Refresh the message and try again.")
                val temp = File.createTempFile("download-", ".part", folder)
                try {
                    var total = 0L
                    val prefix = java.io.ByteArrayOutputStream()
                    body.byteStream().use { input -> temp.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            checkCancelled()
                            val count = input.read(buffer)
                            if (count == -1) break
                            total += count
                            if (total > MAX_BYTES) throw IOException("The attachment is larger than 50 MB.")
                            if (prefix.size() < 1024) prefix.write(buffer, 0, minOf(count, 1024 - prefix.size()))
                            output.write(buffer, 0, count)
                        }
                    } }
                    checkCancelled()
                    if (total == 0L || (body.contentLength() >= 0 && total != body.contentLength())) {
                        throw IOException("The attachment download was incomplete. Try again.")
                    }
                    val isPdf = safeName.endsWith(".pdf", ignoreCase = true) || type == "application/pdf"
                    if (isPdf && !prefix.toString(Charsets.ISO_8859_1.name()).contains("%PDF-")) {
                        throw IOException("Webtop did not return a PDF. Refresh the message and try again.")
                    }
                    val mime = if (isPdf) "application/pdf" else type ?: "application/octet-stream"
                    Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    mimeFile.writeText(mime)
                    return WebtopDownloadedAttachment(target, mime)
                } finally { temp.delete() }
            }
        }
        throw IOException("Too many attachment redirects.")
    }

    companion object { private const val MAX_BYTES = 50L * 1024 * 1024 }
}
