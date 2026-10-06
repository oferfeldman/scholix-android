package com.feldman.scholix.api.platforms

/** Keeps a requested sign-in alive across page disposal; callers join it instead of sending again. */
internal class InbarSmsAttemptRegistry<T>(
    private val completed: (T) -> Boolean,
    private val retainCompleted: (T) -> Boolean = { true },
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val retentionMs: Long = 90_000,
) {
    private data class Entry<T>(val createdAt: Long, val attempt: T)
    private val entries = mutableMapOf<String, Entry<T>>()
    @Synchronized fun find(key: String): T? {
        entries.entries.removeAll {
            completed(it.value.attempt) && (!retainCompleted(it.value.attempt) ||
                nowMs() - it.value.createdAt >= retentionMs)
        }
        return entries[key]?.attempt
    }
    @Synchronized fun getOrCreate(key: String, create: () -> T): T =
        find(key) ?: create().also { entries[key] = Entry(nowMs(), it) }
}
