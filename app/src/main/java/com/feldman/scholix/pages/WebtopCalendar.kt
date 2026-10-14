package com.feldman.scholix.pages

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun parseWebtopCalendarBoard(
    board: JSONObject,
    studentClass: String?,
): List<SchoolCalendarItem> {
    val data = board.optJSONObject("data") ?: return emptyList()
    val ownClass = studentClass
        ?.split('|', limit = 2)
        ?.takeIf { it.size == 2 }
        ?.let { it[0].trim() to it[1].trim() }

    // On the pupil timetable page Webtop labels this list "MyStudyGroups". Use it to
    // keep group-targeted tests/events personal even when they do not also carry a class target.
    val ownStudyGroups = data.optJSONArray("studyGroups").jsonObjects()
        .flatMap { group ->
            listOf(
                group.opt("id")?.toString().orEmpty(),
                group.optString("itemId"),
                group.optString("title"),
            )
        }
        .map(::normalizeTarget)
        .filter(String::isNotBlank)
        .toSet()

    fun targetedToStudent(item: JSONObject, groupField: String): Boolean {
        val classes = item.optJSONArray("classes").jsonObjects()
        val classMatch = ownClass != null && classes.any { clazz ->
            clazz.opt("classCode")?.toString()?.trim() == ownClass.first &&
                clazz.opt("classNumber")?.toString()?.trim() == ownClass.second
        }

        val groups = item.optJSONArray(groupField).jsonObjects()
        val groupTargets = groups.flatMap { group ->
            listOf(
                group.opt("id")?.toString().orEmpty(),
                group.opt("studyGroupId")?.toString().orEmpty(),
                group.optString("itemId"),
                group.optString("title"),
            )
        }.map(::normalizeTarget).filter(String::isNotBlank).toSet()
        val groupMatch = groupTargets.any { it in ownStudyGroups }

        // Completely untargeted board items are school-wide; class/group-targeted items must
        // actually include this pupil.
        return when {
            classes.isEmpty() && groupTargets.isEmpty() -> true
            classMatch -> true
            groupMatch -> true
            else -> false
        }
    }

    val hours = data.optJSONArray("hours").jsonObjects().associateBy {
        it.optInt("hour", Int.MIN_VALUE)
    }
    fun hourLabel(start: Int?, endExclusive: Int?): String {
        if (start == null) return ""
        val from = hours[start]?.optString("fromTime").orEmpty()
        val endKey = endExclusive?.minus(1)
        val to = endKey?.let { hours[it]?.optString("toTime") }.orEmpty()
        return when {
            from.isNotBlank() && to.isNotBlank() -> "$from–$to"
            from.isNotBlank() -> from
            endExclusive != null && endExclusive > start -> "Lessons $start–${endExclusive - 1}"
            else -> "Lesson $start"
        }
    }

    val result = mutableListOf<SchoolCalendarItem>()

    data.optJSONArray("events").jsonObjects().forEach { event ->
        if (!targetedToStudent(event, "studyGroups")) return@forEach
        val startDate = parseWebtopBoardDate(event.optString("from_date")) ?: return@forEach
        val endDate = parseWebtopBoardDate(event.optString("to_date")) ?: startDate
        val title = event.optString("title").ifBlank { event.optString("type", "Event") }
        val type = event.optString("type")
        val fromHour = event.opt("from_hour")?.toString()?.toIntOrNull()
        val toHour = event.opt("to_hour")?.toString()?.toIntOrNull()
        val groupTitles = event.optJSONArray("studyGroups").jsonObjects()
            .mapNotNull { it.optString("title").takeIf(String::isNotBlank) }
        val details = buildList {
            hourLabel(fromHour, toHour).takeIf(String::isNotBlank)?.let(::add)
            event.optString("note").takeIf(String::isNotBlank)?.let(::add)
            event.optString("job").takeIf(String::isNotBlank)?.let(::add)
            addAll(groupTitles)
        }.distinct()
        val rooms = event.rooms()

        var date = startDate
        val cappedEnd = if (endDate.isBefore(startDate)) startDate else endDate
        while (!date.isAfter(cappedEnd)) {
            result += SchoolCalendarItem(
                id = "webtop-event:${event.optString("id")}:${date}:$title",
                date = date,
                title = title,
                subtitle = type,
                details = details,
                rooms = rooms,
                isExam = false,
            )
            date = date.plusDays(1)
        }
    }

    data.optJSONArray("exams").jsonObjects().forEach { exam ->
        if (!targetedToStudent(exam, "groupsList")) return@forEach
        val date = parseWebtopBoardDate(exam.optString("date")) ?: return@forEach
        val title = exam.optString("title").ifBlank { exam.optString("type", "Test") }
        val type = exam.optString("type")
        val fromHour = exam.opt("fromHour")?.toString()?.toIntOrNull()
        val toHour = exam.opt("toHour")?.toString()?.toIntOrNull()
        val groupTitles = exam.optJSONArray("groupsList").jsonObjects()
            .mapNotNull { it.optString("title").takeIf(String::isNotBlank) }
        val details = buildList {
            hourLabel(fromHour, toHour).takeIf(String::isNotBlank)?.let(::add)
            addAll(groupTitles)
        }.distinct()
        result += SchoolCalendarItem(
            id = "webtop-exam:${exam.optString("id")}:${date}:$title",
            date = date,
            title = title,
            subtitle = type,
            details = details,
            rooms = exam.rooms(),
            isExam = true,
        )
    }

    data.optJSONArray("vactications").jsonObjects().forEach { holiday ->
        val start = parseWebtopBoardDate(holiday.optString("startDate")) ?: return@forEach
        val end = parseWebtopBoardDate(holiday.optString("endDate")) ?: start
        val title = holiday.optString("name").takeIf(String::isNotBlank) ?: return@forEach
        var date = start
        while (!date.isAfter(end)) {
            result += SchoolCalendarItem(
                id = "webtop-holiday:${holiday.optString("id")}:$date",
                date = date, title = title, subtitle = "School holiday",
            )
            date = date.plusDays(1)
        }
    }

    return result.distinctBy { it.id }
}

private fun JSONObject.rooms(): List<String> {
    val value = opt("rooms") ?: opt("room") ?: return emptyList()
    return when (value) {
        is JSONArray -> (0 until value.length()).mapNotNull { index ->
            when (val room = value.opt(index)) {
                is JSONObject -> room.optString("title")
                    .ifBlank { room.optString("name") }
                    .takeIf(String::isNotBlank)
                null -> null
                else -> room.toString().trim().takeIf(String::isNotBlank)
            }
        }
        else -> value.toString()
            .split(',', '|')
            .map(String::trim)
            .filter(String::isNotBlank)
    }.distinct()
}

private fun JSONArray?.jsonObjects(): List<JSONObject> = if (this == null) emptyList()
else (0 until length()).mapNotNull(::optJSONObject)

private fun normalizeTarget(value: String): String = value
    .lowercase()
    .replace(Regex("\\s+"), " ")
    .trim()

private fun parseWebtopBoardDate(raw: String): LocalDate? {
    val value = raw.trim()
    if (value.isBlank()) return null
    Regex("""(\d{4})-(\d{2})-(\d{2})""").find(value)?.let { match ->
        return runCatching {
            LocalDate.of(
                match.groupValues[1].toInt(),
                match.groupValues[2].toInt(),
                match.groupValues[3].toInt(),
            )
        }.getOrNull()
    }
    Regex("""(\d{1,2})/(\d{1,2})/(\d{4})""").find(value)?.let { match ->
        return runCatching {
            LocalDate.of(
                match.groupValues[3].toInt(),
                match.groupValues[2].toInt(),
                match.groupValues[1].toInt(),
            )
        }.getOrNull()
    }
    Regex("""/Date\((\d+)""").find(value)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let { millis ->
        return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
    }
    return runCatching { OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toLocalDate() }
        .getOrNull()
}
