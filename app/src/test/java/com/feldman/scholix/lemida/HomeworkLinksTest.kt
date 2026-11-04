package com.feldman.scholix.lemida

import com.feldman.scholix.studies.StudyAssignment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeworkLinksTest {
    private val first = Homework("101:assign:9", 101, "Synthetic course", "Exercise 1", "assign", "https://lemida.biu.ac.il/mod/assign/view.php?id=9", "")
    private val second = Homework("101:quiz:10", 101, "Synthetic course", "Quiz 1", "quiz", "https://lemida.biu.ac.il/mod/quiz/view.php?id=10", "")

    @Test fun theKeyDocumentsSavedOpensThatHomework() {
        // The same identity StudyActions sends when the study is created.
        val saved = StudyAssignment.create(LemidaParser.BASE, "synthetic-user", 101, "assign", "9", "Exercise 1").key
        assertEquals(first, HomeworkLinks.match(saved, listOf(second, first), "synthetic-user"))
    }

    @Test fun renamedHomeworkStillMatchesButAnotherAccountDoesNot() {
        val key = first.studyKey("synthetic-user")!!
        assertEquals(first.copy(title = "Exercise 1 (renamed)"),
            HomeworkLinks.match(key, listOf(first.copy(title = "Exercise 1 (renamed)")), "synthetic-user"))
        assertNull(HomeworkLinks.match(key, listOf(first), "another-user"))
        assertNull(HomeworkLinks.match(key, listOf(first), ""))
    }
}
