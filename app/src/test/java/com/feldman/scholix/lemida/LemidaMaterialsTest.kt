package com.feldman.scholix.lemida

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic course state shaped like Moodle's core_courseformat_get_state "section" and "cm" entries. */
class LemidaMaterialsTest {
    private fun module(cm: Int, type: String, id: Int, name: String, section: Int, visible: Boolean = true) =
        """{"id":"$cm","name":"$name","sectionid":"$section","uservisible":$visible,
            "url":"https://lemida.biu.ac.il/mod/$type/view.php?id=$id"}"""

    private fun section(id: Int, title: String, vararg cms: Int) =
        """{"id":"$id","title":"$title","cmlist":[${cms.joinToString(",") { "\"$it\"" }}]}"""

    private val state = """{
        "section":[${section(1, "כללי", 10)},${section(2, "הרצאות", 21, 20)},${section(3, "תרגולים", 30, 31, 32)},${section(4, "לוח", 40)}],
        "cm":[
            ${module(10, "forum", 100, "הודעות", 1)},
            ${module(20, "resource", 200, "הרצאה 1", 2)},
            ${module(21, "resource", 201, "סילבוס", 2)},
            ${module(30, "resource", 300, "תרגול 1 - קינמטיקה", 3)},
            ${module(31, "assign", 301, "תרגיל בית 1", 3)},
            ${module(32, "folder", 302, "תרגולים נוספים", 3, visible = false)},
            ${module(40, "url", 400, "לוח מהשיעור", 4)}
        ]}"""

    @Test fun recitationsAndWhiteboardKeepTheirSectionsInLemidaOrder() {
        val items = LemidaMaterial.fromState(state, 77, "מכניקה")
        assertEquals(listOf("סילבוס", "הרצאה 1", "תרגול 1 - קינמטיקה", "לוח מהשיעור"), items.map { it.title })
        assertEquals(listOf("הרצאות", "הרצאות", "תרגולים", "לוח"), items.map { it.section })
        assertEquals("77:resource:300", items[2].id)
        assertEquals("url", items[3].type)
    }

    @Test fun workAndHiddenModulesAreNotMaterial() {
        val ids = LemidaMaterial.fromState(state, 77, "מכניקה").map { it.id }
        assertTrue(ids.none { it.contains(":assign:") || it.contains(":forum:") || it.endsWith(":302") })
    }

    @Test fun materialsSurviveTheirStoredForm() {
        val items = LemidaMaterial.fromState(state, 77, "מכניקה")
        assertEquals(items, LemidaMaterial.decode(LemidaMaterial.encode(items)))
        assertEquals(emptyList<LemidaMaterial>(), LemidaMaterial.decode("not json"))
    }
}
