package com.feldman.scholix.attendance

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.json.JSONObject

object AttendancePortionsStorage {
    private const val PREFS_NAME = "attendance_portions_prefs"

    private const val KEY_ENABLED = "portions_enabled"
    private const val KEY_BONUS_POINTS = "portions_bonus_points"
    private const val KEY_BONUS_THRESHOLD = "portions_bonus_threshold"
    private const val KEY_MAX_JUSTIFIED = "portions_max_justified"
    private const val KEY_NEUTRAL_THRESHOLD = "portions_neutral_threshold"
    private const val KEY_DEDUCTION_FIRST = "portions_deduction_first"
    private const val KEY_DEDUCTION_STEP = "portions_deduction_step"
    private const val KEY_FAILING_THRESHOLD = "portions_failing_threshold"
    private const val KEY_DEFAULT_WEEKLY_HOURS = "portions_default_weekly_hours"
    private const val KEY_LATENESS_CONVERSION = "portions_lateness_conversion"
    private const val KEY_SUBJECT_HOURS_JSON = "portions_subject_hours_json"

    private fun getPrefs(context: Context, providerId: String = "", subject: String = ""): SharedPreferences {
        val scope = if (providerId.isBlank()) "" else "_" + java.security.MessageDigest.getInstance("SHA-256")
            .digest("$providerId\u0000$subject".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return context.getSharedPreferences(PREFS_NAME + scope, Context.MODE_PRIVATE)
    }

    fun getPortionsConfig(context: Context, providerId: String = "", subject: String = ""): PortionsConfig {
        val scoped = getPrefs(context, providerId, subject)
        val prefs = if (scoped.contains(KEY_ENABLED) || providerId.isBlank()) scoped else getPrefs(context)
        val subjectHoursJson = prefs.getString(KEY_SUBJECT_HOURS_JSON, null)
        val subjectHours = mutableMapOf<String, Int>()
        if (!subjectHoursJson.isNullOrBlank()) {
            runCatching {
                val json = JSONObject(subjectHoursJson)
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    subjectHours[key] = json.getInt(key)
                }
            }
        }

        return PortionsConfig(
            enabled = prefs.getBoolean(KEY_ENABLED, true),
            bonusPoints = prefs.getInt(KEY_BONUS_POINTS, 2),
            bonusThresholdPortions = prefs.getFloat(KEY_BONUS_THRESHOLD, 1.0f),
            maxJustifiedPortionsForBonus = prefs.getFloat(KEY_MAX_JUSTIFIED, 2.0f),
            neutralThresholdPortions = prefs.getFloat(KEY_NEUTRAL_THRESHOLD, 2.0f),
            deductionAtFirstThreshold = prefs.getInt(KEY_DEDUCTION_FIRST, 5),
            deductionPerExtraPortion = prefs.getInt(KEY_DEDUCTION_STEP, 2),
            failingThresholdPortions = prefs.getFloat(KEY_FAILING_THRESHOLD, 5.0f),
            defaultWeeklyHours = prefs.getInt(KEY_DEFAULT_WEEKLY_HOURS, 2),
            latenessesPerAbsence = prefs.getInt(KEY_LATENESS_CONVERSION, 0),
            subjectWeeklyHours = subjectHours
        )
    }

    fun savePortionsConfig(context: Context, config: PortionsConfig, providerId: String = "", subject: String = "") {
        val json = JSONObject()
        config.subjectWeeklyHours.forEach { (sub, hours) ->
            json.put(sub, hours)
        }
        getPrefs(context, providerId, subject).edit {
            putBoolean(KEY_ENABLED, config.enabled)
            putInt(KEY_BONUS_POINTS, config.bonusPoints)
            putFloat(KEY_BONUS_THRESHOLD, config.bonusThresholdPortions)
            putFloat(KEY_MAX_JUSTIFIED, config.maxJustifiedPortionsForBonus)
            putFloat(KEY_NEUTRAL_THRESHOLD, config.neutralThresholdPortions)
            putInt(KEY_DEDUCTION_FIRST, config.deductionAtFirstThreshold)
            putInt(KEY_DEDUCTION_STEP, config.deductionPerExtraPortion)
            putFloat(KEY_FAILING_THRESHOLD, config.failingThresholdPortions)
            putInt(KEY_DEFAULT_WEEKLY_HOURS, config.defaultWeeklyHours)
            putInt(KEY_LATENESS_CONVERSION, config.latenessesPerAbsence)
            putString(KEY_SUBJECT_HOURS_JSON, json.toString())
        }
    }

    fun setSubjectWeeklyHours(context: Context, subject: String, hours: Int) {
        val current = getPortionsConfig(context)
        val updatedMap = current.subjectWeeklyHours.toMutableMap()
        if (hours > 0) {
            updatedMap[subject] = hours
        } else {
            updatedMap.remove(subject)
        }
        savePortionsConfig(context, current.copy(subjectWeeklyHours = updatedMap))
    }

    fun resetToDefaults(context: Context, providerId: String, subject: String) {
        savePortionsConfig(context, PortionsConfig(), providerId, subject)
    }

    fun configFlow(context: Context, providerId: String = "", subject: String = ""): Flow<PortionsConfig> = callbackFlow {
        val prefs = getPrefs(context, providerId, subject)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            trySend(getPortionsConfig(context, providerId, subject))
        }
        trySend(getPortionsConfig(context, providerId, subject))
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }
}
