package com.feldman.scholix.attendance

import org.json.JSONObject
import kotlin.math.floor
import kotlin.math.max

enum class GradeImpactStatus {
    BONUS,
    NEUTRAL,
    DEDUCTION,
    FAILING
}

data class PortionsConfig(
    val enabled: Boolean = true,
    val bonusPoints: Int = 2,
    val bonusThresholdPortions: Float = 1.0f,
    val maxJustifiedPortionsForBonus: Float = 2.0f,
    val neutralThresholdPortions: Float = 2.0f,
    val deductionAtFirstThreshold: Int = 5,
    val deductionPerExtraPortion: Int = 2,
    val failingThresholdPortions: Float = 5.0f,
    val defaultWeeklyHours: Int = 2,
    val latenessesPerAbsence: Int = 0, // 0 = disabled, 3 = 3 latenesses count as 1 absence
    val subjectWeeklyHours: Map<String, Int> = emptyMap()
)

data class SubjectPortionsSummary(
    val subject: String,
    val weeklyHours: Int,
    val unjustifiedHours: Int,
    val justifiedHours: Int,
    val latenesses: Int,
    val effectiveAbsenceHours: Float,
    val portions: Float,
    val justifiedPortions: Float,
    val status: GradeImpactStatus,
    val pointsDelta: Int,
    val isFailing: Boolean,
    val hoursUntilNextPenalty: Int?,
    val hoursUntilBonusLost: Int?,
    val detailsMessage: String
)

data class SchoolTableRow(
    val weeklyHours: Int,
    val bonusHoursText: String,
    val neutralHoursText: String,
    val minus5HoursText: String,
    val minus7HoursText: String,
    val minus9HoursText: String,
    val failingHoursText: String
)

object AttendancePortionsCalculator {

    fun calculate(
        subject: String,
        unjustifiedHours: Int,
        justifiedHours: Int,
        latenesses: Int = 0,
        weeklyHours: Int,
        config: PortionsConfig = PortionsConfig()
    ): SubjectPortionsSummary {
        val w = max(1, weeklyHours)
        val latenessAbsenceBonus = if (config.latenessesPerAbsence > 0) {
            (latenesses / config.latenessesPerAbsence).toFloat()
        } else {
            0f
        }
        val effectiveAbsenceHours = max(0f, unjustifiedHours.toFloat() + latenessAbsenceBonus)
        val portions = effectiveAbsenceHours / w.toFloat()
        val justifiedPortions = max(0f, justifiedHours.toFloat() / w.toFloat())

        val satisfiesBonusPortions = portions <= config.bonusThresholdPortions
        val satisfiesJustifiedLimit = justifiedPortions <= config.maxJustifiedPortionsForBonus

        if (satisfiesBonusPortions && satisfiesJustifiedLimit) {
            val hoursUntilBonusLost = max(0, (w * config.bonusThresholdPortions).toInt() - effectiveAbsenceHours.toInt())
            val hoursUntilPenalty = if (w == 1) {
                max(0, 3 - effectiveAbsenceHours.toInt())
            } else {
                max(0, 2 * w - effectiveAbsenceHours.toInt())
            }

            return SubjectPortionsSummary(
                subject = subject,
                weeklyHours = w,
                unjustifiedHours = unjustifiedHours,
                justifiedHours = justifiedHours,
                latenesses = latenesses,
                effectiveAbsenceHours = effectiveAbsenceHours,
                portions = portions,
                justifiedPortions = justifiedPortions,
                status = GradeImpactStatus.BONUS,
                pointsDelta = config.bonusPoints,
                isFailing = false,
                hoursUntilNextPenalty = hoursUntilPenalty,
                hoursUntilBonusLost = hoursUntilBonusLost,
                detailsMessage = "בונוס פעיל: +${config.bonusPoints} נקודות לציון"
            )
        }

        val isNeutral = if (w == 1) {
            effectiveAbsenceHours <= 2f
        } else {
            effectiveAbsenceHours < 2 * w
        }

        if (isNeutral) {
            val hoursUntilPenalty = if (w == 1) {
                max(0, 3 - effectiveAbsenceHours.toInt())
            } else {
                max(0, 2 * w - effectiveAbsenceHours.toInt())
            }

            return SubjectPortionsSummary(
                subject = subject,
                weeklyHours = w,
                unjustifiedHours = unjustifiedHours,
                justifiedHours = justifiedHours,
                latenesses = latenesses,
                effectiveAbsenceHours = effectiveAbsenceHours,
                portions = portions,
                justifiedPortions = justifiedPortions,
                status = GradeImpactStatus.NEUTRAL,
                pointsDelta = 0,
                isFailing = false,
                hoursUntilNextPenalty = hoursUntilPenalty,
                hoursUntilBonusLost = null,
                detailsMessage = "ללא שינוי בציון (0 נקודות)"
            )
        }

        if (w == 1) {
            val intHours = effectiveAbsenceHours.toInt()
            if (intHours >= 6) {
                val extraPortions = max(0, intHours - 3)
                val penalty = config.deductionAtFirstThreshold + extraPortions * config.deductionPerExtraPortion
                return SubjectPortionsSummary(
                    subject = subject,
                    weeklyHours = w,
                    unjustifiedHours = unjustifiedHours,
                    justifiedHours = justifiedHours,
                    latenesses = latenesses,
                    effectiveAbsenceHours = effectiveAbsenceHours,
                    portions = portions,
                    justifiedPortions = justifiedPortions,
                    status = GradeImpactStatus.FAILING,
                    pointsDelta = -penalty,
                    isFailing = true,
                    hoursUntilNextPenalty = null,
                    hoursUntilBonusLost = null,
                    detailsMessage = "צבירת מנות מרבית: ציון שלילי במקצוע"
                )
            } else {
                val extraPortions = max(0, intHours - 3)
                val penalty = config.deductionAtFirstThreshold + extraPortions * config.deductionPerExtraPortion
                return SubjectPortionsSummary(
                    subject = subject,
                    weeklyHours = w,
                    unjustifiedHours = unjustifiedHours,
                    justifiedHours = justifiedHours,
                    latenesses = latenesses,
                    effectiveAbsenceHours = effectiveAbsenceHours,
                    portions = portions,
                    justifiedPortions = justifiedPortions,
                    status = GradeImpactStatus.DEDUCTION,
                    pointsDelta = -penalty,
                    isFailing = false,
                    hoursUntilNextPenalty = 1,
                    hoursUntilBonusLost = null,
                    detailsMessage = "הפחתה של $penalty נקודות מהציון"
                )
            }
        }

        val fullPortions = floor(portions).toInt()
        val isFailing = portions >= config.failingThresholdPortions

        val extraPortions = max(0, fullPortions - 2)
        val penalty = config.deductionAtFirstThreshold + extraPortions * config.deductionPerExtraPortion

        if (isFailing) {
            return SubjectPortionsSummary(
                subject = subject,
                weeklyHours = w,
                unjustifiedHours = unjustifiedHours,
                justifiedHours = justifiedHours,
                latenesses = latenesses,
                effectiveAbsenceHours = effectiveAbsenceHours,
                portions = portions,
                justifiedPortions = justifiedPortions,
                status = GradeImpactStatus.FAILING,
                pointsDelta = -penalty,
                isFailing = true,
                hoursUntilNextPenalty = null,
                hoursUntilBonusLost = null,
                detailsMessage = "צבירת 5 מנות ויותר: ציון שלילי במקצוע"
            )
        }

        val nextThresholdHours = (fullPortions + 1) * w
        val hoursUntilNext = max(0, nextThresholdHours - effectiveAbsenceHours.toInt())

        return SubjectPortionsSummary(
            subject = subject,
            weeklyHours = w,
            unjustifiedHours = unjustifiedHours,
            justifiedHours = justifiedHours,
            latenesses = latenesses,
            effectiveAbsenceHours = effectiveAbsenceHours,
            portions = portions,
            justifiedPortions = justifiedPortions,
            status = GradeImpactStatus.DEDUCTION,
            pointsDelta = -penalty,
            isFailing = false,
            hoursUntilNextPenalty = hoursUntilNext,
            hoursUntilBonusLost = null,
            detailsMessage = "הפחתה של $penalty נקודות מהציון"
        )
    }

    fun buildSchoolReferenceTable(): List<SchoolTableRow> {
        return (1..9).map { w ->
            if (w == 1) {
                SchoolTableRow(
                    weeklyHours = 1,
                    bonusHoursText = "1",
                    neutralHoursText = "2",
                    minus5HoursText = "3",
                    minus7HoursText = "4",
                    minus9HoursText = "5",
                    failingHoursText = "6 ומעלה"
                )
            } else {
                val neutralRange = if (w + 1 == 2 * w - 1) "${w + 1}" else "${w + 1}-${2 * w - 1}"
                SchoolTableRow(
                    weeklyHours = w,
                    bonusHoursText = "$w",
                    neutralHoursText = neutralRange,
                    minus5HoursText = "${2 * w}",
                    minus7HoursText = "${3 * w}",
                    minus9HoursText = "${4 * w}",
                    failingHoursText = "${5 * w} ומעלה"
                )
            }
        }
    }

    fun parseEvents(
        eventsMap: Map<String, List<JSONObject>>,
        config: PortionsConfig,
        scheduleHoursBySubject: Map<String, Int> = emptyMap()
    ): List<SubjectPortionsSummary> {
        val allEvents = eventsMap.flatMap { it.value }
        if (allEvents.isEmpty()) return emptyList()

        val bySubject = allEvents.groupBy { it.optString("subject").trim() }
            .filterKeys { it.isNotBlank() }

        return bySubject.map { (subject, subjectEvents) ->
            var unjustified = 0
            var justified = 0
            var lateness = 0

            for (event in subjectEvents) {
                val type = event.optString("type").trim()
                val isJustified = event.optBoolean("isJustified", false) ||
                        type.contains("מוצדק") ||
                        type.contains("justified", ignoreCase = true)

                val isAbsence = type.contains("חיסור") ||
                        type.contains("העדרות") ||
                        type.contains("היעדרות") ||
                        type.contains("absence", ignoreCase = true)

                val isLate = type.contains("איחור") ||
                        type.contains("late", ignoreCase = true) ||
                        type.contains("tardiness", ignoreCase = true)

                if (isAbsence) {
                    if (isJustified) {
                        justified++
                    } else {
                        unjustified++
                    }
                } else if (isLate) {
                    lateness++
                }
            }

            val weeklyHours = config.subjectWeeklyHours[subject]
                ?: scheduleHoursBySubject[subject]
                ?: config.defaultWeeklyHours

            calculate(
                subject = subject,
                unjustifiedHours = unjustified,
                justifiedHours = justified,
                latenesses = lateness,
                weeklyHours = weeklyHours,
                config = config
            )
        }.sortedWith(
            compareByDescending<SubjectPortionsSummary> { it.isFailing }
                .thenBy { it.pointsDelta }
                .thenByDescending { it.portions }
                .thenBy { it.subject }
        )
    }
}
