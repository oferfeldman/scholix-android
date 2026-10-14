package com.feldman.scholix.ui.components

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.feldman.scholix.api.platforms.WebtopDownloadedAttachment
import java.io.IOException

internal fun openMessageAttachment(context: Context, attachment: WebtopDownloadedAttachment) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", attachment.file)
    val mime = if (attachment.mimeType == "application/octet-stream") {
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(attachment.file.extension.lowercase())
            ?: attachment.mimeType
    } else attachment.mimeType
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        clipData = ClipData.newRawUri(attachment.file.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try { context.startActivity(intent) } catch (error: ActivityNotFoundException) {
        throw IOException("Install an app that can open this attachment, then tap it again.", error)
    }
}
