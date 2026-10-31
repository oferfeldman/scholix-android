package android.net

class Uri private constructor(private val value: java.net.URI) {
    val scheme get() = value.scheme
    val host get() = value.host
    val path get() = value.path
    fun getQueryParameter(name: String): String? = value.rawQuery?.split('&')?.firstOrNull { it.substringBefore('=') == name }
        ?.substringAfter('=')?.let { java.net.URLDecoder.decode(it, "UTF-8") }
    companion object { fun parse(value: String) = Uri(java.net.URI(value)) }
}
