package com.feldman.scholix.api

import org.json.JSONArray
import org.json.JSONObject

/** Refresh only unchanged profiles; never restore deleted accounts or overwrite a new sign-in. */
internal fun mergeRefreshedPlatforms(snapshot: JSONArray, current: JSONArray, refreshed: JSONArray): JSONArray {
    fun byId(array: JSONArray): Map<String, JSONObject> = (0 until array.length())
        .map { array.getJSONObject(it) }.associateBy { it.optString("id") }
    val before = byId(snapshot)
    val updates = byId(refreshed)
    return JSONArray((0 until current.length()).map { index ->
        val profile = current.getJSONObject(index)
        val id = profile.optString("id")
        if (before[id]?.toString() == profile.toString()) updates[id] ?: profile else profile
    })
}
