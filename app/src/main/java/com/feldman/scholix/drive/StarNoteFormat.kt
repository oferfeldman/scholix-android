package com.feldman.scholix.drive

import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

data class StarPoint(val x: Float, val y: Float)
data class StarStroke(val id: String, val color: Int, val width: Float, val points: List<StarPoint>,
    val transform: List<Float> = listOf(1f,0f,0f,0f,1f,0f,0f,0f,1f))
data class StarPage(val id: String, val order: Float, val width: Float, val height: Float,
    val kind: String, val resource: String, val resourcePage: Int, val template: String,
    val strokes: List<StarStroke> = emptyList())
data class StarDocument(val pages: List<StarPage>, val warnings: List<String>, val title:String="")

/** Observed sync/v1 wire format. Read only: do not emit StarNote's undocumented sync protocol. */
object StarNoteFormat {
    private const val LIMIT = 64 * 1024 * 1024
    internal data class Field(val number: Int, val wire: Int, val bytes: ByteArray = byteArrayOf(), val value: Long = 0)
    internal fun fields(bytes: ByteArray): List<Field> {
        require(bytes.size <= LIMIT)
        var p = 0
        fun variable(): Long {
            var v = 0L
            for (shift in 0..63 step 7) {
                require(p < bytes.size) { "Truncated StarNote data" }
                val c = bytes[p++].toInt() and 255
                require(shift != 63 || c <= 1) { "Invalid StarNote number" }
                v = v or ((c and 127).toLong() shl shift)
                if (c < 128) return v
            }
            error("Invalid StarNote number")
        }
        val result = ArrayList<Field>()
        while (p < bytes.size) {
            require(result.size < 1_000_000)
            val key = variable(); val n = (key ushr 3).toInt(); val wire = (key and 7).toInt()
            require(n > 0)
            if (wire == 0) result += Field(n, wire, value = variable())
            else {
                val length = when (wire) { 1 -> 8L; 5 -> 4L; 2 -> variable(); else -> error("Unsupported StarNote wire type") }
                require(length in 0..(bytes.size - p).toLong()) { "Truncated StarNote field" }
                result += Field(n, wire, bytes.copyOfRange(p, p + length.toInt())); p += length.toInt()
            }
        }
        return result
    }
    private fun List<Field>.text(n: Int) = firstOrNull { it.number == n }?.bytes?.toString(Charsets.UTF_8).orEmpty()
    private fun List<Field>.number(n: Int) = firstOrNull { it.number == n }?.value ?: 0
    private fun List<Field>.float(n: Int) = firstOrNull { it.number == n && it.wire == 5 }?.bytes
        ?.let { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).float } ?: 0f
    internal fun archive(bytes: ByteArray): Map<String, ByteArray> {
        require(bytes.size <= LIMIT)
        val result = linkedMapOf<String,ByteArray>(); var total = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(result.size < 10000) { "Too many backup entries" }
                require(!entry.name.startsWith('/') && entry.name.split('/').none { it == ".." })
                if (!entry.isDirectory) {
                    val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                    while (true) {
                        val n = zip.read(buffer); if (n < 0) break
                        total += n; require(total <= LIMIT) { "StarNote backup expands beyond 64 MB" }
                        output.write(buffer, 0, n)
                    }
                    require(!result.containsKey(entry.name)) { "Duplicate backup entry" }
                    result[entry.name] = output.toByteArray()
                }
            }
        }
        require(result.isNotEmpty()) { "Empty or unsupported StarNote archive" }
        return result
    }
    fun read(archives: List<ByteArray>): StarDocument {
        val pages = linkedMapOf<String,StarPage>()
        val shapes = linkedMapOf<String,MutableMap<String,Pair<Long,StarStroke?>>>()
        val warnings = linkedSetOf<String>()
        var title=""
        var expanded = 0L
        var pointCount = 0L
        fun unpack(bytes: ByteArray): Map<String,ByteArray> = archive(bytes).also { files ->
            expanded += files.values.sumOf { it.size.toLong() }
            require(expanded <= LIMIT) { "StarNote backup expands beyond 64 MB" }
        }
        require(archives.sumOf { it.size.toLong() } <= LIMIT) { "This note is too large to open here" }
        for (bytes in archives) for ((path, data) in unpack(bytes)) {
            when {
                "/docData/page/" in path -> fields(data).also { fields ->
                    if (fields.any { it.number != 1 || it.wire != 2 }) warnings += "Some page updates are unsupported; this preview may be incomplete."
                }.filter { it.number == 1 && it.wire == 2 }.forEach {
                    val f = fields(it.bytes); val id = f.text(1); val rect = JSONObject(f.text(6))
                    val kind = f.text(9); val resource = f.text(10)
                    val width = (rect.optDouble("right") - rect.optDouble("left")).toFloat()
                    val height = (rect.optDouble("bottom") - rect.optDouble("top")).toFloat()
                    require(id.isNotBlank() && width.isFinite() && height.isFinite() && width > 0 && height > 0)
                    if (kind !in listOf("geo_layout", "import_pdf")) warnings += "Some page backgrounds use an unsupported StarNote format."
                    pages[id] = StarPage(id, f.float(4), width, height, kind,
                        if (kind == "import_pdf") resource.substringAfter("/resource/").substringAfter("/template/") else "",
                        if (kind == "import_pdf") JSONObject(f.text(11)).optInt("pageIndex") else 0,
                        if (kind == "geo_layout") resource.substringAfterLast('/') else "")
                    require(pages.size <= 1000) { "This note has too many pages" }
                }
                "/shape/" in path && path.endsWith(".zip") -> {
                    val page = path.substringAfterLast('/').substringBefore('#')
                    val records = shapes.getOrPut(page) { linkedMapOf() }
                    for ((_, raw) in unpack(data)) for (record in fields(raw)) {
                        if (record.wire != 2 || record.number != 1) { warnings += "Some drawing records could not be displayed."; continue }
                        val f = fields(record.bytes); val id = f.text(1); val time = f.number(3)
                        val pointsData = f.firstOrNull { it.number == 25 }?.bytes
                        if (pointsData == null) {
                            if (records[id] == null || time >= records.getValue(id).first) records[id] = time to null
                            if (!runCatching { JSONObject(f.text(7)).optBoolean("empty") }.getOrDefault(false))
                                warnings += "Some drawing objects are unsupported; this preview may be incomplete."
                            continue
                        }
                        require(pointsData.size % 16 == 0) { "Unsupported StarNote stroke encoding" }
                        val buffer = ByteBuffer.wrap(pointsData).order(ByteOrder.BIG_ENDIAN)
                        val points = ArrayList<StarPoint>()
                        pointCount += pointsData.size / 16
                        require(pointCount <= 1_000_000) { "This note has too much drawing data to preview" }
                        while (buffer.remaining() >= 16) {
                            val x = buffer.float; val y = buffer.float; buffer.position(buffer.position() + 8)
                            require(x.isFinite() && y.isFinite())
                            points += StarPoint(x,y)
                        }
                        val matrix = runCatching { JSONObject(f.text(8)).getJSONArray("values").let { a ->
                            require(a.length() == 9); (0..8).map { a.getDouble(it).toFloat().also { v -> require(v.isFinite()) } }
                        } }.getOrElse { warnings += "A drawing transformation could not be read."; listOf(1f,0f,0f,0f,1f,0f,0f,0f,1f) }
                        val width = f.float(5); require(width.isFinite())
                        if (records[id] == null || time >= records.getValue(id).first)
                            records[id] = time to StarStroke(id, f.number(4).toInt(), width.coerceIn(.1f,100f), points, matrix)
                    }
                }
                "/docData/doc/" in path -> {
                    fields(data).filter {it.wire==2}.forEach {field->
                        val text=field.bytes.toString(Charsets.UTF_8)
                        if(text.startsWith("{"))runCatching {StarNoteTitles.fromJson(JSONObject(text))}.getOrNull()?.takeIf {it.isNotBlank()}?.let {title=it}
                    }
                }
                else -> warnings += "Some StarNote sync updates are unsupported; this preview may be incomplete."
            }
        }
        require(pages.isNotEmpty()) { "This StarNote backup has no supported pages. Export it as PDF from StarNote to open it here." }
        return StarDocument(pages.values.sortedBy { it.order }.map { it.copy(strokes = shapes[it.id]?.values?.mapNotNull { p -> p.second }.orEmpty()) }, warnings.toList(),title)
    }
}

object StarNoteTitles {
    fun fromJson(j:JSONObject):String = listOf("noteName","documentName","title","name").firstNotNullOfOrNull {key->
        (j.opt(key) as? String)?.trim()?.takeIf {it.isNotBlank() && it.length<=300 && !Regex("[a-fA-F0-9-]{32,36}").matches(it)}
    }.orEmpty()
}
