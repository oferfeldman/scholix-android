package com.feldman.scholix.drive

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class MaterialsViewingTest {
    @Test fun filterSettingsKeepEveryFilterExactlyOnceAndRepairInvalidValues() {
        val input=listOf(MaterialFilter("star","bottom"),MaterialFilter("star","left"),MaterialFilter("root","right"),MaterialFilter("bad","top"),MaterialFilter("shared","bad"))
        val normalized=MaterialsFilters.normalize(input)
        assertEquals(MaterialsFilters.labels.keys,normalized.map {it.id}.toSet())
        assertEquals(5,normalized.size);assertEquals(MaterialFilter("star","bottom"),normalized.first())
        assertEquals(normalized,MaterialsFilters.read(MaterialsFilters.encode(normalized)))
        assertEquals(MaterialsFilters.defaults,MaterialsFilters.read("corrupt"))
    }
    @Test fun fitWidthUsesTheActualViewportAndHandlesInvalidDimensions() {
        assertEquals(1f,ReaderSizing.widthZoom(2100f,2970f,400f,800f),.001f)
        assertEquals(2.82857f,ReaderSizing.widthZoom(2100f,2970f,800f,400f),.001f)
        assertEquals(1f,ReaderSizing.widthZoom(0f,2970f,800f,400f),.001f)
    }
    @Test fun folderDownloadDeduplicatesFilesAndStopsShortcutCycles() = runTest {
        val root=DriveItem("root","StarNote",DriveItem.FOLDER)
        val child=DriveItem("child","sync",DriveItem.FOLDER)
        val alias=DriveItem("alias","Back","application/vnd.google-apps.shortcut",targetId="root",targetMime=DriveItem.FOLDER)
        val pdf=DriveItem("pdf","Lesson.pdf","application/pdf",size=100)
        val denied=DriveItem("denied","Private.pdf","application/pdf",canDownload=false)
        var calls=0
        val plan=DriveFolderPlanner.scan(root) {calls++;if(it.effectiveId=="root")listOf(child,pdf,denied) else listOf(alias,pdf)}
        assertEquals(2,calls);assertEquals(listOf(pdf),plan.files);assertEquals(1,plan.skipped)
        assertEquals(setOf("root","child"),plan.folders.keys)
    }
    @Test fun excessiveFolderDepthFailsInsteadOfPublishingPartialSuccess() = runTest {
        try {
            DriveFolderPlanner.scan(DriveItem("0","Deep",DriveItem.FOLDER)) {listOf(DriveItem((it.id.toInt()+1).toString(),"Next",DriveItem.FOLDER))}
            fail("Expected depth limit")
        }catch(_:IllegalArgumentException){}
    }
    @Test fun originalMetadataTitlesArePreferredAndIdentifiersAreNotNames() {
        assertEquals("Linear Algebra",StarNoteTitles.fromJson(org.json.JSONObject("{\"noteName\":\"Linear Algebra\"}")))
        assertEquals("",StarNoteTitles.fromJson(org.json.JSONObject("{\"name\":\"900a1c6a-d284-4b09-808a-897007118586\"}")))
    }
    @Test fun scanReportsProgressForEmptyFoldersAndSkipsCycles()=runTest {
        val root=DriveItem("root","Root",DriveItem.FOLDER)
        val empty=DriveItem("empty","Empty",DriveItem.FOLDER)
        val pdf=DriveItem("pdf","Note.pdf","application/pdf",size=100)
        val updates=mutableListOf<Pair<Int,Int>>()
        DriveFolderPlanner.scan(root,onScan={folders,files->updates+=folders to files}) {folder->
            if(folder.id=="root")listOf(empty,pdf,root) else emptyList()
        }
        assertEquals(listOf(1 to 1,2 to 1),updates)
    }
    @Test fun cancellingScanStopsBeforeTheNextFolder()=runTest {
        var calls=0
        try {
            DriveFolderPlanner.scan(DriveItem("root","Root",DriveItem.FOLDER),onScan={_,_->throw kotlinx.coroutines.CancellationException()}) {
                calls++;listOf(DriveItem("child","Child",DriveItem.FOLDER))
            }
            fail("Expected cancellation")
        }catch(_:kotlinx.coroutines.CancellationException){}
        assertEquals(1,calls)
    }
    @Test fun resumedDownloadsOnlyReserveSpaceForMissingAndChangedFiles() {
        val current=DriveItem("current","Saved.pdf","application/pdf",modified="same",size=100)
        val stale=current.copy(id="stale",modified="new",size=200)
        val missing=current.copy(id="missing",size=300)
        val saved=listOf(current,stale.copy(modified="old"),missing)
        assertEquals(0L,DriveFolderPlanner.remainingBytes(listOf(current),saved){true})
        assertEquals(500L,DriveFolderPlanner.remainingBytes(listOf(current,stale,missing),saved){it.id!="missing"})
    }
}
