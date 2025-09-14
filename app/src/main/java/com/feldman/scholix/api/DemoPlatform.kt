package com.feldman.scholix.api

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDate
import java.time.Year
import java.time.format.DateTimeFormatter
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap

class DemoPlatform() : Platform {

    override var loggedIn: Boolean = false
    override var editing: Boolean = false
    override var displayName: String? = null
    override var _username: String? = null
    override var _password: String? = null

    private val courses: ArrayList<JSONObject> = ArrayList()

    constructor(_username: String, password: String) : this() {
        if (_username == "demo" && password == "demo") {
            loggedIn = true
            displayName = "demo"
            this._username = "demo"
            this._password = "demo"
            courses.add(
                JSONObject()
                    .put("name", "demo")
                    .put("year", Year.now().value)
            )
        }
    }

    override fun getGrades(): JSONArray = getGrades("all")

    private fun createHour(num: String, subject: String, teacher: String): JSONObject {
        val colorClass = findColorClass(subject)
        return JSONObject()
            .put("num", num)
            .put("subject", subject)
            .put("teacher", teacher)
            .put("colorClass", colorClass)
            .put("changes", "")
            .put("exams", "")
    }

    override fun getSchedule(dayIndex: Int): JSONObject {
        val week = Array(7) { JSONObject() }

        week[0].apply {
            put("1", createHour("1", "מתמטיקה", "רון נתניהו"))
            put("2", createHour("2", "עברית", "יעל כהן"))
            put("3", createHour("3", "אנגלית", "מירב שלו"))
            put("4", createHour("4", "היסטוריה", "אורי מוח"))
            put("5", createHour("5", "תנ\"ך", "רועי ברק"))
        }

        week[1].apply {
            put("1", createHour("1", "עברית", "יעל כהן"))
            put("2", createHour("2", "אנגלית", "מירב שלו"))
            put("3", createHour("3", "חינוך", "שרה דוד"))
            put("4", createHour("4", "ערבית", "אורי חן"))
        }

        week[2].apply {
            put("1", createHour("1", "מדעים", "רון נתניהו"))
            put("2", createHour("2", "מתמטיקה", "רועי ברק"))
            put("3", createHour("3", "עברית", "יעל כהן"))
            put("4", createHour("4", "תנ\"ך", "יוסי כהן"))
            put("5", createHour("5", "של\"ח", "אורי חן"))
        }

        week[3].apply {
            put("1", createHour("1", "ספרות", "מירב שלו"))
            put("2", createHour("2", "עברית", "יעל כהן"))
            put("3", createHour("3", "אנגלית", "רועי ברק"))
            put("4", createHour("4", "מדעים", "אורי מוח"))
            put("5", createHour("5", "של\"ח", "אורי חן"))
            put("6", createHour("6", "חינוך", "שרה דוד"))
        }

        week[4].apply {
            put("1", createHour("1", "היסטוריה", "רון נתניהו"))
            put("2", createHour("2", "עברית", "יעל כהן"))
            put("3", createHour("3", "אנגלית", "מירב שלו"))
            put("4", createHour("4", "תנ\"ך", "יוסי כהן"))
        }

        week[5].apply {
            put("1", createHour("1", "של\"ח", "רועי ברק"))
            put("2", createHour("2", "ספרות", "שרה דוד"))
            put("3", createHour("3", "עברית", "יעל כהן"))
            put("4", createHour("4", "אנגלית", "מירב שלו"))
            put("5", createHour("5", "חינוך", "אורי מוח"))
            put("6", createHour("6", "מדעים", "רון נתניהו"))
        }

        week[6].apply {
            put("1", createHour("1", "עברית", "יעל כהן"))
            put("2", createHour("2", "חינוך", "שרה דוד"))
            put("3", createHour("3", "של\"ח", "יוסי כהן"))
            put("4", createHour("4", "היסטוריה", "רון נתניהו"))
        }

        return week[dayIndex]
    }

    override fun getOriginalSchedule(dayIndex: Int): JSONObject = getSchedule(dayIndex)

    private fun createGrade(subject: String, name: String, gradeValue: Int, type: String): JSONObject {
        val date = LocalDate.of(LocalDate.now().year, 1, 1).format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
        val submission = JSONObject()
            .put("type", type)
            .put("grade", gradeValue)
            .put("date", date)
        val submissions = JSONArray().put(submission)

        return JSONObject()
            .put("subject", subject)
            .put("name", name)
            .put("date", date)
            .put("grade", gradeValue)
            .put("submissions", submissions)
    }

    @Throws(JSONException::class, IOException::class)
    override fun getGrades(course: String, semester: String): JSONArray {
        // In Demo mode we just ignore semester and return the same grades.
        return getGrades(course)
    }
    @Throws(JSONException::class, IOException::class)
    override fun getGrades(course: String, year: Int, semester: String): JSONArray {
        return getGrades(course) // ignores semester
    }

    override fun getDisciplineEvents(period: String): JSONObject {
        // Fake demo events for Bar Ilan
        val result = JSONObject()
        val eventsByType = JSONObject()

        val absence = JSONObject()
            .put("type", "חיסור")
            .put("date", "2025-09-01T00:00:00")
            .put("subject", "אלגברה לינארית")
            .put("teacher", "פרופ׳ כהן")
            .put("remark", "החסיר שיעור ללא הודעה")

        val positive = JSONObject()
            .put("type", "חיזוק חיובי")
            .put("date", "2025-09-05T00:00:00")
            .put("subject", "מבוא למדעי המחשב")
            .put("teacher", "ד״ר לוי")
            .put("remark", "תרם רבות לשיעור")

        eventsByType.put("חיסור", JSONArray().put(absence))
        eventsByType.put("חיזוק חיובי", JSONArray().put(positive))

        result.put("events", eventsByType)
        return result
    }

    override fun getDisciplineEvents(year: Int, period: String): JSONObject {
        return getDisciplineEvents(period)
    }
    override fun getGrades(course: String): JSONArray {
        val grades = JSONArray()
        grades.put(createGrade("אנגלית", "מבחן באנגלית", 98, "מועד א"))
        grades.put(createGrade("עברית", "חיבור", 87, "מועד ב"))
        grades.put(createGrade("מתמטיקה", "מבחן סוף", 100, "מועד א"))
        grades.put(createGrade("תנ\"ך", "מבחן תנ\"ך", 90, "מועד א"))
        grades.put(createGrade("ספרות", "בגרות פנימית", 85, "מועד ב"))
        grades.put(createGrade("היסטוריה", "מבחן יחידה 2", 78, "מועד א"))
        grades.put(createGrade("ערבית", "מבחן הבנה", 92, "מועד א"))
        grades.put(createGrade("מדעים", "מעבדה", 95, "מועד ב"))
        grades.put(createGrade("חינוך", "השתתפות", 100, "שנתי"))
        grades.put(createGrade("של\"ח", "מבחן מסכם", 89, "מועד א"))
        return grades
    }

    override fun toString(): String {
        return "DemoPlatform(loggedIn=$loggedIn, editing=$editing, name=$displayName, _username=$_username, password=$_password, courses=$courses)"
    }

    private fun findColorClass(subject: String): String {
        SUBJECT_COLORS[subject]?.let { return it }
        for (key in SUBJECT_COLORS.keys) {
            if (subject.contains(key)) return SUBJECT_COLORS[key] ?: ""
        }

        val colorPool = arrayOf("red", "green", "blue", "orange", "yellow", "purple", "teal", "lime", "pink")
        val randomColor = "custom-${colorPool[Random().nextInt(colorPool.size)]}-cell"
        SUBJECT_COLORS[subject] = randomColor
        return randomColor
    }

    override fun isLoggedIn(): Boolean = loggedIn

    override fun refreshCookies(): Boolean {
        loggedIn = true
        return true
    }

    override fun toJson(): JSONObject {
        return JSONObject()
            .put("class", javaClass.name)
            .put("loggedIn", loggedIn)
            .put("name", displayName)
            .put("_username", _username)
            .put("password", _password)
    }

    override fun getScheduleIndexes(): JSONArray {
        return JSONArray().put(0).put(1).put(2).put(3).put(4).put(5)
    }

    override fun isEditing(): Boolean = editing
    override fun startEditing() { editing = true }
    override fun stopEditing() { editing = false }
    override fun setName(name: String) { this.displayName = name }
    override fun setUsername(_username: String) { this._username = _username }
    override fun setPassword(password: String) { this._password = password }
    override fun getCourses(): ArrayList<JSONObject> = courses
    override fun getName(): String = displayName ?: ""
    override fun getUsername(): String = _username ?: ""
    override fun getPassword(): String = _password ?: ""
    companion object {
        private val SUBJECT_COLORS: MutableMap<String, String> = HashMap<String, String>().apply {
            put("מתמטיקה האצה", "lightgreen-cell")
            put("מדעים", "lightyellow-cell")
            put("של`ח", "lightgreen-cell")
            put("חינוך", "pink-cell")
            put("ערבית", "lightblue-cell")
            put("היסטוריה", "lightred-cell")
            put("עברית", "lightpurple-cell")
            put("חינוך גופני", "lightorange-cell")
            put("נחשון", "lightyellow-cell")
            put("אנגלית", "lime-cell")
            put("ספרות", "blue-cell")
            put("תנך", "lightgrey-cell")
            put("תנ`ך", "lightgrey-cell")
            put("cancel", "cancel-cell")
        }

        @Throws(IOException::class, JSONException::class)
        fun fromJson(obj: JSONObject): DemoPlatform {
            val p = DemoPlatform("demo", "demo")
            p.loggedIn = obj.optBoolean("loggedIn", false)
            p.displayName = obj.optString("name", null)
            return p
        }
    }
}
