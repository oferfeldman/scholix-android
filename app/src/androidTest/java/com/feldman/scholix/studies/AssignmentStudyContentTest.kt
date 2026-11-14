package com.feldman.scholix.studies

import androidx.test.platform.app.InstrumentationRegistry
import com.feldman.scholix.attendance.AttendancePortionsStorage
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class AssignmentStudyContentTest {
    @Test fun bundleContainsOriginalSourceAndCompleteQuestionCrops() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PDFBoxResourceLoader.init(context)
        val file = File.createTempFile("synthetic-questions-", ".pdf", context.cacheDir)
        var bundle: File? = null
        try {
            PDDocument().use { pdf ->
                repeat(2) { index ->
                    val page = PDPage().also { pdf.addPage(it) }
                    PDPageContentStream(pdf, page).use { content ->
                        content.beginText()
                        content.setFont(PDType1Font.HELVETICA, 14f)
                        content.newLineAtOffset(40f, 740f)
                        content.showText("Question ${index + 1}")
                        content.newLineAtOffset(0f, -30f)
                        content.showText("Synthetic prompt ${index + 1}")
                        content.endText()
                    }
                }
                pdf.save(file)
            }
            val assignment = StudyAssignment.create("https://synthetic.invalid", "qa", 1, "assign", "1", "Synthetic assignment")
            bundle = AssignmentStudyBundle.build(context, assignment, listOf(AssignmentStudySource("Questions.pdf", file)))
            ZipFile(bundle).use { zip ->
                val study = JSONObject(zip.getInputStream(zip.getEntry("study.json")).bufferedReader().readText()).getJSONObject("study")
                val questions = study.getJSONArray("questions")
                assertEquals(2, questions.length())
                assertEquals("1", questions.getJSONObject(0).getString("number"))
                assertEquals("2", questions.getJSONObject(1).getString("number"))
                assertEquals(1, study.getJSONArray("sources").length())
                assertArrayEquals(file.readBytes(), zip.getInputStream(zip.getEntry("sources/source-0.pdf")).readBytes())
                assertEquals(1, questions.getJSONObject(1).getJSONArray("sourceCrops").getJSONObject(0).getInt("pageIndex"))
            }
        } finally { file.delete(); bundle?.delete() }
    }

    @Test fun portionsEditsStayWithTheirProviderAccountAndSubject() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = AttendancePortionsStorage.getPortionsConfig(context, "qa-webtop", "Algebra")
        val otherSubject = AttendancePortionsStorage.getPortionsConfig(context, "qa-webtop", "Physics")
        val otherProvider = AttendancePortionsStorage.getPortionsConfig(context, "qa-mashov", "Algebra")
        try {
            AttendancePortionsStorage.savePortionsConfig(context, original.copy(bonusPoints = 9), "qa-webtop", "Algebra")
            assertEquals(9, AttendancePortionsStorage.getPortionsConfig(context, "qa-webtop", "Algebra").bonusPoints)
            assertEquals(otherSubject, AttendancePortionsStorage.getPortionsConfig(context, "qa-webtop", "Physics"))
            assertEquals(otherProvider, AttendancePortionsStorage.getPortionsConfig(context, "qa-mashov", "Algebra"))
        } finally { AttendancePortionsStorage.savePortionsConfig(context, original, "qa-webtop", "Algebra") }
    }
}
