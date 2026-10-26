package com.feldman.scholix.widgets

import com.feldman.scholix.api.PlatformStorage
import com.feldman.scholix.pages.WINDOW_FLAG
import com.feldman.scholix.pages.hourOf
import com.feldman.scholix.pages.parseLessonEndTimeMinutes
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime

internal fun scheduleWidgetDate(
    today: LocalDate,
    now: LocalTime,
    lessons: List<JSONObject>,
    windowSubjects: Set<String>,
): LocalDate {
    val active = lessons.filter {
        !it.optBoolean(WINDOW_FLAG) && it.optString("colorClass") != "cancel-cell" &&
            !PlatformStorage.isWindowSubject(it.optString("subject"), windowSubjects)
    }
    val end = active.maxOfOrNull { parseLessonEndTimeMinutes(it.optString("time"), hourOf(it)) }
    return if (end == null || now.hour * 60 + now.minute >= end) today.plusDays(1) else today
}
