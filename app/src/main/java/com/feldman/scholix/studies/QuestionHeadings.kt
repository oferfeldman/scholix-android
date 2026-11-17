package com.feldman.scholix.studies

/** One run of text as the PDF places it, in fractions of its page (top/bottom from the page's top). */
internal data class PdfWord(val page: Int, val top: Float, val bottom: Float, val left: Float, val right: Float, val text: String)

/** Where a printed question starts. [section] is the heading of the part it is printed under, such as bonus exercises. */
internal data class QuestionHeading(val number: String, val page: Int, val top: Float, val section: String? = null)

/** Where a part such as "תרגילי בונוס" starts; it ends the question before it. */
internal data class SectionHeading(val title: String, val page: Int, val top: Float)

internal data class PrintedQuestions(val questions: List<QuestionHeading>, val sections: List<SectionHeading>)

/**
 * Finds the printed question headings of a homework sheet: "שאלה 3:", "תרגיל .2", "* שאלה 4:", "Question 1".
 * PDF text extraction often hands Hebrew over one word at a time, so words are first put back into lines
 * by their position, in reading order, and only the start of a line can be a heading. References inside
 * sentences ("בשאלה 3", "ראו שאלה 2") and titles ("תרגיל בית 5") are not headings.
 */
internal object QuestionHeadings {
    private val hebrewWords = setOf("שאלה", "תרגיל")
    private val latinWords = setOf("question", "exercise", "problem")
    private val numberToken = Regex("^[.():\\-]*(\\d{1,3})(\\*?)[.():\\-]*(\\*?)$")
    private val glued = Regex("^(?:(\\d{1,3})(שאלה|תרגיל)|(שאלה|תרגיל)(\\d{1,3}))[.:]?$")
    // Not \b: Java's word boundary doesn't treat Hebrew letters as word characters.
    private val section = Regex("^(?:(?:תרגילי|שאלות)\\s+(?:בונוס|רשות|אתגר)|bonus(?:\\s+(?:questions|exercises|problems))?)[:.]?(?:\\s.*)?$",
        RegexOption.IGNORE_CASE)
    private val hebrew = Regex("[\\u0590-\\u05FF]")

    fun find(words: List<PdfWord>): PrintedQuestions {
        val questions = mutableListOf<QuestionHeading>()
        val sections = mutableListOf<SectionHeading>()
        for (line in lines(words)) {
            val tokens = line.flatMap { it.text.trim().split(Regex("\\s+")) }.filter { it.isNotEmpty() }
            if (tokens.isEmpty()) continue
            val top = line.minOf { it.top }
            val page = line.first().page
            val text = tokens.joinToString(" ")
            if (section.matches(text) && text.length <= 60) {
                sections += SectionHeading(text.trimEnd(':', '.', ' '), page, top)
                continue
            }
            val expected = (questions.lastOrNull()?.number?.trimEnd('*')?.toIntOrNull() ?: 0) + 1
            heading(tokens, expected)?.let { number ->
                if (questions.none { it.number.trimEnd('*') == number.trimEnd('*') })
                    questions += QuestionHeading(number, page, top, sections.lastOrNull()?.title)
            }
        }
        return PrintedQuestions(questions, sections)
    }

    /** The question number when [tokens] (one line, in reading order) start with a heading. */
    private fun heading(tokens: List<String>, expected: Int): String? {
        var index = 0
        var starred = false
        while (index < tokens.size && tokens[index].all { it == '*' || it == '•' || it == '(' || it == ')' }) {
            starred = starred || '*' in tokens[index]; index++
        }
        val first = tokens.getOrNull(index) ?: return null
        glued.matchEntire(first)?.let { match ->
            val number = match.groupValues[1].ifEmpty { match.groupValues[4] }
            return number + if (starred) "*" else ""
        }
        if (!isHeadingWord(first)) return null
        val next = tokens.getOrNull(index + 1)
        // "תרגיל בית 5" is the sheet's title, not a question.
        if (next == null || next == "בית" || next == "ביתי") return null
        numberToken.matchEntire(next)?.let { match ->
            val star = starred || match.groupValues[2].isNotEmpty() || match.groupValues[3].isNotEmpty() ||
                tokens.getOrNull(index + 2)?.startsWith("*") == true
            return match.groupValues[1] + if (star) "*" else ""
        }
        // The number can be laid out away from the word ("תרגיל מצאו את ... .1"): accept only the one expected next.
        val elsewhere = tokens.drop(index + 1).mapNotNull { numberToken.matchEntire(it)?.groupValues?.get(1) }
        return if (isHebrewWord(first) && expected.toString() in elsewhere) expected.toString() + if (starred) "*" else "" else null
    }

    private fun isHeadingWord(token: String): Boolean {
        val word = token.trim(':', '.', ',', ')', '(').lowercase()
        return word in latinWords || isHebrewWord(token)
    }

    private fun isHebrewWord(token: String): Boolean {
        val word = token.trim(':', '.', ',', ')', '(')
        // Some PDFs store Hebrew in visual order, so the word comes out reversed.
        return word in hebrewWords || word.reversed() in hebrewWords
    }

    /** Words grouped into lines by page and baseline, each in reading order (right to left when it has Hebrew). */
    fun lines(words: List<PdfWord>): List<List<PdfWord>> {
        val lines = mutableListOf<MutableList<PdfWord>>()
        for (word in words.filter { it.text.isNotBlank() }.sortedWith(compareBy({ it.page }, { it.bottom }))) {
            // Same baseline, or the same top: a number set beside a tall bracket ends lower than its line.
            val line = lines.lastOrNull { line -> line.first().page == word.page &&
                (kotlin.math.abs(line.first().bottom - word.bottom) < 0.006f || kotlin.math.abs(line.first().top - word.top) < 0.004f) }
            if (line != null) line += word else lines += mutableListOf(word)
        }
        return lines.map { line ->
            if (line.any { hebrew.containsMatchIn(it.text) }) line.sortedByDescending { it.right } else line.sortedBy { it.left }
        }
    }
}
