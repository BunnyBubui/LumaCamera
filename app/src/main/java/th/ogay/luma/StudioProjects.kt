package th.ogay.luma

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Video-editor projects kept on the phone: one small JSON file per project (the source video's address plus every
 * edit setting) and one thumbnail. The source video itself is never copied or changed. [SCHEMA] is written into
 * each file so a later version of the app can tell how to read an older project.
 */
object StudioProjects {
    /**
     * Saving and listing projects run here, on their own thread. The shared photo/video reading thread can be busy for minutes with an
     * export or caption job, and a project queued behind it would not be saved or shown until that job ended.
     */
    val io: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newSingleThreadExecutor()

    const val SCHEMA = 1
    class Info(val id: String, val name: String, val source: Uri, val updated: Long, val thumb: File, val missing: Boolean,
        /** Short words for what was edited (for the list), and the length in seconds when it can be told simply (else 0). */
        val tags: List<String> = emptyList(), val seconds: Long = 0)
    private fun summary(st: Bundle): Pair<List<String>, Long> {
        val extras = st.getStringArrayList("extras")?.size ?: 0
        val speed = st.getFloat("speed", 1f)
        val tags = ArrayList<String>()
        if (extras > 0) tags += "${extras + 1} คลิป"
        if (speed < 0.99f) tags += "สโลว์ ${"%.2f".format(speed).trimEnd('0').trimEnd('.')}×" else if (speed > 1.01f) tags += "เร็ว ${"%.1f".format(speed).trimEnd('0').trimEnd('.')}×"
        if (!st.getString("music").isNullOrEmpty()) tags += "เพลง"
        if (!st.getString("caption").isNullOrBlank()) tags += "คำบรรยาย"
        if (st.getInt("crop") != 0) tags += "ครอป"
        if (!st.getString("film").isNullOrEmpty()) tags += "ฟิลเตอร์"
        val simple = extras == 0 && st.getLong("split", -1) < 0 && st.getLong("cutFrom", -1) < 0
        val end = st.getLong("end", 0); val start = st.getLong("start", 0)
        val secs = if (simple && end > start) ((end - start) / speed.coerceAtLeast(0.05f) / 1000f).toLong() else 0L
        return tags.take(3) to secs
    }

    private fun dir(c: Context) = File(c.filesDir, "studio").apply { mkdirs() }
    private fun file(c: Context, id: String) = File(dir(c), "$id.json")
    fun thumb(c: Context, id: String) = File(dir(c), "$id.jpg")
    fun newId() = "p" + System.currentTimeMillis().toString(36)
    fun exists(c: Context, id: String) = file(c, id).exists()

    /** Every value keeps its type, so a number saved as a whole number comes back as one. */
    fun encode(state: Bundle): JSONObject = JSONObject().also { o ->
        for (k in state.keySet()) {
            @Suppress("DEPRECATION") val v = state.get(k) ?: continue
            when (v) {
                is Long -> o.put(k, JSONArray().put("l").put(v))
                is Int -> o.put(k, JSONArray().put("i").put(v))
                is Float -> o.put(k, JSONArray().put("f").put(v.toDouble()))
                is Boolean -> o.put(k, JSONArray().put("b").put(v))
                is String -> o.put(k, JSONArray().put("s").put(v))
                is FloatArray -> o.put(k, JSONArray().put("F").put(JSONArray().also { a -> v.forEach { a.put(it.toDouble()) } }))
                is ArrayList<*> -> o.put(k, JSONArray().put("S").put(JSONArray().also { a -> v.forEach { a.put(it.toString()) } }))
            }
        }
    }
    fun decode(o: JSONObject): Bundle = Bundle().also { b ->
        for (k in o.keys()) {
            val a = o.optJSONArray(k) ?: continue
            when (a.optString(0)) {
                "l" -> b.putLong(k, a.getLong(1)); "i" -> b.putInt(k, a.getInt(1)); "f" -> b.putFloat(k, a.getDouble(1).toFloat())
                "b" -> b.putBoolean(k, a.getBoolean(1)); "s" -> b.putString(k, a.getString(1))
                "F" -> { val x = a.getJSONArray(1); b.putFloatArray(k, FloatArray(x.length()) { x.getDouble(it).toFloat() }) }
                "S" -> { val x = a.getJSONArray(1); b.putStringArrayList(k, ArrayList((0 until x.length()).map { x.getString(it) })) }
            }
        }
    }
    /** The edit as one line of text, without what is only about the screen (open tool, picked layer): two equal edits give the same text. */
    fun key(state: Bundle): String = encode(state).also { it.remove("tool"); it.remove("layerPick"); it.remove("projectId"); it.remove("projectName") }
        .let { o -> o.keys().asSequence().sorted().joinToString("|") { "$it=${o.get(it)}" } }

    fun save(c: Context, id: String, name: String, source: Uri, state: Bundle, picture: Bitmap?) {
        val o = JSONObject().put("schema", SCHEMA).put("name", name).put("source", source.toString()).put("updated", System.currentTimeMillis()).put("state", encode(state))
        // Written beside the real file first, then swapped in, so a crash mid-write cannot leave half a project.
        val tmp = File(dir(c), "$id.tmp"); tmp.writeText(o.toString()); tmp.renameTo(file(c, id))
        val t = thumb(c, id)
        if (picture != null && !picture.isRecycled && !t.exists()) runCatching { t.outputStream().use { picture.compress(Bitmap.CompressFormat.JPEG, 80, it) } }
    }
    class Loaded(val name: String, val source: Uri, val state: Bundle)
    fun load(c: Context, id: String): Loaded? = runCatching {
        val o = JSONObject(file(c, id).readText())
        Loaded(o.optString("name", "โปรเจกต์"), Uri.parse(o.getString("source")), decode(o.getJSONObject("state")))
    }.getOrNull()
    fun list(c: Context): List<Info> = (dir(c).listFiles { f -> f.name.endsWith(".json") } ?: emptyArray()).mapNotNull { f ->
        runCatching {
            val o = JSONObject(f.readText()); val id = f.name.removeSuffix(".json"); val src = Uri.parse(o.getString("source"))
            val gone = runCatching { c.contentResolver.openAssetFileDescriptor(src, "r")?.close() ?: throw IllegalStateException() }.isFailure
            val (tags, secs) = runCatching { summary(decode(o.getJSONObject("state"))) }.getOrDefault(emptyList<String>() to 0L)
            Info(id, o.optString("name", "โปรเจกต์"), src, o.optLong("updated"), thumb(c, id), gone, tags, secs)
        }.getOrNull()
    }.sortedByDescending { it.updated }
    fun rename(c: Context, id: String, name: String) { runCatching { val f = file(c, id); val o = JSONObject(f.readText()).put("name", name); f.writeText(o.toString()) } }
    fun duplicate(c: Context, id: String): String? = runCatching {
        val n = newId(); val o = JSONObject(file(c, id).readText()); o.put("name", o.optString("name") + " (สำเนา)").put("updated", System.currentTimeMillis())
        file(c, n).writeText(o.toString()); thumb(c, id).takeIf { it.exists() }?.copyTo(thumb(c, n), true); n
    }.getOrNull()
    /** Points a project whose video went missing at another file. The edits stay; times beyond the new file's end are cut back when it opens. */
    fun relink(c: Context, id: String, uri: Uri) { runCatching {
        val f = file(c, id); val o = JSONObject(f.readText()); val old = o.getString("source")
        o.put("source", uri.toString())
        o.optJSONObject("state")?.let { st -> st.optJSONArray("mainSource")?.let { a -> if (a.optString(1) == old) st.put("mainSource", JSONArray().put("s").put(uri.toString())) } }
        f.writeText(o.toString())
    } }
    fun delete(c: Context, id: String) { file(c, id).delete(); thumb(c, id).delete() }
}
