package com.feldman.scholix.lemida

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic course modules shaped like Moodle's core_courseformat_get_state "cm" entries. */
class LemidaActivitiesTest {
    private fun module(type: String, id: Int, name: String) =
        """{"name":"$name","url":"https://lemida.biu.ac.il/mod/$type/view.php?id=$id"}"""

    private fun state(vararg modules: String) = """{"cm":[${modules.joinToString(",")}]}"""

    @Test fun courseWorkBeyondAssignmentsAndQuizzesIsListed() {
        val items = LemidaParser.stateHomework(state(
            module("assign", 1, "Exercise 1"),
            module("quiz", 2, "בוחן אמצע"),
            module("vpl", 3, "Linked list in C"),
            module("lti", 4, "Gradescope submission"),
            module("lesson", 5, "Pointers walkthrough"),
        ), 77, "Data structures")
        assertEquals(listOf("77:assign:1", "77:quiz:2", "77:vpl:3", "77:lti:4", "77:lesson:5"), items.map { it.id })
        assertEquals(listOf("Assignments", "Quizzes", "Programming exercises", "External tools", "Lessons"),
            items.map { LemidaActivities.group(it.type) })
    }

    @Test fun postedExerciseSheetsCountButLectureMaterialAndSolutionsDoNot() {
        val items = LemidaParser.stateHomework(state(
            module("resource", 10, "תרגיל 3"),
            module("resource", 11, "Ex2 - recursion"),
            module("url", 12, "HW 4 submission system"),
            module("folder", 13, "מטלות"),
            module("resource", 14, "הרצאה 2 - שקפים"),
            module("resource", 15, "פתרון תרגיל 3"),
            module("resource", 16, "Lecture 5 notes"),
            module("page", 17, "תרגול 4"),
            module("forum", 18, "Exercise questions forum"),
            module("label", 19, "Exercise 1"),
        ), 77, "Data structures")
        assertEquals(listOf("77:resource:10", "77:resource:11", "77:url:12", "77:folder:13"), items.map { it.id })
        assertTrue(items.all { LemidaActivities.group(it.type) == "Exercise files" })
    }

    @Test fun existingIdentitiesAreUnchanged() {
        val item = LemidaParser.stateHomework(state(module("assign", 900, "Exercise")), 123, "Math").single()
        assertEquals("123:assign:900", item.id)
    }

    @Test fun filesAndLinksOpenOnLemidaInsteadOfAnInstructionsPage() {
        assertTrue(LemidaActivities.hasDetailPage("vpl"))
        assertFalse(LemidaActivities.hasDetailPage("resource"))
        assertEquals("Hand in on Lemida", LemidaActivities.openLabel("vpl"))
        assertEquals("Open quiz on Lemida", LemidaActivities.openLabel("quiz"))
        assertEquals("Open on Lemida", LemidaActivities.openLabel("url"))
    }

    @Test fun exerciseTitlesAcceptHebrewPrefixesAndRejectRecitations() {
        assertTrue(LemidaActivities.isExerciseTitle("לתרגיל הבית"))
        assertTrue(LemidaActivities.isExerciseTitle("המטלה השנייה"))
        assertTrue(LemidaActivities.isExerciseTitle("שיעורי בית 2"))
        assertFalse(LemidaActivities.isExerciseTitle("תרגול 3"))
        assertFalse(LemidaActivities.isExerciseTitle("Syllabus"))
        assertFalse(LemidaActivities.isExerciseTitle("HW1 solutions"))
    }
}
