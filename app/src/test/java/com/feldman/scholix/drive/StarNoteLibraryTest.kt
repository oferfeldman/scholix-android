package com.feldman.scholix.drive

import org.junit.Assert.*
import org.junit.Test

class StarNoteLibraryTest {
    private val identifier="900a1c6a-d284-4b09-808a-897007118586"
    private fun note(id:String,name:String=identifier,modified:String="",native:String="")=
        DriveItem(id,name,DriveItem.FOLDER,modified=modified,sourceTitle=native)

    @Test fun searchFindsResolvedNamesEvenWhenStorageNamesAreIdentifiers() {
        val notes=listOf(note("algebra"),note("planner"))
        val titles=mapOf("algebra" to "Linear Algebra", "planner" to "Weekly Planner")
        assertEquals(listOf("algebra"),StarNoteLibrary.visible(notes,titles,"  LINEAR   algebra  ",StarNoteSort.Recent).map {it.id})
        assertEquals(emptyList<DriveItem>(),StarNoteLibrary.visible(notes,titles,identifier,StarNoteSort.Recent))
    }

    @Test fun localNamesTakePrecedenceButTheOriginalNameRemainsSearchable() {
        val renamed=note("renamed","Exam revision",native="Linear Algebra")
        assertEquals("Exam revision",StarNoteLibrary.title(renamed,"Linear Algebra"))
        for(query in listOf("Exam revision","Linear Algebra"))
            assertEquals(listOf(renamed),StarNoteLibrary.visible(listOf(renamed),emptyMap(),query,StarNoteSort.Title))
    }

    @Test fun missingTitlesAreExplicitAndNeverDerivedFromStorageIds() {
        val unnamed=note("unnamed")
        assertFalse(StarNoteLibrary.hasTitle(unnamed))
        assertEquals(StarNoteLibrary.UNTITLED,StarNoteLibrary.title(unnamed))
        assertEquals(listOf(unnamed),StarNoteLibrary.visible(listOf(unnamed),emptyMap(),"untitled",StarNoteSort.Title))
    }

    @Test fun nameOrderPlacesUntitledNotesAfterNamedNotesAndStabilizesDuplicates() {
        val notes=listOf(note("untitled"),note("b","Beta"),note("second","Algebra"),note("first","Algebra"))
        assertEquals(listOf("first","second","b","untitled"),
            StarNoteLibrary.visible(notes,emptyMap(),"",StarNoteSort.Title).map {it.id})
    }

    @Test fun recentOrderParsesTimestampsAndPlacesMissingOrInvalidTimesLast() {
        val notes=listOf(note("invalid","Invalid","not a date"),note("old","Old","2026-10-04T10:00:00Z"),
            note("new","New","2026-10-04T13:00:00+02:00"),note("missing","Missing"))
        assertEquals(listOf("new","old","invalid","missing"),
            StarNoteLibrary.visible(notes,emptyMap(),"",StarNoteSort.Recent).map {it.id})
    }

    @Test fun searchSupportsHebrewTitlesAndReturnsEmptyForNoMatch() {
        val hebrew=note("hebrew","אלגברה לינארית")
        assertEquals(listOf(hebrew),StarNoteLibrary.visible(listOf(hebrew),emptyMap(),"לינארית אלגברה",StarNoteSort.Title))
        assertTrue(StarNoteLibrary.visible(listOf(hebrew),emptyMap(),"calculus",StarNoteSort.Title).isEmpty())
    }
}
