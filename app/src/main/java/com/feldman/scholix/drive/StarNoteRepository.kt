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
    val resources: Map<String,File>, val templates: Map<String,JSONObject>)

class StarNoteRepository(private val context: Context, private val drive: DriveRepository) {
    private fun root(account: String) = File(context.noBackupFilesDir,"drive-materials/starnote/" +
        MessageDigest.getInstance("SHA-256").digest(account.toByteArray()).joinToString("") { "%02x".format(it) })
    private fun safeId(id:String):String { require(Regex("[A-Za-z0-9_-]+").matches(id)); return id }
    private fun draft(account:String,id:String)=File(root(account),"${safeId(id)}/edits.json")
    suspend fun notes(): List<DriveItem> = drive.starAccess { api, token, _ ->
        val roots=api.list(token,search="trashed = false and mimeType = '${DriveItem.FOLDER}' and name = 'StarNote'")
        val result=mutableListOf<DriveItem>()
        for(root in roots) {
            val sync=api.list(token,root).firstOrNull { it.folder && it.name == "sync" } ?: continue
            val version=api.list(token,sync).firstOrNull { it.folder && it.name == "v1" } ?: continue
            for(account in api.list(token,version).filter { it.folder }) {
                val docs=api.list(token,account).firstOrNull { it.folder && it.name == "document" } ?: continue
                result += api.list(token,docs).filter { it.folder }
            }
        }
        withContext(Dispatchers.IO) { result.distinctBy { it.id }.map { note ->
            val title=runCatching { JSONObject(draft(drive.state.value.account,note.id).readText()).optString("title") }.getOrDefault("")
            note.copy(name=title.ifBlank { note.name })
        } }
    }
    suspend fun cover(note:DriveItem):File? = drive.starAccess { api,token,account ->
        val dir=File(root(account),safeId(note.id));dir.mkdirs()
        val cover=api.list(token,note).firstOrNull { !it.folder && it.name.startsWith("thumbnail") && it.mime=="image/png" }
        cover?.let { val file=File(dir,"thumbnail.png");api.download(token,it,file,2L*1024*1024);file }
    }
    suspend fun open(note:DriveItem):StarOpen = drive.starAccess { api,token,account -> withContext(Dispatchers.IO) {
        val dir=File(root(account),safeId(note.id));dir.mkdirs()
        val children=api.list(token,note)
        val inc=children.firstOrNull { it.folder && it.name=="inc" } ?: error("Unsupported StarNote backup. Export this note as PDF in StarNote.")
        val archives=mutableListOf<DriveItem>()
        for(month in api.list(token,inc).filter { it.folder }) archives += api.list(token,month).filter { it.name.endsWith(".zip") }
        require(archives.size <= 200 && archives.sumOf { it.size } <= 64L*1024*1024) { "This note is too large to open here. Export it as PDF from StarNote." }
        val raw=archives.sortedBy { it.name }.map { item ->
            val file=File(dir,"${safeId(item.id)}.zip");api.download(token,item,file,64L*1024*1024);file.readBytes()
        }
        val document=StarNoteFormat.read(raw)
        val resources=linkedMapOf<String,File>(); val templates=linkedMapOf<String,JSONObject>()
        suspend fun collect(folder:DriveItem,path:String,depth:Int) {
            require(depth<=3)
            for(item in api.list(token,folder)) {
                if(item.folder) collect(item,"$path${item.name}/",depth+1)
                else if(document.pages.any { it.resource=="$path${item.name}" || it.template==item.name }) {
                    val file=File(dir,"${safeId(item.id)}.asset");api.download(token,item,file,32L*1024*1024)
                    if(item.name.endsWith(".template_json")) templates[item.name]=JSONObject(file.readText())
                    else resources["$path${item.name}"]=file
                }
            }
        }
        children.filter { it.folder && it.name in listOf("resource","template") }.forEach { collect(it,"",0) }
        val local=runCatching { val j=JSONObject(draft(account,note.id).readText());StarEdits.read(j,note.id,j.optBoolean("backedUp")) }.getOrNull()
        val remote=api.list(token,search="trashed = false and appProperties has { key='scholixStarSource' and value='${safeId(note.id)}' }")
            .maxByOrNull { it.modified }
        val edits=if(local!=null && !local.backedUp) local else if(remote!=null) {
            val file=File(dir,"remote.json");api.download(token,remote,file,4L*1024*1024)
            StarEdits.read(JSONObject(file.readText()),note.id,true)
        } else local ?: StarEdits(note.id,backedUp=true)
        StarOpen(account,document,edits,resources,templates)
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
