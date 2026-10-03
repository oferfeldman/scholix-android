package com.feldman.scholix.lemida

import android.content.Context
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

open class LemidaSessionExpired(message: String = "Sign in to Lemida again to resume automatic updates.") : IOException(message)
class LemidaVerificationRequired : LemidaSessionExpired("Open Sign in to Lemida and complete the browser verification.")

class LemidaRepository internal constructor(context: Context, preferencesName: String,
    private val browserFactory: (Context, String?, WebView?) -> LemidaTransport) {
    constructor(context: Context, preferencesName: String = "lemida_sync") :
        this(context, preferencesName, { owner, agent, view -> LemidaBrowser(owner, agent, view) })
    val syncing get() = syncState.asStateFlow()
    private val prefs = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val appContext = context.applicationContext
    fun enabled() = prefs.getBoolean("enabled", false)
    fun setEnabled(value: Boolean) { prefs.edit().putBoolean("enabled", value).apply() }
    fun status() = prefs.getString("status", "Sign in to Lemida to sync homework.").orEmpty()
    fun lastSync() = prefs.getLong("last_sync", 0L)
    fun cached() = LemidaParser.decode(prefs.getString("homework", "[]") ?: "[]")
    fun setUserAgent(value: String) { prefs.edit().putString("user_agent", value).apply() }
    private fun detailKey(item: Homework) = "detail:${prefs.getString("user_id", "")}:${item.id}"
    fun cachedDetail(item: Homework): HomeworkDetail? = prefs.getString(detailKey(item), null)?.let {
        runCatching { HomeworkDetail.fromJson(it) }.getOrNull()
    }
    suspend fun detail(item: Homework): HomeworkDetail = mutex.withLock {
        withContext(Dispatchers.IO) {
            val browser = withContext(Dispatchers.Main) { browserFactory(appContext, prefs.getString("user_agent", null), null) }
            try {
                browser.prepare()
                val html = browser.get(item.url)
                if (!LemidaParser.authenticated(html)) throw LemidaSessionExpired()
                check(LemidaParser.config(html, "userId") == prefs.getString("user_id", null)) {
                    "The signed-in account changed. Refresh your homework list first."
                }
                val result = LemidaParser.detail(html)
                val items = cached().map { cached ->
                    if (cached.id == item.id && result.dates.isNotBlank()) cached.copy(dates = result.dates) else cached
                }
                prefs.edit().putString(detailKey(item), result.json().toString())
                    .putString("homework", LemidaParser.encode(items)).commit()
                result
            } catch (e: LemidaSessionExpired) {
                prefs.edit().putBoolean("needs_login", true).putString("status", e.message).commit()
                throw e
            } finally { browser.close() }
        }
    }

    /** Returns the account of the successfully committed snapshot, for guarded alert delivery. */
    suspend fun sync(view: WebView? = null): String = mutex.withLock {
        syncState.value = true
        try {
            withContext(Dispatchers.IO) {
                val browser = withContext(Dispatchers.Main) { browserFactory(appContext, prefs.getString("user_agent", null), view) }
                try {
                    browser.prepare()
                    val homepage = browser.get("${LemidaParser.BASE}/my/")
                    if (!LemidaParser.authenticated(homepage)) throw LemidaSessionExpired()
                    val user = LemidaParser.config(homepage, "userId")
                        ?: throw IOException("Could not identify the signed-in Lemida account.")
                    val key = LemidaParser.config(homepage, "sesskey")
                        ?: throw IOException("Could not discover the Moodle session key.")
                    val courses = linkedMapOf<Int, String>()
                    var offset = 0
                    var complete = false
                    for (page in 0 until 100) {
                        val payload = JSONArray().put(JSONObject().put("index", 0)
                            .put("methodname", "core_course_get_enrolled_courses_by_timeline_classification")
                            .put("args", JSONObject().put("classification", "allincludinghidden")
                                .put("limit", 50).put("offset", offset).put("sort", "fullname")))
                        val raw = browser.post("${LemidaParser.BASE}/lib/ajax/service.php?sesskey=$key", payload.toString())
                        val data = LemidaParser.ajaxData(raw) as? JSONObject
                            ?: throw IOException("Unexpected Moodle course data. Previous homework preserved.")
                        val next = LemidaParser.nextCourseOffset(data, offset)
                        val batch = data.getJSONArray("courses")
                        for (i in 0 until batch.length()) {
                            val c = batch.getJSONObject(i)
                            courses[c.getInt("id")] = org.jsoup.Jsoup.parse(c.getString("fullname")).text()
                        }
                        if (next == offset) { complete = true; break }
                        offset = next
                    }
                    if (!complete) throw IOException("Course pagination did not finish; previous data preserved.")
                    val sameAccount = prefs.getString("user_id", null) == user
                    val previous = if (sameAccount) cached().associateBy { it.id } else emptyMap()
                    val items = courses.flatMap { (id, name) ->
                        val payload = JSONArray().put(JSONObject().put("index", 0)
                            .put("methodname", "core_courseformat_get_state").put("args", JSONObject().put("courseid", id)))
                        val raw = browser.post("${LemidaParser.BASE}/lib/ajax/service.php?sesskey=$key", payload.toString())
                        val state = LemidaParser.ajaxData(raw) as? String
                            ?: throw IOException("Unexpected Moodle activity data. Previous homework preserved.")
                        LemidaParser.stateHomework(state, id, name).map { item ->
                            item.copy(dates = previous[item.id]?.dates.orEmpty())
                        }
                    }
                    val seen = if (sameAccount) prefs.getStringSet("seen", null)?.toSet() else null
                    val pending = if (sameAccount) LemidaParser.decode(prefs.getString("pending", "[]") ?: "[]") else emptyList()
                    val alerts = LemidaParser.pendingAlerts(items, seen, pending)
                    // Commit only after every course succeeded. Never turn an error into an empty baseline.
                    check(prefs.edit().putString("homework", LemidaParser.encode(items)).putString("user_id", user)
                        .putStringSet("seen", seen.orEmpty() + items.map { it.id })
                        .putString("pending", LemidaParser.encode(alerts))
                        .putLong("last_sync", System.currentTimeMillis()).putBoolean("needs_login", false)
                        .putBoolean("login_notified", false)
                        .putString("status", "${courses.size} courses • ${items.size} homework items • automatic sync every 30 minutes")
                        .commit()) { "Could not save homework." }
                    user
                } catch (e: CancellationException) {
                    throw e
                } catch (e: LemidaSessionExpired) {
                    prefs.edit().putBoolean("needs_login", true).putString("status", e.message).commit()
                    throw e
                } catch (e: IOException) {
                    prefs.edit().putString("status", "Update failed. Previous homework is still available. ${e.message}").commit()
                    throw e
                } catch (e: Exception) {
                    prefs.edit().putString("status", "Update failed. Previous homework is still available. Try Refresh.").commit()
                    throw e
                } finally {
                    browser.close()
                }
            }
        } finally { syncState.value = false }
    }
    fun needsLogin() = prefs.getBoolean("needs_login", false)
    suspend fun deliverPending(account: String, deliver: (List<Homework>) -> Boolean) = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!enabled() || needsLogin() || prefs.getString("user_id", null) != account) return@withContext
            val pending = LemidaParser.decode(prefs.getString("pending", "[]") ?: "[]")
            // Read, deliver, and acknowledge under the same lock as sync/account changes.
            if (pending.isNotEmpty() && deliver(pending)) {
                check(prefs.edit().putString("pending", "[]").commit()) { "Could not acknowledge homework alerts." }
            }
        }
    }
    suspend fun deliverLoginReminder(deliver: () -> Boolean) = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (enabled() && needsLogin() && !prefs.getBoolean("login_notified", false) && deliver()) {
                check(prefs.edit().putBoolean("login_notified", true).commit()) { "Could not acknowledge sign-in reminder." }
            }
        }
    }

    companion object {
        private val mutex = Mutex()
        private val syncState = MutableStateFlow(false)
    }
}
