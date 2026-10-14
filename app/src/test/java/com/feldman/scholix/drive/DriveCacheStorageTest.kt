package com.feldman.scholix.drive

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DriveCacheStorageTest {
    @Test fun cleanupKeepsUnsentEditsNamesAndFolderMetadata() {
        val root=Files.createTempDirectory("note-cache").toFile()
        try {
            val protected=listOf("note/edits.json","note/edits.tmp","note/title.txt","lists/folder.json","roots.json")
            val cached=listOf("note/archive.zip","note/archive.zip.version","note/pdf.asset","note/thumbnail.png","note/remote.json","note/drive-123.part")
            (protected+cached).forEach {File(root,it).apply {parentFile!!.mkdirs();writeText("keep or cache")}}
            DriveCacheStorage.remove(DriveCacheStorage.files(root).filter {DriveCacheStorage.noteCache(it)})
            assertTrue(protected.all {File(root,it).isFile})
            assertTrue(cached.none {File(root,it).exists()})
        }finally{root.deleteRecursively()}
    }
    @Test fun budgetEvictsOldestOriginalCopiesAndTheirVersionMarkers() {
        val root=Files.createTempDirectory("note-cache").toFile()
        try {
            val old=File(root,"old.asset").apply {writeText("12345");setLastModified(1000)}
            val version=File(root,"old.asset.version").apply {writeText("v1:5")}
            val recent=File(root,"new.asset").apply {writeText("67890");setLastModified(2000)}
            val edits=File(root,"edits.json").apply {writeText("unsent annotations")}
            DriveCacheStorage.trimNotes(root,5)
            assertFalse(old.exists());assertFalse(version.exists());assertTrue(recent.exists());assertTrue(edits.exists())
        }finally{root.deleteRecursively()}
    }
    @Test fun downloadedFileIsReusedAndLegacyDuplicateRemoved() {
        val root=Files.createTempDirectory("note-cache").toFile()
        try {
            val saved=File(root,"saved.bin").apply {writeText("original")}
            val duplicate=File(root,"resource.asset").apply {writeText("original")}
            val marker=File(root,"resource.asset.version").apply {writeText("v1:8")}
            assertEquals(saved,DriveCacheStorage.useDownload(saved,duplicate,8))
            assertFalse(duplicate.exists());assertFalse(marker.exists());assertEquals("original",saved.readText())
            val edits=File(root,"edits.json").apply {writeText("unsent")}
            DriveCacheStorage.useDownload(saved,edits,8)
            assertEquals("unsent",edits.readText())
        }finally{root.deleteRecursively()}
    }
    @Test fun invalidCanonicalFileNeverDeletesTheExistingPreview() {
        val root=Files.createTempDirectory("note-cache").toFile()
        try {
            val existing=File(root,"resource.asset").apply {writeText("only copy")}
            try {DriveCacheStorage.useDownload(File(root,"missing.bin"),existing,100);fail()}catch(_:IllegalArgumentException){}
            assertEquals("only copy",existing.readText())
        }finally{root.deleteRecursively()}
    }
}
