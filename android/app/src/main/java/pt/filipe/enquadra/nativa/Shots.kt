package pt.filipe.enquadra.nativa

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.File

/** One photo in the app's own gallery: the original crop plus the edits (filter, border) and what we know about it. */
class Rec(val id: Long, val date: Long, var zoom: Double, var subj: String, var style: String, var filter: String, var border: String,
           var lens: String, var w: Int, var h: Int, var savedUri: String = "") {
    fun json(): JSONObject = JSONObject().put("id", id).put("date", date).put("zoom", zoom).put("subj", subj).put("style", style)
        .put("filter", filter).put("border", border).put("lens", lens).put("w", w).put("h", h).put("savedUri", savedUri)
    companion object {
        fun of(j: JSONObject) = Rec(j.getLong("id"), j.optLong("date", j.getLong("id")), j.optDouble("zoom", 1.0), j.optString("subj"), j.optString("style", "tercos"),
            j.optString("filter", "clean"), j.optString("border", "none"), j.optString("lens"), j.optInt("w"), j.optInt("h"), j.optString("savedUri"))
    }
}

/** The app gallery lives in the app's private folder: shots/<id>.jpg (original), <id>_t.jpg (thumbnail), <id>.json (edits). */
class Shots(ctx: Context) {
    private val dir = File(ctx.filesDir, "shots").apply { mkdirs() }
    fun photo(id: Long) = File(dir, "$id.jpg")
    fun thumbFile(id: Long) = File(dir, "${id}_t.jpg")
    private fun meta(id: Long) = File(dir, "$id.json")

    fun add(bmp: Bitmap, s: Rec) {
        photo(s.id).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 96, it) }
        val k = minOf(1f, 480f / minOf(bmp.width, bmp.height))
        val t = Bitmap.createScaledBitmap(bmp, maxOf(1, (bmp.width * k).toInt()), maxOf(1, (bmp.height * k).toInt()), true)
        thumbFile(s.id).outputStream().use { t.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        put(s)
    }
    fun put(s: Rec) { meta(s.id).writeText(s.json().toString()) }
    fun all(): List<Rec> = (dir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray())
        .mapNotNull { try { Rec.of(JSONObject(it.readText())) } catch (e: Exception) { null } }.sortedByDescending { it.id }
    fun delete(id: Long) { photo(id).delete(); thumbFile(id).delete(); meta(id).delete() }
    fun load(id: Long): Bitmap? = BitmapFactory.decodeFile(photo(id).path)
    fun thumb(id: Long): Bitmap? = BitmapFactory.decodeFile(thumbFile(id).path)
}
