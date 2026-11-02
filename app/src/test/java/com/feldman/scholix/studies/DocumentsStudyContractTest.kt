package com.feldman.scholix.studies

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class DocumentsStudyContractTest {
    private fun assignment(account: String = "synthetic-account", title: String = "Homework") =
        StudyAssignment.create("https://university.invalid", account, 42, "assign", "123", title)

    @Test fun identityUsesAccountCourseActivityAndRetainsHubEncoding() {
        val target = assignment()
        assertEquals(target.key, assignment(title = "Renamed").key)
        assertNotEquals(target.key, assignment(account = "other-account").key)
        val canonical = JSONArray(listOf("https://university.invalid", "synthetic-account", 42, "assign", "123"))
            .toString().replace("\\/", "/")
        val expected = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals(expected, target.key)
        assertNotEquals(target.key, StudyAssignment.create("https://university.invalid", "synthetic-account", 42, "assign", "124", "Homework").key)
    }

    private fun receipt(request: String) = JSONObject().put("version", 1)
        .put("requestId", JSONObject(request).getString("requestId"))
        .put("studyId", "synthetic-study").put("title", "Study").put("revision", 9)

    @Test fun staleOrMalformedReceiptsCannotBindAnotherAssignment() {
        val request = DocumentsStudyContract.request(assignment())
        listOf(receipt(request).put("requestId", "different-request"), receipt(request).put("studyId", "../other"),
            receipt(request).put("version", 2), receipt(request).put("revision", -1)).forEach {
            assertTrue(runCatching { DocumentsStudyContract.receipt(it.toString(), request, "com.example.documents") }.isFailure)
        }
    }

    @Test fun generalAndAssignmentReferencesRoundTripWithIndependentPublicationVersions() {
        listOf(null, assignment()).forEach { assignment ->
            val request = DocumentsStudyContract.request(assignment)
            val local = DocumentsStudyContract.receipt(receipt(request).toString(), request, "com.example.documents")
            assertEquals(assignment?.key, local.assignmentKey)
            val published = local.copy(publication = StudyPublication("shared-study-1", "server-v2", "https://studies.invalid/example.fstudy"),
                importedPublicationRevision = "server-v1")
            assertEquals(published, StudyReference.fromJson(JSONObject(published.json().toString())))
            assertEquals(9L, published.localRevision)
            assertEquals(local.studyId, published.studyId)
            assertEquals("server-v1", published.importedPublicationRevision)
        }
    }

    @Test fun aPublishedCatalogEntryRetainsItsIdentityWhenAttachedToDocuments() {
        val published = StudyReference("published:synthetic-1", "Shared study", publication =
            StudyPublication("synthetic-1", "v2", "https://studies.invalid/synthetic.fstudy"))
        val request = DocumentsStudyContract.request(null, published)
        val local = DocumentsStudyContract.receipt(receipt(request).toString(), request, "com.example.documents")
        assertEquals(published.id, local.id)
        assertTrue(local.isLocal)
        assertNull(local.assignmentKey)
    }
}
