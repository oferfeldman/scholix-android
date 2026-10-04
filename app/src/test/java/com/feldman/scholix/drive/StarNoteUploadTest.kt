package com.feldman.scholix.drive

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StarNoteUploadTest {
    @Test fun savesCreateSeparateJsonFilesRatherThanPatchStarNoteBackups() = runTest {
        val server=MockWebServer();server.start()
        try {
            server.enqueue(MockResponse().setBody("{\"id\":\"revision\",\"name\":\"edits.json\",\"mimeType\":\"application/json\"}"))
            val api=DriveApi(OkHttpClient.Builder().followRedirects(false).build(),server.url("/drive/v3/"))
            api.create("token",JSONObject().put("name","edits.json").put("mimeType","application/json"),"{\"comment\":\"proof\"}")
            val request=server.takeRequest()
            assertEquals("POST",request.method)
            assertEquals("/upload/drive/v3/files",request.requestUrl!!.encodedPath)
            assertEquals("multipart",request.requestUrl!!.queryParameter("uploadType"))
            assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/related"))
            assertEquals("Bearer token",request.getHeader("Authorization"))
            val body=request.body.readUtf8();assertTrue(body.contains("edits.json"));assertTrue(body.contains("proof"))
        } finally {server.shutdown()}
    }
}
