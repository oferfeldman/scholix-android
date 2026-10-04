package com.feldman.scholix.api

import android.content.Context
import android.util.Log
import com.feldman.app.api.BarIlanPlatform
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import androidx.core.content.edit
import com.feldman.motion.MotionSymbols
import com.feldman.scholix.R
import com.feldman.scholix.api.platforms.DemoPlatform
import com.feldman.scholix.api.platforms.InbarPlatform
import com.feldman.scholix.api.platforms.MashovPlatform
import com.feldman.scholix.api.platforms.OpenAUPlatform
import com.feldman.scholix.api.platforms.StudentsPortalPlatform
import com.feldman.scholix.api.platforms.WebtopPlatform
import java.util.Locale

data class PlatformInfo(
    val name: String,
    val iconRes: Int,
    val iconSymbol: String? = null,
    val factory: () -> Platform
)

data class ProviderCourseOverrides(
    val hiddenCourseKeys: Set<String> = emptySet(),
    val courseOrder: List<String> = emptyList(),
    /** Course key -> the name the user gave it, replacing the provider's. */
    val courseNames: Map<String, String> = emptyMap()
)

val platformOptions = listOf(
    PlatformInfo("Webtop", R.drawable.ic_webtop) { WebtopPlatform() as Platform },
    PlatformInfo("Bar-Ilan", R.drawable.ic_bar_ilan) { BarIlanPlatform() as Platform },
    PlatformInfo("Inbar (Bar-Ilan)", R.drawable.ic_bar_ilan) { InbarPlatform() },
    PlatformInfo("Open University", R.drawable.ic_open_au) { OpenAUPlatform() as Platform },
    PlatformInfo("Mashov", R.drawable.ic_mashov) { MashovPlatform() as Platform },
    PlatformInfo("Education Portal", R.drawable.ic_moe) { StudentsPortalPlatform() as Platform },
    PlatformInfo(
        "Demo",
        R.drawable.ic_account_circle,
        iconSymbol = MotionSymbols.ic_preview
    ) { DemoPlatform() as Platform }
)



object PlatformStorage {

    private const val PREFS_NAME = "platform_prefs"
    const val KEY_PLATFORMS = "platforms_logins"
    private const val TAG = "PlatformStorage"
    private const val KEY_PROVIDER_COURSE_OVERRIDES_PREFIX = "provider_course_overrides_"
    private const val KEY_PROVIDER_WINDOW_SUBJECTS_PREFIX = "provider_window_subjects_"

    /** Called inside the SMS flow mutex: a different page may already have saved a verified session. */
    fun restoreVerifiedInbarSession(context: Context, expected: InbarPlatform): InbarPlatform? {
        val current = loadPlatforms(context).filterIsInstance<InbarPlatform>().firstOrNull {
            it.id == expected.id && it.getUsername() == expected.getUsername() && it.mobile == expected.mobile && it.isLoggedIn()
        } ?: return null
        return current.takeIf { it.refreshCookies() }
    }

    // --- free periods ("חלונות") ---------------------------------------------
    // Subjects the user no longer attends (e.g. a bagrut already completed) are
    // shown in the schedule as free periods rather than lessons.

    private fun normalizeSubject(subject: String): String =
        subject.trim().lowercase(Locale.ROOT)

    fun loadWindowSubjects(context: Context, providerId: String): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getStringSet("$KEY_PROVIDER_WINDOW_SUBJECTS_PREFIX$providerId", null)
        return raw?.toSet() ?: emptySet()
    }

    fun saveWindowSubjects(context: Context, providerId: String, subjects: Set<String>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit {
            if (subjects.isEmpty()) {
                remove("$KEY_PROVIDER_WINDOW_SUBJECTS_PREFIX$providerId")
            } else {
                putStringSet("$KEY_PROVIDER_WINDOW_SUBJECTS_PREFIX$providerId", subjects)
            }
        }
    }

    /** True if this lesson's subject is marked as a free period for the provider. */
    fun isWindowSubject(subject: String, windowSubjects: Set<String>): Boolean {
        if (subject.isBlank() || windowSubjects.isEmpty()) return false
        val normalized = normalizeSubject(subject)
        return windowSubjects.any { normalizeSubject(it) == normalized }
    }

    /**
     * Serializes and saves the entire list of Platform objects.
     */
    @Synchronized fun savePlatforms(context: Context, platforms: List<Platform>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val array = JSONArray()
        for (p in platforms) {
            try {
                array.put(p.toJson())
            } catch (e: Exception) {
                Log.e(TAG, "Error serializing platform: ${p.javaClass.simpleName}", e)
            }
        }
        prefs.edit {
            putString(KEY_PLATFORMS, array.toString())
        }
    }

    /**
     * Loads and deserializes the full list of Platform objects using manual JSON parsing.
     */
    fun loadPlatforms(context: Context): MutableList<Platform> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_PLATFORMS, null)
        val platforms = mutableListOf<Platform>()
        if (json.isNullOrEmpty()) return platforms

        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                try {
                    val className = obj.getString("class")
                    val cls = Class.forName(className)
                    Log.d(TAG, "Deserializing platform: $className")

                    val method = cls.getMethod("fromJson", JSONObject::class.java)
                    val p = method.invoke(null, obj) as Platform
                    platforms.add(p)
                } catch (e: Exception) {
                    Log.e(TAG, "Error deserializing platform JSON at index $i", e)
                }
            }
        } catch (e: JSONException) {
            Log.e(TAG, "Invalid platforms JSON", e)
        }
        return platforms
    }

    @Synchronized fun addPlatforms(context: Context, newPlatforms: List<Platform>) {
        if (newPlatforms.isEmpty()) return
        val platforms = loadPlatforms(context)
        for (np in newPlatforms) {
            platforms.removeAll { existing ->
                existing.id == np.id || (
                    existing is WebtopPlatform && np is WebtopPlatform
                )
            }
            platforms.add(np)
        }
        savePlatforms(context, platforms)
    }

    fun addPlatform(context: Context, platform: Platform) {
        addPlatforms(context, listOf(platform))
    }

    @Throws(JSONException::class, IOException::class)
    fun addPlatform(context: Context, username: String, password: String): List<Platform> {
        val platforms = loadPlatforms(context)
        val newPlatforms = mutableListOf<Platform>()

        try {

            val barIlanFields = LoginFields()
                .addField("id", Type.Id, username)
                .addField("password", Type.Password, password)

            val barIlan = BarIlanPlatform(barIlanFields)
            if (barIlan.loggedIn) {
                platforms.add(barIlan)
                newPlatforms.add(barIlan)
            }
        } catch (e: Exception) {
            Log.e(TAG, "BarIlan login failed", e)
        }

        try {
            val fields = LoginFields().addField("username", Type.Username, username).addField("password", Type.Password, password)

            val webtop = WebtopPlatform(fields)
            if (webtop.loggedIn) {
                platforms.add(webtop)
                newPlatforms.add(webtop)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Webtop login failed", e)
        }

        try {
            // Try OpenAU platform - use username as student ID as well
            val openAUFields = LoginFields()
                .addField("username", Type.Username, username)
                .addField("password", Type.Password, password)
                .addField("id", Type.Id, username) // Use username as student ID

            val openAU = OpenAUPlatform(openAUFields)
            if (openAU.loggedIn) {
                platforms.add(openAU)
                newPlatforms.add(openAU)
            }
        } catch (e: Exception) {
            Log.e(TAG, "OpenAU login failed", e)
        }

        try {
            val demo = DemoPlatform()
            if (demo.loggedIn) {
                platforms.add(demo)
                newPlatforms.add(demo)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Demo login failed", e)
        }

        savePlatforms(context, platforms)
        return newPlatforms
    }

    fun checkPlatform(context: Context, username: String, password: String): Boolean {
        val executor = Executors.newFixedThreadPool(4) // Increased to 4 for OpenAU
        val webtopFields = LoginFields()
            .addField("username", Type.Username, username)
            .addField("password", Type.Password, password)

        val barIlanFields = LoginFields()
            .addField("id", Type.Id, username)
            .addField("password", Type.Password, password)

        val openAUFields = LoginFields()
            .addField("username", Type.Username, username)
            .addField("password", Type.Password, password)
            .addField("id", Type.Id, username) // Use username as student ID

        val tasks = listOf(
            Callable { BarIlanPlatform(barIlanFields).loggedIn },
            Callable { WebtopPlatform(webtopFields).loggedIn },
            Callable { OpenAUPlatform(openAUFields).loggedIn },
            Callable { DemoPlatform().loggedIn }
        )

        return try {
            val results = executor.invokeAll(tasks)
            for (result in results) {
                if (result.get()) {
                    executor.shutdownNow()
                    return true
                }
            }
            false
        } catch (e: Exception) {
            e.printStackTrace()
            false
        } finally {
            executor.shutdownNow()
        }
    }

    suspend fun refreshCookies(context: Context): List<String> = coroutineScope {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val (snapshot, platforms) = synchronized(this@PlatformStorage) {
            JSONArray(prefs.getString(KEY_PLATFORMS, "[]")) to loadPlatforms(context)
        }

        val results = platforms.map { p ->
            async {
                // Inbar is checked when the user opens its grades or timetable.
                if (p is InbarPlatform) return@async null
                val success = try {
                    p.refreshCookies()
                } catch (e: Exception) {
                    Log.e("PlatformStorage", "Error refreshing cookies for ${p.javaClass.simpleName}", e)
                    false
                }

                if (!success) {
                    Log.w("PlatformStorage", "Failed to refresh cookies for ${p.javaClass.simpleName}")
                    return@async p.javaClass.simpleName
                }
                null
            }
        }

        val failed = results.awaitAll().filterNotNull()
        if (failed.isNotEmpty()) Log.w("Refresh", "Failed to refresh: $failed")

        val refreshed = JSONArray(platforms.map { it.toJson() })
        synchronized(this@PlatformStorage) {
            val current = JSONArray(prefs.getString(KEY_PLATFORMS, "[]"))
            // A sign-in or settings save made while requests ran takes precedence.
            prefs.edit { putString(KEY_PLATFORMS, mergeRefreshedPlatforms(snapshot, current, refreshed).toString()) }
        }
        failed // return list of failed platform names
    }




    fun getPlatform(context: Context, index: Int): Platform? {
        val platforms = loadPlatforms(context)
        return if (index in platforms.indices) {
            platforms[index]
        } else {
            Log.w(TAG, "getAccount: index out of bounds: $index")
            null
        }
    }

    fun removePlatform(context: Context, index: Int) {
        val platforms = loadPlatforms(context)
        if (index !in platforms.indices) {
            Log.w(TAG, "removePlatform: index out of bounds: $index")
            return
        }
        platforms.removeAt(index)
        savePlatforms(context, platforms)
        Log.d(TAG, "Removed platform at index $index")
    }

    fun updatePlatform(context: Context, index: Int, updatedPlatform: Platform) {
        val platforms = loadPlatforms(context)
        if (index !in platforms.indices) {
            Log.w(TAG, "updatePlatform: index out of bounds: $index")
            return
        }
        platforms[index] = updatedPlatform
        savePlatforms(context, platforms)
        Log.d(TAG, "Updated platform at index $index")
    }

    /** Field holding the provider's original course name once the user renames it. */
    const val SOURCE_NAME = "sourceName"

    fun courseOverrideKey(course: JSONObject): String {
        val sourceId = course.optString("courseKey").ifBlank { course.optString("id") }
        if (sourceId.isNotBlank()) {
            return "$sourceId|${course.optString("term")}"
        }

        return listOf(
            // The provider's own name, never the user's custom one, so renaming
            // a course does not change its identity.
            course.optString(SOURCE_NAME).ifBlank { course.optString("name") },
            course.optString("term"),
            course.optString("year"),
            course.optString("semester"),
            course.optString("teacher"),
            course.optString("index")
        ).joinToString("|")
    }

    private fun legacyCourseOverrideKey(course: JSONObject): String {
        val sourceId = course.optString("courseKey").ifBlank { course.optString("id") }
        val identity = sourceId
            .ifBlank { course.optString(SOURCE_NAME) }
            .ifBlank { course.optString("name") }
        return "$identity|${course.optString("term")}"
    }

    fun isCourseHidden(course: JSONObject, hiddenCourseKeys: Set<String>): Boolean =
        courseOverrideKey(course) in hiddenCourseKeys ||
            legacyCourseOverrideKey(course) in hiddenCourseKeys

    fun loadProviderCourseOverrides(context: Context, providerId: String): ProviderCourseOverrides {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString("$KEY_PROVIDER_COURSE_OVERRIDES_PREFIX$providerId", null)
            ?: return ProviderCourseOverrides()

        return try {
            val json = JSONObject(raw)
            val hiddenArray = json.optJSONArray("hidden") ?: JSONArray()
            val orderArray = json.optJSONArray("order") ?: JSONArray()
            val namesObject = json.optJSONObject("names") ?: JSONObject()

            val hidden = buildSet {
                for (i in 0 until hiddenArray.length()) {
                    val key = hiddenArray.optString(i)
                    if (key.isNotBlank()) add(key)
                }
            }

            val order = buildList {
                for (i in 0 until orderArray.length()) {
                    val key = orderArray.optString(i)
                    if (key.isNotBlank()) add(key)
                }
            }

            val names = buildMap {
                val keys = namesObject.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = namesObject.optString(key)
                    if (key.isNotBlank() && value.isNotBlank()) put(key, value)
                }
            }

            ProviderCourseOverrides(
                hiddenCourseKeys = hidden,
                courseOrder = order,
                courseNames = names
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode course overrides for provider $providerId", e)
            ProviderCourseOverrides()
        }
    }

    fun saveProviderCourseOverrides(
        context: Context,
        providerId: String,
        overrides: ProviderCourseOverrides
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (overrides.hiddenCourseKeys.isEmpty() &&
            overrides.courseOrder.isEmpty() &&
            overrides.courseNames.isEmpty()
        ) {
            prefs.edit { remove("$KEY_PROVIDER_COURSE_OVERRIDES_PREFIX$providerId") }
            return
        }

        val json = JSONObject().apply {
            put("hidden", JSONArray(overrides.hiddenCourseKeys.toList()))
            put("order", JSONArray(overrides.courseOrder))
            put("names", JSONObject(overrides.courseNames))
        }

        prefs.edit {
            putString("$KEY_PROVIDER_COURSE_OVERRIDES_PREFIX$providerId", json.toString())
        }
    }

    private fun withCustomName(course: JSONObject, courseNames: Map<String, String>): JSONObject {
        val custom = courseNames[courseOverrideKey(course)] ?: courseNames[legacyCourseOverrideKey(course)]
        if (custom.isNullOrBlank()) return course

        val copy = JSONObject(course.toString())
        if (!copy.has(SOURCE_NAME)) {
            copy.put(SOURCE_NAME, course.optString("name"))
        }
        copy.put("name", custom)
        return copy
    }

    fun getProviderCourses(
        context: Context,
        provider: Platform,
        includeHidden: Boolean = false
    ): List<JSONObject> {
        val overrides = loadProviderCourseOverrides(context, provider.id)
        val rawCourses = provider.getCourses()
            .filter { includeHidden || !isCourseHidden(it, overrides.hiddenCourseKeys) }
            .map { withCustomName(it, overrides.courseNames) }
            .map { course ->
                val copy = JSONObject(course.toString())
                if (!copy.has("platformId") || copy.optString("platformId").isBlank()) {
                    copy.put("platformId", provider.id)
                }
                copy
            }
        if (overrides.courseOrder.isEmpty()) return rawCourses

        val orderIndex = overrides.courseOrder.withIndex().associate { it.value to it.index }
        return rawCourses.sortedBy { course ->
            val key = courseOverrideKey(course)
            val legacyKey = legacyCourseOverrideKey(course)
            orderIndex[key] ?: orderIndex[legacyKey] ?: Int.MAX_VALUE
        }
    }

    /**
     * Retrieves all courses across all platforms.
     */
    fun getCourses(context: Context): ArrayList<JSONObject> {
        val platforms = loadPlatforms(context)
        val courses = ArrayList<JSONObject>()

        for ((index, platform) in platforms.withIndex()) {
            try {
                val pCourses = getProviderCourses(context, platform)
                pCourses.forEach { course ->
                    course.put("platformId", platform.id)
                    course.put("index", index)
                }
                courses.addAll(pCourses)
            } catch (e: Exception) {
                Log.e(TAG, "Error retrieving courses for platform: ${platform.javaClass.simpleName}", e)
            }
        }
        return courses
    }

    fun clearPlatforms(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit { remove(KEY_PLATFORMS) }
        Log.d(TAG, "Cleared all stored platforms")
    }

    fun clearProviderCourseOverrides(context: Context, providerId: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit { remove("") }
    }
}
