package com.feldman.scholix.drive

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class DriveListing(val items: List<DriveItem> = emptyList(), val updated: Long = 0)
data class DriveState(val account: String = "", val followed: List<DriveItem> = emptyList(),
    val listings: Map<String, DriveListing> = emptyMap(), val offline: List<DriveItem> = emptyList(),
    val status: String = "", val needsConsent: Boolean = false)

class DriveRepository private constructor(private val context: Context) {
    private val root = File(context.noBackupFilesDir, "drive-materials")
    private val store = File(root, "state.json")
    private val mutex = Mutex()
    private val api = DriveApi()
    private val mutable = MutableStateFlow(read())
    val state = mutable.asStateFlow()
    private fun read(): DriveState = runCatching {
        val j = JSONObject(store.readText())
        val listings = j.getJSONObject("listings")
        DriveState(j.getString("account"), decode(j.getJSONArray("followed")),
            listings.keys().asSequence().associateWith { k -> listings.getJSONObject(k).let {
                DriveListing(decode(it.getJSONArray("items")), it.getLong("updated"))
            } }, decode(j.getJSONArray("offline")).filter { offlineFile(it).isFile })
    }.getOrDefault(DriveState())
    private fun decode(a: JSONArray) = (0 until a.length()).map { DriveItem.parse(a.getJSONObject(it)) }
    private fun encode(items: List<DriveItem>) = JSONArray().apply { items.forEach { put(it.json()) } }
    private fun publish(s: DriveState) {
        root.mkdirs()
        val j = JSONObject().put("account", s.account).put("followed", encode(s.followed))
            .put("offline", encode(s.offline)).put("listings", JSONObject().apply {
                s.listings.forEach { (k, v) -> put(k, JSONObject().put("items", encode(v.items)).put("updated", v.updated)) }
            })
        val temp = File(root, "state.tmp")
        temp.writeText(j.toString())
        java.nio.file.Files.move(temp.toPath(), store.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        mutable.value = s
    }
    fun listingKey(folder: DriveItem?, shared: Boolean = false) =
        if (shared) "shared" else folder?.effectiveId ?: "root"
    fun offlineFile(item: DriveItem): File = File(root, "offline/${hash(item.effectiveId)}${extension(item)}")
    private fun extension(item: DriveItem) = if (item.pdf) ".pdf" else when(item.effectiveMime) {
        "image/png" -> ".png"; "image/jpeg" -> ".jpg"; "text/plain" -> ".txt"
        else -> ".bin"
    }
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
    suspend fun connect(token: String) = mutex.withLock { withContext(Dispatchers.IO) {
        val account = api.account(token)
        if (mutable.value.account != account) {
            clearFiles()
            publish(DriveState(account = account))
        } else publish(mutable.value.copy(status = "", needsConsent = false))
    } }
    suspend fun disconnect() = mutex.withLock { withContext(Dispatchers.IO) {
        clearFiles()
        mutable.value = DriveState()
        DriveSyncWorker.cancel(context)
    } }
    private fun clearFiles() {
        // Refuse an account switch if old private files could survive it.
        for (directory in listOf(root, File(context.cacheDir, "drive-preview"), File(context.cacheDir, "drive-share"))) {
            if (directory.exists() && !directory.deleteRecursively())
                throw java.io.IOException("Could not remove cached Drive files. Try disconnecting again.")
        }
    }
    suspend fun follow(folder: DriveItem) = mutex.withLock { withContext(Dispatchers.IO) {
        val current = mutable.value
        val exists = current.followed.any { it.effectiveId == folder.effectiveId }
        publish(current.copy(followed = if (exists) current.followed.filterNot { it.effectiveId == folder.effectiveId }
            else current.followed + folder))
        DriveSyncWorker.schedule(context)
    } }
    private suspend fun <T> authorized(edits: Boolean = false, block: suspend (String) -> T): T {
        val account = mutable.value.account
        if (account.isBlank()) throw DriveNeedsConsent()
        val token = DriveAuth.token(context, account, edits)
        return try { block(token) } catch (e: DriveHttpError) {
            if (e.status != 401) throw e
            DriveAuth.clear(context, token)
            // One retry with a new token, never an unbounded authorization loop.
            block(DriveAuth.token(context, account, edits))
        }
    }
    internal suspend fun <T> starAccess(edits: Boolean = false, block: suspend (DriveApi, String, String) -> T): T = mutex.withLock {
        val account = mutable.value.account
        authorized(edits) { block(api, it, account) }
    }
    internal suspend fun <T> starLocalAccess(block: suspend (String) -> T): T = mutex.withLock {
        val account = mutable.value.account
        require(account.isNotBlank()) { "Connect Google Drive first." }
        block(account)
    }
    suspend fun refresh(folder: DriveItem? = null, shared: Boolean = false) = mutex.withLock {
        try {
            val items = authorized { api.list(it, folder, shared) }
            withContext(Dispatchers.IO) { publish(mutable.value.copy(
                listings = mutable.value.listings + (listingKey(folder, shared) to DriveListing(items, System.currentTimeMillis())),
                status = "", needsConsent = false)) }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            mutable.value = mutable.value.copy(status = DriveAuth.message(e),
                needsConsent = e is DriveNeedsConsent || e is DriveHttpError && e.status == 401)
            throw e
        }
    }
    suspend fun saveOffline(item: DriveItem) = mutex.withLock {
        authorized { api.download(it, item, offlineFile(item)) }
        withContext(Dispatchers.IO) { publish(mutable.value.copy(
            offline = mutable.value.offline.filterNot { it.effectiveId == item.effectiveId } + item)) }
    }
    suspend fun removeOffline(item: DriveItem) = mutex.withLock { withContext(Dispatchers.IO) {
        offlineFile(item).delete()
        publish(mutable.value.copy(offline = mutable.value.offline.filterNot { it.effectiveId == item.effectiveId }))
    } }
    suspend fun preview(item: DriveItem): File = mutex.withLock {
        if (offlineFile(item).isFile) return@withLock offlineFile(item)
        require(item.pdf) { "Open this file in Google Drive." }
        val target = File(context.cacheDir, "drive-preview/${hash(item.effectiveId)}.pdf")
        authorized { api.download(it, item, target) }
        withContext(Dispatchers.IO) {
            // Keep only the current online preview; offline copies are managed separately.
            target.parentFile!!.listFiles()?.filter { it.isFile && it != target }?.forEach { it.delete() }
        }
        target
    }
    suspend fun shareCopy(item: DriveItem): File = mutex.withLock { withContext(Dispatchers.IO) {
        require(offlineFile(item).isFile)
        val target = File(context.cacheDir, "drive-share/${hash(item.effectiveId)}${extension(item)}")
        target.parentFile!!.mkdirs()
        offlineFile(item).copyTo(target, overwrite = true)
        target.parentFile!!.listFiles()?.filter { it.isFile && it != target }?.forEach { it.delete() }
        target
    } }
    companion object {
        @Volatile private var instance: DriveRepository? = null
        fun get(context: Context): DriveRepository = instance ?: synchronized(this) {
            instance ?: DriveRepository(context.applicationContext).also { instance = it }
        }
    }
}
