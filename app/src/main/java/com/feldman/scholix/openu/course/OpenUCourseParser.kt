package com.feldman.scholix.openu.course

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.safety.Safelist
import java.io.IOException
import java.net.URI
import java.net.URLDecoder

/** Opal selectors and URLs from the original OpenU application's api/src/openu.ts. */
internal object OpenUCourseParser {
    const val BASE = "https://opal.openu.ac.il"
    fun parameter(url: String, name: String): String = URI(url).rawQuery.orEmpty().split('&')
        .firstOrNull { it.substringBefore('=') == name }?.substringAfter('=')?.let { URLDecoder.decode(it, "UTF-8") }.orEmpty()
    fun opalUrl(value: String): Boolean = runCatching {
        val u = URI(value)
        u.scheme == "https" && u.host == "opal.openu.ac.il" && u.port in listOf(-1, 443) && u.userInfo == null
    }.getOrDefault(false)
    fun documentUrl(value: String): Boolean = runCatching {
        val u = URI(value)
        u.scheme == "https" && u.port in listOf(-1, 443) && u.userInfo == null &&
            ((u.host == "opal.openu.ac.il" && (u.path.startsWith("/pluginfile.php/") ||
                u.path == "/local/ouil_ebook/signedpdf.php")) ||
                (u.host == "sheilta.apps.openu.ac.il" && u.path == "/pls/myopr/booklet.download"))
    }.getOrDefault(false)
    fun hlsUrl(value: String): Boolean = runCatching {
        val u = URI(value)
        u.scheme == "https" && u.port in listOf(-1, 443) && u.userInfo == null &&
            listOf("openu.ac.il", "bynetcdn.com").any { u.host == it || u.host.endsWith(".$it") }
    }.getOrDefault(false)
    fun checkCourse(html: String, moodleId: String) {
        if (!Regex("(?:^|\\s)course-${Regex.escape(moodleId)}(?:\\s|$)").containsMatchIn(Jsoup.parse(html).body().className()))
            throw IOException("OpenU returned content for a different course.")
    }
    fun hasBooklet(html: String) = Regex("booklet\\.(viewbook|tafrit|download)|pls/myopr/booklet|חוברת\\s+ה?קורס", RegexOption.IGNORE_CASE).containsMatchIn(html)
    fun sections(html: String): JSONArray {
        val doc = Jsoup.parse(html, BASE)
        val result = linkedMapOf<String, JSONObject>()
        val links = doc.select(".sections-nav-list .dropdown-menu a[href*=sectionid=], a.dropdown-item[href*=sectionid=]")
            .ifEmpty { doc.select("[role=main] a.section[href*=sectionid=]") }
        links.forEach { link ->
            val id = parameter(link.absUrl("href"), "sectionid")
            if (id.matches(Regex("\\d+")) && link.text().isNotBlank()) result.putIfAbsent(id,
                JSONObject().put("id", id).put("title", link.selectFirst(".sectionname:not(.hidden)")?.text() ?: link.text()))
        }
        if (result.isEmpty()) doc.select("li.section[data-sectionid], [data-sectionid].section").forEach {
            val id = it.attr("data-sectionid")
            val title = it.selectFirst(".sectionname, .section-title, h3")?.text().orEmpty()
            if (id.matches(Regex("\\d+")) && title.isNotBlank()) result[id] = JSONObject().put("id", id).put("title", title)
        }
        return JSONArray(result.values.toList())
    }
    fun activities(html: String, sectionId: String = ""): JSONArray {
        val doc = Jsoup.parse(html, BASE)
        val result = linkedMapOf<String, JSONObject>()
        val candidates = doc.select(".block_ouil_navigation li.type_structure.current_parent > ul > li.type_activity, " +
            ".block_ouil_navigation li.type_structure.contains_current > ul > li.type_activity, " +
            ".block_ouil_navigation li.type_structure.contains_direct_current > ul > li.type_activity, .course-content li.activity")
        if (sectionId.isNotEmpty()) doc.select(".block_ouil_navigation li.type_structure").filter {
            it.selectFirst("p a[href*=sectionid=]")?.absUrl("href")?.let { url -> parameter(url, "sectionid") } == sectionId
        }.forEach { candidates.addAll(it.select("ul > li.type_activity")) }
        candidates.addAll(doc.select("#region-main [role=main] a[href*=/mod/][href*=id=]"))
        candidates.forEach { row ->
            val link = if (row.tagName() == "a") row else row.selectFirst("a[href*=/mod/]") ?: return@forEach
            val url = link.absUrl("href")
            val id = parameter(url, "id")
            val module = Regex("/mod/([a-z][a-z0-9_]*)/view\\.php").find(URI(url).path.orEmpty())?.groupValues?.get(1)
            val title = link.selectFirst(".item-content-wrap, .instancename")?.text() ?: link.text()
            if (opalUrl(url) && id.matches(Regex("\\d+")) && module != null && title.isNotBlank())
                result.putIfAbsent(id, JSONObject().put("id", id).put("title", title).put("module", module)
                    .put("url", url).put("completed", row.hasClass("mod_completed") || row.select(".completion-btn-success").isNotEmpty()))
        }
        return JSONArray(result.values.toList())
    }
    fun navigationActivities(raw: String): JSONArray {
        val items = JSONObject(raw).optJSONArray("children") ?: return JSONArray()
        val result = JSONArray()
        for (i in 0 until items.length()) {
            val child = items.getJSONObject(i)
            val url = URI(BASE).resolve(child.optString("link").replace("&amp;", "&")).toString()
            val module = Regex("/mod/([a-z][a-z0-9_]*)/view\\.php").find(URI(url).path.orEmpty())?.groupValues?.get(1) ?: continue
            val id = parameter(url, "id")
            if (opalUrl(url) && id.matches(Regex("\\d+"))) result.put(JSONObject().put("id", id).put("title", Jsoup.parse(child.optString("name")).text())
                .put("url", url).put("module", module).put("completed", child.optString("class").contains("mod_completed")))
        }
        return result
    }
    fun forums(html: String): JSONArray = JSONArray(Jsoup.parse(html, BASE).select("li.forumslist[data-forumid]").map { row ->
        JSONObject().put("id", row.attr("data-forumid")).put("title", row.selectFirst(".forum_list_title a")?.text().orEmpty())
            .put("description", row.selectFirst(".forum_body_content")?.text().orEmpty())
            .put("meta", row.selectFirst(".forum_footer")?.text().orEmpty())
            .put("url", "$BASE/mod/ouilforum/view.php?f=${row.attr("data-forumid")}")
    })
    fun books(html: String): JSONArray = JSONArray(Jsoup.parse(html, BASE).select(".ebook_cell[id^=ebook]").map { row ->
        val link = row.selectFirst("a.ebook_digital_link")
        val href = link?.attr("href-content")?.ifBlank { link.attr("href") }.orEmpty()
        val url = runCatching { URI(BASE).resolve(href).toString() }.getOrDefault("").takeIf(::documentUrl).orEmpty()
        JSONObject().put("id", row.id().removePrefix("ebook")).put("title", row.selectFirst(".ebook_name")?.text().orEmpty())
            .put("description", listOf(".ebook_part", ".ebook_subtitle").mapNotNull { row.selectFirst(it)?.text() }.joinToString(" · "))
            .put("url", url).put("requiresConsent", url.isEmpty())
    })
    fun booklet(html: String): JSONArray = JSONArray(Jsoup.parse(html).select("a[href*=booklet.download], frame[src*=booklet.download], iframe[src*=booklet.download]")
        .mapNotNull { node ->
            val url = URI("https://sheilta.apps.openu.ac.il/pls/myopr/").resolve(node.attr(if (node.hasAttr("href")) "href" else "src")).toString()
            val file = parameter(url, "in_file_name")
            if (!file.matches(Regex("[\\w.-]+\\.pdf", RegexOption.IGNORE_CASE))) null
            else JSONObject().put("id", file).put("title", node.text().ifBlank { "Course booklet" }).put("file", file)
        }.distinctBy { it.getString("file") })
    fun recordings(html: String, collection: String, cmid: String): JSONArray {
        val doc = Jsoup.parse(html, BASE)
        val context = doc.selectFirst(".collection_link[cid][mid]")
        val course = Regex("[\"']courseid[\"']\\s*:\\s*[\"'](\\d+)").find(html)?.groupValues?.get(1).orEmpty()
        return JSONArray(doc.select(".ovc_playlist_border").map { row ->
            JSONObject().put("id", row.id().ifBlank { row.selectFirst("[id^=playlist]")?.id().orEmpty() }.removePrefix("playlist"))
                .put("title", row.selectFirst(".ovc_playlist_title")?.text().orEmpty())
                .put("description", row.selectFirst(".ovc_descr_short")?.text().orEmpty())
                .put("meta", listOf(".pl_instructor", ".pl_recorddate").mapNotNull { row.selectFirst(it)?.text() }.joinToString(" · "))
                .put("collection", collection).put("cmid", cmid).put("context", context?.attr("cid").orEmpty())
                .put("mid", context?.attr("mid").orEmpty()).put("course", course)
        })
    }
    fun detail(html: String, url: String): JSONObject {
        val doc = Jsoup.parse(html, url)
        val root = doc.selectFirst("#region-main [role=main], #region-main") ?: throw IOException("OpenU did not return course content.")
        val result = JSONObject().put("title", root.selectFirst("h1, h2")?.text() ?: doc.title())
        result.put("images", JSONArray(root.select("img[src]").map { JSONObject().put("url", it.absUrl("src")) }))
        if (URI(url).path.contains("/mod/quiz/")) result.put("quiz", quiz(html, url))
        val posts = root.select(".ouilforum_post").map { post ->
            JSONObject().put("id", post.attr("data-postid")).put("title", post.selectFirst(".post_subject")?.text().orEmpty())
                .put("author", post.selectFirst(".of_post_author a")?.text().orEmpty()).put("date", post.selectFirst(".of_postdate")?.text().orEmpty())
                .put("body", post.selectFirst(".post_content")?.wholeText().orEmpty())
        }
        result.put("posts", JSONArray(posts))
        result.put("links", JSONArray(root.select("a[href]").mapNotNull { a ->
            val href = a.absUrl("href")
            if (href.isBlank() || a.text().isBlank() || !readOnlyLink(href)) null
            else JSONObject().put("title", a.text()).put("url", href)
        }.distinctBy { it.optString("url") }))
        result.put("media", JSONArray(root.select("video[src], video source[src], iframe[src]").map { node ->
            JSONObject().put("url", node.absUrl("src")).put("title", node.attr("title")).put("type", node.tagName())
        }))
        root.select("script, style, form, button, input, .navlinks, .ouilforum_paging").remove()
        root.select("a[href]").filter { !readOnlyLink(it.absUrl("href")) }.forEach { it.unwrap() }
        result.put("text", if (posts.isEmpty()) root.wholeText().trim() else "")
        result.put("html", safeHtml(root, url))
        return result
    }

    fun containsResource(data: JSONObject, url: String): Boolean = listOf("items", "links", "media", "images").any { key ->
        data.optJSONArray(key)?.objects()?.any { it.optString("url") == url } == true
    }

    fun nativeActivityUrl(url: String): Boolean = opalUrl(url) && runCatching {
        Regex("/mod/[a-z][a-z0-9_]*/(?:view|discuss|review)\\.php").matches(URI(url).path) && readOnlyLink(url)
    }.getOrDefault(false)

    private fun readOnlyLink(url: String): Boolean = runCatching {
        val u = URI(url)
        u.rawQuery.orEmpty().split('&').none { it.substringBefore('=') in listOf("sesskey", "delete", "submit", "action") } &&
            !u.path.orEmpty().endsWith("/startattempt.php") && !u.path.orEmpty().endsWith("/processattempt.php")
    }.getOrDefault(false)

    fun quiz(html: String, url: String): JSONObject {
        val doc = Jsoup.parse(html, url)
        val root = doc.selectFirst("#region-main") ?: doc.body()
        val state = when {
            URI(url).path.endsWith("/review.php") -> "review"
            root.select(".que").isNotEmpty() -> "attempt"
            root.select(".quizsummaryofattempt").isNotEmpty() -> "summary"
            else -> "overview"
        }
        return JSONObject().put("state", state).put("attempt", root.selectFirst("input[name=attempt]")?.attr("value")
            ?: parameter(url, "attempt"))
            .put("canStart", root.select("form[action*=/mod/quiz/startattempt.php], a[href*=/mod/quiz/attempt.php]").isNotEmpty())
            .put("canFinish", root.select("input[name=finishattempt][value=1]").isNotEmpty())
            .put("info", root.select("#intro, .quizinfo, #feedback, .quizsummaryofattempt").joinToString("\n") { it.wholeText().trim() })

    }

    private fun safeHtml(element: Element, base: String): String = Jsoup.clean(element.html(), base,
        Safelist.relaxed().addTags("math", "mrow", "mi", "mn", "mo", "msup", "msub", "msubsup", "mfrac", "msqrt", "mroot",
            "mfenced", "mover", "munder", "munderover", "mtable", "mtr", "mtd", "mtext", "semantics", "annotation")
            .addAttributes(":all", "dir", "lang", "class").addAttributes("math", "display", "xmlns")
            .addAttributes("mo", "stretchy", "fence", "separator").addAttributes("img", "width", "height")
            .addProtocols("img", "src", "https").addProtocols("a", "href", "https"))
}

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull(::optJSONObject)
