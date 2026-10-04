package com.feldman.scholix.drive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MultipartBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

data class DriveItem(val id: String, val name: String, val mime: String,
    val modified: String = "", val size: Long = 0, val resourceKey: String = "",
    val canDownload: Boolean = true, val targetId: String = "", val targetMime: String = "",
    val targetKey: String = "", val sourceTitle:String="") {
    val folder get() = effectiveMime == FOLDER
    val effectiveId get() = targetId.ifEmpty { id }
    val effectiveMime get() = targetMime.ifEmpty { mime }
    val effectiveKey get() = if (targetId.isEmpty()) resourceKey else targetKey
    val pdf get() = effectiveMime == "application/pdf" || effectiveMime in NATIVE_PDF
    val downloadable get() = canDownload && !folder &&
        (!effectiveMime.startsWith("application/vnd.google-apps.") || effectiveMime in NATIVE_PDF)
    fun json() = JSONObject().put("id", id).put("name", name).put("mimeType", mime)
        .put("modifiedTime", modified).put("size", size).put("resourceKey", resourceKey)
        .put("scholixSourceTitle",sourceTitle)
        .put("capabilities", JSONObject().put("canDownload", canDownload))
        .put("shortcutDetails", JSONObject().put("targetId", targetId).put("targetMimeType", targetMime)
            .put("targetResourceKey", targetKey))
    companion object {
        const val FOLDER = "application/vnd.google-apps.folder"
        val NATIVE_PDF = setOf("application/vnd.google-apps.document", "application/vnd.google-apps.spreadsheet",
            "application/vnd.google-apps.presentation")
        fun parse(j: JSONObject): DriveItem {
            val s = j.optJSONObject("shortcutDetails")
            return DriveItem(j.getString("id"), j.getString("name"), j.getString("mimeType"),
                j.optString("modifiedTime"), j.optString("size").toLongOrNull() ?: 0,
                j.optString("resourceKey"), j.optJSONObject("capabilities")?.optBoolean("canDownload", true) ?: true,
                s?.optString("targetId").orEmpty(), s?.optString("targetMimeType").orEmpty(),
                s?.optString("targetResourceKey").orEmpty(),j.optString("scholixSourceTitle").ifBlank {
                    StarNoteTitles.fromJson(j.optJSONObject("properties") ?: JSONObject()).ifBlank {
                        runCatching {StarNoteTitles.fromJson(JSONObject(j.optString("description")))}.getOrDefault("")
                    }
                })
        }
    }
}

class DriveHttpError(val status: Int) : IOException(when (status) {
    401 -> "Google Drive needs reconnection."
    403 -> "Google Drive denied access. Check file permissions and Drive API setup."
    404 -> "This Drive item is no longer available."
    429 -> "Google Drive is busy. Try again later."
    else -> "Google Drive request failed ($status)."
})

class DriveApi(private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
    .followRedirects(false).followSslRedirects(false).build(),
    private val base: HttpUrl = "https://www.googleapis.com/drive/v3/".toHttpUrl()) {
    private fun request(url: HttpUrl, token: String, id: String = "", key: String = ""): Request =
        Request.Builder().url(url).header("Authorization", "Bearer $token").apply {
            if (key.isNotEmpty()) header("X-Goog-Drive-Resource-Keys", "$id/$key")
        }.build()
    private fun json(url: HttpUrl, token: String, id: String = "", key: String = ""): JSONObject =
        client.newCall(request(url, token, id, key)).execute().use {
            if (!it.isSuccessful) throw DriveHttpError(it.code)
            JSONObject(it.body.string())
        }
    suspend fun account(token: String): String = withContext(Dispatchers.IO) {
        json(base.newBuilder().addPathSegment("about").addQueryParameter("fields", "user(emailAddress)").build(), token)
            .getJSONObject("user").getString("emailAddress").also { require(it.isNotBlank()) }
    }
    // A complete page sequence is returned before the repository replaces a cached listing.
    suspend fun list(token: String, folder: DriveItem? = null, shared: Boolean = false, search: String? = null): List<DriveItem> =
        withContext(Dispatchers.IO) {
            val id = folder?.effectiveId ?: "root"
            require(Regex("[A-Za-z0-9_-]+").matches(id)) { "Invalid Drive folder ID" }
            val query = search ?: if (shared) "trashed = false and sharedWithMe = true" else
                "trashed = false and '$id' in parents"
            val items = linkedMapOf<String, DriveItem>()
            val seen = hashSetOf<String>()
            var page = ""
            do {
                val url = base.newBuilder().addPathSegment("files").addQueryParameter("q", query)
                    .addQueryParameter("pageSize", "200").addQueryParameter("supportsAllDrives", "true")
                    .addQueryParameter("includeItemsFromAllDrives", "true")
                    .addQueryParameter("fields", "nextPageToken,incompleteSearch,files(id,name,mimeType,modifiedTime,size,resourceKey,description,properties,capabilities(canDownload),shortcutDetails)")
                    .apply { if (page.isNotEmpty()) addQueryParameter("pageToken", page) }.build()
                val response = json(url, token, id, folder?.effectiveKey.orEmpty())
                if (response.optBoolean("incompleteSearch")) throw IOException("Drive returned an incomplete listing. Try refreshing this folder.")
                val files = response.getJSONArray("files")
                for (i in 0 until files.length()) {
                    val item = DriveItem.parse(files.getJSONObject(i))
                    require(Regex("[A-Za-z0-9_-]+").matches(item.effectiveId)) { "Invalid Drive file ID" }
                    items[item.id] = item
                }
                page = response.optString("nextPageToken")
                if (page.isNotEmpty() && !seen.add(page)) throw IOException("Drive repeated a page. Try refreshing.")
            } while (page.isNotEmpty())
            items.values.sortedWith(compareBy<DriveItem> { !it.folder }.thenBy { it.name.lowercase() })
        }

    // Every save creates a new revision. No StarNote sync file is ever overwritten.
    suspend fun create(token: String, metadata: JSONObject, content: String? = null): DriveItem = withContext(Dispatchers.IO) {
        val url = if (content == null) base.newBuilder().addPathSegment("files")
            else base.newBuilder().encodedPath(base.encodedPath.replace("/drive/v3/", "/upload/drive/v3/") + "files")
                .addQueryParameter("uploadType", "multipart")
        val body = if (content == null) metadata.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            else MultipartBody.Builder().setType("multipart/related".toMediaType())
                .addPart(metadata.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .addPart(content.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        client.newCall(Request.Builder().url(url.addQueryParameter("fields", "id,name,mimeType,modifiedTime,size").build())
            .header("Authorization", "Bearer $token").post(body).build()).execute().use {
            if (!it.isSuccessful) throw DriveHttpError(it.code)
            DriveItem.parse(JSONObject(it.body.string()))
        }
    }

    suspend fun download(token: String, item: DriveItem, destination: File, maxBytes: Long = 100L * 1024 * 1024) =
        withContext(Dispatchers.IO) {
            require(item.downloadable) { "This file cannot be saved offline." }
            require(item.size <= maxBytes) { "Files larger than 100 MB must be opened in Google Drive." }
            val export = item.effectiveMime in DriveItem.NATIVE_PDF
            val url = base.newBuilder().addPathSegment("files").addPathSegment(item.effectiveId).apply {
                if (export) addPathSegment("export").addQueryParameter("mimeType", "application/pdf")
                else addQueryParameter("alt", "media").addQueryParameter("supportsAllDrives", "true")
            }.build()
            destination.parentFile!!.mkdirs()
            val temp = File.createTempFile("drive-", ".part", destination.parentFile)
            try {
                client.newCall(request(url, token, item.effectiveId, item.effectiveKey)).execute().use { response ->
                    if (!response.isSuccessful) throw DriveHttpError(response.code)
                    require(response.body.contentLength() <= maxBytes) { "This file is too large to save offline." }
                    response.body.byteStream().use { input -> temp.outputStream().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var total = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n == -1) break
                            total += n
                            if (total > maxBytes) throw IOException("This file is too large to save offline.")
                            output.write(buffer, 0, n)
                        }
                    } }
                }
                java.nio.file.Files.move(temp.toPath(), destination.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            } finally { temp.delete() }
        }
}
