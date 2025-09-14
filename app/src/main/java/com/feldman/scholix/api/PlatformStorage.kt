package com.feldman.scholix.api

import android.content.Context
import android.content.SharedPreferences
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

object PlatformStorage {

    private const val PREFS_NAME = "platform_prefs"
    const val KEY_PLATFORMS = "platforms_logins"
    private const val TAG = "PlatformStorage"

    /**
     * Serializes and saves the entire list of Platform objects.
     */
    fun savePlatforms(context: Context, platforms: List<Platform>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val array = JSONArray()
        for (p in platforms) {
            try {
                array.put(p.toJson())
            } catch (e: Exception) {
                Log.e(TAG, "Error serializing platform: ${p.javaClass.simpleName}", e)
            }
        }
        prefs.edit()
            .putString(KEY_PLATFORMS, array.toString())
            .apply()
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
            val barIlan = BarIlanPlatform(username, password)
            if (barIlan.loggedIn) {
                platforms.add(barIlan)
                newPlatforms.add(barIlan)
            }
        } catch (e: Exception) {
            Log.e(TAG, "BarIlan login failed", e)
        }

        try {
            val webtop = WebtopPlatform(username, password)
            if (webtop.loggedIn) {
                platforms.add(webtop)
                newPlatforms.add(webtop)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Webtop login failed", e)
        }

        try {
            val demo = DemoPlatform(username, password)
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
        val tasks = listOf<Callable<Boolean>>(
            Callable { BarIlanPlatform(username, password).loggedIn },
            Callable { WebtopPlatform(username, password).loggedIn },
            Callable { DemoPlatform(username, password).loggedIn }
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

    suspend fun refreshCookies(context: Context) = coroutineScope {
        val platforms = loadPlatforms(context)

        // Launch all refresh tasks in parallel
        val results = platforms.map { p ->
            async {
                val success = try {
                    p.refreshCookies()
                } catch (e: Exception) {
                    Log.e("Refresh", "Error refreshing cookies for ${p.javaClass.simpleName}", e)
                    false
                }

                if (success) {
                    Log.d("Refresh", "Successfully refreshed cookies for ${p.javaClass.simpleName}")

                    // ✅ If Webtop refreshed, immediately reload grades
                    if (p is WebtopPlatform) {
                        try {
                            val courses = p.getCourses()
                            if (courses.isNotEmpty()) {
                                courses[0].put("grades", p.getGrades())
                            }
                        } catch (e: Exception) {
                            Log.e("Refresh", "Error refreshing grades for WebtopPlatform", e)
                        }
                    }
                } else {
                    Log.w("Refresh", "Failed to refresh cookies for ${p.javaClass.simpleName}")

                    // Optional: retry once for Webtop
                    if (p is WebtopPlatform) {
                        try {
                            if (p.refreshCookies()) {
                                Log.d("Refresh", "Retry succeeded for WebtopPlatform")
                                val courses = p.getCourses()
                                if (courses.isNotEmpty()) {
                                    courses[0].put("grades", p.getGrades())
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("Refresh", "Retry also failed for WebtopPlatform", e)
                        }
                    }
                }
                p // return the platform after refresh
            }
        }

        // ✅ Wait until all refreshes are done
        val updatedPlatforms = results.awaitAll()

        savePlatforms(context, updatedPlatforms)
    }

    fun clearPlatforms(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(KEY_PLATFORMS)
            .apply()
        Log.d(TAG, "Cleared all stored platforms")
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
                if (seenNames.add(name)) {   // ✅ only add first occurrence
                    val copy = JSONObject(course.toString()) // deep copy
                    copy.put("index", index)
                    allCourses.add(copy)
                }
            }
        }
        return allCourses
    }
}
