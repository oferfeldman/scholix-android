package com.feldman.scholix.drive

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class DriveDownloadSessionTest {
    @Test fun multipleDriveRequestsReuseAuthorization()=runTest {
        val server=MockWebServer();server.start()
        try {
            repeat(3){server.enqueue(MockResponse().setBody("{\"files\":[]}"))}
            val api=DriveApi(OkHttpClient(),server.url("/drive/v3/"));var authorizations=0
            val session=DriveDownloadSession(authorize={authorizations++;"token"},clear={fail("No rejection")})
            repeat(3){session.request {api.list(it)}}
            assertEquals(1,authorizations)
            repeat(3){assertEquals("Bearer token",server.takeRequest().getHeader("Authorization"))}
        }finally{server.shutdown()}
    }
    @Test fun rejectedTokenIsRenewedAndKeptForLaterRequests()=runTest {
        val server=MockWebServer();server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(401))
            repeat(2){server.enqueue(MockResponse().setBody("{\"files\":[]}"))}
            val api=DriveApi(OkHttpClient(),server.url("/drive/v3/"));var authorizations=0;val cleared=mutableListOf<String>()
            val session=DriveDownloadSession(authorize={"token-${++authorizations}"},clear={cleared+=it})
            repeat(2){session.request {api.list(it)}}
            assertEquals(2,authorizations);assertEquals(listOf("token-1"),cleared)
            assertEquals("Bearer token-1",server.takeRequest().getHeader("Authorization"))
            repeat(2){assertEquals("Bearer token-2",server.takeRequest().getHeader("Authorization"))}
        }finally{server.shutdown()}
    }
    @Test fun permissionErrorsAndRepeatedRejectionStopRequests()=runTest {
        for(status in listOf(403,401)) {
            val server=MockWebServer();server.start()
            try {
                repeat(2){server.enqueue(MockResponse().setResponseCode(status))}
                val api=DriveApi(OkHttpClient(),server.url("/drive/v3/"));var authorizations=0
                val session=DriveDownloadSession(authorize={authorizations++;"token"},clear={})
                try{session.request {api.list(it)};fail("Expected rejection")}catch(e:DriveHttpError){assertEquals(status,e.status)}
                assertEquals(if(status==401)2 else 1,server.requestCount)
                assertEquals(server.requestCount,authorizations)
            }finally{server.shutdown()}
        }
    }
}
