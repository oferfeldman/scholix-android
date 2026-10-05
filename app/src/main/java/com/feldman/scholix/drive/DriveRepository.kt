package com.feldman.scholix.drive

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class DriveListing(val items: List<DriveItem> = emptyList(), val updated: Long = 0)
data class DriveState(val account: String = "", val followed: List<DriveItem> = emptyList(),
    val listings: Map<String, DriveListing> = emptyMap(), val offline: List<DriveItem> = emptyList(),
    val status: String = "", val needsConsent: Boolean = false, val savedFolders:List<DriveItem> = emptyList(),val networkUnavailable:Boolean=false)

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
            } }, decode(j.getJSONArray("offline")).filter { offlineFile(it).isFile },
            savedFolders=decode(j.optJSONArray("savedFolders") ?: JSONArray()))
    }.getOrDefault(DriveState())
    private fun decode(a: JSONArray) = (0 until a.length()).map { DriveItem.parse(a.getJSONObject(it)) }
    private fun encode(items: List<DriveItem>) = JSONArray().apply { items.forEach { put(it.json()) } }
    private fun publish(s: DriveState) {
        root.mkdirs()
        val j = JSONObject().put("account", s.account).put("followed", encode(s.followed))
            .put("savedFolders",encode(s.savedFolders))
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
            androidx.work.WorkManager.getInstance(context).cancelAllWorkByTag(DriveFolderDownloadWorker.TAG)
            androidx.work.WorkManager.getInstance(context).cancelAllWorkByTag(StarNoteBackupWorker.TAG)
            publish(DriveState(account = account))
        } else publish(mutable.value.copy(status = "", needsConsent = false,networkUnavailable=false))
    } }
    suspend fun disconnect() = mutex.withLock { withContext(Dispatchers.IO) {
        clearFiles()
        mutable.value = DriveState()
        DriveSyncWorker.cancel(context)
        androidx.work.WorkManager.getInstance(context).cancelAllWorkByTag(DriveFolderDownloadWorker.TAG)
        androidx.work.WorkManager.getInstance(context).cancelAllWorkByTag(StarNoteBackupWorker.TAG)
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
                status = "", needsConsent = false,networkUnavailable=false)) }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            mutable.value = mutable.value.copy(status = if(DriveConnection.unavailable(e))DriveConnection.OFFLINE else DriveAuth.message(e),
                networkUnavailable=DriveConnection.unavailable(e),
                needsConsent = e is DriveNeedsConsent || e is DriveHttpError && e.status == 401)
            throw e
        }
    }
    suspend fun saveOffline(item: DriveItem) = mutex.withLock {
        authorized { api.download(it, item, offlineFile(item)) }
        withContext(Dispatchers.IO) { publish(mutable.value.copy(
            offline = mutable.value.offline.filterNot { it.effectiveId == item.effectiveId } + item)) }
    }
    suspend fun downloadFolder(account:String,folder:DriveItem,progress:suspend(Int,Int,String)->Unit) {
        progress(0,0,"Finding files…")
        val session=DriveDownloadSession(authorize={DriveAuth.token(context,account)},clear={DriveAuth.clear(context,it)})
        val plan=DriveFolderPlanner.scan(folder,onScan={folders,files->progress(folders,0,"Finding files · $folders folders · $files files")}) { child -> mutex.withLock {
            require(mutable.value.account==account) {"The connected account changed."}
            session.request {api.list(it,child)}
        } }
        starLocalAccess {current->withContext(Dispatchers.IO) {
            require(current==account)
            val remaining=DriveFolderPlanner.remainingBytes(plan.files,mutable.value.offline){offlineFile(it).isFile}
            require(root.apply {mkdirs()}.usableSpace>remaining+64L*1024*1024) {"There is not enough free storage for the remaining files."}
            publish(mutable.value.copy(listings=mutable.value.listings+plan.folders.mapValues {(_,pair)->DriveListing(pair.second,System.currentTimeMillis())}))
        }}
        progress(0,plan.files.size,"Saving files…")
        var bytes=0L
        var metadataDirty=false
        for((index,item) in plan.files.withIndex()) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            mutex.withLock {
                require(mutable.value.account==account) {"The connected account changed."}
                val saved=mutable.value.offline.firstOrNull {it.effectiveId==item.effectiveId}
                if(saved==null || saved.modified!=item.modified || !offlineFile(item).isFile) {
                    session.request {api.download(it,item,offlineFile(item))}
                    mutable.value=mutable.value.copy(offline=mutable.value.offline.filterNot {it.effectiveId==item.effectiveId}+item)
                    metadataDirty=true
                }
                if(metadataDirty && (index % 10 == 0 || index==plan.files.lastIndex))withContext(Dispatchers.IO){publish(mutable.value);metadataDirty=false}
                bytes+=offlineFile(item).length()
                require(bytes<=2L*1024*1024*1024) {"This folder exceeds the 2 GB download limit. Saved files are still available."}
            }
            progress(index+1,plan.files.size,item.name)
        }
        starLocalAccess {current->withContext(Dispatchers.IO) {
            require(current==account)
            publish(mutable.value.copy(savedFolders=mutable.value.savedFolders.filterNot {it.effectiveId==folder.effectiveId}+folder,
                status=if(plan.skipped>0)"Folder saved. ${plan.skipped} unavailable or oversized files were skipped." else "Folder saved locally.",
                networkUnavailable=false,needsConsent=false))
        }}
    }
    internal fun cachedList(folder:DriveItem):List<DriveItem> = mutable.value.listings[folder.effectiveId]?.items
        ?: throw java.io.IOException("This folder has not been fully downloaded yet.")
    internal fun cachedFile(item:DriveItem):File? = offlineFile(item).takeIf {it.isFile && mutable.value.offline.any {saved->saved.effectiveId==item.effectiveId && saved.modified==item.modified}}
    private fun temporaryBytes(file:File):Long {
        val original=File(root,"offline/${file.name}")
        val shared=file.parentFile==File(context.cacheDir,"drive-share") && original.isFile &&
            runCatching {java.nio.file.Files.isSameFile(file.toPath(),original.toPath())}.getOrDefault(false)
        return if(shared)0 else file.length()
    }
    suspend fun storageUsage():DriveStorageUsage=mutex.withLock {withContext(Dispatchers.IO) {
        val notes=DriveCacheStorage.files(File(root,"starnote"))
        DriveStorageUsage(
            downloads=DriveCacheStorage.files(File(root,"offline")).sumOf {it.length()},
            temporary=(notes.filter {DriveCacheStorage.noteCache(it)}+DriveCacheStorage.files(File(context.cacheDir,"drive-preview"))+
                DriveCacheStorage.files(File(context.cacheDir,"drive-share"))).sumOf {temporaryBytes(it)},
            edits=notes.filter {it.name=="edits.json"||it.name=="edits.tmp"}.sumOf {it.length()})
    }}
    suspend fun clearTemporary():Long=mutex.withLock {withContext(Dispatchers.IO) {
        val files=DriveCacheStorage.files(File(root,"starnote")).filter {DriveCacheStorage.noteCache(it)}+
            DriveCacheStorage.files(File(context.cacheDir,"drive-preview"))+DriveCacheStorage.files(File(context.cacheDir,"drive-share"))
        val freed=files.sumOf {temporaryBytes(it)}
        DriveCacheStorage.remove(files);freed
    }}
    suspend fun trimNoteCache():Long=mutex.withLock {withContext(Dispatchers.IO) {
        DriveCacheStorage.trimNotes(File(root,"starnote"))
    }}
    suspend fun clearDownloads() {
        withContext(Dispatchers.IO) {androidx.work.WorkManager.getInstance(context).cancelAllWorkByTag(DriveFolderDownloadWorker.TAG).result.get()}
        mutex.withLock {withContext(Dispatchers.IO) {
            try {
                DriveCacheStorage.remove(DriveCacheStorage.files(File(context.cacheDir,"drive-share")))
                DriveCacheStorage.remove(DriveCacheStorage.files(File(root,"offline")))
            } finally {
                publish(mutable.value.copy(offline=mutable.value.offline.filter {offlineFile(it).isFile},savedFolders=emptyList()))
            }
        }}
    }
    suspend fun removeOffline(item: DriveItem) = mutex.withLock { withContext(Dispatchers.IO) {
        offlineFile(item).delete()
        publish(mutable.value.copy(offline = mutable.value.offline.filterNot { it.effectiveId == item.effectiveId }))
    } }
    suspend fun preview(item: DriveItem): File = mutex.withLock {withContext(Dispatchers.IO) {
        if (offlineFile(item).isFile) return@withContext offlineFile(item)
        require(item.pdf) { "Open this file in Google Drive." }
        val target = File(context.cacheDir, "drive-preview/${hash(item.effectiveId)}.pdf")
        val version=File(target.parentFile,target.name+".version")
        if(target.isFile && version.isFile && version.readText()=="${item.modified}:${target.length()}")return@withContext target
        authorized { api.download(it, item, target) }
        version.writeText("${item.modified}:${target.length()}")
        withContext(Dispatchers.IO) {
            // Keep only the current online preview; offline copies are managed separately.
            target.parentFile!!.listFiles()?.filter { it.isFile && it != target && it!=version }?.forEach { it.delete() }
        }
        target
    }}
    suspend fun shareCopy(item: DriveItem): File = mutex.withLock { withContext(Dispatchers.IO) {
        require(offlineFile(item).isFile)
        val target = File(context.cacheDir, "drive-share/${hash(item.effectiveId)}${extension(item)}")
        target.parentFile!!.mkdirs()
        // A read-only share can reference the same bytes without doubling file storage.
        java.nio.file.Files.deleteIfExists(target.toPath())
        try {java.nio.file.Files.createLink(target.toPath(),offlineFile(item).toPath())}
        catch(_:java.io.IOException){offlineFile(item).copyTo(target,overwrite=true)}
        catch(_:UnsupportedOperationException){offlineFile(item).copyTo(target,overwrite=true)}
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
