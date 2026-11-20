package com.feldman.scholix.lemida

/** Text from an assignment page with the lines that only name an attached file, or its upload time, left out. */
internal object LemidaDetailText {
    private val uploadTime = Regex("^\\d{1,2}/\\d{1,2}/\\d{4},?\\s+\\d{1,2}:\\d{2}$")

    fun withoutAttachments(text: String, attachments: List<String>): String {
        val names = attachments.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (names.isEmpty()) return text.trim()
        val lines = text.lines().map { it.trim() }
        val kept = lines.filterIndexed { index, line ->
            line !in names && !(uploadTime.matches(line) && lines.getOrNull(index - 1) in names)
        }
        return kept.joinToString("\n").trim()
    }
}
