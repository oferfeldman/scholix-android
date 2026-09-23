package com.feldman.scholix.api.platforms

import com.feldman.scholix.ui.components.MessageHtml
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.Base64

class WebtopMailboxTest {
    private fun transport(handler: (Request) -> Pair<Int, String>) = OkHttpClient.Builder().addInterceptor { chain ->
        val (code, body) = handler(chain.request())
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("fixture").body(body.toResponseBody()).build()
    }.build()

    @Test fun sendingUsesUnicodeRecipientsRichHtmlAndAllFiveAttachments() {
        val requests = mutableListOf<Request>()
        val client = WebtopMailbox({ "session=fixture" }, { error("Must not reauthenticate a send") }, transport {
            requests += it; 200 to """{"status":true,"data":true}"""
        })
        val draft = MailDraft(subject = "שלום", html = "<p dir=\"rtl\"><b>Bold</b> H<sub>2</sub>O x<sup>2</sup></p><table><tr><td>Cell</td></tr></table>",
            recipients = listOf(jsonObject("firstName" to "דנה", "lastName" to "מורה", "itemId" to "teacher-1", "type" to "Teacher")),
            attachments = (1..5).map { MailAttachment("file$it.txt", uri = "content://fixture/$it") }, replyType = 2, senderId = "original-sender", hideRecipients = true, blockReplies = true)
        client.send(draft) { "fixture bytes".toRequestBody() }
        assertEquals(1, requests.size)
        val request = requests.single()
        assertEquals(WebtopMailbox.API + "messageBox/SendMessage", request.url.toString())
        assertEquals("session=fixture", request.header("Cookie"))
        val parts = (request.body as MultipartBody).parts.associate { part ->
            val name = Regex("name=\"([^\"]+)\"").find(part.headers!!.get("Content-Disposition")!!)!!.groupValues[1]
            val buffer = Buffer(); part.body.writeTo(buffer); name to buffer.readUtf8()
        }
        assertEquals(draft.html, parts["content"])
        assertEquals("שלום", parts["Subject"])
        assertEquals("1", parts["IsReplay"])
        assertEquals("0", parts["IsForward"])
        assertEquals("original-sender", parts["SenderId"])
        assertEquals("1", parts["hiddenRecipients"])
        assertEquals("1", parts["blockMessage"])
        assertTrue(parts.keys.containsAll((1..5).map { "file$it" }))
        val recipient = JSONArray(String(Base64.getDecoder().decode(parts.getValue("recipients")), Charsets.UTF_8)).getJSONObject(0)
        assertEquals("דנה", recipient.getString("firstName"))
        assertEquals("Teacher", recipient.getString("mailType"))
    }

    @Test fun ambiguousSendFailureNeverRetriesOrRefreshesAuthentication() {
        var calls = 0
        val http = OkHttpClient.Builder().addInterceptor { calls++; throw IOException("timeout") }.build()
        val client = WebtopMailbox({ "fixture" }, { error("Unexpected refresh") }, http)
        assertThrows(IOException::class.java) { client.send(MailDraft(subject = "Test", recipients = listOf(JSONObject()))) { error("No attachment") } }
        assertEquals(1, calls)
    }

    @Test fun expiredReadRetriesOnceWithTheNewCookie() {
        var calls = 0; var refreshes = 0; var cookie = "old"
        val client = WebtopMailbox({ cookie }, { refreshes++; cookie = "new"; true }, transport { request ->
            calls++
            if (calls == 1) 401 to "{}" else { assertEquals("new", request.header("Cookie")); 200 to """{"status":true,"data":[]}""" }
        })
        assertTrue(client.messages(MailboxFolder.INBOX, 1).isEmpty())
        assertEquals(2, calls); assertEquals(1, refreshes)
    }

    @Test fun failedEnvelopeIsNotAnEmptyMailbox() {
        val client = WebtopMailbox({ "" }, { false }, transport { 200 to """{"status":false,"data":null}""" })
        assertThrows(IOException::class.java) { client.messages(MailboxFolder.INBOX, 1) }
    }

    @Test fun draftsPreserveReplyContextAndFormattingAcrossRestart() {
        val draft = MailDraft(subject = "Test", html = "<p><u>Underlined</u></p>", replyType = 2, senderId = "sender", draftId = "server-draft", scheduledAt = "10/10/2027 16:30:00",
            attachments = listOf(MailAttachment("image.png", "content://fixture/image", mimeType = "image/png", size = 1200)))
        assertEquals(draft, MailDraft.fromJson(JSONObject(draft.toJson().toString())))
    }

    @Test fun htmlKeepsFormattingTablesAndMathButRemovesActiveContent() {
        val html = MessageHtml.sanitize("""<script>alert(1)</script><p dir="rtl" style="font-size:20px;color:red;background:url(https://bad)"><b>B</b><i>I</i><u>U</u><sub>2</sub><sup>3</sup></p><table><tr><td>cell</td></tr></table><math><mfrac><mi>x</mi><mn>2</mn></mfrac></math><img src="https://example.com/a.png" onerror="alert(1)"><a href="javascript:alert(1)">link</a>""")
        for (tag in listOf("<b>", "<i>", "<u>", "<sub>", "<sup>", "<table>", "<math>", "<mfrac>")) assertTrue(tag, html.contains(tag))
        assertTrue(html.contains("dir=\"rtl\"")); assertTrue(html.contains("font-size:20px"))
        for (unsafe in listOf("<script", "onerror", "javascript:", "url(")) assertFalse(unsafe, html.contains(unsafe))
    }
}
