package pt.filipe.enquadra.nativa

import kotlin.math.*

/*
 * Composition engine, ported line by line from index.html (features / metrics / scoreOf / bestCrop / planCrop / toFrame).
 * Pure Kotlin, no Android types: it is unit-tested against the JavaScript on the same pixels.
 */

val STYLE_IDS = listOf("tercos", "simetrico", "minimal", "aurea", "diagonal", "centro", "moldura")
val STYLE_LABEL = mapOf("tercos" to "Terços", "simetrico" to "Simetria", "minimal" to "Minimal", "aurea" to "Proporção áurea",
    "diagonal" to "Diagonal", "centro" to "Centro", "moldura" to "Moldura natural")
val LOCAL_TIP = mapOf("tercos" to "Motivo num ponto forte", "simetrico" to "Eixo da cena ao centro", "minimal" to "Deixa o vazio trabalhar",
    "aurea" to "Motivo no ponto áureo", "diagonal" to "Segue a diagonal", "centro" to "Motivo ao centro", "moldura" to "A cena emoldura o motivo")

fun cl(v: Double, a: Double, b: Double) = min(b, max(a, v))
private fun clI(v: Int, a: Int, b: Int) = min(b, max(a, v))
private fun jsRound(v: Double) = floor(v + 0.5).toInt()   // Math.round

class Subj(val name: String, val cls: String?, val w: Double, val box: DoubleArray, val focus: DoubleArray, val facing: String = "none")
class Shot(val x: Double, val y: Double, val w: Double, val h: Double)
class Plan(var subj: Subj?, var shot: Shot?, var rule: String, var tip: String, var filter: String? = null, var border: String? = null, var name: String = "", var lens: Double? = null)
data class RectD(val x: Double, val y: Double, val w: Double, val h: Double)
class Crop(val x0: Int, val y0: Int, val cw: Int, val ch: Int)

class Features(val W: Int, val H: Int, val L: FloatArray, val Is: DoubleArray, val Isx: DoubleArray, val Isy: DoubleArray,
               val Ie: DoubleArray, val Id: DoubleArray, val Il: DoubleArray, val Ih: DoubleArray,
               val salTot: Double, val salMean: Double, val eMean: Double, val horizon: Double?)

fun boxBlur(a: FloatArray, W: Int, H: Int, r: Int) {
    val t = FloatArray(a.size)
    for (y in 0 until H) { var acc = 0.0; val o = y * W
        for (x in -r..r) acc += a[o + clI(x, 0, W - 1)]
        for (x in 0 until W) { t[o + x] = (acc / (2 * r + 1)).toFloat(); acc += a[o + clI(x + r + 1, 0, W - 1)] - a[o + clI(x - r, 0, W - 1)] } }
    for (x in 0 until W) { var acc = 0.0
        for (y in -r..r) acc += t[clI(y, 0, H - 1) * W + x]
        for (y in 0 until H) { a[y * W + x] = (acc / (2 * r + 1)).toFloat(); acc += t[clI(y + r + 1, 0, H - 1) * W + x] - t[clI(y - r, 0, H - 1) * W + x] } }
}

fun integ(arr: FloatArray, W: Int, H: Int): DoubleArray {
    val w = W + 1; val I = DoubleArray(w * (H + 1))
    for (y in 0 until H) { var row = 0.0; for (x in 0 until W) { row += arr[y * W + x]; I[(y + 1) * w + x + 1] = I[y * w + x + 1] + row } }
    return I
}
private fun rs(I: DoubleArray, W: Int, x0: Int, y0: Int, x1: Int, y1: Int): Double { val w = W + 1; return I[y1 * w + x1] - I[y0 * w + x1] - I[y1 * w + x0] + I[y0 * w + x0] }

/** Size of the small analysis image for a source of SW x SH (longest side 160). */
fun featureSize(SW: Double, SH: Double): Pair<Int, Int> {
    val k = 160.0 / max(SW, SH)
    return Pair(max(16, jsRound(SW * k)), max(16, jsRound(SH * k)))
}

/** px: ARGB pixels of the scene already scaled to W x H. NS: optional neural saliency (W*H, 0..1). */
fun features(px: IntArray, W: Int, H: Int, NS: FloatArray? = null): Features {
    val n = W * H
    val L = FloatArray(n); val A = FloatArray(n); val B = FloatArray(n); val MX = FloatArray(n)
    var mL = 0.0; var mA = 0.0; var mB = 0.0
    for (i in 0 until n) { val p = px[i]; val r = (p shr 16) and 255; val g = (p shr 8) and 255; val b = p and 255
        MX[i] = max(r, max(g, b)).toFloat()
        L[i] = (.299 * r + .587 * g + .114 * b).toFloat(); A[i] = (r - g).toFloat(); B[i] = ((r + g) / 2.0 - b).toFloat()
        mL += L[i]; mA += A[i]; mB += B[i] }
    mL /= n; mA /= n; mB /= n
    val Ld = DoubleArray(n) { L[it].toDouble() }
    val E = FloatArray(n); val Dg = FloatArray(n); val Hz = FloatArray(H)
    for (y in 1 until H - 1) for (x in 1 until W - 1) { val i = y * W + x
        val gx = (Ld[i - W + 1] + 2 * Ld[i + 1] + Ld[i + W + 1]) - (Ld[i - W - 1] + 2 * Ld[i - 1] + Ld[i + W - 1])
        val gy = (Ld[i + W - 1] + 2 * Ld[i + W] + Ld[i + W + 1]) - (Ld[i - W - 1] + 2 * Ld[i - W] + Ld[i - W + 1])
        val mag = hypot(gx, gy); E[i] = mag.toFloat(); Hz[y] = (Hz[y] + max(0.0, abs(gy) - 1.5 * abs(gx))).toFloat()
        val ang = atan2(abs(gy), abs(gx)) * 57.3
        if (ang > 25 && ang < 65) Dg[i] = mag.toFloat() }
    var eMax = 1e-6; for (i in 0 until n) if (E[i] > eMax) eMax = E[i].toDouble()
    for (i in 0 until n) { E[i] = min(1.0, E[i] / (eMax * .5)).toFloat(); Dg[i] = min(1.0, Dg[i] / (eMax * .5)).toFloat() }
    val Ln = FloatArray(n); val Hot = FloatArray(n)
    for (i in 0 until n) { Ln[i] = L[i] / 255f; Hot[i] = if (MX[i] > 245 && L[i] > 180) 1f else 0f }
    val HotB = Hot.copyOf(); boxBlur(HotB, W, H, 2)
    val C = FloatArray(n)
    for (i in 0 until n) C[i] = sqrt(.5 * (L[i] - mL).pow(2) + (A[i] - mA).pow(2) + (B[i] - mB).pow(2)).toFloat()
    boxBlur(C, W, H, 2)
    val Eb = E.copyOf(); boxBlur(Eb, W, H, 3)
    var cMax = 1e-6; for (i in 0 until n) if (C[i] > cMax) cMax = C[i].toDouble()
    val sal = FloatArray(n); var sMax = 1e-6
    for (i in 0 until n) { sal[i] = (C[i] / cMax + .6 * Eb[i]).toFloat(); if (sal[i] > sMax) sMax = sal[i].toDouble() }
    val SX = FloatArray(n); val SY = FloatArray(n); var salTot = 0.0; var eTot = 0.0
    for (y in 0 until H) for (x in 0 until W) { val i = y * W + x
        val dx = (x + .5) / W - .5; val dy = (y + .5) / H - .5; val prior = .5 + .5 * exp(-(dx * dx + dy * dy) / (2 * .3 * .3))
        val base = (sal[i] / sMax).pow(2)
        val v = (if (NS != null) (.3 * base + .7 * NS[i]) else base) * prior * (1 - .85 * min(1.0, HotB[i] * 3.0))
        sal[i] = v.toFloat(); SX[i] = (v * (x + .5)).toFloat(); SY[i] = (v * (y + .5)).toFloat(); salTot += v; eTot += E[i] }
    var hy: Int? = null
    run { var best = 0.0; var mean = 0.0; var cnt = 0
        for (y in jsRound(H * .08) until jsRound(H * .92)) { val v = (Hz[y - 1].toDouble() + Hz[y] + Hz[y + 1]) / 3; mean += v; cnt++; if (v > best) { best = v; hy = y } }
        mean /= max(1, cnt); if (!(best > 2.6 * mean)) hy = null }
    return Features(W, H, L, integ(sal, W, H), integ(SX, W, H), integ(SY, W, H), integ(E, W, H), integ(Dg, W, H), integ(Ln, W, H), integ(Hot, W, H),
        salTot + 1e-6, salTot / n + 1e-6, eTot / n + 1e-6, hy?.let { it.toDouble() / H })
}

private val TH = arrayOf(doubleArrayOf(1 / 3.0, 1 / 3.0), doubleArrayOf(2 / 3.0, 1 / 3.0), doubleArrayOf(1 / 3.0, 2 / 3.0), doubleArrayOf(2 / 3.0, 2 / 3.0))
private val PHI = arrayOf(doubleArrayOf(.382, .382), doubleArrayOf(.618, .382), doubleArrayOf(.382, .618), doubleArrayOf(.618, .618))

class M(val s: Double) {
    var salIn = 0.0; var dens = 0.0; var cut = 0.0; var clutter = 0.0; var simp = 0.0; var cx = 0.0; var cy = 0.0
    var third = 0.0; var phi = 0.0; var pp = 0.0; var ctr = 0.0; var aimOut = 0.0; var dline = 0.0; var edgeDist = 0.0
    var edgeBright = 0.0; var hotEdge = 0.0; var diag = 0.0; var symLR = 0.0; var symTB = 0.0; var sym = 0.0; var symEff = 0.0
    var hy: Double? = null
    var cover: Double? = null; var f3 = 0.0; var fphi = 0.0; var fc = 0.0; var fill = 0.0; var head: Double? = null
}

private fun minDist(pts: List<DoubleArray>, x: Double, y: Double) = pts.minOf { hypot(x - it[0], y - it[1]) }

fun metrics(F: Features, x0: Int, y0: Int, cw: Int, ch: Int, s: Double, subj: Subj?): M {
    val W = F.W; val x1 = x0 + cw; val y1 = y0 + ch; val area = (cw * ch).toDouble()
    val sIn = rs(F.Is, W, x0, y0, x1, y1) + 1e-6
    val b = max(1, jsRound(min(cw, ch) * .08))
    val sInner = rs(F.Is, W, x0 + b, y0 + b, x1 - b, y1 - b)
    val eAll = rs(F.Ie, W, x0, y0, x1, y1); val eInner = rs(F.Ie, W, x0 + b, y0 + b, x1 - b, y1 - b)
    val bandA = max(1.0, area - (cw - 2 * b).toDouble() * (ch - 2 * b))
    val m = M(s)
    m.salIn = sIn / F.salTot
    m.dens = (sIn / area) / F.salMean
    m.cut = ((sIn - sInner) / bandA) / (sIn / area)
    m.clutter = ((eAll - eInner) / bandA) / F.eMean
    m.simp = 1 - min(1.0, (eAll / area) / (F.eMean * 1.4))
    m.cx = (rs(F.Isx, W, x0, y0, x1, y1) / sIn - x0) / cw
    m.cy = (rs(F.Isy, W, x0, y0, x1, y1) / sIn - y0) / ch
    m.third = minDist(TH.toList(), m.cx, m.cy)
    m.phi = minDist(PHI.toList(), m.cx, m.cy)
    m.pp = min(m.third, m.phi * 1.05)
    m.ctr = hypot(m.cx - .5, m.cy - .5)
    run { val ux = (W / 2.0 - x0) / cw; val uy = (F.H / 2.0 - y0) / ch
        m.aimOut = max(0.0, max(-ux, ux - 1)) + max(0.0, max(-uy, uy - 1)) + .5 * max(0.0, .12 - min(min(ux, 1 - ux), min(uy, 1 - uy))) }
    m.dline = min(abs(m.cx - m.cy), abs(m.cx - (1 - m.cy))) / 1.414
    m.edgeDist = min(min(m.cx, 1 - m.cx), min(m.cy, 1 - m.cy))
    val lAll = rs(F.Il, W, x0, y0, x1, y1); val lIn = rs(F.Il, W, x0 + b, y0 + b, x1 - b, y1 - b)
    val lBand = (lAll - lIn) / bandA; val lInner = lIn / max(1.0, (cw - 2 * b).toDouble() * (ch - 2 * b))
    m.edgeBright = lBand - lInner
    run { val hb = max(2, jsRound(min(cw, ch) * .16))
        m.hotEdge = rs(F.Ih, W, x0, y0, x1, y1) - rs(F.Ih, W, x0 + hb, y0 + hb, x1 - hb, y1 - hb) }
    m.diag = rs(F.Id, W, x0, y0, x1, y1) / max(1e-6, eAll)
    val G = 12; val L = F.L; var lr = 0.0; var tb = 0.0
    for (j in 0 until G) { val yy = y0 + floor((j + .5) * ch / G).toInt()
        for (i in 0 until G / 2) { val o = floor((i + .5) * cw / G).toInt(); lr += abs(L[yy * W + x0 + o] - L[yy * W + x1 - 1 - o]) } }
    for (i in 0 until G) { val xx = x0 + floor((i + .5) * cw / G).toInt()
        for (j in 0 until G / 2) { val o = floor((j + .5) * ch / G).toInt(); tb += abs(L[(y0 + o) * W + xx] - L[(y1 - 1 - o) * W + xx]) } }
    lr /= G * G / 2.0 * 255; tb /= G * G / 2.0 * 255
    m.symLR = 1 - min(1.0, lr * 5); m.symTB = 1 - min(1.0, tb * 4)
    m.sym = max(m.symLR, m.symTB * .9)
    m.symEff = m.sym * min(1.0, (eAll / area) / (F.eMean * .7))
    m.hy = F.horizon?.let { val h = (it * F.H - y0) / ch; if (h > .04 && h < .96) h else null }
    if (subj != null) {
        val sx0 = subj.box[0] * W; val sy0 = subj.box[1] * F.H; val sx1 = sx0 + subj.box[2] * W; val sy1 = sy0 + subj.box[3] * F.H
        val ix = max(0.0, min(x1.toDouble(), sx1) - max(x0.toDouble(), sx0)); val iy = max(0.0, min(y1.toDouble(), sy1) - max(y0.toDouble(), sy0))
        m.cover = (ix * iy) / max(1e-6, (sx1 - sx0) * (sy1 - sy0))
        val fx = (subj.focus[0] * W - x0) / cw; val fy = (subj.focus[1] * F.H - y0) / ch
        var tgt = TH.toList()
        if (subj.facing == "left") tgt = tgt.filter { it[0] > .5 } else if (subj.facing == "right") tgt = tgt.filter { it[0] < .5 }
        m.f3 = minDist(tgt, fx, fy)
        var tgp = PHI.toList()
        if (subj.facing == "left") tgp = tgp.filter { it[0] > .5 } else if (subj.facing == "right") tgp = tgp.filter { it[0] < .5 }
        m.fphi = minDist(tgp, fx, fy)
        m.fc = abs(fx - .5) + .5 * abs(fy - .45)
        m.fill = (subj.box[3] * F.H) / ch
        m.head = (sy0 - y0) / ch
    }
    return m
}

private fun lg(v: Double) = ln(max(v, .25))

fun scoreOf(style: String, m: M, subj: Subj?): Double {
    val over = max(0.0, m.cut - 1); val clut = max(0.0, m.clutter - 1)
    val thin = if (m.salIn < .3) (.3 - m.salIn) * 3 else 0.0
    var common = -2.0 * max(0.0, .15 - m.edgeDist) - 1.6 * max(0.0, m.edgeBright - .06) + .9 * min(.2, max(0.0, -m.edgeBright - .04)) -
        2.0 * m.aimOut - min(1.5, m.hotEdge / 6)
    if (subj != null && m.head != null) common += -1.5 * max(0.0, .04 - m.head!!) - .6 * max(0.0, m.head!! - .45)
    val sw = if (subj != null) subj.w else 0.0
    var sc: Double
    val cover = m.cover
    when (style) {
        "tercos" -> {
            sc = .55 * m.salIn + .8 * lg(m.dens) - 1.8 * m.pp + .4 * m.diag - 1.4 * over - .35 * clut - .6 * abs(m.s - .75) - thin
            m.hy?.let { sc += -1.2 * min(abs(it - 1 / 3.0), abs(it - 2 / 3.0)) - (if (abs(it - .5) < .08) .3 else 0.0) }
            if (subj != null && cover != null) sc += sw * (-2.2 * (1 - cover) - 1.2 * m.f3 - (if (m.fill > .9) .4 else 0.0))
        }
        "simetrico" -> {
            sc = .5 * m.salIn + .6 * lg(m.dens) + 1.8 * m.symEff - (if (m.symEff < .5) 1.0 else 0.0) - 2 * abs(m.cx - .5) - 1.4 * over - .3 * clut - .6 * abs(m.s - .8) - thin * 2
            m.hy?.let { sc += -.9 * abs(it - .5) }
            if (subj != null && cover != null) sc += sw * (-2.2 * (1 - cover) - 1.0 * m.fc)
        }
        "aurea" -> {
            sc = .55 * m.salIn + .8 * lg(m.dens) - 1.8 * m.phi + .3 * m.diag - 1.4 * over - .35 * clut - .6 * abs(m.s - .75) - thin
            m.hy?.let { sc += -1.2 * min(abs(it - .382), abs(it - .618)) }
            if (subj != null && cover != null) sc += sw * (-2.2 * (1 - cover) - 1.2 * m.fphi)
        }
        "diagonal" -> {
            sc = .5 * m.salIn + .7 * lg(m.dens) + 1.4 * m.diag - 2.0 * m.dline - 1.4 * over - .3 * clut - .6 * abs(m.s - .8) - thin
            if (subj != null && cover != null) sc += sw * (-2.2 * (1 - cover))
        }
        "centro" -> {
            sc = .55 * m.salIn + .8 * lg(m.dens) - 2.2 * m.ctr - 1.4 * over - .35 * clut - .6 * abs(m.s - .7) - thin
            if (subj != null && cover != null) sc += sw * (-2.2 * (1 - cover) - 1.2 * m.fc)
        }
        "moldura" -> {
            sc = .5 * m.salIn + .6 * lg(m.dens) + 3.0 * max(0.0, -m.edgeBright) + .25 * min(m.clutter, 2.5) - 1.2 * m.ctr - .6 * over - .6 * abs(m.s - .85) - thin * 2
            if (subj != null && cover != null) sc += sw * (-2.2 * (1 - cover))
        }
        else -> {
            sc = .35 * m.salIn + 1.2 * m.simp + .8 * lg(m.dens) - 1.6 * m.pp - 1.6 * over - .6 * abs(m.s - .7) - thin * 4
            m.hy?.let { sc += -1.0 * min(abs(it - 2 / 3.0), abs(it - 1 / 3.0)) }
            if (subj != null && cover != null) sc += sw * (-2.2 * (1 - cover) - 1.0 * m.f3 + (if (m.fill < .35) .3 else -.3))
        }
    }
    return sc + common
}

class CropOpt(val scales: List<Double>, val cx: Double? = null, val cy: Double? = null, val radius: Double = 0.0)

/** Best crop for one rule, either around a point (AI suggestion) or over the whole image (local). */
fun bestCrop(F: Features, subj: Subj?, r: Double, style: String, opt: CropOpt): Crop? {
    val W = F.W; val H = F.H; val maxH = min(H.toDouble(), W / r); var best: Crop? = null; var top = -1e9
    val center = opt.cx != null && opt.cy != null
    for (s in opt.scales) {
        val ch = max(8, floor(maxH * s).toInt()); val cw = max(8, min(W, floor(ch * r).toInt()))
        val xs = ArrayList<Double>(); val ys = ArrayList<Double>(); val N = if (center) 8 else 12
        for (i in 0..N) {
            if (center) { val R = opt.radius * max(W, H); xs.add(opt.cx!! - cw / 2.0 - R + 2 * R * i / N); ys.add(opt.cy!! - ch / 2.0 - R + 2 * R * i / N) }
            else { xs.add((W - cw) * i.toDouble() / N); ys.add((H - ch) * i.toDouble() / N) }
        }
        for (y in ys) for (x in xs) {
            val x0 = jsRound(cl(x, 0.0, (W - cw).toDouble())); val y0 = jsRound(cl(y, 0.0, (H - ch).toDouble()))
            if (x0 < 0 || y0 < 0 || x0 + cw > W || y0 + ch > H) continue
            val m = metrics(F, x0, y0, cw, ch, s, subj); m.aimOut = 0.0
            var sc = scoreOf(style, m, subj)
            if (center) { val d = hypot(x0 + cw / 2.0 - opt.cx!!, y0 + ch / 2.0 - opt.cy!!) / max(W, H); sc -= 2.5 * d }
            if (sc > top) { top = sc; best = Crop(x0, y0, cw, ch) }
        }
    }
    return best
}

/** Crop in feature pixels -> frame in source pixels, with the exact ratio. */
fun toFrame(c: Crop, F: Features, srcW: Double, srcH: Double, ratio: Double): RectD {
    val kx = srcW / F.W; val ky = srcH / F.H
    var w = c.cw * kx; var h = w / ratio
    if (h > srcH) { h = srcH; w = h * ratio }
    return RectD(cl(c.x0 * kx, 0.0, srcW - w), cl(c.y0 * ky, 0.0, srcH - h), w, h)
}

fun planCrop(F: Features, plan: Plan, srcW: Double, srcH: Double, ratio: Double, range: DoubleArray, localScales: List<Double>): Crop? {
    val maxW = min(srcW, srcH * ratio)
    val shot = plan.shot
    if (shot != null) {
        val sw = shot.w * srcW; val sh = shot.h * srcH; val w = sqrt(sw * sh * ratio)
        val s0 = cl(w / maxW, range[0], range[1]); val k = F.W / srcW
        return bestCrop(F, plan.subj, ratio, plan.rule, CropOpt(listOf(s0 * .88, s0, s0 * 1.12).map { cl(it, .25, .8) },
            (shot.x + shot.w / 2) * srcW * k, (shot.y + shot.h / 2) * srcH * k, .06))
    }
    return bestCrop(F, plan.subj, ratio, plan.rule, CropOpt(localScales))
}
