package com.feldman.scholix.drive

import java.time.Instant
import java.util.Locale

enum class StarNoteSort(val label: String) { Recent("Drive date"), Title("Name") }

/** Library search uses names people can recognize, never StarNote's storage identifiers. */
object StarNoteLibrary {
    const val UNTITLED = "Untitled StarNote"
    private val identifier = Regex("[a-fA-F0-9-]{32,36}")
    private fun name(value: String) = value.trim().takeUnless { it.isBlank() || identifier.matches(it) }.orEmpty()

    fun title(note: DriveItem, resolved: String = ""): String =
        name(note.name).ifBlank { name(resolved) }.ifBlank { name(note.sourceTitle) }.ifBlank { UNTITLED }

    fun hasTitle(note: DriveItem, resolved: String = "") =
        listOf(note.name, resolved, note.sourceTitle).any { name(it).isNotBlank() }

    fun visible(notes: List<DriveItem>, resolved: Map<String, String>, query: String, sort: StarNoteSort): List<DriveItem> {
        val words = query.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotBlank() }
        val matches = notes.filter { note ->
            val names = listOf(title(note, resolved[note.id].orEmpty()), name(note.sourceTitle), name(resolved[note.id].orEmpty()))
                .joinToString(" ").lowercase(Locale.ROOT)
            words.all { it in names }
        }
        return when (sort) {
            StarNoteSort.Recent -> matches.sortedWith(compareByDescending<DriveItem> {
                runCatching { Instant.parse(it.modified).toEpochMilli() }.getOrDefault(Long.MIN_VALUE)
            }.thenBy { title(it, resolved[it.id].orEmpty()).lowercase(Locale.ROOT) }.thenBy { it.id })
            StarNoteSort.Title -> matches.sortedWith(compareBy<DriveItem> {
                !hasTitle(it, resolved[it.id].orEmpty())
            }.thenBy { title(it, resolved[it.id].orEmpty()).lowercase(Locale.ROOT) }.thenBy { it.id })
        }
    }
}
