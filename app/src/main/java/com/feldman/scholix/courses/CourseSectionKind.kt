package com.feldman.scholix.courses

/** A course exposes only the sections its provider supports. */
enum class CourseSectionKind(val label: String) {
    OVERVIEW("Overview"), UNITS("Units"), ASSIGNMENTS("Assignments"),
    RECORDINGS("Recordings"), FORUMS("Forums"), ARCHIVE("Archive"), BOOKS("Books"),
    BOOKLET("Booklet"), FILES("Files"), GRADES("Grades"), STUDIES("Studies"),
}
