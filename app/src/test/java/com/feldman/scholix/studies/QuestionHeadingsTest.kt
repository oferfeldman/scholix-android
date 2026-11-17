package com.feldman.scholix.studies

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class QuestionHeadingsTest {
    /** Words of one line laid out right to left from the right margin, the way a Hebrew PDF places them. */
    private fun rtl(page: Int, top: Float, vararg words: String): List<PdfWord> {
        var right = .95f
        return words.map { text ->
            val width = .012f * text.length + .01f
            PdfWord(page, top, top + .015f, right - width, right, text).also { right -= width + .01f }
        }.shuffled(java.util.Random(top.toBits().toLong()))
    }

    @Test fun wordByWordHebrewSheetFindsExercisesBonusPartAndIgnoresTitleAndSubItems() {
        val words = rtl(0, .13f, "תרגיל", "בית", "5", "באינפי", "1") +
            rtl(0, .28f, "תרגיל", "מצאו", "את", "הגבולות") +
            // The number beside a tall bracket: same top as its line, lower bottom.
            listOf(PdfWord(0, .28f, .31f, .30f, .32f, ".)1")) +
            rtl(0, .31f, ".1", "an", "=") +
            rtl(0, .43f, "תרגיל", ".2", "תהי", "סדרה") +
            rtl(0, .46f, ".1", "הוכיחו", "כי") +
            rtl(0, .53f, "תרגיל", ".3", "הוכיחו") +
            rtl(0, .60f, "תרגיל", ".4") +
            rtl(1, .08f, "תרגילי", "בונוס") +
            rtl(1, .11f, "תרגיל", ".5", "תהי", "סדרה") +
            rtl(1, .14f, "תרגיל", ".6", "מטרת", "התרגיל")
        val found = QuestionHeadings.find(words)
        assertEquals(listOf("1", "2", "3", "4", "5", "6"), found.questions.map { it.number })
        assertEquals(listOf(null, null, null, null, "תרגילי בונוס", "תרגילי בונוס"), found.questions.map { it.section })
        assertEquals(listOf(1 to .08f), found.sections.map { it.page to it.top })
    }

    @Test fun wholeLineHeadingsKeepTheirStarAndSkipReferencesInSentences() {
        fun line(page: Int, top: Float, text: String) = PdfWord(page, top, top + .015f, .5f, .88f, text)
        val words = listOf(line(0, .12f, "תרגיל בית 3  – תנועה יחסית"), line(0, .2f, "שאלה 1 :"),
            line(0, .3f, "ענו על השאלה בעזרת שאלה 2 מהתרגול"), line(0, .64f, "שאלה 2 :"),
            line(1, .38f, "* שאלה 4:"), line(1, .1f, "שאלה 3* :"), line(1, .8f, "בשאלה 5 נעזרים בתוצאה"),
            line(2, .2f, "Question 5"), line(2, .5f, "הלאש 6:"))
        assertEquals(listOf("1", "2", "3*", "4*", "5", "6"), QuestionHeadings.find(words).questions.map { it.number })
    }

    @Test fun summarySaysHowManyQuestionsAndWhereBeforeSending() {
        val study = org.json.JSONObject("""{"sources":[{"title":"Ex6.pdf"}],"folders":[{"id":"b","title":"תרגילי בונוס"}],
            "questions":[{"number":"1"},{"number":"2"},{"number":"3","folderId":"b"}]}""")
        assertEquals("3 questions from Ex6.pdf (1 in תרגילי בונוס)", StudyBundleSummary.describe(study))
        val pages = org.json.JSONObject("""{"sources":[{"title":"scan.pdf"}],"questions":[{"number":"Page 1"},{"number":"Page 2"}]}""")
        assertEquals("No question headings were found in scan.pdf, so each of its 2 pages is a question", StudyBundleSummary.describe(pages))
    }

    @Test fun repeatedNumbersAreOneQuestion() {
        val words = rtl(0, .2f, "שאלה", "1") + rtl(0, .5f, "שאלה", "2") + rtl(1, .2f, "שאלה", "2", "(המשך)")
        assertEquals(listOf("1", "2"), QuestionHeadings.find(words).questions.map { it.number })
    }

    /**
     * Optional check against real homework PDFs, which stay outside source control: point
     * SCHOLIX_PDF_WORDS at a folder of word dumps (page, top, bottom, left, right, text per line, tab separated).
     */
    @Test fun privateWordDumpsPrintTheQuestionsFound() {
        val directory = System.getenv("SCHOLIX_PDF_WORDS")?.let(::File)
        assumeTrue(directory?.isDirectory == true)
        directory!!.listFiles { file -> file.extension == "tsv" }!!.sorted().forEach { file ->
            val words = file.readLines(Charsets.UTF_8).mapNotNull { row ->
                val cells = row.split('\t')
                if (cells.size < 6) null else PdfWord(cells[0].toInt(), cells[1].toFloat(), cells[2].toFloat(),
                    cells[3].toFloat(), cells[4].toFloat(), cells.drop(5).joinToString("\t"))
            }
            val found = QuestionHeadings.find(words)
            File(directory, file.nameWithoutExtension + ".found.txt").writeText(
                found.questions.joinToString("\n") { "${it.number}\tpage ${it.page + 1}\t${"%.3f".format(it.top)}\t${it.section.orEmpty()}" },
                Charsets.UTF_8)
        }
    }
}
