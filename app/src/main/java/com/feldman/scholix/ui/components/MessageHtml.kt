package com.feldman.scholix.ui.components

import org.jsoup.Jsoup
import org.jsoup.safety.Safelist

/** Rich mail is untrusted, including drafts opened from Webtop. */
object MessageHtml {
    private val mathTags = arrayOf("math", "semantics", "annotation", "mrow", "mi", "mn", "mo", "ms", "mtext", "mspace", "msub", "msup", "msubsup", "mfrac", "msqrt", "mroot", "munder", "mover", "munderover", "mtable", "mtr", "mtd", "menclose", "mpadded", "mstyle", "mphantom", "mmultiscripts", "mprescripts", "none")
    private val allowed = Safelist.relaxed()
        .addTags("hr", "sub", "sup", "s", "strike", "u", "mark", "figure", "figcaption", "section", "article", "div", "h1", "h2", "h3", "h4", "h5", "h6", *mathTags)
        .addAttributes(":all", "style", "dir", "class", "title", "align", "data-exp", "data-font-size", "data-se-value", "data-se-type", "contenteditable")
        .addAttributes("table", "border", "cellpadding", "cellspacing", "width")
        .addAttributes("td", "width", "height", "valign")
        .addAttributes("ol", "start", "type")
        .addAttributes("img", "width", "height")
        .addProtocols("img", "src", "data")
        .apply { mathTags.forEach { addAttributes(it, "display", "mathvariant", "mathsize", "mathcolor", "mathbackground", "encoding", "stretchy", "fence", "separator", "accent", "accentunder", "columnalign", "columnspacing", "rowspacing", "linethickness", "lspace", "rspace", "displaystyle", "scriptlevel", "width", "height", "depth", "voffset", "notation") } }
    private val styles = setOf("color", "background-color", "font-family", "font-size", "font-weight", "font-style", "text-decoration", "text-align", "text-indent", "vertical-align", "line-height", "letter-spacing", "word-spacing", "white-space", "direction", "unicode-bidi", "width", "height", "max-width", "min-width", "border", "border-width", "border-style", "border-color", "border-collapse", "border-spacing", "padding", "padding-left", "padding-right", "padding-top", "padding-bottom", "margin", "margin-left", "margin-right", "margin-top", "margin-bottom", "list-style-type")

    fun sanitize(html: String): String {
        val clean = Jsoup.parseBodyFragment(Jsoup.clean(html, allowed))
        clean.outputSettings().prettyPrint(false)
        clean.select("[style]").forEach { element ->
            val safe = element.attr("style").split(';').mapNotNull { rule ->
                val parts = rule.split(':', limit = 2)
                if (parts.size != 2 || parts[0].trim().lowercase() !in styles) return@mapNotNull null
                val value = parts[1].trim()
                if (Regex("url|expression|javascript|@|[\\\\<>]", RegexOption.IGNORE_CASE).containsMatchIn(value)) null else "${parts[0].trim()}:$value"
            }.joinToString(";")
            element.attr("style", safe)
        }
        clean.select("img[src^=data:]").filterNot {
            Regex("^data:image/(png|jpeg|gif|webp);base64,", RegexOption.IGNORE_CASE).containsMatchIn(it.attr("src"))
        }.forEach { it.remove() }
        clean.select("[contenteditable]").forEach { it.removeAttr("contenteditable") }
        return clean.body().html()
    }
}
