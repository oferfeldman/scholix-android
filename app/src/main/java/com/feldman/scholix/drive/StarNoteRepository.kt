package com.feldman.scholix.drive

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

data class StarAnnotation(val id: String = UUID.randomUUID().toString(), val page: String, val kind: String,
    val text: String = "", val points: List<StarPoint> = emptyList())
data class StarEdits(val source: String, val title: String = "", val annotations: List<StarAnnotation> = emptyList(),
    val revision: String = UUID.randomUUID().toString(), val backedUp: Boolean = false) {
    fun json() = JSONObject().put("version", 1).put("source",source).put("title",title).put("revision",revision)
        .put("annotations",JSONArray().apply { annotations.forEach { a -> put(JSONObject()
            .put("id",a.id).put("page",a.page).put("kind",a.kind).put("text",a.text)
            .put("points",JSONArray().apply { a.points.forEach { put(JSONArray().put(it.x).put(it.y)) } })) } })
    fun changed(title: String = this.title, annotations: List<StarAnnotation> = this.annotations) =
        copy(title=title, annotations=annotations, revision=UUID.randomUUID().toString(), backedUp=false)
    companion object {
        fun read(j: JSONObject, source: String, backedUp: Boolean = false): StarEdits {
            require(j.getInt("version") == 1 && j.getString("source") == source) { "Wrong note or unsupported edit format" }
            val a=j.getJSONArray("annotations"); require(a.length() <= 10000)
            val annotations=(0 until a.length()).map { i -> a.getJSONObject(i).let { x ->
                val kind=x.getString("kind"); require(kind in listOf("ink","text","comment","hide"))
                val points=x.getJSONArray("points"); require(points.length() <= 100000)
                StarAnnotation(x.getString("id"),x.getString("page"),kind,x.getString("text"),
                    (0 until points.length()).map { n -> points.getJSONArray(n).let { p ->
                        val px=p.getDouble(0).toFloat(); val py=p.getDouble(1).toFloat()
                        require(px.isFinite() && py.isFinite() && px in 0f..1f && py in 0f..1f)
                        StarPoint(px,py)
                    } })
            } }
            return StarEdits(source,j.optString("title"),annotations,j.getString("revision"),backedUp)
        }
    }
}
data class StarOpen(val account: String, val document: StarDocument, val edits: StarEdits,
    val resources: Map<String,File>, val templates: Map<String,JSONObject>,val title:String="StarNote",val local:Boolean=false)

private class StarSource(val account:String,val offline:Boolean,val list:suspend(DriveItem)->List<DriveItem>,
    val download:suspend(DriveItem,File,Long)->Unit,val search:suspend(String)->List<DriveItem>)

class StarNoteRepository(private val context: Context, private val drive: DriveRepository) {
    private fun root(account: String) = File(context.noBackupFilesDir,"drive-materials/starnote/" +
        MessageDigest.getInstance("SHA-256").digest(account.toByteArray()).joinToString("") { "%02x".format(it) })
    private fun safeId(id:String):String { require(Regex("[A-Za-z0-9_-]+").matches(id)); return id }
    private fun draft(account:String,id:String)=File(root(account),"${safeId(id)}/edits.json")
    private fun write(file:File,value:String) {file.parentFile!!.mkdirs();val temp=File(file.parentFile,file.name+".tmp");temp.writeText(value)
        java.nio.file.Files.move(temp.toPath(),file.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE)}
    private fun listing(account:String,folder:DriveItem)=File(root(account),"lists/${safeId(folder.effectiveId)}.json")
    private fun decode(value:String)=JSONArray(value).let {a->(0 until a.length()).map {DriveItem.parse(a.getJSONObject(it))}}
    private fun encode(items:List<DriveItem>)=JSONArray().apply {items.forEach {put(it.json())}}.toString()
    private suspend fun <T> source(refresh:Boolean=false,block:suspend(StarSource)->T):T {
        if(!refresh) {
            try {return drive.starLocalAccess {account->withContext(Dispatchers.IO) {
                block(StarSource(account,true,list={folder->
                    runCatching {drive.cachedList(folder)}.getOrElse {decode(listing(account,folder).readText())}
                },download={item,file,max->
                    val saved=drive.cachedFile(item)
                    if(saved!=null) {require(saved.length()<=max);file.parentFile!!.mkdirs();if(saved!=file)saved.copyTo(file,true)}
                    else {
                        val version=File(file.parentFile,file.name+".version")
                        require(file.isFile && version.isFile && version.readText()=="${item.modified}:${file.length()}" && file.length()<=max) {"Download this note to read it locally."}
                    }
                },search={_->
                    val saved=drive.state.value.savedFolders.filter {it.name=="StarNote"}
                    if(saved.isNotEmpty())saved else decode(File(root(account),"roots.json").readText())
                }))
            }} }catch(e:Exception){if(e is kotlinx.coroutines.CancellationException)throw e}
        }
        return drive.starAccess {api,token,account->withContext(Dispatchers.IO) {
            block(StarSource(account,false,list={folder->api.list(token,folder).also {write(listing(account,folder),encode(it))}},
                download={item,file,max->
                    val version=File(file.parentFile,file.name+".version")
                    val saved=drive.cachedFile(item)
                    if(saved!=null) {require(saved.length()<=max);file.parentFile!!.mkdirs();if(saved!=file)saved.copyTo(file,true)}
                    else if(!file.isFile || !version.isFile || version.readText()!="${item.modified}:${file.length()}")api.download(token,item,file,max)
                    require(file.length()<=max);write(version,"${item.modified}:${file.length()}")
                },search={query->api.list(token,search=query)}))
        }}
    }
    suspend fun roots(refresh:Boolean=false):List<DriveItem> = source(refresh) {s->
        s.search("trashed = false and mimeType = '${DriveItem.FOLDER}' and name = 'StarNote'").also {write(File(root(s.account),"roots.json"),encode(it))}
    }
    suspend fun notes(refresh:Boolean=false): List<DriveItem> = source(refresh) { s ->
        val roots=s.search("trashed = false and mimeType = '${DriveItem.FOLDER}' and name = 'StarNote'")
        write(File(root(s.account),"roots.json"),encode(roots))
        val result=mutableListOf<DriveItem>()
        for(root in roots) {
            val sync=s.list(root).firstOrNull { it.folder && it.name == "sync" } ?: continue
            val version=s.list(sync).firstOrNull { it.folder && it.name == "v1" } ?: continue
            for(account in s.list(version).filter { it.folder }) {
                val docs=s.list(account).firstOrNull { it.folder && it.name == "document" } ?: continue
                result += s.list(docs).filter { it.folder }
            }
        }
        withContext(Dispatchers.IO) { result.distinctBy { it.id }.map { note ->
            val title=runCatching { JSONObject(draft(s.account,note.id).readText()).optString("title") }.getOrDefault("")
            val native=note.sourceTitle.ifBlank {runCatching {File(root(s.account),"${safeId(note.id)}/title.txt").readText()}.getOrDefault("")}
            note.copy(name=title.ifBlank { native.ifBlank {note.name} },sourceTitle=native)
        } }
    }
    suspend fun cover(note:DriveItem):File? = source { s ->
        val account=s.account
        val dir=File(root(account),safeId(note.id));dir.mkdirs()
        val cover=s.list(note).firstOrNull { !it.folder && it.name.startsWith("thumbnail") && it.mime=="image/png" }
        cover?.let { val file=File(dir,"thumbnail.png");s.download(it,file,2L*1024*1024);file }
    }
    /** Bulk library indexing must never authorize or fetch from Drive. */
    suspend fun cachedTitle(note:DriveItem):String = drive.starLocalAccess {account->withContext(Dispatchers.IO) {
        if(note.sourceTitle.isNotBlank())return@withContext note.sourceTitle
        val cached=File(root(account),"${safeId(note.id)}/title.txt")
        if(cached.isFile)return@withContext cached.readText()
        fun children(folder:DriveItem)=runCatching {drive.cachedList(folder)}.getOrElse {
            // A missing listing is incomplete knowledge, not an empty folder.
            decode(listing(account,folder).readText())
        }
        val pdfs=mutableListOf<String>()
        fun find(folder:DriveItem,depth:Int) {
            for(item in children(folder)) {
                if(item.pdf)pdfs+=item.name.substringBeforeLast('.')
                else if(item.folder&&depth<2)find(item,depth+1)
            }
        }
        children(note).filter {it.folder&&it.name in listOf("resource","template")}.forEach {find(it,0)}
        pdfs.distinct().singleOrNull().orEmpty().also {if(it.isNotBlank())write(cached,it)}
    }}
    suspend fun title(note:DriveItem):String = source {s->
        if(note.sourceTitle.isNotBlank())return@source note.sourceTitle
        val cached=File(root(s.account),"${safeId(note.id)}/title.txt")
        if(cached.isFile)return@source cached.readText()
        val pdfs=mutableListOf<String>()
        suspend fun find(folder:DriveItem,depth:Int) {
            for(item in s.list(folder)) {
                if(item.pdf)pdfs+=item.name.substringBeforeLast('.')
                else if(item.folder&&depth<2)find(item,depth+1)
            }
        }
        s.list(note).filter {it.folder&&it.name in listOf("resource","template")}.forEach {find(it,0)}
        pdfs.distinct().singleOrNull().orEmpty().also {if(it.isNotBlank())write(cached,it)}
    }
    suspend fun open(note:DriveItem,refresh:Boolean=false):StarOpen = source(refresh) { s -> withContext(Dispatchers.IO) {
        val account=s.account
        val dir=File(root(account),safeId(note.id));dir.mkdirs()
        val children=s.list(note)
        val inc=children.firstOrNull { it.folder && it.name=="inc" } ?: error("Unsupported StarNote backup. Export this note as PDF in StarNote.")
        val archives=mutableListOf<DriveItem>()
        for(month in s.list(inc).filter { it.folder }) archives += s.list(month).filter { it.name.endsWith(".zip") }
        require(archives.size <= 200 && archives.sumOf { it.size } <= 64L*1024*1024) { "This note is too large to open here. Export it as PDF from StarNote." }
        val raw=archives.sortedBy { it.name }.map { item ->
            val file=File(dir,"${safeId(item.id)}.zip");s.download(item,file,64L*1024*1024);file.readBytes()
        }
        val document=StarNoteFormat.read(raw)
        val resources=linkedMapOf<String,File>(); val templates=linkedMapOf<String,JSONObject>()
        suspend fun collect(folder:DriveItem,path:String,depth:Int) {
            require(depth<=3)
            for(item in s.list(folder)) {
                if(item.folder) collect(item,"$path${item.name}/",depth+1)
                else if(document.pages.any { it.resource=="$path${item.name}" || it.template==item.name }) {
                    val file=File(dir,"${safeId(item.id)}.asset");s.download(item,file,32L*1024*1024)
                    if(item.name.endsWith(".template_json")) templates[item.name]=JSONObject(file.readText())
                    else resources["$path${item.name}"]=file
                }
            }
        }
        children.filter { it.folder && it.name in listOf("resource","template") }.forEach { collect(it,"",0) }
        val local=runCatching { val j=JSONObject(draft(account,note.id).readText());StarEdits.read(j,note.id,j.optBoolean("backedUp")) }.getOrNull()
        val remote=if(s.offline)null else s.search("trashed = false and appProperties has { key='scholixStarSource' and value='${safeId(note.id)}' }")
            .maxByOrNull { it.modified }
        val edits=if(local!=null && !local.backedUp) local else if(remote!=null) {
            val file=File(dir,"remote.json");s.download(remote,file,4L*1024*1024)
            StarEdits.read(JSONObject(file.readText()),note.id,true)
        } else local ?: StarEdits(note.id,backedUp=true)
        val title=document.title.ifBlank {note.sourceTitle}.ifBlank {
            document.pages.map {it.resource.substringAfterLast('/')}.filter {it.endsWith(".pdf",true)}.distinct().singleOrNull()?.substringBeforeLast('.').orEmpty()
        }
        if(title.isNotBlank())write(File(dir,"title.txt"),title)
        StarOpen(account,document,edits,resources,templates,title.ifBlank {if(Regex("[a-fA-F0-9-]{32,36}").matches(note.name))"Untitled StarNote" else note.name},s.offline)
    } }
    suspend fun saveLocal(account:String, edits:StarEdits, acknowledge:Boolean = false) = drive.starLocalAccess { current -> withContext(Dispatchers.IO) {
        require(account==current) { "The connected Google account changed. Reopen this note." }
        val file=draft(account,edits.source);file.parentFile!!.mkdirs()
        // A slow cloud save must not replace a newer local draft with an older acknowledgement.
        if (acknowledge && file.exists() && JSONObject(file.readText()).optString("revision") != edits.revision)
            return@withContext
        val temp=File(file.parentFile,"edits.tmp");temp.writeText(edits.json().put("backedUp",edits.backedUp).toString())
        java.nio.file.Files.move(temp.toPath(),file.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    } }
    suspend fun localDraft(account:String,source:String):StarEdits? = drive.starLocalAccess {current->withContext(Dispatchers.IO) {
        require(current==account) {"The connected account changed."}
        val file=draft(account,source)
        if(!file.exists())null else JSONObject(file.readText()).let {StarEdits.read(it,source,it.optBoolean("backedUp"))}
    }}
    suspend fun backup(account:String, edits:StarEdits):StarEdits = drive.starAccess(edits=true) { api,token,current ->
        require(account==current) { "The connected Google account changed. Reopen this note." }
        safeId(edits.source); safeId(edits.revision)
        require(edits.json().toString().toByteArray().size <= 4*1024*1024) { "These edits are too large to back up" }
        val already=api.list(token,search="trashed = false and appProperties has { key='scholixStarRevision' and value='${edits.revision}' }")
        if(already.isEmpty()) {
            val folders=api.list(token,search="trashed = false and mimeType = '${DriveItem.FOLDER}' and appProperties has { key='scholixKind' and value='star-edits' }")
            val folder=folders.firstOrNull() ?: api.create(token,JSONObject().put("name","Scholix StarNote edits")
                .put("mimeType",DriveItem.FOLDER).put("appProperties",JSONObject().put("scholixKind","star-edits")))
            api.create(token,JSONObject().put("name","${edits.title.ifBlank { "StarNote" }.take(80)} - ${edits.revision}.json")
                .put("mimeType","application/json").put("parents",JSONArray().put(folder.id))
                .put("appProperties",JSONObject().put("scholixStarSource",edits.source).put("scholixStarRevision",edits.revision)),edits.json().toString())
        }
        edits.copy(backedUp=true)
    }
}
