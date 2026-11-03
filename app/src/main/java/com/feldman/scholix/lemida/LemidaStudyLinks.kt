package com.feldman.scholix.lemida

import android.content.Intent
import com.feldman.scholix.studies.StudyAssignment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The study identity of a Lemida activity: the same key Documents saves beside the study. */
internal fun Homework.studyAssignment(account: String, status: String = "", checkedAt: Long = 0): StudyAssignment =
    StudyAssignment.create(LemidaParser.BASE, account, courseId, type, id.substringAfterLast(':'), title, course,
        status, checkedAt)

internal fun Homework.studyKey(account: String): String? =
    runCatching { studyAssignment(account).key }.getOrNull()

/**
 * "Open Scholix homework" from a study in Documents names the assignment by its study key. The
 * homework page opens that assignment when it belongs to the signed-in Lemida account.
 */
object HomeworkLinks {
    const val EXTRA_ASSIGNMENT = "open_assignment"
    private val key = Regex("[a-f0-9]{64}")
    private val pendingKey = MutableStateFlow<String?>(null)
    val pending = pendingKey.asStateFlow()

    fun request(intent: Intent?) {
        intent?.getStringExtra(EXTRA_ASSIGNMENT)?.takeIf { it.matches(key) }?.let { pendingKey.value = it }
    }

    /** The homework item this request names, if any. */
    fun match(request: String, homework: List<Homework>, account: String): Homework? =
        if (account.isBlank()) null else homework.firstOrNull { it.studyKey(account) == request }

    fun consume(request: String) { pendingKey.compareAndSet(request, null) }
}
