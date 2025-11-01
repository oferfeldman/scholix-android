package com.feldman.scholix.api.platforms

import android.content.Context
import android.os.Environment
import android.util.Log
import android.widget.Toast
import com.feldman.scholix.api.LoginFields
import com.feldman.scholix.api.Platform
import com.feldman.scholix.api.Type
import com.feldman.scholix.api.UnsafeOkHttpClient
import com.feldman.scholix.api.platforms.WebtopPlatform.Companion.generateId
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.LocalDate
import java.time.Year
import java.time.format.DateTimeFormatter
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap

class DemoPlatform: Platform {
    private val loginFields = LoginFields()

    override var loggedIn: Boolean = false
    override var editing: Boolean = false
    override var displayName: String? = null
    override var _username: String? = null
    override var _password: String? = null

    private val courses: ArrayList<JSONObject> = ArrayList()
    override var id: String = generateId()
        private set

    override val suportsGrades: Boolean = true
    override val supportsSchedule: Boolean = true
    override val supportsAttendance: Boolean = true

    init {
        if (id.isBlank()) {
            id = generateId()
        }
    }


    constructor() {
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

    fun getGrades(): JSONArray = getGrades("all")

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

    override fun getOriginalSchedule(dayIndex: Int, institutionCode: Int?, selectedValue: String?): JSONObject {
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
    override fun getGrades(course: String, year: Int?, semester: String?): JSONArray {
        // In Demo mode we just ignore semester and return the same grades.
        return getGrades(course)
    }

    override fun getAttendanceEvents(period: String): JSONObject {
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

    override fun getAttendanceEvents(year: Int, period: String): JSONObject {
        return getAttendanceEvents(period)
    }
    fun getGrades(course: String): JSONArray {
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
            .put("id", id)
            .put("loggedIn", loggedIn)
            .put("name", displayName)
            .put("_username", _username)
            .put("password", _password)
    }

    override fun getScheduleIndexes(): JSONArray {
        return JSONArray().put(0).put(1).put(2).put(3).put(4).put(5)
    }
    override fun getSchedule(dayIndex: Int, institutionCode: Int?, selectedValue: String?): JSONObject = JSONObject()

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

    override fun getSubjectList(): List<String> {
        // Return a static/dummy list of subjects for demo mode
        return listOf(
            "מתמטיקה",
            "עברית",
            "אנגלית",
            "היסטוריה",
            "תנ\"ך",
            "ספרות",
            "ערבית",
            "מדעים",
            "חינוך",
            "של\"ח"
        )
    }


    override fun getInfo(): JSONObject {
        return JSONObject()
            .put("name", "Webtop")
            .put("supportsEndpoints", JSONArray(listOf("grades", "schedule", "disciplineEvents")))
            .put("loginVariables", JSONArray(listOf("username", "password")))
            .put("supportsSchedule", true)
            .put("supportsGrades", true)
            .put("supportsAttendance", true)
    }
    companion object : Platform.Companion {
        override val client: OkHttpClient = UnsafeOkHttpClient.getUnsafeOkHttpClient()

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

        @JvmStatic
        override fun fromJson(obj: JSONObject): DemoPlatform {
            val p = DemoPlatform()
            p.id = obj.optString("id", "").ifEmpty { generateId() }
            p.loggedIn = obj.optBoolean("loggedIn", false)
            p.displayName = obj.optString("name", "demo")
            return p
        }

        override fun checkCredentials(loginFields: LoginFields): Boolean {
            return try {
                val p = DemoPlatform()
                p.isLoggedIn()
            } catch (e: Exception) {
                Log.e("DemoPlatform", "checkCredentials failed", e)
                false
            }
        }
    }
    @Throws(JSONException::class, IOException::class)
    override fun getMessages(page: Int): JSONArray {
        val messages = JSONArray()

        // Mocked sample inbox messages
        val msg1 = JSONObject()
            .put("messageId", "MSG001")
            .put("subject", "ברוך הבא לסמסטר א׳!")
            .put("from", "דיקן הסטודנטים")
            .put("date", "2025-09-01T08:45:00")
            .put("hasRead", 0)
            .put("filesAttached", false)

        val msg2 = JSONObject()
            .put("messageId", "MSG002")
            .put("subject", "תזכורת להרצאה מוקלטת - מבוא לאלגברה")
            .put("from", "פרופ׳ כהן")
            .put("date", "2025-09-05T11:00:00")
            .put("hasRead", 1)
            .put("filesAttached", true)

        val msg3 = JSONObject()
            .put("messageId", "MSG003")
            .put("subject", "הודעה חשובה לגבי מערכת השעות")
            .put("from", "מזכירות הפקולטה")
            .put("date", "2025-09-10T09:30:00")
            .put("hasRead", 0)
            .put("filesAttached", false)

        messages.put(msg1).put(msg2).put(msg3)

        Log.d("BarIlanPlatform", "Demo messages loaded: ${messages.length()} items")
        return messages
    }

    @Throws(JSONException::class, IOException::class)
    override fun getMessageDetails(messageId: String): JSONObject {
        val message = JSONObject()

        when (messageId) {
            "MSG001" -> {
                message.put("subject", "ברוך הבא לסמסטר א׳!")
                    .put("from", "דיקן הסטודנטים")
                    .put("date", "2025-09-01T08:45:00")
                    .put("contentHtml", "<p>סטודנטים יקרים,<br>ברוכים הבאים לסמסטר החדש. אנו מאחלים לכם הצלחה רבה!</p>")
                    .put("attachments", JSONArray())
            }

            "MSG002" -> {
                val attachments = JSONArray().put(
                    JSONObject()
                        .put("name", "lecture1-recording.mp4")
                        .put("url", "https://example.com/lecture1-recording.mp4")
                        .put("size", 120.5)
                        .put("sizeUnit", "MB")
                )
                message.put("subject", "תזכורת להרצאה מוקלטת - מבוא לאלגברה")
                    .put("from", "פרופ׳ כהן")
                    .put("date", "2025-09-05T11:00:00")
                    .put("contentHtml", "<p>שלום לכולם,<br>הרצאה מוקלטת זמינה כעת במערכת. מומלץ לעבור עליה לפני השיעור הבא.</p>")
                    .put("attachments", attachments)
            }

            "MSG003" -> {
                message.put("subject", "הודעה חשובה לגבי מערכת השעות")
                    .put("from", "מזכירות הפקולטה")
                    .put("date", "2025-09-10T09:30:00")
                    .put("contentHtml", "<p>שלום,<br>אנא שימו לב כי מערכת השעות עודכנה. בדקו את זמני השיעורים באתר.</p>")
                    .put("attachments", JSONArray())
            }

            else -> {
                message.put("error", "Message not found")
            }
        }

        Log.d("BarIlanPlatform", "Demo message details loaded for ID: $messageId")
        return message
    }
    override fun getLoginFields(): LoginFields = loginFields


    override suspend fun downloadAttachment(context: Context, attachment: JSONObject): Boolean {
        val name = attachment.optString("name", "DemoFile.txt")
        val type = attachment.optString("type", "text/plain")

        return try {
            // 🕒 simulate network delay
            delay(1500)

            // 🎯 Create fake file path in Downloads folder
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(downloadsDir, name)

            // 📝 Write fake content
            val demoText = "This is a demo attachment.\nFile name: $name\nType: $type\nGenerated by Scholix Demo Platform."
            FileOutputStream(file).use { it.write(demoText.toByteArray()) }

            // ✅ Notify user
            with(android.os.Handler(context.mainLooper)) {
                post {
                    Toast.makeText(context, "הקובץ נשמר בתיקיית ההורדות: ${file.name}", Toast.LENGTH_LONG).show()
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            with(android.os.Handler(context.mainLooper)) {
                post {
                    Toast.makeText(context, "שגיאה בהורדת קובץ ההדגמה.", Toast.LENGTH_SHORT).show()
                }
            }
            false
        }
    }
}
