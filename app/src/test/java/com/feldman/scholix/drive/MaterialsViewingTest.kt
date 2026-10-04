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
}
