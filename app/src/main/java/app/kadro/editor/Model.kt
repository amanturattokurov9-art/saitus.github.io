package app.kadro.editor

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** One piece of footage on the main track. All times are milliseconds. */
data class Clip(
    val id: String = newId(),
    val uri: String,
    val mime: String,
    val isImage: Boolean,
    /** Full length of the source file (for photos: how long the photo may be stretched). */
    val sourceMs: Long,
    val hasAudio: Boolean,
    val width: Int,
    val height: Int,
    /** Trim window inside the source. */
    val inMs: Long,
    val outMs: Long,
    val speed: Float = 1f,
    val volume: Float = 1f,
    val rotation: Int = 0,
    val mirror: Boolean = false,
    val fill: Boolean = false,
    val zoom: Float = 1f,
    val look: String = "none",
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val fadeInMs: Long = 0,
    val fadeOutMs: Long = 0,
) {
    /** Length on the timeline after trim and speed. */
    val durMs: Long get() = ((outMs - inMs) / speed).toLong().coerceAtLeast(1)
}

data class TextLayer(
    val id: String = newId(),
    val text: String,
    val startMs: Long,
    val endMs: Long,
    /** Centre position in canvas space, -1..1, y pointing up. */
    val x: Float = 0f,
    val y: Float = 0f,
    /** Text height as a fraction of the canvas height. */
    val size: Float = 0.07f,
    val color: Int = 0xFFFFFFFF.toInt(),
    val plate: Boolean = false,
    val bold: Boolean = true,
    val outline: Boolean = true,
)

data class Music(
    val uri: String,
    val title: String,
    val sourceMs: Long,
    val inMs: Long = 0,
    val volume: Float = 0.8f,
    val loop: Boolean = true,
)

data class Project(
    val id: String = newId(),
    val name: String = "Без названия",
    val ratio: String = "9:16",
    val clips: List<Clip> = emptyList(),
    val texts: List<TextLayer> = emptyList(),
    val music: Music? = null,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val totalMs: Long get() = clips.sumOf { it.durMs }

    fun startOf(index: Int): Long = clips.take(index).sumOf { it.durMs }

    /** Index of the clip under timeline position [t], and the offset inside it. */
    fun locate(t: Long): Pair<Int, Long>? {
        var acc = 0L
        clips.forEachIndexed { i, c ->
            if (t < acc + c.durMs) return i to (t - acc)
            acc += c.durMs
        }
        return if (clips.isEmpty()) null else clips.lastIndex to clips.last().durMs
    }

    /** Canvas size in pixels whose short side is [shortSide]. */
    fun canvas(shortSide: Int): Pair<Int, Int> {
        val (rw, rh) = when (ratio) {
            "16:9" -> 16f to 9f
            "1:1" -> 1f to 1f
            "4:5" -> 4f to 5f
            "3:4" -> 3f to 4f
            "orig" -> clips.firstOrNull()?.let {
                val turned = it.rotation % 180 != 0
                if (turned) it.height.toFloat() to it.width.toFloat()
                else it.width.toFloat() to it.height.toFloat()
            } ?: (9f to 16f)
            else -> 9f to 16f
        }
        fun even(v: Float) = (Math.round(v / 2f) * 2).coerceAtLeast(2)
        return if (rw >= rh) even(shortSide * rw / rh) to even(shortSide.toFloat())
        else even(shortSide.toFloat()) to even(shortSide * rh / rw)
    }
}

fun newId(): String = UUID.randomUUID().toString().take(8)

/** Colour looks. Each is a 4x4 column-major RGBA matrix. */
object Looks {
    val names = listOf(
        "none" to "Оригинал",
        "mono" to "Графит",
        "sepia" to "Сепия",
        "amber" to "Янтарь",
        "frost" to "Иней",
        "punch" to "Сочно",
        "haze" to "Дымка",
        "noir" to "Нуар",
        "film" to "Плёнка",
        "rose" to "Роза",
    )

    private fun rows(
        r: FloatArray, g: FloatArray, b: FloatArray,
    ): FloatArray = floatArrayOf(
        // column-major: each group below is one column
        r[0], g[0], b[0], 0f,
        r[1], g[1], b[1], 0f,
        r[2], g[2], b[2], 0f,
        r[3], g[3], b[3], 1f,
    )

    private fun sat(s: Float, gain: Float = 1f, lift: Float = 0f): FloatArray {
        val lr = 0.2126f; val lg = 0.7152f; val lb = 0.0722f
        val i = 1f - s
        return rows(
            floatArrayOf((lr * i + s) * gain, lg * i * gain, lb * i * gain, lift),
            floatArrayOf(lr * i * gain, (lg * i + s) * gain, lb * i * gain, lift),
            floatArrayOf(lr * i * gain, lg * i * gain, (lb * i + s) * gain, lift),
        )
    }

    fun matrix(name: String): FloatArray? = when (name) {
        "mono" -> sat(0f)
        "sepia" -> rows(
            floatArrayOf(0.393f, 0.769f, 0.189f, 0f),
            floatArrayOf(0.349f, 0.686f, 0.168f, 0f),
            floatArrayOf(0.272f, 0.534f, 0.131f, 0f),
        )
        "amber" -> rows(
            floatArrayOf(1.12f, 0.04f, 0f, 0.02f),
            floatArrayOf(0f, 1.02f, 0f, 0.01f),
            floatArrayOf(0f, 0f, 0.84f, -0.02f),
        )
        "frost" -> rows(
            floatArrayOf(0.88f, 0f, 0f, -0.01f),
            floatArrayOf(0f, 1.0f, 0.02f, 0f),
            floatArrayOf(0f, 0.04f, 1.14f, 0.03f),
        )
        "punch" -> sat(1.45f, 1.05f)
        "haze" -> sat(0.75f, 0.86f, 0.1f)
        "noir" -> sat(0f, 1.35f, -0.12f)
        "film" -> rows(
            floatArrayOf(1.06f, 0.02f, -0.02f, 0.03f),
            floatArrayOf(0f, 0.98f, 0.02f, 0.02f),
            floatArrayOf(-0.02f, 0.06f, 0.88f, 0.05f),
        )
        "rose" -> rows(
            floatArrayOf(1.1f, 0.02f, 0.02f, 0.03f),
            floatArrayOf(0f, 0.94f, 0.02f, 0f),
            floatArrayOf(0.02f, 0f, 1.02f, 0.03f),
        )
        else -> null
    }

    /** Same look as an Android ColorMatrix (row-major 4x5), for the picker swatches. */
    fun colorMatrix(name: String): FloatArray? {
        val m = matrix(name) ?: return null
        return floatArrayOf(
            m[0], m[4], m[8], 0f, m[12] * 255f,
            m[1], m[5], m[9], 0f, m[13] * 255f,
            m[2], m[6], m[10], 0f, m[14] * 255f,
            0f, 0f, 0f, 1f, 0f,
        )
    }
}

// ---------- JSON ----------

fun Project.toJson(): JSONObject = JSONObject().apply {
    put("id", id); put("name", name); put("ratio", ratio); put("updatedAt", updatedAt)
    put("clips", JSONArray().also { a ->
        clips.forEach { c ->
            a.put(JSONObject().apply {
                put("id", c.id); put("uri", c.uri); put("mime", c.mime); put("isImage", c.isImage)
                put("sourceMs", c.sourceMs); put("hasAudio", c.hasAudio)
                put("width", c.width); put("height", c.height)
                put("inMs", c.inMs); put("outMs", c.outMs)
                put("speed", c.speed.toDouble()); put("volume", c.volume.toDouble())
                put("rotation", c.rotation); put("mirror", c.mirror)
                put("fill", c.fill); put("zoom", c.zoom.toDouble()); put("look", c.look)
                put("brightness", c.brightness.toDouble()); put("contrast", c.contrast.toDouble())
                put("saturation", c.saturation.toDouble())
                put("fadeInMs", c.fadeInMs); put("fadeOutMs", c.fadeOutMs)
            })
        }
    })
    put("texts", JSONArray().also { a ->
        texts.forEach { t ->
            a.put(JSONObject().apply {
                put("id", t.id); put("text", t.text); put("startMs", t.startMs); put("endMs", t.endMs)
                put("x", t.x.toDouble()); put("y", t.y.toDouble()); put("size", t.size.toDouble())
                put("color", t.color); put("plate", t.plate); put("bold", t.bold)
                put("outline", t.outline)
            })
        }
    })
    music?.let { m ->
        put("music", JSONObject().apply {
            put("uri", m.uri); put("title", m.title); put("sourceMs", m.sourceMs)
            put("inMs", m.inMs); put("volume", m.volume.toDouble()); put("loop", m.loop)
        })
    }
}

fun projectFromJson(o: JSONObject): Project {
    val clips = o.optJSONArray("clips") ?: JSONArray()
    val texts = o.optJSONArray("texts") ?: JSONArray()
    return Project(
        id = o.getString("id"),
        name = o.optString("name", "Без названия"),
        ratio = o.optString("ratio", "9:16"),
        updatedAt = o.optLong("updatedAt"),
        clips = (0 until clips.length()).map { i ->
            val c = clips.getJSONObject(i)
            Clip(
                id = c.getString("id"), uri = c.getString("uri"), mime = c.optString("mime"),
                isImage = c.optBoolean("isImage"), sourceMs = c.getLong("sourceMs"),
                hasAudio = c.optBoolean("hasAudio"),
                width = c.optInt("width", 1080), height = c.optInt("height", 1920),
                inMs = c.getLong("inMs"), outMs = c.getLong("outMs"),
                speed = c.optDouble("speed", 1.0).toFloat(),
                volume = c.optDouble("volume", 1.0).toFloat(),
                rotation = c.optInt("rotation"), mirror = c.optBoolean("mirror"),
                fill = c.optBoolean("fill"), zoom = c.optDouble("zoom", 1.0).toFloat(),
                look = c.optString("look", "none"),
                brightness = c.optDouble("brightness", 0.0).toFloat(),
                contrast = c.optDouble("contrast", 0.0).toFloat(),
                saturation = c.optDouble("saturation", 0.0).toFloat(),
                fadeInMs = c.optLong("fadeInMs"), fadeOutMs = c.optLong("fadeOutMs"),
            )
        },
        texts = (0 until texts.length()).map { i ->
            val t = texts.getJSONObject(i)
            TextLayer(
                id = t.getString("id"), text = t.getString("text"),
                startMs = t.getLong("startMs"), endMs = t.getLong("endMs"),
                x = t.optDouble("x", 0.0).toFloat(), y = t.optDouble("y", 0.0).toFloat(),
                size = t.optDouble("size", 0.07).toFloat(), color = t.optInt("color", -1),
                plate = t.optBoolean("plate"), bold = t.optBoolean("bold", true),
                outline = t.optBoolean("outline", true),
            )
        },
        music = o.optJSONObject("music")?.let { m ->
            Music(
                uri = m.getString("uri"), title = m.optString("title", "Музыка"),
                sourceMs = m.getLong("sourceMs"), inMs = m.optLong("inMs"),
                volume = m.optDouble("volume", 0.8).toFloat(), loop = m.optBoolean("loop", true),
            )
        },
    )
}
