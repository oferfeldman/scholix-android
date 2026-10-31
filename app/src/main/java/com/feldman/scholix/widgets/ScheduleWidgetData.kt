package com.feldman.scholix.widgets

import com.feldman.scholix.api.PlatformStorage
import com.feldman.scholix.pages.WINDOW_FLAG
import com.feldman.scholix.pages.hourOf
import com.feldman.scholix.pages.parseLessonEndTimeMinutes
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime

internal fun scheduleWidgetLessonMinutes(lesson: JSONObject): IntRange {
    val times = Regex("\\d{1,2}:\\d{2}").findAll(lesson.optString("time")).map {
        val (hour, minute) = it.value.split(':').map(String::toInt)
        hour * 60 + minute
    }.toList()
    val end = parseLessonEndTimeMinutes(lesson.optString("time"), hourOf(lesson))
    val start = if (times.size >= 2) times.min() else (end - 45).coerceAtLeast(0)
    return start until end
}

internal fun scheduleWidgetActiveIndex(lessons: List<JSONObject>, now: LocalTime, isToday: Boolean): Int {
    val active = lessons.indices.filter {
        !lessons[it].optBoolean(WINDOW_FLAG) && lessons[it].optString("colorClass") != "cancel-cell"
    }
    if (!isToday) return active.firstOrNull() ?: -1
    val minute = now.hour * 60 + now.minute
    return active.firstOrNull { minute in scheduleWidgetLessonMinutes(lessons[it]) }
        ?: active.filter { scheduleWidgetLessonMinutes(lessons[it]).first > minute }
            .minByOrNull { scheduleWidgetLessonMinutes(lessons[it]).first }
        ?: -1
}

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
