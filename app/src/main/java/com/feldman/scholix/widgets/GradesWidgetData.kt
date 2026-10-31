package com.feldman.scholix.widgets

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import com.feldman.scholix.api.Platform
import com.feldman.scholix.api.PlatformStorage
import com.feldman.scholix.api.platforms.WebtopPlatform
import com.feldman.scholix.ui.components.providerLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

internal fun fetchGradesWidgetGrades(context: Context, platform: Platform): JSONArray {
    val all = JSONArray()
    val cookies = (platform as? WebtopPlatform)?.cookies
    try {
        val courses = if (platform is WebtopPlatform) listOf(org.json.JSONObject().put("courseKey", "Webtop"))
            else PlatformStorage.getProviderCourses(context, platform)
        courses.forEach { course ->
            // Embedded course grades and the monitor cache can belong to an older semester.
            val current = platform.getGrades(course.optString("courseKey").ifBlank { course.optString("name") })
            for (i in 0 until current.length()) {
                val grade = current.getJSONObject(i)
                check(!grade.has("error")) { grade.optString("error") }
                all.put(grade)
            }
        }
    } finally {
        if (platform is WebtopPlatform) PlatformStorage.saveWebtopSession(context, platform, cookies)
    }
    return all
}

internal suspend fun refreshGradesWidgetState(context: Context, id: GlanceId) = withContext(Dispatchers.IO) {
    val prefs = getAppWidgetState(context, PreferencesGlanceStateDefinition, id)
    try {
        val platforms = PlatformStorage.loadPlatforms(context).filter { it.suportsGrades }
        val providerId = prefs[gradesProviderKey]
        val legacyName = prefs[courseNameKey]
        val provider = if (providerId != null) platforms.firstOrNull { it.id == providerId }
            else platforms.firstOrNull { it.providerLabel() == legacyName }
                ?: platforms.firstOrNull { platform -> PlatformStorage.getProviderCourses(context, platform).any { it.optString("name") == legacyName } }
        check(provider != null) { "Choose a provider" }
        val grades = fetchGradesWidgetGrades(context, provider)
        val visible = com.feldman.scholix.pages.processGrades(grades)
        android.util.Log.i("GradesWidgetData", "Live response: ${grades.length()} records, ${visible.first.size} visible grades, final=${visible.third != null}")
        updateAppWidgetState(context, id) {
            it[gradesProviderKey] = provider.id
            it[gradesDataKey] = grades.toString()
            it[gradesUpdatedKey] = System.currentTimeMillis().toString()
            it.remove(gradesErrorKey)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        updateAppWidgetState(context, id) {
            it[gradesUpdatedKey] = System.currentTimeMillis().toString()
            it[gradesErrorKey] = if (e.message == "login_failed") "Sign in again in Scholix" else "Could not refresh grades"
        }
    }
}
