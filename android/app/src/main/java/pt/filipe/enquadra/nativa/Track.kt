package pt.filipe.enquadra.nativa

import kotlin.math.*

/** A small grey image (values 0..255). */
class Gray(val g: FloatArray, val W: Int, val H: Int)

/**
 * Visual tracking: how far the scene moved, measured on the image itself. Port of vtStep / vtMatch from index.html.
 * Fed with a 128-px-wide grey frame from every camera frame; dx/dy are in grey pixels, accumulated since reset().
 */
class Tracker {
    @Volatile var dx = 0.0; @Volatile var dy = 0.0; @Volatile var lost = 0
    @Volatile var prev: Gray? = null
    @Volatile var resetPending = false

    fun reset() { resetPending = true }

    private fun half(a: Gray): Gray {
        val W = a.W shr 1; val H = a.H shr 1; val g = FloatArray(W * H)
        for (y in 0 until H) for (x in 0 until W) { val i = (2 * y) * a.W + 2 * x; g[y * W + x] = (a.g[i] + a.g[i + 1] + a.g[i + a.W] + a.g[i + a.W + 1]) / 4f }
        return Gray(g, W, H)
    }
    private fun variance(a: Gray, cx: Int, cy: Int, r: Int): Double {
        var s = 0.0; var s2 = 0.0; var n = 0
        var y = cy - r
        while (y < cy + r) { var x = cx - r; while (x < cx + r) { val v = a.g[y * a.W + x].toDouble(); s += v; s2 += v * v; n++; x += 2 }; y += 2 }
        val m = s / n; return s2 / n - m * m
    }
    class Match(val u: Double, val v: Double, val err: Double)

    /** best shift (u,v) of the block of p centred at (cx,cy) inside c, searching ±rng around (u0,v0); mean-removed SAD */
    private fun match(p: Gray, c: Gray, cx: Int, cy: Int, r: Int, rng: Int, u0: Int, v0: Int, sub: Boolean): Match {
        var pm = 0.0; var n = 0
        for (y in cy - r until cy + r) for (x in cx - r until cx + r) { pm += p.g[y * p.W + x]; n++ }
        pm /= n
        var best = 1e18; var bu = u0; var bv = v0
        val side = 2 * rng + 1; val E = if (sub) DoubleArray(side * side) { Double.NaN } else null
        for (v in v0 - rng..v0 + rng) for (u in u0 - rng..u0 + rng) {
            if (cx + u - r < 0 || cy + v - r < 0 || cx + u + r > c.W || cy + v + r > c.H) continue
            var cm = 0.0
            run { var y = cy - r; while (y < cy + r) { var x = cx - r; while (x < cx + r) { cm += c.g[(y + v) * c.W + x + u]; x += 2 }; y += 2 } }
            cm /= n / 4.0
            var e = 0.0
            for (y in cy - r until cy + r) { val po = y * p.W; val co = (y + v) * c.W + u
                for (x in cx - r until cx + r) e += abs(p.g[po + x] - pm - c.g[co + x] + cm)
                if (!sub && e >= best) break }
            if (E != null) E[(v - v0 + rng) * side + (u - u0 + rng)] = e
            if (e < best) { best = e; bu = u; bv = v }
        }
        var fu = bu.toDouble(); var fv = bv.toDouble()
        if (E != null) {
            fun at(u: Int, v: Int): Double? { val iu = u - u0 + rng; val iv = v - v0 + rng
                if (iu < 0 || iv < 0 || iu >= side || iv >= side) return null; val e = E[iv * side + iu]; return if (e.isNaN()) null else e }
            fun q(a: Double?, b: Double?, c0: Double): Double { if (a == null || b == null) return 0.0; val d = a - 2 * c0 + b; return if (d > 0) cl((a - b) / (2 * d), -.5, .5) else 0.0 }
            fu += q(at(bu - 1, bv), at(bu + 1, bv), best); fv += q(at(bu, bv - 1), at(bu, bv + 1), best)
        }
        return Match(fu, fv, best / n)
    }

    /** Scene motion since reset, as a zoom S about the image centre plus a shift (dx, dy): p_now = c + (p_ref - c)·S + (dx, dy). */
    @Volatile var S = 1.0
    @Volatile var lastScale = 1.0

    /** prev resampled as if zoomed by s about the centre */
    private fun warp(a: Gray, s: Double): Gray {
        val g = FloatArray(a.W * a.H); val cx = a.W / 2.0; val cy = a.H / 2.0
        for (y in 0 until a.H) { val sy = cy + (y + .5 - cy) / s - .5
            for (x in 0 until a.W) { val sx = cx + (x + .5 - cx) / s - .5
                val xx = sx.coerceIn(0.0, a.W - 1.001); val yy = sy.coerceIn(0.0, a.H - 1.001)
                val x0 = xx.toInt(); val y0 = yy.toInt(); val fx = (xx - x0).toFloat(); val fy = (yy - y0).toFloat(); val i = y0 * a.W + x0
                val t = a.g[i] + (a.g[i + 1] - a.g[i]) * fx; val bb = a.g[i + a.W] + (a.g[i + a.W + 1] - a.g[i + a.W]) * fx
                g[y * a.W + x] = t + (bb - t) * fy } }
        return Gray(g, a.W, a.H)
    }

    /** Median shift of the 3 most textured blocks from p to cur, with the mean error; null when nothing can be followed. */
    private fun measure(p: Gray, cur: Gray): Match? {
        val R = 14
        class Pt(val cx: Int, val cy: Int, val v: Double)
        val pts = ArrayList<Pt>()
        for (fy in doubleArrayOf(.3, .5, .7)) for (fx in doubleArrayOf(.3, .5, .7)) {
            val cx = (cur.W * fx).roundToInt(); val cy = (cur.H * fy).roundToInt()
            if (cx - R - 24 < 0 || cx + R + 24 > cur.W || cy - R - 24 < 0 || cy + R + 24 > cur.H) continue
            pts.add(Pt(cx, cy, variance(p, cx, cy, R)))
        }
        pts.sortByDescending { it.v }
        val ph = half(p); val chh = half(cur); val res = ArrayList<Match>()
        for (pt in pts.take(3)) {
            if (pt.v < 20) continue
            val c1 = match(ph, chh, pt.cx shr 1, pt.cy shr 1, R shr 1, 10, 0, 0, false)
            val c2 = match(p, cur, pt.cx, pt.cy, R, 2, (c1.u * 2).roundToInt(), (c1.v * 2).roundToInt(), true)
            if (c2.err < 22) res.add(c2)
        }
        if (res.isEmpty()) return null
        val mu = res.map { it.u }.sorted()[res.size shr 1]; val mv = res.map { it.v }.sorted()[res.size shr 1]
        return Match(mu, mv, res.sumOf { it.err } / res.size)
    }

    /**
     * One new frame. Called on the camera thread. zoomStep: how much the camera zoom changed since the previous frame
     * (as far as we know; the real value is searched around it, because frames lag the zoom command).
     */
    fun step(cur: Gray, zoomStep: Double = 1.0) {
        if (resetPending) { resetPending = false; dx = 0.0; dy = 0.0; S = 1.0; lost = 0; prev = cur; return }
        val p = prev; prev = cur
        if (p == null || p.W != cur.W || p.H != cur.H) return
        var best: Match? = null; var bs = 1.0
        val cands = if (abs(zoomStep - 1) < .004 && abs(lastScale - 1) < .004) doubleArrayOf(1.0)
            else (doubleArrayOf(1.0, zoomStep, lastScale, 1 + (zoomStep - 1) * .5, 1 + (zoomStep - 1) * 1.5)).distinct().toDoubleArray()
        for (s in cands) {
            val m = (if (abs(s - 1) < 1e-4) measure(p, cur) else measure(warp(p, s), cur)) ?: continue
            if (best == null || m.err < best.err - .05) { best = m; bs = s }
        }
        val b = best ?: run { lost++; return }
        lost = 0; lastScale = bs
        // compose: p_now = c + ((p_ref - c)·S + d)·s + shift
        S *= bs; dx = dx * bs + b.u; dy = dy * bs + b.v
    }
}

/**
 * Where did the frame land after the lens changed? Search the new view for the template cut from the old one.
 * ref: grey of the frame region (template TW x TH, mean removed). Returns the frame in new-view grey pixels, or null.
 */
class Located(val x: Double, val y: Double, val w: Double, val h: Double, val err: Double)

fun sampleGray(src: Gray, x: Double, y: Double, w: Double, h: Double, TW: Int, TH: Int): FloatArray {
    // area-ish sampling: 2x2 bilinear taps per template cell
    val out = FloatArray(TW * TH); var m = 0.0
    fun bil(px: Double, py: Double): Float {
        val xx = cl(px, 0.0, src.W - 1.001); val yy = cl(py, 0.0, src.H - 1.001)
        val x0 = xx.toInt(); val y0 = yy.toInt(); val fx = (xx - x0).toFloat(); val fy = (yy - y0).toFloat(); val i = y0 * src.W + x0
        val a = src.g[i] + (src.g[i + 1] - src.g[i]) * fx; val b = src.g[i + src.W] + (src.g[i + src.W + 1] - src.g[i + src.W]) * fx
        return a + (b - a) * fy
    }
    val sx = w / TW; val sy = h / TH
    for (j in 0 until TH) for (i in 0 until TW) {
        val bx = x + i * sx; val by = y + j * sy
        val v = (bil(bx + sx * .25, by + sy * .25) + bil(bx + sx * .75, by + sy * .25) + bil(bx + sx * .25, by + sy * .75) + bil(bx + sx * .75, by + sy * .75)) / 4f
        out[j * TW + i] = v; m += v
    }
    m /= out.size; for (k in out.indices) out[k] = (out[k] - m).toFloat()
    return out
}

private fun mad(a: FloatArray, b: FloatArray): Double { var e = 0.0; for (i in a.indices) e += abs(a[i] - b[i]); return e / a.size }

fun locate(ref: FloatArray, TW: Int, TH: Int, cur: Gray, ex: Double, ey: Double, ew: Double, eh: Double): Located? {
    // ex,ey,ew,eh: where the frame should be if the lenses were perfectly aligned
    var best: Located? = null
    fun test(x: Double, y: Double, w: Double, h: Double) {
        if (x < -1 || y < -1 || x + w > cur.W + 1 || y + h > cur.H + 1) return
        val e = mad(ref, sampleGray(cur, x, y, w, h, TW, TH))
        if (best == null || e < best!!.err) best = Located(x, y, w, h, e)
    }
    val span = .18 * max(cur.W, cur.H); val st = span / 9
    for (k in doubleArrayOf(.88, .94, 1.0, 1.06, 1.12)) {
        val w = ew * k; val h = eh * k; val cx = ex + ew / 2; val cy = ey + eh / 2
        for (j in -9..9) for (i in -9..9) test(cx - w / 2 + i * st, cy - h / 2 + j * st, w, h)
    }
    var b = best ?: return null
    // refine position and scale
    for (it in 0 until 2) {
        val s = if (it == 0) st / 3 else st / 9
        val b0 = b
        for (ks in doubleArrayOf(.98, 1.0, 1.02)) for (j in -3..3) for (i in -3..3) {
            val w = b0.w * ks; val h = b0.h * ks
            test(b0.x + b0.w / 2 - w / 2 + i * s, b0.y + b0.h / 2 - h / 2 + j * s, w, h)
        }
        b = best!!
    }
    return b
}
