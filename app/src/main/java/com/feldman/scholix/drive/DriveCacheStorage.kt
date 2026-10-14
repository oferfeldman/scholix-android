package com.feldman.scholix.drive

import java.io.File
import java.io.IOException

data class DriveStorageUsage(val downloads:Long=0,val temporary:Long=0,val edits:Long=0)

/** Only original-file caches are eligible; drafts, titles and folder metadata are retained. */
object DriveCacheStorage {
    const val PREVIEW_BUDGET=128L*1024*1024
    fun useDownload(saved:File,oldPreview:File,maxBytes:Long):File {
        require(saved.isFile&&saved.length()<=maxBytes)
        if(saved.canonicalFile!=oldPreview.canonicalFile&&noteCache(oldPreview)) {
            if(!oldPreview.exists()||oldPreview.delete())File(oldPreview.parentFile,oldPreview.name+".version").delete()
        }
        return saved
    }
    fun noteCache(file:File):Boolean {
        val name=file.name.removeSuffix(".version")
        return name=="thumbnail.png" || name=="remote.json" || name.endsWith(".asset") ||
            name.endsWith(".zip") || (name.startsWith("drive-")&&name.endsWith(".part"))
    }
    fun files(root:File):List<File> = if(root.isDirectory)root.walkTopDown().filter {it.isFile}.toList() else emptyList()
    fun remove(files:List<File>):Long {
        var freed=0L
        for(file in files) {
            val size=file.length()
            if(file.exists()&&!file.delete())throw IOException("Some cached files could not be removed. Try again.")
            freed+=size
        }
        return freed
    }
    fun trimNotes(root:File,budget:Long=PREVIEW_BUDGET):Long {
        require(budget>=0)
        val candidates=files(root).filter {noteCache(it)&&!it.name.endsWith(".version")}.sortedBy {it.lastModified()}
        var total=candidates.sumOf {it.length()};var freed=0L
        for(file in candidates) {
            if(total<=budget)break
            val size=file.length()
            freed+=remove(listOf(file,File(file.parentFile,file.name+".version")))
            total-=size
        }
        return freed
    }
}
