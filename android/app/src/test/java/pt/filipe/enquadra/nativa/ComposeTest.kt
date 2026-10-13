package pt.filipe.enquadra.nativa

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/** The Kotlin engine must pick the same crops as the JavaScript in index.html. Expected values come from running the JS on the same pixels. */
class ComposeTest {
    private fun image(): IntArray {
        val W = 160; val H = 120; val px = IntArray(W * H)
        for (y in 0 until H) for (x in 0 until W) {
            var r: Int; var g: Int; var b: Int
            if (y < 70) { r = 120 + ((x * 3 + y) % 40); g = 150 + ((x * 2) % 30); b = 200 } else { r = 40 + ((x * 7 + y * 5) % 50); g = 60 + ((x * x + y) % 40); b = 30 + ((x + y * y) % 35) }
            if (hypot((x - 110).toDouble(), (y - 40).toDouble()) < 14) { r = 250; g = 230; b = 180 }
            if (abs((y - 70) - (x - 20) * 0.6) < 2 && x > 20 && x < 120) { r = 20; g = 20; b = 20 }
            if (x in 31..45 && y in 81..109) { r = 200; g = 40; b = 40 }
            px[y * W + x] = (255 shl 24) or (r shl 16) or (g shl 8) or b
        }
        return px
    }

    @Test fun sameAsJavaScript() {
        val F = features(image(), 160, 120, null)
        val sb = StringBuilder("salTot=${F.salTot} eMean=${F.eMean} horizon=${F.horizon}\n")
        assertEquals(1467.8591816638757, F.salTot, 1e-3)
        assertEquals(0.12455806431897852, F.eMean, 1e-6)
        assertEquals(0.5833333333333334, F.horizon!!, 1e-9)
        val expect = mapOf("tercos" to intArrayOf(28, 50, 48, 60), "simetrico" to intArrayOf(8, 27, 67, 84), "minimal" to intArrayOf(28, 50, 48, 60),
            "aurea" to intArrayOf(28, 50, 48, 60), "diagonal" to intArrayOf(19, 60, 48, 60), "centro" to intArrayOf(19, 60, 48, 60), "moldura" to intArrayOf(19, 60, 48, 60))
        val got = HashMap<String, IntArray>()
        for (st in STYLE_IDS) { val c = bestCrop(F, null, .8, st, CropOpt(listOf(.35, .5, .7)))!!; got[st] = intArrayOf(c.x0, c.y0, c.cw, c.ch); sb.append("$st ${got[st]!!.toList()}\n") }
        val subj = Subj("x", null, 1.0, doubleArrayOf(.18, .66, .12, .27), doubleArrayOf(.24, .77))
        val c2 = bestCrop(F, subj, .8, "tercos", CropOpt(listOf(.4, .5), 50.0, 80.0, .06))!!
        sb.append("subjCenter ${listOf(c2.x0, c2.y0, c2.cw, c2.ch)}\n")
        val m = metrics(F, 20, 10, 60, 75, .5, subj)
        val mv = doubleArrayOf(m.salIn, m.dens, m.cut, m.clutter, m.cx, m.cy, m.edgeBright, m.sym, m.diag, m.cover!!, m.f3)
        sb.append("m ${mv.toList()}\n"); println(sb)
        val mExp = doubleArrayOf(0.23944432862283138, 1.0216157731393705, 1.248906098669004, 0.9785995389417451, 0.48779353130317726, 0.6522739596248136,
            -0.06698996643469879, 0.8391236458988232, 0.1743162078518653, 0.17901234567901222, 0.43282226272583435)
        assertArrayEquals(mExp, mv, 1e-4)
        for ((k, v) in expect) assertArrayEquals("estilo $k", v, got[k])
        assertArrayEquals(intArrayOf(26, 52, 48, 60), intArrayOf(c2.x0, c2.y0, c2.cw, c2.ch))
    }

    @Test fun trackerFindsAShift() {
        // a textured image moved by (3, -2) pixels must be measured as (3, -2)
        val W = 128; val H = 170
        fun tex(x: Int, y: Int) = (((x * 37) xor (y * 91)) % 251 + 40 * kotlin.math.sin(x * .3) + 30 * kotlin.math.cos(y * .23)).toFloat()
        val a = Gray(FloatArray(W * H) { tex(it % W, it / W) }, W, H)
        val b = Gray(FloatArray(W * H) { tex(it % W - 3, it / W + 2) }, W, H)
        val t = Tracker(); t.reset(); t.step(a); t.step(b)
        assertEquals(3.0, t.dx, .3); assertEquals(-2.0, t.dy, .3)
    }

    @Test fun locateFindsTheFrame() {
        val W = 320; val H = 427
        fun tex(x: Double, y: Double) = (128 + 60 * kotlin.math.sin(x * .07) * kotlin.math.cos(y * .05) + 40 * kotlin.math.sin((x + y) * .031)).toFloat()
        val a = Gray(FloatArray(W * H) { tex((it % W).toDouble(), (it / W).toDouble()) }, W, H)
        val ref = sampleGray(a, 100.0, 150.0, 80.0, 100.0, 40, 50)
        // the new view is zoomed 2x around the centre and shifted by 12 px (parallax)
        val b = Gray(FloatArray(W * H) { val x = it % W; val y = it / W; tex((x - 12 - W / 2.0) / 2 + W / 2.0, (y - H / 2.0) / 2 + H / 2.0) }, W, H)
        val ex = W / 2.0 + (100.0 - W / 2.0) * 2; val ey = H / 2.0 + (150.0 - H / 2.0) * 2
        val loc = locate(ref, 40, 50, b, ex, ey, 160.0, 200.0)!!
        println("locate ${loc.x} ${loc.y} ${loc.w} ${loc.err}")
        assertEquals(ex + 12, loc.x, 4.0); assertEquals(ey, loc.y, 4.0); assertEquals(160.0, loc.w, 8.0)
    }
}
