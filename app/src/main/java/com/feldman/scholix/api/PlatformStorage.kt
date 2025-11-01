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
import com.feldman.scholix.R
import com.feldman.scholix.api.platforms.DemoPlatform
import com.feldman.scholix.api.platforms.OpenAUPlatform
import com.feldman.scholix.api.platforms.WebtopPlatform

data class PlatformInfo(
    val name: String,
    val iconRes: Int,
    val factory: () -> Platform
)

val platformOptions = listOf(
    PlatformInfo("Webtop", R.drawable.ic_webtop) { WebtopPlatform() as Platform },
    PlatformInfo("Bar-Ilan", R.drawable.ic_bar_ilan) { BarIlanPlatform() as Platform },
    PlatformInfo("Open University", R.drawable.ic_open_au) { OpenAUPlatform() as Platform },
    PlatformInfo("Demo", R.drawable.ic_account_circle) { DemoPlatform() as Platform }
)



object PlatformStorage {

    private const val PREFS_NAME = "platform_prefs"
    const val KEY_PLATFORMS = "platforms_logins"
    private const val TAG = "PlatformStorage"

    /**
     * Serializes and saves the entire list of Platform objects.
     */
    fun savePlatforms(context: Context, platforms: List<Platform>) {
        println(platforms)
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

    fun addPlatform(context: Context, platform: Platform) {

        val platforms = loadPlatforms(context)

        platforms.add(platform)
        savePlatforms(context, platforms)
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
        val executor = Executors.newFixedThreadPool(3)
        val webtopFields = LoginFields()
            .addField("username", Type.Username, username)
            .addField("password", Type.Password, password)

        val barIlanFields = LoginFields()
            .addField("id", Type.Id, username)
            .addField("password", Type.Password, password)

        val tasks = listOf(
            Callable { BarIlanPlatform(barIlanFields).loggedIn },
            Callable { WebtopPlatform(webtopFields).loggedIn },
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
        val platforms = loadPlatforms(context)

        val results = platforms.map { p ->
            async {
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

        savePlatforms(context, platforms)
        failed // return list of failed platform names
    }




    fun getAccount(context: Context, index: Int): Platform? {
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

    @Throws(JSONException::class)
    fun getCourses(context: Context): ArrayList<JSONObject> {
        val platforms = loadPlatforms(context)
        val allCourses = ArrayList<JSONObject>()
        val seenNames = mutableSetOf<String>()

        for ((index, platform) in platforms.withIndex()) {
            for (course in platform.getCourses()) {
                val name = course.optString("name")
                if (seenNames.add(name)) {
                    val copy = JSONObject(course.toString())
                    copy.put("index", index)
                    allCourses.add(copy)
                }
            }
        }
        return allCourses
    }

    //Clear all platforms from shared preferences
    fun clearPlatforms(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit {
            remove(KEY_PLATFORMS)
        }
        Log.d(TAG, "Cleared all stored platforms")
    }

    fun hasGradesSupport(context: Context): Boolean {
        val platforms = loadPlatforms(context)
        return platforms.any { it.suportsGrades }
    }

    fun hasScheduleSupport(context: Context): Boolean {
        val platforms = loadPlatforms(context)
        return platforms.any { it.supportsSchedule }
    }

    fun hasAttendanceSupport(context: Context): Boolean {
        val platforms = loadPlatforms(context)
        return platforms.any { it.supportsAttendance }
    }


}
