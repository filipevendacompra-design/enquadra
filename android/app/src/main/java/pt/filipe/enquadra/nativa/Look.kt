package pt.filipe.enquadra.nativa

import android.graphics.*
import kotlin.math.*

/*
 * Filters and borders, ported from index.html (FILTERS / BORDERS / filteredOf / renderResult).
 * The CSS filter chain becomes one ColorMatrix; washes, grain and vignette are drawn on top with the same blend modes.
 */

class Wash(val color: Int, val mode: BlendMode, val alpha: Float)
class Filt(val id: String, val label: String, val chain: List<Pair<String, Float>>, val wash: Wash? = null, val wash2: Wash? = null,
           val grain: Float = 0f, val vig: Float = 0f)

private fun c(s: String) = Color.parseColor(s)

val FILTERS = listOf(
    Filt("clean", "Original", emptyList()),
    Filt("vivo", "Vivo", listOf("contrast" to 1.18f, "saturate" to 1.35f), Wash(c("#7fb2ff"), BlendMode.SOFT_LIGHT, .14f)),
    Filt("quente", "Quente", listOf("contrast" to 1.08f, "saturate" to 1.2f, "sepia" to .12f), Wash(c("#ffb36b"), BlendMode.SOFT_LIGHT, .22f)),
    Filt("claro", "Claro", listOf("brightness" to 1.12f, "contrast" to .92f, "saturate" to .95f), Wash(c("#e8f4ff"), BlendMode.SOFT_LIGHT, .2f)),
    Filt("suave", "Suave", listOf("brightness" to 1.05f, "contrast" to .85f, "saturate" to .78f, "sepia" to .08f), Wash(c("#f6efe2"), BlendMode.LIGHTEN, .16f)),
    Filt("pelicula", "Película", listOf("contrast" to .95f, "saturate" to 1.05f, "sepia" to .18f), Wash(c("#2b3a55"), BlendMode.LIGHTEN, .2f), grain = .10f),
    Filt("dourado", "Dourado", listOf("contrast" to 1.08f, "saturate" to 1.25f, "sepia" to .25f, "brightness" to 1.04f), Wash(c("#f2b33d"), BlendMode.OVERLAY, .12f)),
    Filt("cinema", "Cinema", listOf("contrast" to 1.15f, "saturate" to 1.1f), Wash(c("#0f6b78"), BlendMode.SOFT_LIGHT, .28f), Wash(c("#ff8a3d"), BlendMode.SOFT_LIGHT, .16f), vig = .35f),
    Filt("porto", "Porto", listOf("contrast" to 1.08f, "saturate" to 1.12f, "brightness" to 1.02f)),
    Filt("dusk", "Dusk", listOf("sepia" to .22f, "saturate" to 1.3f, "contrast" to 1.06f, "hue" to -8f)),
    Filt("relic", "Relic", listOf("grayscale" to .3f, "sepia" to .35f, "contrast" to .9f, "brightness" to 1.06f), grain = .06f),
    Filt("lofi", "Lo-fi", listOf("contrast" to 1.35f, "saturate" to 1.5f), vig = .45f),
    Filt("pb", "P&B", listOf("grayscale" to 1f, "contrast" to 1.1f)),
    Filt("noir", "Noir", listOf("grayscale" to 1f, "contrast" to 1.45f, "brightness" to .95f), vig = .5f, grain = .08f)
)
val BORDERS = listOf("none" to "Sem", "ficha" to "Ficha técnica", "polaroid" to "Polaroid", "white" to "Branca", "galeria" to "Galeria",
    "pelicula" to "Película", "desfocado" to "Desfocado", "paper" to "Papel", "date" to "Data")

/** One CSS filter function as a 4x5 colour matrix (values 0..255). */
private fun cssMatrix(name: String, a: Float): ColorMatrix {
    val m = ColorMatrix()
    when (name) {
        "brightness" -> m.setScale(a, a, a, 1f)
        "contrast" -> { val o = (.5f - .5f * a) * 255f; m.set(floatArrayOf(a, 0f, 0f, 0f, o, 0f, a, 0f, 0f, o, 0f, 0f, a, 0f, o, 0f, 0f, 0f, 1f, 0f)) }
        "saturate" -> { val s = a; m.set(floatArrayOf(
            .213f + .787f * s, .715f - .715f * s, .072f - .072f * s, 0f, 0f,
            .213f - .213f * s, .715f + .285f * s, .072f - .072f * s, 0f, 0f,
            .213f - .213f * s, .715f - .715f * s, .072f + .928f * s, 0f, 0f, 0f, 0f, 0f, 1f, 0f)) }
        "grayscale" -> { val k = 1 - a; m.set(floatArrayOf(
            .2126f + .7874f * k, .7152f - .7152f * k, .0722f - .0722f * k, 0f, 0f,
            .2126f - .2126f * k, .7152f + .2848f * k, .0722f - .0722f * k, 0f, 0f,
            .2126f - .2126f * k, .7152f - .7152f * k, .0722f + .9278f * k, 0f, 0f, 0f, 0f, 0f, 1f, 0f)) }
        "sepia" -> { val k = 1 - a; m.set(floatArrayOf(
            .393f + .607f * k, .769f - .769f * k, .189f - .189f * k, 0f, 0f,
            .349f - .349f * k, .686f + .314f * k, .168f - .168f * k, 0f, 0f,
            .272f - .272f * k, .534f - .534f * k, .131f + .869f * k, 0f, 0f, 0f, 0f, 0f, 1f, 0f)) }
        "hue" -> { val r = Math.toRadians(a.toDouble()); val co = cos(r).toFloat(); val si = sin(r).toFloat(); m.set(floatArrayOf(
            .213f + co * .787f - si * .213f, .715f - co * .715f - si * .715f, .072f - co * .072f + si * .928f, 0f, 0f,
            .213f - co * .213f + si * .143f, .715f + co * .285f + si * .140f, .072f - co * .072f - si * .283f, 0f, 0f,
            .213f - co * .213f - si * .787f, .715f - co * .715f + si * .715f, .072f + co * .928f + si * .072f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)) }
    }
    return m
}
fun filterMatrix(f: Filt): ColorMatrix { val m = ColorMatrix(); for ((n, a) in f.chain) m.postConcat(cssMatrix(n, a)); return m }

private var grainTile: Bitmap? = null
private fun grain(): Bitmap = grainTile ?: Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888).also { b ->
    val r = java.util.Random(7); val px = IntArray(160 * 160) { val v = (128 + (r.nextDouble() - .5) * 255).toInt().coerceIn(0, 255); Color.rgb(v, v, v) }
    b.setPixels(px, 0, 160, 0, 0, 160, 160); grainTile = b }

/** The photo with a filter applied (same size). */
fun filtered(src: Bitmap, id: String): Bitmap {
    val f = FILTERS.firstOrNull { it.id == id } ?: FILTERS[0]
    val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888); val cv = Canvas(out)
    cv.drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { if (f.chain.isNotEmpty()) colorFilter = ColorMatrixColorFilter(filterMatrix(f)) })
    for (w in listOfNotNull(f.wash, f.wash2)) cv.drawRect(0f, 0f, out.width.toFloat(), out.height.toFloat(), Paint().apply { color = w.color; alpha = (w.alpha * 255).roundToInt(); blendMode = w.mode })
    if (f.grain > 0) cv.drawRect(0f, 0f, out.width.toFloat(), out.height.toFloat(), Paint().apply {
        shader = BitmapShader(grain(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT); alpha = (f.grain * 255).roundToInt(); blendMode = BlendMode.OVERLAY })
    if (f.vig > 0) { val w = out.width.toFloat(); val h = out.height.toFloat(); val r = hypot(w, h) / 2
        cv.drawRect(0f, 0f, w, h, Paint().apply { shader = RadialGradient(w / 2, h / 2, r, intArrayOf(0, 0, Color.argb((f.vig * 255).roundToInt(), 0, 0, 0)), floatArrayOf(0f, .45f, 1f), Shader.TileMode.CLAMP) }) }
    return out
}

class ShotInfo(val cam: String, val lens: String, val settings: String, val date: String, val label: String, val shotDate: java.util.Date)

/** The finished picture: filter + border (and the border's text). */
fun render(src: Bitmap, filterId: String, border: String, info: ShotInfo): Bitmap {
    val ph = filtered(src, filterId); val W = ph.width; val H = ph.height; val m = min(W, H).toFloat()
    var pl = 0; var pt = 0; var pr = 0; var pb = 0; var bg: Int? = null
    fun r(v: Double) = (m * v).roundToInt()
    when (border) {
        "white" -> { pl = r(.04); pt = pl; pr = pl; pb = pl; bg = Color.WHITE }
        "ficha" -> { pl = r(.035); pt = pl; pr = pl; pb = r(.17); bg = Color.WHITE }
        "polaroid" -> { pl = r(.055); pt = pl; pr = pl; pb = r(.27); bg = c("#fbfaf7") }
        "galeria" -> { pl = r(.13); pr = pl; pt = r(.13); pb = r(.24); bg = c("#f3f1ec") }
        "pelicula" -> { pl = r(.035); pr = pl; pt = r(.085); pb = pt; bg = c("#0c0c0b") }
        "paper" -> { pl = r(.06); pt = pl; pr = pl; pb = r(.18); bg = c("#f1ece2") }
    }
    if (border == "desfocado") {
        val side = (max(W, H) * 1.16).roundToInt()
        val out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888); val o = Canvas(out)
        // blurred, darker copy as the backdrop (downscale + upscale = a soft blur)
        val k = max(side.toFloat() / W, side.toFloat() / H) * 1.1f
        val small = Bitmap.createScaledBitmap(ph, max(8, W / 24), max(8, H / 24), true)
        val cm = ColorMatrix().apply { postConcat(cssMatrix("brightness", .72f)); postConcat(cssMatrix("saturate", 1.2f)) }
        val dw = W * k; val dh = H * k
        o.drawBitmap(small, null, RectF((side - dw) / 2, (side - dh) / 2, (side + dw) / 2, (side + dh) / 2), Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(cm) })
        val x = (side - W) / 2f; val y = (side - H) / 2f
        o.drawRect(x, y, x + W, y + H, Paint().apply { color = Color.BLACK; setShadowLayer(m * .05f, 0f, m * .012f, Color.argb(115, 0, 0, 0)) })
        o.drawBitmap(ph, x, y, null)
        return out
    }
    val ow = W + pl + pr; val oh = H + pt + pb
    val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888); val o = Canvas(out)
    bg?.let { o.drawColor(it) }
    if (border == "galeria") { val g = m * .018f
        o.drawRect(pl - g, pt - g, pl + W + g, pt + H + g, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = c("#d8d3c8"); strokeWidth = max(1f, m * .003f) }) }
    o.drawBitmap(ph, pl.toFloat(), pt.toFloat(), null)
    val by = (pt + H).toFloat()
    fun paint(tf: Typeface, size: Float, color: Int, align: Paint.Align = Paint.Align.LEFT) = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = tf; textSize = size; this.color = color; textAlign = align }
    fun midY(p: Paint, y: Float) = y - (p.descent() + p.ascent()) / 2
    when (border) {
        "ficha" -> {
            val mid = by + pb / 2f; val gap = m * .022f
            o.drawText(info.cam, pl.toFloat(), mid - gap * .2f, paint(F.uiSemi, m * .042f, c("#141414")))
            o.drawText(info.lens, pl.toFloat(), mid + gap * 1.25f, paint(F.ui, m * .029f, c("#8a877f")))
            val ps = paint(F.monoMed, m * .031f, c("#141414"), Paint.Align.RIGHT)
            o.drawText(info.settings, (pl + W).toFloat(), mid - gap * .2f, ps)
            o.drawText(info.date, (pl + W).toFloat(), mid + gap * 1.25f, paint(F.mono, m * .024f, c("#8a877f"), Paint.Align.RIGHT))
            val sw = ps.measureText(info.settings); val xd = pl + W - sw - m * .035f
            o.drawRect(xd, mid - gap * 1.6f, xd + max(1f, m * .0025f), mid + gap * 1.6f, Paint().apply { color = c("#d9d6cf") })
            val q = m * .03f
            o.drawRect(xd - m * .035f - q, mid - q / 2 - gap * .2f, xd - m * .035f, mid + q / 2 - gap * .2f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = c("#f2b33d"); strokeWidth = max(2f, m * .006f) })
        }
        "polaroid" -> {
            val p1 = paint(F.hand, m * .085f, c("#2b2a27"), Paint.Align.CENTER); o.drawText(info.label, pl + W / 2f, midY(p1, by + pb * .48f), p1)
            val p2 = paint(F.hand, m * .05f, c("#7a766e"), Paint.Align.RIGHT); o.drawText(info.date, (pl + W).toFloat(), midY(p2, by + pb * .82f), p2)
        }
        "galeria" -> {
            o.drawText(info.label, ow / 2f, by + pb * .5f, paint(F.uiMed, m * .048f, c("#2a2926"), Paint.Align.CENTER))
            o.drawText("${info.date}  ·  ${info.settings}", ow / 2f, by + pb * .5f + m * .065f, paint(F.mono, m * .03f, c("#8d897f"), Paint.Align.CENTER))
        }
        "pelicula" -> {
            val pL = paint(F.monoMed, m * .03f, c("#f29a2e")); val pR = paint(F.monoMed, m * .03f, c("#f29a2e"), Paint.Align.RIGHT)
            o.drawText("ENQUADRA 400", pl.toFloat(), midY(pL, pt / 2f), pL); o.drawText("12A", (pl + W).toFloat(), midY(pR, pt / 2f), pR)
            o.drawText("12", pl.toFloat(), midY(pL, by + pb / 2f), pL); o.drawText(info.date, (pl + W).toFloat(), midY(pR, by + pb / 2f), pR)
        }
        "paper" -> { val p = paint(F.mono, m * .035f, c("#6b655a")); o.drawText(info.label, pl.toFloat(), midY(p, by + pb / 2f), p) }
        "date" -> {
            val d = info.shotDate; val cal = java.util.Calendar.getInstance().apply { time = d }
            val t = "%02d %02d '%02d".format(cal.get(java.util.Calendar.DAY_OF_MONTH), cal.get(java.util.Calendar.MONTH) + 1, cal.get(java.util.Calendar.YEAR) % 100)
            val p = paint(F.monoMed, m * .045f, c("#ff9a3c"), Paint.Align.RIGHT).apply { setShadowLayer(m * .012f, 0f, 0f, Color.argb(204, 255, 120, 30)) }
            o.drawText(t, ow - m * .05f, oh - m * .04f - p.descent(), p)
        }
    }
    return out
}
