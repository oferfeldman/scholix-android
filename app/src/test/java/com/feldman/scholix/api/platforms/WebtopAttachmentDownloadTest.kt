package com.feldman.scholix.api.platforms

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class WebtopAttachmentDownloadTest {
    @get:Rule val directory = TemporaryFolder()
    private val pdf = "%PDF-1.4\nsynthetic PDF bytes"
    private fun downloader(requests: MutableList<Request>, reply: (Request) -> Response): WebtopAttachmentDownload =
        WebtopAttachmentDownload({ "session=synthetic" }, OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            reply(chain.request())
        }.build())

    private fun response(request: Request, body: String = pdf, code: Int = 200, type: String = "application/pdf") =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Synthetic")
            .header("Content-Type", type).body(body.toResponseBody(type.toMediaType())).build()

    @Test fun pdfIsReadyToOpenAndRepeatedTapsUseTheAccountScopedCache() = runBlocking {
        val requests = mutableListOf<Request>()
        val download = downloader(requests) { response(it) }
        val first = download.download(directory.root, "account-a", "https://files.example/document", "grade.PDF")
        assertEquals("application/pdf", first.mimeType)
        assertEquals(pdf, first.file.readText())
        assertEquals(first, download.download(directory.root, "account-a", "https://files.example/document", "grade.PDF"))
        assertEquals(1, requests.size)
        val other = download.download(directory.root, "account-b", "https://files.example/document", "grade.PDF")
        assertNotEquals(first.file, other.file)
        assertEquals(2, requests.size)
        assertTrue(requests.all { it.header("Cookie") == null })
    }

    @Test fun redirectsDoNotLeakSessionCookiesToStorageHosts() = runBlocking {
        val requests = mutableListOf<Request>()
        val download = downloader(requests) {
            if (it.url.host == "webtopserver.smartschool.co.il") response(it, code = 302).newBuilder()
                .header("Location", "https://files.example/document").build() else response(it)
        }
        download.download(directory.root, "account", "https://webtopserver.smartschool.co.il/file", "file.pdf")
        assertEquals("session=synthetic", requests[0].header("Cookie"))
        assertNull(requests[1].header("Cookie"))
    }

    @Test fun invalidOrExpiredDownloadsAreNeverCachedAsPdfs() {
        for ((body, code, type) in listOf(Triple("<html>login</html>", 200, "text/html"),
            Triple("not a PDF", 200, "application/octet-stream"), Triple("", 200, "application/pdf"),
            Triple("expired", 403, "text/plain"))) {
            val requests = mutableListOf<Request>()
            val download = downloader(requests) { response(it, body, code, type) }
            repeat(2) {
                assertThrows(IOException::class.java) { runBlocking {
                    download.download(directory.root, "account", "https://files.example/document", "file.pdf")
                } }
            }
            assertEquals(2, requests.size)
            assertTrue(directory.root.walkTopDown().none { it.extension == "part" })
        }
    }

    @Test fun unsafePathsStayInsideTheAttachmentDirectory() = runBlocking {
        val download = downloader(mutableListOf()) { response(it) }
        val result = download.download(directory.root, "account", "https://files.example/file", "../../outside.pdf")
        assertEquals("outside.pdf", result.file.name)
        assertTrue(result.file.canonicalPath.startsWith(directory.root.canonicalPath + java.io.File.separator))
    }

    @Test fun insecureRedirectsAreRejectedBeforeAnotherRequest() {
        val requests = mutableListOf<Request>()
        val download = downloader(requests) { response(it, code = 302).newBuilder()
            .header("Location", "http://files.example/file").build() }
        assertThrows(IllegalArgumentException::class.java) { runBlocking {
            download.download(directory.root, "account", "https://files.example/file", "file.pdf")
        } }
        assertEquals(1, requests.size)
    }

    @Test fun leavingThePageCancelsTheNetworkCallAndNeverOpensOrCachesTheFile() = runBlocking {
        val entered = java.util.concurrent.CountDownLatch(1)
        val cancelled = java.util.concurrent.CountDownLatch(1)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            entered.countDown()
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            while (!chain.call().isCanceled() && System.nanoTime() < deadline) Thread.sleep(5)
            if (chain.call().isCanceled()) cancelled.countDown()
            throw IOException("Call cancelled")
        }.build()
        var opened = false
        val job = launch {
            WebtopAttachmentDownload({ "" }, client).download(directory.root, "account", "https://files.example/file", "file.pdf")
            opened = true
        }
        withContext(Dispatchers.IO) { assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
        job.cancelAndJoin()
        withContext(Dispatchers.IO) { assertTrue(cancelled.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
        assertFalse(opened)
        assertTrue(directory.root.walkTopDown().none { it.extension in listOf("pdf", "part") })
    }
}
