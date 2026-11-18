package com.feldman.scholix.ui.components

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import org.jsoup.safety.Safelist
import org.jsoup.select.NodeFilter
import org.jsoup.select.NodeTraversor

/** The "From / To / date / subject" block Webtop puts under a divider when a message quotes an earlier one. */
data class QuotedHeader(val from: String, val date: String, val subject: String)

class DisplayMessage(val html: String, val quoted: QuotedHeader?)

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

    /** Webtop sends subjects and names HTML-escaped (`tomorrow&#39;s`); some arrive escaped twice. */
    fun plainText(value: String): String {
        var text = value
        repeat(2) { text = Parser.unescapeEntities(text, false) }
        return text.replace('\u00A0', ' ').trim()
    }

    private val blocks = setOf("p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "li", "ul", "ol", "tr", "table", "blockquote", "section", "article", "pre")
    private val fromLabel = Regex("""^(מאת|From)\s*:\s*""", RegexOption.IGNORE_CASE)
    private val dateLine = Regex("""\d{1,2}[/.]\d{1,2}[/.]\d{2,4}""")

    /**
     * Pulls a leading quoted-message header and its divider out of the body so it can be shown natively.
     * Only applies when nothing but whitespace precedes the divider; otherwise the HTML is returned as is.
     */
    fun splitQuotedHeader(html: String): DisplayMessage {
        val doc = Jsoup.parseBodyFragment(html)
        doc.outputSettings().prettyPrint(false)
        val body = doc.body()
        val hr = body.selectFirst("hr") ?: return DisplayMessage(html, null)
        val lines = mutableListOf(mutableListOf<TextNode>())
        val breaks = mutableListOf<Pair<Element, Int>>()
        var leadingContent = false
        var passed = false
        fun completed() = lines.count { line -> line.any { it.text().isNotBlank() } }
        fun newLine() { if (lines.last().any { it.text().isNotBlank() }) lines += mutableListOf<TextNode>() }
        NodeTraversor.filter(object : NodeFilter {
            override fun head(node: Node, depth: Int): NodeFilter.FilterResult {
                if (node === hr) { passed = true; return NodeFilter.FilterResult.CONTINUE }
                if (!passed) {
                    if ((node is TextNode && node.text().isNotBlank()) || (node is Element && node.normalName() in setOf("img", "table", "math"))) {
                        leadingContent = true
                        return NodeFilter.FilterResult.STOP
                    }
                    return NodeFilter.FilterResult.CONTINUE
                }
                when {
                    node is TextNode -> if (node.text().isNotBlank()) lines.last() += node
                    node is Element && node.normalName() == "br" -> { breaks += node to completed(); newLine() }
                    node is Element && node.normalName() in blocks -> newLine()
                }
                return if (completed() > 6) NodeFilter.FilterResult.STOP else NodeFilter.FilterResult.CONTINUE
            }
            override fun tail(node: Node, depth: Int): NodeFilter.FilterResult {
                if (passed && node is Element && node.normalName() in blocks) newLine()
                return NodeFilter.FilterResult.CONTINUE
            }
        }, body)
        if (leadingContent) return DisplayMessage(html, null)
        val content = lines.filter { line -> line.any { it.text().isNotBlank() } }
        fun text(index: Int) = content.getOrNull(index)?.joinToString("") { it.text() }?.replace('\u00A0', ' ')?.trim().orEmpty()
        if (!fromLabel.containsMatchIn(text(0))) return DisplayMessage(html, null)
        val dateIndex = (1..4).firstOrNull { dateLine.containsMatchIn(text(it)) } ?: return DisplayMessage(html, null)
        val subject = text(dateIndex + 1).takeIf { it.isNotBlank() && it.length <= 200 }
        val last = if (subject != null) dateIndex + 1 else dateIndex
        val header = QuotedHeader(fromLabel.replace(text(0), "").trim(), text(dateIndex), subject.orEmpty())

        val touched = mutableListOf<Element>()
        var ancestor: Element? = hr
        while (ancestor != null && ancestor !== body) {
            ancestor.siblingNodes().filter { it.siblingIndex() < ancestor!!.siblingIndex() }.forEach { it.remove() }
            ancestor = ancestor.parent()
        }
        hr.parent()?.let(touched::add); hr.remove()
        (0..last).flatMap { content[it] }.forEach { node -> (node.parent() as? Element)?.let(touched::add); node.remove() }
        breaks.filter { (_, before) -> before <= last + 1 }.forEach { (br, _) -> br.parent()?.let(touched::add); br.remove() }
        fun Element.blank() = text().isBlank() && select("img, table, math, hr").isEmpty()
        touched.forEach { start ->
            var element: Element? = start
            while (element != null && element !== body && element.parent() != null && element.blank()) {
                val parent = element.parent(); element.remove(); element = parent
            }
        }
        while (true) {
            val first = body.childNodes().firstOrNull() ?: break
            val blank = (first is TextNode && first.text().isBlank()) || (first is Element && (first.normalName() == "br" || first.blank()))
            if (!blank) break
            first.remove()
        }
        return DisplayMessage(body.html(), header)
    }
}
