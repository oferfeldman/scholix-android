package com.feldman.scholix.lemida

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

/** Read-only endpoint discovery with the account holder's saved phone session. */
class LemidaApiDiscoveryTest {
    @Test fun discoverReadOnlyHomeworkEndpoints() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val browser = withContext(Dispatchers.Main) { LemidaBrowser(context, null) }
        try {
            browser.prepare()
            val html = browser.get("${LemidaParser.BASE}/my/")
            val key = LemidaParser.config(html, "sesskey") ?: error("No session key")
            val courseId = LemidaRepository(context).cached().first().courseId
            val calls = listOf(
                "core_course_get_contents" to JSONObject().put("courseid", courseId),
                "core_courseformat_get_state" to JSONObject().put("courseid", courseId),
                "mod_assign_get_assignments" to JSONObject().put("courseids", JSONArray().put(courseId)),
                "mod_quiz_get_quizzes_by_courses" to JSONObject().put("courseids", JSONArray().put(courseId))
            )
            calls.forEach { (name, args) ->
                val payload = JSONArray().put(JSONObject().put("index", 0).put("methodname", name).put("args", args))
                val result = JSONArray(browser.post("${LemidaParser.BASE}/lib/ajax/service.php?sesskey=$key", payload.toString())).getJSONObject(0)
                val error = result.optJSONObject("exception")?.optString("errorcode").orEmpty()
                val data = result.opt("data")
                android.util.Log.d("LemidaApi", "$name: error=$error; dataType=${data?.javaClass?.simpleName}")
                if (name == "core_courseformat_get_state" && data is String) {
                    val state = JSONObject(data)
                    val modules = state.optJSONArray("cm")
                    android.util.Log.d("LemidaApi", "state keys=${state.keys().asSequence().toList()}; modules=${modules?.length()}; module keys=${modules?.optJSONObject(0)?.keys()?.asSequence()?.toList()}")
                    if (modules != null) {
                        val first = modules.optJSONObject(0)
                        // Only schema and counts, never tokens or personal homework content.
                        android.util.Log.d("LemidaApi", "module fields types=${first?.keys()?.asSequence()?.associateWith { first.opt(it)?.javaClass?.simpleName }}")
                    }
                }
            }
        } finally { browser.close() }
    }
}
