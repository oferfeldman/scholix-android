package android.util

/** Provider debug messages can contain personal data; never forward them to MCP stdout. */
object Log {
    fun v(tag: String, message: String?, error: Throwable? = null) = 0
    fun d(tag: String, message: String?, error: Throwable? = null) = 0
    fun i(tag: String, message: String?, error: Throwable? = null) = 0
    fun w(tag: String, message: String?, error: Throwable? = null) = 0
    fun e(tag: String, message: String?, error: Throwable? = null) = 0
}
