package com.feldman.scholix.studies

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class StudyCatalogTest {
    @Test fun reopenPreservesPublicationAndRejectsAStaleLocalRevision() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val catalog = StudyCatalog(context)
        val id = "synthetic-${UUID.randomUUID()}"
        val publication = StudyPublication("shared-synthetic", "published-v2", "https://studies.invalid/example.fstudy")
        val first = StudyReference(id, "Synthetic study", documentsPackage = "com.example.documents",
            studyId = "synthetic-study", localRevision = 3, publication = publication, importedPublicationRevision = "published-v1")
        try {
            catalog.save(first)
            catalog.save(first.copy(localRevision = 4, publication = null, importedPublicationRevision = null))
            val reopened = StudyCatalog(context).entries().single { it.id == id }
            assertEquals(publication, reopened.publication)
            assertEquals("published-v1", reopened.importedPublicationRevision)
            assertEquals(4L, reopened.localRevision)
            assertTrue(runCatching { catalog.save(first.copy(localRevision = 2)) }.isFailure)
            assertEquals(reopened, catalog.entries().single { it.id == id })
        } finally {
            context.getSharedPreferences("study-catalog-v1", 0).edit().remove(id).commit()
        }
    }
}
