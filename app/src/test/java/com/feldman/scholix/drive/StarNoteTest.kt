package com.feldman.scholix.drive

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class StarNoteTest {
    private fun zip(name:String,data:ByteArray):ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use {it.putNextEntry(ZipEntry(name));it.write(data);it.closeEntry()}
    }.toByteArray()
    private fun rejected(block:()->Unit) {try {block();fail("Expected malformed data to be rejected")}catch(_:IllegalArgumentException){}}
    @Test fun wireDecoderRejectsTruncationAndOverflow() {
        rejected {StarNoteFormat.fields(byteArrayOf(10,5,1))}
        rejected {StarNoteFormat.fields(ByteArray(11){128.toByte()})}
        rejected {StarNoteFormat.fields(byteArrayOf(0))}
    }
    @Test fun archiveRejectsTraversalAndExpansionBomb() {
        rejected {StarNoteFormat.archive(zip("../../other-account",byteArrayOf(1)))}
        rejected {StarNoteFormat.archive(zip("huge",ByteArray(65*1024*1024)))}
    }
    @Test fun commentsAndInkRoundTripAndKeepPriorRevisionImmutable() {
        val original=StarEdits("source",annotations=listOf(StarAnnotation(page="page",kind="comment",text="Check proof",points=listOf(StarPoint(.2f,.4f)))))
        val backed=original.copy(backedUp=true)
        val edited=backed.changed(annotations=backed.annotations+StarAnnotation(page="page",kind="ink",points=listOf(StarPoint(.1f,.2f),StarPoint(.4f,.5f))))
        assertNotEquals(backed.revision,edited.revision);assertFalse(edited.backedUp)
        assertEquals(1,backed.annotations.size)
        assertEquals(edited,StarEdits.read(edited.json(),"source"))
    }
    @Test fun restoreRejectsOtherNotesFutureFormatsAndInvalidCoordinates() {
        val j=StarEdits("source").json()
        rejected {StarEdits.read(j,"other")}
        rejected {StarEdits.read(JSONObject(j.toString()).put("version",2),"source")}
        val bad=StarEdits("source",annotations=listOf(StarAnnotation(page="page",kind="ink",points=listOf(StarPoint(2f,.5f)))))
        rejected {StarEdits.read(bad.json(),"source")}
    }
    @Test fun realBackupsReconstructHandwritingAndPdfPagesWhenPrivateFixturesAreProvided() {
        val directory=System.getenv("SCHOLIX_STARNOTE_FIXTURES")
        assumeTrue("Private user fixtures are intentionally outside version control",directory!=null)
        val handwritten=StarNoteFormat.read(listOf(File(directory,"handwriting.zip").readBytes()))
        assertEquals(3,handwritten.pages.size)
        assertTrue(handwritten.pages.sumOf {it.strokes.size}>150)
        assertTrue(handwritten.pages.flatMap {it.strokes}.sumOf {it.points.size}>1000)
        val pdf=StarNoteFormat.read(listOf(File(directory,"planner.zip").readBytes()))
        assertEquals(3,pdf.pages.size)
        assertEquals(listOf(0,1,2),pdf.pages.map {it.resourcePage})
        assertTrue(pdf.pages.all {it.resource=="content/Planner.pdf" && it.kind=="import_pdf"})
    }
}
