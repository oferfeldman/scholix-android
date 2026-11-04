package com.feldman.scholix.openu.course

import android.content.Context
import com.feldman.scholix.api.PlatformStorage
import com.feldman.scholix.api.platforms.OpenAUPlatform
import com.feldman.scholix.api.platforms.WebtopDownloadedAttachment
import com.feldman.scholix.openu.OpenUAssignmentParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.File
import java.io.IOException
import java.net.URLEncoder

internal class OpenUCourseRepository(context: Context, private val platform: OpenAUPlatform) {
    private val context = context.applicationContext
    private val account = platform.getUsername()
    private val prefs = this.context.getSharedPreferences("openu_course_${platform.id}", Context.MODE_PRIVATE)
    fun cached(courseKey: String, section: String): JSONObject? = prefs.getString("$courseKey:$section", null)?.let(::JSONObject)
        ?.takeIf { it.optString("account") == account }?.optJSONObject("data")
    private fun save(courseKey: String, section: String, data: JSONObject): JSONObject {
        prefs.edit().putString("$courseKey:$section", JSONObject().put("account", account)
            .put("checkedAt", System.currentTimeMillis()).put("data", data).toString()).apply()
        if (PlatformStorage.loadPlatforms(context).any { it.id == platform.id && it.getUsername() == account })
            PlatformStorage.addPlatform(context, platform)
        return data
    }
    suspend fun course(courseKey: String): JSONObject = withContext(Dispatchers.IO) {
        requireAccount()
        val course = platform.assignmentCourses().singleOrNull { it.optString("courseKey") == courseKey }
            ?: throw IOException("This course is no longer available for the selected OpenU account.")
        val html = platform.assignmentPage(OpenUAssignmentParser.courseUrl(course))
        val moodle = OpenUAssignmentParser.moodleId(html)
        val data = JSONObject(course.toString()).put("moodleId", moodle).put("sections", OpenUCourseParser.sections(html))
            .put("activities", OpenUCourseParser.activities(html)).put("hasBooklet", OpenUCourseParser.hasBooklet(html))
        val recordings = Jsoup.parse(html, OpenUCourseParser.BASE).select("a[href]").firstOrNull {
            it.attr("href").contains("ouilvideocollection") || Regex("הקלטות|שיעורים מוקלטים").containsMatchIn(it.text())
        }?.absUrl("href").orEmpty()
        data.put("recordingsUrl", recordings)
        save(courseKey, "overview", data)
    }
    suspend fun section(courseKey: String, sectionId: String): JSONObject = withContext(Dispatchers.IO) {
        requireCourse(courseKey)
        require(sectionId.matches(Regex("\\d+")))
        val course = cached(courseKey, "overview") ?: course(courseKey)
        val moodle = course.getString("moodleId")
        val html = platform.assignmentPage("${OpenUCourseParser.BASE}/course/view.php?id=$moodle&sectionid=$sectionId&from=courseHome")
        OpenUCourseParser.checkCourse(html, moodle)
        var activities = OpenUCourseParser.activities(html, sectionId)
        if (activities.length() == 0) {
            val token = sessionKey(html)
            activities = OpenUCourseParser.navigationActivities(platform.courseForm("${OpenUCourseParser.BASE}/lib/ajax/getnavbranch.php",
                mapOf("elementid" to "expandable_branch_30_$sectionId", "id" to sectionId, "type" to "30", "sesskey" to token)))
        }
        save(courseKey, "unit:$sectionId", JSONObject().put("items", activities))
    }
    suspend fun list(courseKey: String, kind: String): JSONObject = withContext(Dispatchers.IO) {
        requireCourse(courseKey)
        val course = cached(courseKey, "overview") ?: course(courseKey)
        val moodle = course.getString("moodleId")
        val items = when (kind) {
            "forums" -> OpenUCourseParser.forums(platform.assignmentPage("${OpenUCourseParser.BASE}/mod/ouilforum/index.php?id=$moodle&quicklinks=1"))
            "books" -> OpenUCourseParser.books(platform.assignmentPage(booksUrl(moodle)))
            "booklet" -> {
                val base = "https://sheilta.apps.openu.ac.il/pls/myopr/"
                val query = "p_kurs=${course.getString("id").trimStart('0')}&p_semester=${course.getString("term")}"
                var files = OpenUCourseParser.booklet(platform.coursePage("${base}booklet.viewbook?$query"))
                if (files.length() == 0) files = OpenUCourseParser.booklet(platform.coursePage("${base}booklet.tafrit?$query"))
                files.objects().forEach { it.put("url", "${base}booklet.download?$query&in_file_name=${encode(it.getString("file"))}") }
                files
            }
            "recordings" -> {
                val url = course.optString("recordingsUrl").takeIf(OpenUCourseParser::opalUrl)
                    ?: return@withContext save(courseKey, kind, JSONObject().put("items", JSONArray()))
                val html = platform.assignmentPage(url + if ('?' in url) "&quicklinks=1" else "?quicklinks=1")
                val collections = Jsoup.parse(html, OpenUCourseParser.BASE).select(".collection_link[cmid]")
                val recordings = JSONArray()
                if (collections.isEmpty()) OpenUCourseParser.recordings(html, "Recordings", OpenUCourseParser.parameter(url, "id"))
                else {
                    for (collection in collections.distinctBy { it.attr("cmid") }) {
                        val cmid = collection.attr("cmid")
                        if (!cmid.matches(Regex("\\d+"))) continue
                        val page = platform.assignmentPage("${OpenUCourseParser.BASE}/mod/ouilvideocollection/view.php?id=$cmid")
                        OpenUCourseParser.recordings(page, collection.text(), cmid).objects().forEach { recordings.put(it) }
                    }
                    recordings
                }
            }
            else -> throw IOException("Unsupported OpenU section.")
        }
        save(courseKey, kind, JSONObject().put("items", items))
    }
    suspend fun detail(courseKey: String, url: String): JSONObject = withContext(Dispatchers.IO) {
        requireCourse(courseKey)
        require(OpenUCourseParser.nativeActivityUrl(url))
        val course = cached(courseKey, "overview") ?: course(courseKey)
        val html = platform.assignmentPage(url)
        OpenUCourseParser.checkCourse(html, course.getString("moodleId"))
        save(courseKey, "detail:$url", OpenUCourseParser.detail(html, url))
    }
    suspend fun consent(courseKey: String, bookId: String): String = withContext(Dispatchers.IO) {
        requireCourse(courseKey)
        val course = cached(courseKey, "overview") ?: course(courseKey)
        val moodle = course.getString("moodleId")
        val html = platform.assignmentPage(booksUrl(moodle))
        OpenUCourseParser.checkCourse(html, moodle)
        if (OpenUCourseParser.books(html).objects().none { it.optString("id") == bookId }) throw IOException("Book no longer available in this course.")
        requireAccount()
        val response = JSONObject(platform.courseForm("${OpenUCourseParser.BASE}/local/ouil_ebook/ebook_actions.php",
            mapOf("action" to "subscribe", "course" to moodle, "book" to bookId, "sesskey" to sessionKey(html))))
        response.optString("file").also { url ->
            require(OpenUCourseParser.documentUrl(url))
            val books = OpenUCourseParser.books(html)
            books.objects().single { it.optString("id") == bookId }.put("url", url).put("requiresConsent", false)
            save(courseKey, "books", JSONObject().put("items", books))
        }
    }
    suspend fun stream(courseKey: String, routeItem: JSONObject): String = withContext(Dispatchers.IO) {
        requireCourse(courseKey)
        val item = cached(courseKey, "recordings")?.optJSONArray("items")?.objects()?.singleOrNull { saved ->
            listOf("id", "cmid", "context", "course", "mid").all { saved.optString(it) == routeItem.optString(it) }
        } ?: throw IOException("This recording is not in the selected course. Refresh the recording list.")
        val fields = mapOf("action" to "getplaylist", "context" to item.getString("context"), "playlistid" to item.getString("id"),
            "course" to item.getString("course"), "mid" to item.getString("mid"))
        val cipher = JSONObject(platform.courseForm("${OpenUCourseParser.BASE}/mod/ouilvideocollection/actions.php", fields))
            .optJSONObject("media")?.optString("cipher")?.takeIf(String::isNotBlank) ?: throw IOException("OpenU did not return a recording stream.")
        val html = platform.assignmentPage("${OpenUCourseParser.BASE}/local/ouil_video/player.php?mediaid=${encode(cipher)}")
        resolveVideo(html)
    }
    suspend fun resolveVideo(html: String): String {
        val source = Regex("[\"']file[\"']\\s*:\\s*[\"'](https:[^\"']+)").find(html)?.groupValues?.get(1)
            ?.replace("\\/", "/") ?: throw IOException("OpenU did not provide a video source.")
        val (url, body) = platform.courseStream(source)
        val hls = Regex("source\\s+file=[\"']([^\"']+)").find(body)?.groupValues?.get(1)?.replace("&amp;", "&")
            ?: url.takeIf { body.trimStart().startsWith("#EXTM3U") || url.contains(".m3u8") }
            ?: throw IOException("OpenU did not return an HLS stream.")
        require(OpenUCourseParser.hlsUrl(hls))
        return hls
    }
    suspend fun playerStream(courseKey: String, url: String): String {
        requireResource(courseKey, url)
        if (OpenUCourseParser.hlsUrl(url) && Regex("\\.(m3u8|mp4)(?:[?]|$)", RegexOption.IGNORE_CASE).containsMatchIn(url)) return url
        require(OpenUCourseParser.opalUrl(url))
        return resolveVideo(platform.assignmentPage(url))
    }
    suspend fun file(courseKey: String, name: String, url: String): WebtopDownloadedAttachment = withContext(Dispatchers.IO) {
        requireResource(courseKey, url)
        require(OpenUCourseParser.documentUrl(url) || OpenUAssignmentParser.fileUrl(url))
        val folder = File(context.cacheDir, "message_attachments/openu-course-${platform.id}").apply { mkdirs() }
        val target = File.createTempFile("course-", ".part", folder)
        try {
            val mime = platform.assignmentFile(url, target)
            val safeName = name.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[\\p{Cntrl}:*?\"<>|]"), "_")
                .take(140).ifBlank { "Course document" }.let { if (mime == "application/pdf" && !it.endsWith(".pdf", true)) "$it.pdf" else it }
            val complete = File(File(folder, java.util.UUID.randomUUID().toString()).apply { mkdirs() }, safeName)
            if (!target.renameTo(complete)) { target.copyTo(complete); target.delete() }
            WebtopDownloadedAttachment(complete, mime)
        } catch (e: Exception) { target.delete(); throw e }
    }
    private fun requireAccount(): OpenAUPlatform = PlatformStorage.loadPlatforms(context).filterIsInstance<OpenAUPlatform>()
        .singleOrNull { it.id == platform.id && it.getUsername() == account }
        ?: throw IOException("The OpenU account was removed or changed. Reopen the course.")
    private fun requireCourse(courseKey: String) {
        val current = requireAccount()
        if (current.toJson().optJSONArray("courses")?.objects()?.none { it.optString("courseKey") == courseKey } != false)
            throw IOException("This course is no longer available for the selected OpenU account.")
    }
    private fun requireResource(courseKey: String, url: String) {
        requireCourse(courseKey)
        val known = prefs.all.filterKeys { it.startsWith("$courseKey:") }.values.filterIsInstance<String>().any { raw ->
            val snapshot = JSONObject(raw)
            snapshot.optString("account") == account && snapshot.optJSONObject("data")?.let { OpenUCourseParser.containsResource(it, url) } == true
        }
        if (!known) throw IOException("This resource is not in the selected course. Reopen it from the course content.")
    }
    private fun booksUrl(moodle: String) = "${OpenUCourseParser.BASE}/local/ouil_ebook/index.php?id=$moodle&type=digital&quicklinks=1"
    private fun sessionKey(html: String) = Regex("[\"']sesskey[\"']\\s*:\\s*[\"']([^\"']+)").find(html)?.groupValues?.get(1)
        ?: throw IOException("OpenU did not provide an authenticated course session.")
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
}
