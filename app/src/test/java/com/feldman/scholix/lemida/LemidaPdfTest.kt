package com.feldman.scholix.lemida

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException

class LemidaPdfTest {
    private fun page(body: String) = "<body><main id='region-main'>$body</main></body>"
    @Test fun linkedAndEmbeddedPdfsSurviveParsingAndCacheRestart() {
        val url = "${LemidaParser.BASE}/pluginfile.php/1/mod_assign/intro/תרגיל.PDF"
        val detail = LemidaParser.detail(page("""
            <div id='intro'><a href='$url'>Questions</a><a href='$url#page=2'>Duplicate</a></div>
            <object type='application/pdf' data='/pluginfile.php/2/file' title='Solutions'></object>
            <iframe src='/pluginfile.php/3/example.pdf'></iframe>
        """))
        assertEquals(listOf("Questions", "Solutions", "example.pdf"), detail.pdfs.map { it.name })
        assertEquals(url, detail.pdfs.first().url)
        assertEquals(detail.pdfs, HomeworkDetail.fromJson(detail.json().toString()).pdfs)
        assertTrue(HomeworkDetail.fromJson(detail.json().apply { remove("pdfs") }.toString()).pdfs.isEmpty())
    }
    @Test fun authenticatedResourceLinksCanIdentifyPdfWithoutFilenameInUrl() {
        val detail = LemidaParser.detail(page("<a href='/mod/resource/view.php?id=42'>worksheet.pdf</a>"))
        assertEquals("${LemidaParser.BASE}/mod/resource/view.php?id=42", detail.pdfs.single().url)
    }
    @Test fun nonPdfExternalLoginAndUnsafeUrlsAreNotOpenableAttachments() {
        val urls = listOf("https://evil.example/pluginfile.php/test.pdf", "//evil.example/pluginfile.php/test.pdf",
            "http://lemida.biu.ac.il/pluginfile.php/test.pdf", "https://name@lemida.biu.ac.il/pluginfile.php/test.pdf",
            "https://lemida.biu.ac.il:8080/pluginfile.php/test.pdf", "/login/logout.php?file=test.pdf", "javascript:alert(1)",
            "https://lemida.biu.ac.il/pluginfile.php/%2e%2e/login/logout.php", "https://lemida.biu.ac.il/pluginfile.php/../login/logout.php")
        val detail = LemidaParser.detail(page(urls.joinToString("") { "<a type='application/pdf' href='$it'>file.pdf</a>" } +
            "<a href='/pluginfile.php/1/notes.docx'>Word notes</a>"))
        assertTrue(detail.pdfs.isEmpty())
    }
    @Test fun htmlAndIncompleteDownloadsCannotBePassedToPdfRenderer() {
        val file = File.createTempFile("lemida-pdf-test", ".pdf")
        try {
            file.writeText("<html>Please sign in</html>")
            assertThrows(IOException::class.java) { LemidaPdf.requireFile(file) }
            file.writeText("%PDF-1.7\nsynthetic")
            LemidaPdf.requireFile(file)
            file.writeText("%PD")
            assertThrows(IOException::class.java) { LemidaPdf.requireFile(file) }
        } finally { file.delete() }
    }
}
