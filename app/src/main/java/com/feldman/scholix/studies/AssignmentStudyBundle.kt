package com.feldman.scholix.studies

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class AssignmentStudySource(val name: String, val file: File, val solutionOnly: Boolean = false)

/** Keeps original PDFs and links prompts to their printed question headings. */
object AssignmentStudyBundle {
    suspend fun build(context: Context, assignment: StudyAssignment, files: List<AssignmentStudySource>, description: String = ""): File =
        withContext(Dispatchers.IO) {
            files.firstOrNull { it.file.extension.equals("fstudy", true) }?.let { return@withContext it.file }
            PDFBoxResourceLoader.init(context.applicationContext)
            val sources = JSONArray()
            val questions = JSONArray()
            val folders = JSONArray()
            val pdfs = files.filter { it.file.inputStream().use { input ->
                val header = ByteArray(5)
                input.read(header) == 5 && header.contentEquals("%PDF-".toByteArray())
            } }
            pdfs.forEachIndexed { index, source ->
                val sourceId = "source-$index"
                PDDocument.load(source.file).use { pdf ->
                    sources.put(JSONObject().put("id", sourceId).put("title", source.name)
                        .put("fileName", "$sourceId.pdf").put("mimeType", "application/pdf")
                        .put("pageCount", pdf.numberOfPages).put("solutionOnly", source.solutionOnly))
                    if (!source.solutionOnly) {
                        val words = mutableListOf<PdfWord>()
                        val stripper = object : PDFTextStripper() {
                            override fun writeString(text: String?, positions: MutableList<TextPosition>?) {
                                if (text.isNullOrBlank() || positions.isNullOrEmpty()) return
                                val box = pdf.getPage(currentPageNo - 1).cropBox
                                words += PdfWord(currentPageNo - 1,
                                    positions.minOf { it.yDirAdj - it.heightDir } / box.height, positions.maxOf { it.yDirAdj } / box.height,
                                    positions.minOf { it.xDirAdj } / box.width, positions.maxOf { it.xDirAdj + it.widthDirAdj } / box.width, text)
                            }
                        }
                        stripper.sortByPosition = true
                        stripper.getText(pdf)
                        val printed = QuestionHeadings.find(words)
                        val prompts = printed.questions
                        if (prompts.isEmpty()) {
                            repeat(pdf.numberOfPages) { page ->
                                questions.put(question("$sourceId-page-$page", source.name, "Page ${page + 1}",
                                    JSONArray().put(crop(sourceId, page, 0f, 1f))))
                            }
                        } else prompts.forEachIndexed { questionIndex, start ->
                            // A question ends where the next question or part (such as the bonus exercises) begins.
                            val end = (prompts.drop(questionIndex + 1).map { it.page to it.top } +
                                printed.sections.map { it.page to it.top })
                                .filter { (page, top) -> page > start.page || page == start.page && top > start.top }
                                .minWithOrNull(compareBy({ it.first }, { it.second }))
                            val crops = JSONArray()
                            for (page in start.page..(end?.first ?: (pdf.numberOfPages - 1))) {
                                val top = if (page == start.page) (start.top - .01f).coerceAtLeast(0f) else 0f
                                val bottom = if (page == end?.first) (end.second - .005f).coerceAtMost(1f) else 1f
                                if (bottom > top) crops.put(crop(sourceId, page, top, bottom))
                            }
                            val entry = question("$sourceId-question-${start.number.trimEnd('*')}-$questionIndex", source.name, start.number, crops)
                            start.section?.let { title ->
                                val folderId = "$sourceId-part-" + printed.sections.indexOfFirst { it.title == title }
                                if ((0 until folders.length()).none { folders.getJSONObject(it).getString("id") == folderId })
                                    folders.put(JSONObject().put("id", folderId).put("title", title))
                                entry.put("folderId", folderId).put("topic", title)
                            }
                            questions.put(entry)
                        }
                    }
                }
            }
            if (questions.length() == 0 && description.isNotBlank()) {
                questions.put(question("instructions", assignment.course, assignment.title, JSONArray()).put("description", description))
            }
            require(questions.length() > 0) { "This assignment has no question files or instructions to import. Refresh the assignment first." }
            val now = System.currentTimeMillis()
            val study = JSONObject().put("id", "scholix-${assignment.key}").put("title", assignment.title)
                .put("createdAtMillis", now).put("updatedAtMillis", now).put("sources", sources).put("folders", folders).put("questions", questions)
            val directory = File(context.cacheDir, "study-handoffs").apply { mkdirs() }
            directory.listFiles()?.filter { now - it.lastModified() > 24 * 60 * 60 * 1000 }?.forEach { it.delete() }
            File.createTempFile("assignment-", ".fstudy", directory).also { bundle ->
                ZipOutputStream(bundle.outputStream()).use { zip ->
                    zip.putNextEntry(ZipEntry("study.json"))
                    zip.write(JSONObject().put("schemaVersion", 2).put("study", study).put("answers", JSONArray()).toString().toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                    pdfs.forEachIndexed { index, source ->
                        zip.putNextEntry(ZipEntry("sources/source-$index.pdf"))
                        source.file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
                require(bundle.length() <= 40L * 1024 * 1024) { "The study exceeds the 40 MB transfer limit." }
            }
        }

    private fun question(id: String, topic: String, number: String, crops: JSONArray) =
        JSONObject().put("id", id).put("topic", topic).put("number", number).put("sourceCrops", crops)
    private fun crop(source: String, page: Int, top: Float, bottom: Float) = JSONObject().put("sourceId", source)
        .put("pageIndex", page).put("left", 0).put("top", top.toDouble()).put("right", 1).put("bottom", bottom.toDouble())
}
