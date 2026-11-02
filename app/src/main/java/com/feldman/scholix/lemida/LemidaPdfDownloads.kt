package com.feldman.scholix.lemida

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Publish only complete PDFs to shared Downloads; no broad storage permission is needed. */
internal object LemidaPdfDownloads {
    suspend fun save(context: Context, file: File, name: String): Uri = withContext(Dispatchers.IO) {
        LemidaPdf.requireFile(file)
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, LemidaPdf(name, "").downloadName())
            put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Scholix")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }) ?: throw IOException("Could not create the download. Check available storage and try again.")
        var complete = false
        try {
            file.inputStream().use { input ->
                (resolver.openOutputStream(uri, "w") ?: throw IOException("Could not write the download.")).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            if (resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) != 1)
                throw IOException("Could not finish saving the download. Try again.")
            complete = true
            uri
        } finally {
            if (!complete) runCatching { resolver.delete(uri, null, null) }
        }
    }
}
