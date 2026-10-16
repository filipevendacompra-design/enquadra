package pt.filipe.enquadra.nativa

import android.graphics.*
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * "Curator" mode. Vision models are poor at drawing precise boxes but good at judging pictures.
 * So the phone proposes a dozen real, well-built crops of the scene (the composition engine),
 * shows them to Gemini as one numbered contact sheet, and Gemini picks the most artistic photograph.
 * What it picks is exactly what gets shot: nothing is re-cropped after its choice.
 */

class Cand(val crop: Crop, val style: String, val score: Double, val tele: Boolean)

private fun iou(a: Crop, b: Crop): Double {
    val ix = max(0, min(a.x0 + a.cw, b.x0 + b.cw) - max(a.x0, b.x0)); val iy = max(0, min(a.y0 + a.ch, b.y0 + b.ch) - max(a.y0, b.y0))
    val inter = ix.toDouble() * iy; val uni = a.cw.toDouble() * a.ch + b.cw.toDouble() * b.ch - inter
    return if (uni <= 0) 0.0 else inter / uni
}

/** Diverse candidate crops: every composition rule at several sizes, best first, no two alike. s5: the 5x frame size (or null). */
fun candidates(F: Features, ratio: Double, sizes: List<Double>, s5: Double?, max: Int = 12): List<Cand> {
    val raw = ArrayList<Cand>()
    val all = (sizes + listOfNotNull(s5)).distinct()
    for (s in all) for (st in STYLE_IDS) {
        val c = bestCrop(F, null, ratio, st, CropOpt(listOf(s))) ?: continue
        val m = metrics(F, c.x0, c.y0, c.cw, c.ch, s, null)
        raw.add(Cand(c, st, scoreOf(st, m, null), s5 != null && s == s5))
    }
    // also every position on a grid, scored by its best rule: more variety for the editor to choose from
    val maxH = min(F.H.toDouble(), F.W / ratio)
    for (s in all) {
        val chh = max(8, (maxH * s).toInt()); val cww = max(8, min(F.W, (chh * ratio).toInt()))
        for (iy in 0..6) for (ix in 0..6) {
            val x0 = ((F.W - cww) * ix / 6.0).roundToInt(); val y0 = ((F.H - chh) * iy / 6.0).roundToInt()
            if (x0 < 0 || y0 < 0 || x0 + cww > F.W || y0 + chh > F.H) continue
            val m = metrics(F, x0, y0, cww, chh, s, null)
            var bst = ""; var bsc = -1e9
            for (st in STYLE_IDS) { val sc = scoreOf(st, m, null); if (sc > bsc) { bsc = sc; bst = st } }
            raw.add(Cand(Crop(x0, y0, cww, chh), bst, bsc - .15, s5 != null && s == s5))   // slightly behind the optimised crops
        }
    }
    raw.sortByDescending { it.score }
    val out = ArrayList<Cand>()
    // a couple of 5x options first, when the 5x lens exists: tight, distant details are where it shines
    for (c in raw.filter { it.tele }) { if (out.count { it.tele } >= 3) break; if (out.all { iou(it.crop, c.crop) < .35 }) out.add(c) }
    for (c in raw) { if (out.size >= max) break; if (out.all { iou(it.crop, c.crop) < .45 }) out.add(c) }
    return out
}

/** One image with the candidates as numbered cards (3 columns), for Gemini to compare. */
fun contactSheet(snap: Bitmap, F: Features, cands: List<Cand>, ratio: Double): Bitmap {
    val cols = 3; val rows = (cands.size + cols - 1) / cols
    val cw = 340; val ch = (cw / ratio).roundToInt(); val gap = 14; val band = 36
    val W = cols * cw + (cols + 1) * gap; val H = rows * (ch + band) + (rows + 1) * gap
    val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888); val cv = Canvas(out); cv.drawColor(Color.WHITE)
    val kx = snap.width.toFloat() / F.W; val ky = snap.height.toFloat() / F.H
    val num = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 28f; typeface = Typeface.DEFAULT_BOLD }
    val pf = Paint(Paint.FILTER_BITMAP_FLAG)
    cands.forEachIndexed { i, c ->
        val col = i % cols; val row = i / cols
        val x = gap + col * (cw + gap); val y = gap + row * (ch + band + gap)
        cv.drawText("${i + 1}", x.toFloat(), y + 28f, num)       // the number sits above the photo, never on it
        val src = Rect((c.crop.x0 * kx).roundToInt(), (c.crop.y0 * ky).roundToInt(), ((c.crop.x0 + c.crop.cw) * kx).roundToInt(), ((c.crop.y0 + c.crop.ch) * ky).roundToInt())
        cv.drawBitmap(snap, src, Rect(x, y + band, x + cw, y + band + ch), pf)
    }
    return out
}
