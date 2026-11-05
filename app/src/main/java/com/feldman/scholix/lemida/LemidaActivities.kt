package com.feldman.scholix.lemida

/**
 * Which Lemida (Moodle) activities count as course work, and how Scholix groups them.
 *
 * Not every course hands work in through Moodle's own assignment module. Computer science
 * courses, for example, often run programming exercises in VPL, submit through an external
 * tool (LTI, such as a grading service), or post the exercise sheet as a file and collect it
 * elsewhere. Showing only assignments, quizzes and workshops hid all of those.
 */
object LemidaActivities {
    /** Activities that are work in themselves. */
    val workTypes = setOf("assign", "assignment", "quiz", "workshop", "vpl", "lti", "lesson", "h5pactivity", "hvp", "scorm")

    /** Posted material, which counts only when its title names an exercise or assignment. */
    val materialTypes = setOf("resource", "url", "folder", "page")

    val types = workTypes + materialTypes

    /** Section titles in the order they are shown. */
    val groups = listOf("Assignments", "Programming exercises", "Exercise files", "External tools",
        "Quizzes", "Workshops", "Lessons")

    fun group(type: String): String = when (type) {
        "assign", "assignment" -> "Assignments"
        "vpl" -> "Programming exercises"
        "lti" -> "External tools"
        "quiz" -> "Quizzes"
        "workshop" -> "Workshops"
        "lesson", "h5pactivity", "hvp", "scorm" -> "Lessons"
        else -> "Exercise files"
    }

    /** Whether Scholix can read a page of instructions for it; files and links open directly. */
    fun hasDetailPage(type: String) = type in workTypes

    /** What the button to Lemida's own page says for [type]. */
    fun openLabel(type: String) = when (type) {
        "quiz" -> "Open quiz on Lemida"
        "assign", "assignment", "workshop", "vpl" -> "Hand in on Lemida"
        else -> "Open on Lemida"
    }

    // Hebrew words are matched inside words, so prefixes such as ב, ל or ה still count
    // ("לתרגיל", "המטלה"). "תרגול" (a recitation) is a different word from "תרגיל".
    private val exerciseWords = Regex(
        "תרגיל|מטל[הת]|מטלות|עבוד[הת]|עבודות|שיעורי[\\s-]*בית|ש\"ב|פרויקט|" +
            "\\b(?:hw|homework|exercises?|assignments?|project|lab)\\b|\\bex\\.?\\s*\\d+|\\bhw\\s*\\d+",
        RegexOption.IGNORE_CASE,
    )
    private val solutionWords = Regex("פתרון|פתרונות|תשובות|\\bsolutions?\\b|\\bsol\\b|\\banswers?\\b",
        RegexOption.IGNORE_CASE)

    /** Whether posted material named [title] is an exercise to do, rather than its solution or notes. */
    fun isExerciseTitle(title: String): Boolean =
        exerciseWords.containsMatchIn(title) && !solutionWords.containsMatchIn(title)

    /** Whether an activity of [type] named [title] belongs in the work list. */
    fun counts(type: String, title: String): Boolean =
        type in workTypes || (type in materialTypes && isExerciseTitle(title))
}
