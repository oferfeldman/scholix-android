package com.feldman.scholix.lemida

import java.net.URI
import java.io.File
import java.io.IOException

data class LemidaPdf(val name: String, val url: String) {
    fun downloadName(): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
            .filter { !it.isISOControl() && Character.getType(it) != Character.FORMAT.toInt() }
            .trim().trim('.').take(160).ifBlank { "Homework" }
        return if (base.endsWith(".pdf", true)) base else "$base.pdf"
    }
    companion object {
        const val MAX_BYTES = 20 * 1024 * 1024
        fun from(name: String, raw: String, pdfHint: Boolean = true): LemidaPdf? = runCatching {
            val uri = URI("${LemidaParser.BASE}/").resolve(raw)
            if (!allowedUrl(uri.toString()) || (!pdfHint && !uri.path.endsWith(".pdf", true))) return null
            val url = uri.toString().substringBefore('#')
            LemidaPdf(name.trim().ifBlank { uri.path.substringAfterLast('/').ifBlank { "PDF attachment" } }, url)
        }.getOrNull()
        fun allowedUrl(raw: String): Boolean = runCatching {
            val uri = URI(raw)
            val path = uri.path.orEmpty()
            uri.scheme == "https" && uri.host.equals("lemida.biu.ac.il", true) &&
                uri.port in setOf(-1, 443) && uri.userInfo == null &&
                '\\' !in path && path.split('/').none { it == "." || it == ".." } &&
                (listOf("/pluginfile.php/", "/draftfile.php/", "/file.php/").any { path.startsWith(it) } ||
                    (path == "/mod/resource/view.php" && Regex("(?:^|&)id=[1-9][0-9]*(?:&|$)").containsMatchIn(uri.rawQuery.orEmpty())))
        }.getOrDefault(false)
        fun requireFile(file: File) {
            if (file.length() !in 5L..MAX_BYTES.toLong()) throw IOException("The PDF is empty or exceeds the 20 MB limit.")
            val header = ByteArray(5)
            file.inputStream().use { if (it.read(header) != 5 || !header.contentEquals("%PDF-".toByteArray()))
                throw IOException("Lemida did not return a PDF. Reconnect and try again.") }
        }
    }
}
