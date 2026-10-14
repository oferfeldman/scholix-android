package com.feldman.scholix.drive

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DriveApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: DriveApi
    private lateinit var dir: File
    private val pdf = DriveItem("abc", "../../notes.pdf", "application/pdf")
    @Before fun setup() {
        server = MockWebServer(); server.start()
        api = DriveApi(OkHttpClient.Builder().followRedirects(false).build(), server.url("/drive/v3/"))
        dir = Files.createTempDirectory("drive-test").toFile()
    }
    @After fun cleanup() { server.shutdown(); dir.deleteRecursively() }
    private fun page(files: String, next: String = "") = MockResponse().setBody(
        "{\"files\":[$files],\"nextPageToken\":\"$next\"}")
    private fun item(id: String, mime: String = "application/pdf", name: String = id) =
        "{\"id\":\"$id\",\"name\":\"$name\",\"mimeType\":\"$mime\"}"
    @Test fun paginatedListsIncludeEmptyPagesAndSortFoldersFirst() = runTest {
        server.enqueue(page(item("z"), "opaque-1"))
        server.enqueue(page("", "opaque-2"))
        server.enqueue(page(item("folder", DriveItem.FOLDER)))
        val items = api.list("test-token", DriveItem("parent", "Course", DriveItem.FOLDER, resourceKey = "key"))
        assertEquals(listOf("folder", "z"), items.map { it.id })
        val first = server.takeRequest()
        assertEquals("Bearer test-token", first.getHeader("Authorization"))
        assertEquals("parent/key", first.getHeader("X-Goog-Drive-Resource-Keys"))
        assertEquals("trashed = false and 'parent' in parents", first.requestUrl!!.queryParameter("q"))
        assertEquals("true", first.requestUrl!!.queryParameter("includeItemsFromAllDrives"))
        assertEquals("opaque-1", server.takeRequest().requestUrl!!.queryParameter("pageToken"))
        assertEquals("opaque-2", server.takeRequest().requestUrl!!.queryParameter("pageToken"))
    }
    @Test fun secondPageFailureDoesNotReturnPartialData() = runTest {
        server.enqueue(page(item("a"), "next")); server.enqueue(MockResponse().setResponseCode(503))
        try { api.list("test-token"); fail("Expected failure") } catch (e: DriveHttpError) { assertEquals(503, e.status) }
    }
    @Test fun repeatedPageTokenStopsLoop() = runTest {
        server.enqueue(page(item("a"), "same")); server.enqueue(page(item("b"), "same"))
        try { api.list("test-token"); fail("Expected failure") } catch (e: java.io.IOException) { assertTrue(e.message!!.contains("repeated")) }
    }
    @Test fun sharedListingUsesSharedWithMe() = runTest {
        server.enqueue(page("")); api.list("test-token", shared = true)
        assertEquals("trashed = false and sharedWithMe = true", server.takeRequest().requestUrl!!.queryParameter("q"))
    }
    @Test fun failedDownloadPreservesExistingOfflineCopy() = runTest {
        val destination = File(dir, "notes.pdf").apply { writeText("old") }
        server.enqueue(MockResponse().setResponseCode(403))
        try { api.download("test-token", pdf, destination); fail() } catch (_: DriveHttpError) { }
        assertEquals("old", destination.readText())
        assertEquals(listOf("notes.pdf"), dir.list()!!.toList())
    }
    @Test fun oversizedStreamPreservesOldFileAndCleansTemporaryFile() = runTest {
        val destination = File(dir, "notes.pdf").apply { writeText("old") }
        server.enqueue(MockResponse().setChunkedBody("1234567890", 2))
        try { api.download("test-token", pdf, destination, maxBytes = 5); fail() } catch (_: java.io.IOException) { }
        assertEquals("old", destination.readText()); assertEquals(1, dir.list()!!.size)
    }
    @Test fun nativeDocumentExportsPdfInsteadOfRequestingMedia() = runTest {
        server.enqueue(MockResponse().setBody("pdf-bytes"))
        api.download("test-token", pdf.copy(mime = "application/vnd.google-apps.document"), File(dir, "export.pdf"))
        val request = server.takeRequest()
        assertEquals("/drive/v3/files/abc/export", request.requestUrl!!.encodedPath)
        assertEquals("application/pdf", request.requestUrl!!.queryParameter("mimeType"))
        assertEquals("pdf-bytes", File(dir, "export.pdf").readText())
    }
    @Test fun shortcutTargetsCarryTheirOwnResourceKey() = runTest {
        val shortcut = DriveItem.parse(JSONObject("""{"id":"shortcut","name":"Course","mimeType":"application/vnd.google-apps.shortcut","shortcutDetails":{"targetId":"target","targetMimeType":"application/vnd.google-apps.folder","targetResourceKey":"target-key"}}"""))
        assertTrue(shortcut.folder)
        server.enqueue(page("")); api.list("test-token", shortcut)
        val request = server.takeRequest()
        assertEquals("target/target-key", request.getHeader("X-Goog-Drive-Resource-Keys"))
        assertTrue(request.requestUrl!!.queryParameter("q")!!.contains("'target'"))
    }
    @Test fun restrictedDownloadsNeverMakeRequest() = runTest {
        try { api.download("test-token", pdf.copy(canDownload = false), File(dir, "file")); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(0, server.requestCount)
    }
    @Test fun redirectsDoNotForwardBearerCredentials() = runTest {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", server.url("/other")))
        try { api.list("test-token"); fail() } catch (e: DriveHttpError) { assertEquals(302, e.status) }
        assertEquals(1, server.requestCount)
    }
    @Test fun incompleteSearchIsNotPublishedAsComplete() = runTest {
        server.enqueue(MockResponse().setBody("{\"files\":[],\"incompleteSearch\":true}"))
        try { api.list("test-token"); fail() } catch (e: java.io.IOException) { assertTrue(e.message!!.contains("incomplete")) }
    }
    @Test fun cancelledDownloadStopsPromptlyAndKeepsTheSavedCopy() = kotlinx.coroutines.runBlocking {
        val destination=File(dir,"notes.pdf").apply {writeText("saved copy")}
        server.enqueue(MockResponse().setBody("x".repeat(10000)).throttleBody(1,100,java.util.concurrent.TimeUnit.MILLISECONDS))
        val operation=kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            api.download("test-token",pdf,destination)
        }
        assertNotNull(server.takeRequest(3,java.util.concurrent.TimeUnit.SECONDS))
        operation.cancel()
        kotlinx.coroutines.withTimeout(3000){operation.join()}
        assertEquals("saved copy",destination.readText())
        assertEquals(listOf("notes.pdf"),dir.list()!!.toList())
    }
}
