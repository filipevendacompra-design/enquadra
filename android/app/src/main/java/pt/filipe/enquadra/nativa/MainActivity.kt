package pt.filipe.enquadra.nativa

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.text.InputType
import android.util.Base64
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.PathInterpolator
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.*

class MainActivity : ComponentActivity(), SensorEventListener {
    // ---------- state (same names as the web version's S) ----------
    private var mode = "idle"          // idle | live
    private var phase = "idle"         // idle | scan | guide | lock | locked
    private var busy = false; private var capturing = false
    private var ratio = .8
    private var frame: RectD? = null
    private var subj: Subj? = null; private var style = "tercos"; private var tip = ""; private var note = ""; private var label = ""
    private var plan: Plan? = null
    private val seen = ArrayList<String>()
    private var zoomed = false; private var lockAt = 0L; private var alignedSince = 0L
    private var homeZoom = 1f
    private val LENS_FILL = .9        // after the lens change the frame fills 90% of the view (room for a shaky hand)
    private var roll = 0.0; private var levelValid = false
    private var grid = true; private var levelOn = true
    private var lastMatch = ""
    private var scanAt = 0L
    private var autoLock = false      // emulator test only: the camera cannot be moved there

    private lateinit var set: Settings
    private lateinit var gem: Gemini
    private lateinit var cam: Cam

    // ---------- views ----------
    private lateinit var root: LinearLayout
    private lateinit var stage: FrameLayout
    private lateinit var pv: PreviewView
    private lateinit var freeze: ImageView
    private lateinit var ov: Overlay
    private lateinit var nocam: LinearLayout
    private lateinit var msgEl: TextView
    private lateinit var subEl: TextView
    private lateinit var lenses: LinearLayout
    private lateinit var shutter: Shutter
    private lateinit var aiBtn: FrameLayout
    private lateinit var aiIco: Icon
    private lateinit var thumb: FrameLayout
    private lateinit var thumbImg: ImageView
    private lateinit var thumbIco: Icon
    private lateinit var gridBtn: Icon
    private lateinit var levelBtn: Icon
    private lateinit var ratioBtn: TextView
    private lateinit var engDot: View
    private lateinit var engTxt: TextView
    private var engBlink: ObjectAnimator? = null
    private var settingsView: View? = null
    private var resultView: View? = null

    private val RATIOS = listOf(.8 to "4:5", 1.0 to "1:1", .75 to "3:4", .5625 to "9:16", 1.5 to "3:2", 16.0 / 9 to "16:9")

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else showNoCam("Sem permissão para a câmara. Dá a permissão e toca em Abrir câmara.")
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        installCrashLog()
        autoLock = intent?.getBooleanExtra("autolock", false) == true
        F.load(this)
        set = Settings(this); gem = Gemini(set); cam = Cam(this)
        ratio = set.ratio.toDouble(); grid = set.grid; levelOn = set.level
        gem.onChange = { runOnUiThread { renderEngine(); (settingsView?.tag as? (() -> Unit))?.invoke() } }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.BLACK; window.navigationBarColor = Color.BLACK
        buildUi()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() { stepBack() } })
        renderEngine(); ui(); refreshThumb()
        if (!(gem.state == "quota" && System.currentTimeMillis() < gem.until)) lifecycleScope.launch { gem.check() }
        setMsg("A abrir a câmara…", "dim")
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) pv.post { startCamera() }
        else askCamera.launch(Manifest.permission.CAMERA)
        showLastCrash()
    }

    override fun onResume() {
        super.onResume()
        (getSystemService(Context.SENSOR_SERVICE) as SensorManager).let { sm -> sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) } }
        lifecycleScope.launch { gem.maybeRecheck() }
    }
    override fun onPause() { super.onPause(); (getSystemService(Context.SENSOR_SERVICE) as SensorManager).unregisterListener(this) }
    override fun onDestroy() { super.onDestroy(); cam.shutdown() }

    // ---------- level (gravity only) ----------
    override fun onSensorChanged(e: SensorEvent) {
        val r = Math.toDegrees(atan2(-e.values[0].toDouble(), e.values[1].toDouble()))
        roll = if (abs(r - roll) > 90) r else roll * .8 + r * .2; levelValid = true
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    // ---------- UI ----------
    private fun tbtn(kind: String, desc: String, click: () -> Unit): Icon = Icon(this, kind).apply {
        contentDescription = desc; setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(dpi(38), dpi(38))
    }

    @SuppressLint("SetTextI18n")
    private fun buildUi() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.INK) }
        // top bar: grid · level · ratio ····· engine · settings
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dpi(40) }
        gridBtn = tbtn("grid", "Grelha") { grid = !grid; set.grid = grid; ui() }
        levelBtn = tbtn("level", "Nível") { levelOn = !levelOn; set.level = levelOn; ui() }
        ratioBtn = text(12f, C.FG, F.monoMed, "4:5").apply {
            background = bg(0, dp(999), C.LINE, dpi(1)); setPadding(dpi(11), dpi(6), dpi(11), dpi(6))
            setOnClickListener { cycleRatio() }; contentDescription = "Formato da foto"
        }
        val eng = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dpi(6), dpi(8), dpi(6), dpi(8)) }
        engDot = View(this).apply { background = bg(C.MUTED, dp(4)) }
        engTxt = text(11f, C.MUTED, F.mono, "local").oneLine()
        eng.addView(engDot, LinearLayout.LayoutParams(dpi(7), dpi(7)).apply { rightMargin = dpi(6) }); eng.addView(engTxt)
        eng.setOnClickListener { engTap() }
        val setBtn = tbtn("dots", "Definições") { openSettings() }.apply { color = C.FG }
        top.addView(gridBtn); top.addView(levelBtn)
        top.addView(ratioBtn, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dpi(4) })
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(eng); top.addView(setBtn)

        // stage: camera + frame overlay, rounded
        stage = FrameLayout(this).apply {
            background = bg(0xFF0B0B0B.toInt(), dp(20))
            outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(20)) } }
            clipToOutline = true
        }
        pv = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE   // a TextureView: it can be zoomed smoothly like the web video
            scaleType = PreviewView.ScaleType.FILL_CENTER; pivotX = 0f; pivotY = 0f
        }
        freeze = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_XY; pivotX = 0f; pivotY = 0f; visibility = View.GONE }
        ov = Overlay(this)
        nocam = buildNoCam()
        stage.addView(pv, FrameLayout.LayoutParams(-1, -1)); stage.addView(freeze, FrameLayout.LayoutParams(-1, -1))
        stage.addView(ov, FrameLayout.LayoutParams(-1, -1)); stage.addView(nocam, FrameLayout.LayoutParams(-1, -1))

        // status, lenses, controls
        val hud = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val status = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; minimumHeight = dpi(40) }
        msgEl = text(13f, C.AMBER, F.monoMed).apply { gravity = Gravity.CENTER }
        subEl = text(11f, C.MUTED, F.mono).apply { gravity = Gravity.CENTER; minHeight = dpi(15) }.oneLine()
        status.addView(msgEl, LinearLayout.LayoutParams(-1, -2)); status.addView(subEl, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(2) })
        lenses = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; minimumHeight = dpi(36) }
        val controls = FrameLayout(this)
        thumb = FrameLayout(this).apply { background = bg(C.PANEL, dp(12)); clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(12)) } }
            setOnClickListener { openLastPhoto() }; contentDescription = "Galeria" }
        thumbImg = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; visibility = View.GONE }
        thumbIco = Icon(this, "gallery")
        thumb.addView(thumbIco, FrameLayout.LayoutParams(-1, -1)); thumb.addView(thumbImg, FrameLayout.LayoutParams(-1, -1))
        shutter = Shutter(this).apply { setOnClickListener { onShutter() }; contentDescription = "Tirar foto" }
        aiBtn = FrameLayout(this).apply { background = bg(C.PANEL, dp(25)); setOnClickListener { onAI() }; contentDescription = "Encontrar a foto" }
        aiIco = Icon(this, "find", C.AMBER); aiBtn.addView(aiIco, FrameLayout.LayoutParams(-1, -1))
        controls.addView(thumb, FrameLayout.LayoutParams(dpi(48), dpi(48), Gravity.START or Gravity.CENTER_VERTICAL))
        controls.addView(shutter, FrameLayout.LayoutParams(dpi(74), dpi(74), Gravity.CENTER))
        controls.addView(aiBtn, FrameLayout.LayoutParams(dpi(50), dpi(50), Gravity.END or Gravity.CENTER_VERTICAL))
        hud.addView(status, LinearLayout.LayoutParams(-1, -2))
        hud.addView(lenses, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(10) })
        hud.addView(controls, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(10) })

        root.addView(top, LinearLayout.LayoutParams(-1, -2))
        root.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dpi(10) })
        root.addView(hud, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(10) })
        val host = FrameLayout(this); host.addView(root, FrameLayout.LayoutParams(-1, -1))
        setContentView(host)
        host.setOnApplyWindowInsetsListener { _, ins ->
            val bars = WindowInsetsCompat.toWindowInsetsCompat(ins).getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            root.setPadding(dpi(16), dpi(8) + bars.top, dpi(16), dpi(14) + bars.bottom)
            for (i in 1 until host.childCount) host.getChildAt(i).setPadding(dpi(16), dpi(10) + bars.top, dpi(16), dpi(14) + bars.bottom)
            ins
        }
    }

    private fun buildNoCam(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dpi(24), dpi(24), dpi(24), dpi(24)); visibility = View.GONE
        setBackgroundColor(0xFF0A0A0A.toInt())
        val h = text(26f, C.FG, F.uiSemi).apply {
            val s = android.text.SpannableString("Aponta, carrega em ✦ e a app encontra a foto.")
            s.setSpan(android.text.style.ForegroundColorSpan(C.AMBER), s.indexOf("encontra"), s.length, 0); text = s
        }
        val p = text(14f, C.MUTED, F.ui).apply { tag = "why" }
        val btn = text(15f, 0xFF1A1405.toInt(), F.uiMed, "Abrir câmara").apply {
            background = bg(C.AMBER, dp(12)); setPadding(dpi(16), dpi(11), dpi(16), dpi(11))
            setOnClickListener {
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
                else askCamera.launch(Manifest.permission.CAMERA)
            }
        }
        addView(h, LinearLayout.LayoutParams(-1, -2)); addView(p, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(14) })
        addView(btn, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpi(14) })
    }

    private fun showNoCam(why: String) {
        mode = "idle"; phase = "idle"; frame = null
        (nocam.findViewWithTag<TextView>("why")).text = why
        nocam.visibility = View.VISIBLE; setMsg("", ""); ui()
    }

    // ---------- camera ----------
    private fun startCamera() {
        if (pv.width == 0 || pv.viewPort == null) { pv.postDelayed({ startCamera() }, 50); return }
        nocam.visibility = View.GONE; setMsg("A abrir a câmara…", "dim")
        cam.start(this, pv, onReady = {
            // wait for the first frames, then go live
            val t0 = SystemClock.uptimeMillis()
            fun wait() {
                if (cam.srcW > 0) {
                    mode = "live"; phase = "idle"; frame = null
                    homeZoom = cam.minZoom; cam.setZoom(homeZoom)   // like Framed: start on the widest lens, the AI sees more
                    setMsg("Aponta e toca em ✦ para encontrar a foto.", "dim"); ui()
                } else if (SystemClock.uptimeMillis() - t0 > 10000) showNoCam("A câmara não arrancou (${cam.lastError.ifEmpty { "sem imagens" }}).")
                else pv.postDelayed({ wait() }, 50)
            }
            wait()
        }, onFail = { showNoCam("A câmara não arrancou ($it).") })
    }

    private fun lensValues(): List<Float> {
        val mn = cam.minZoom; val mx = cam.maxZoom
        if (mx <= mn) return emptyList()
        return listOf(mn, 1f, 2f, 3f, 5f).filter { it >= mn - 1e-6 && it <= mx + 1e-6 }.map { (it * 100).roundToInt() / 100f }.distinct().sorted()
    }
    /** The phone's real lenses (S26 Ultra: 1x, 3x, 5x); zooms between them are digital crops. */
    private fun lensStops(): List<Double> = listOf(1.0, 3.0, 5.0).filter { it >= cam.minZoom - 1e-3 && it <= cam.maxZoom + 1e-3 }
    private fun fmtZ(z: Double) = if (abs(z - z.roundToInt()) < .05) "${z.roundToInt()}×" else "%.1f×".format(z)

    private fun renderLenses() {
        lenses.removeAllViews()
        if (mode != "live" || phase != "idle") return
        for (v in lensValues()) {
            val on = abs(v - homeZoom) < .06
            val t = text(11f, if (on) C.AMBER else C.FG, F.monoMed, (if (v < 1) v.toString().removePrefix("0") else if (v == v.roundToInt().toFloat()) v.roundToInt().toString() else v.toString()) + "×").apply {
                gravity = Gravity.CENTER; background = bg(if (on) C.RAISE else C.PANEL, dp(18))
                setOnClickListener { homeZoom = v; cam.setZoom(v); renderLenses(); ui() }
            }
            lenses.addView(t, LinearLayout.LayoutParams(dpi(36), dpi(36)).apply { leftMargin = dpi(4); rightMargin = dpi(4) })
        }
    }

    // ---------- geometry ----------
    private class Lay(val s: Float, val ox: Float, val oy: Float, val VW: Float, val VH: Float)
    private fun layout(): Lay {
        val VW = stage.width.toFloat(); val VH = stage.height.toFloat(); val w = cam.srcW.toFloat(); val h = cam.srcH.toFloat()
        if (w <= 0 || h <= 0) return Lay(1f, 0f, 0f, VW, VH)
        val s = max(VW / w, VH / h)
        return Lay(s, (VW - w * s) / 2, (VH - h * s) / 2, VW, VH)
    }
    private fun tracking() = mode == "live" && (phase == "scan" || phase == "guide")
    private var tsX: Double? = null; private var tsY = 0.0; private var tsT = 0L
    private fun smoothTrack() {
        if (!tracking()) { tsX = null; return }
        val p = cam.tracker.prev; val k = cam.srcW.toDouble() / (p?.W ?: 128)
        val tx = cam.tracker.dx * k; val ty = cam.tracker.dy * k; val now = SystemClock.uptimeMillis()
        val x = tsX
        if (x == null) { tsX = tx; tsY = ty } else { val a = 1 - exp(-(now - tsT) / 70.0); tsX = x + (tx - x) * a; tsY += (ty - tsY) * a }
        tsT = now
    }
    private fun curFrame(): RectD? {
        val f = frame ?: return null
        if (!tracking()) return f
        return RectD(f.x + (tsX ?: 0.0), f.y + tsY, f.w, f.h)
    }

    // ---------- viewfinder overlay (port of draw()) ----------
    private val pDim = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pChipBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x8C000000.toInt() }
    private val pChipTxt = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pScan = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pSweep = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dimPath = Path()
    private val easeScanB = PathInterpolator(.45f, 0f, .55f, 1f)
    private val easeScanS = PathInterpolator(.4f, 0f, .2f, 1f)
    private var lastTf = floatArrayOf(1f, 0f, 0f)

    private fun applyTransform(z: Float, tx: Float, ty: Float) {
        if (abs(z - lastTf[0]) < 1e-4 && abs(tx - lastTf[1]) < .05 && abs(ty - lastTf[2]) < .05) return
        lastTf = floatArrayOf(z, tx, ty)
        for (v in arrayOf<View>(pv, freeze)) { v.scaleX = z; v.scaleY = z; v.translationX = tx; v.translationY = ty }
    }

    inner class Overlay(ctx: Context) : View(ctx) {
        override fun onDraw(cv: Canvas) { try { drawOverlay(cv) } catch (e: Throwable) { lastMatch = "draw: ${e.message}" }; postInvalidateOnAnimation() }
    }

    private fun drawOverlay(cv: Canvas) {
        smoothTrack()
        val L = layout(); val d = resources.displayMetrics.density
        if (mode == "idle") return
        val now = SystemClock.uptimeMillis()
        val cx = L.VW / 2; val cy = L.VH / 2; val zm = zoomed && mode == "live"
        if (grid && !zm) { pLine.color = 0x2EFFFFFF; pLine.strokeWidth = 1 * d; pLine.pathEffect = null
            for (i in 1..2) { cv.drawLine(L.VW * i / 3, 0f, L.VW * i / 3, L.VH, pLine); cv.drawLine(0f, L.VH * i / 3, L.VW, L.VH * i / 3, pLine) } }
        val f = curFrame(); var aligned = false
        val camZoom = cam.zoom
        if (f != null) {
            var fx = L.ox + f.x.toFloat() * L.s; var fy = L.oy + f.y.toFloat() * L.s; var fw = f.w.toFloat() * L.s; var fh = f.h.toFloat() * L.s
            val dx = fx + fw / 2 - cx; val dy = fy + fh / 2 - cy; val dist = hypot(dx, dy); val tol = min(L.VW, L.VH) * .05f
            if (phase == "guide") {
                aligned = dist < tol || autoLock
                if (aligned) { if (alignedSince == 0L) alignedSince = now; if (now - alignedSince > 350) lock() } else alignedSince = 0
            } else aligned = phase == "locked" || phase == "lock"
            var ease = 0f
            if (zm) {
                val tt = ((now - lockAt) / 700f).coerceIn(0f, 1f)
                ease = if (tt < .5f) 4 * tt * tt * tt else 1 - (-2 * tt + 2).pow(3) / 2
                val zt = min(L.VW / fw, L.VH / fh) * .92f; val z = 1 + (zt - 1) * ease
                val tx = (cx - (fx + fw / 2) * zt) * ease; val ty = (cy - (fy + fh / 2) * zt) * ease
                applyTransform(z, tx, ty)
                fx = fx * z + tx; fy = fy * z + ty; fw *= z; fh *= z
            }
            val col = if (aligned) C.LOCK else C.AMBER
            val r = 16 * d
            dimPath.reset(); dimPath.fillType = Path.FillType.EVEN_ODD
            dimPath.addRect(0f, 0f, L.VW, L.VH, Path.Direction.CW); dimPath.addRoundRect(fx, fy, fx + fw, fy + fh, r, r, Path.Direction.CW)
            pDim.color = Color.argb(((.38f + .4f * ease) * 255).roundToInt(), 0, 0, 0); cv.drawPath(dimPath, pDim)
            pLine.pathEffect = null; pLine.strokeWidth = 1 * d; pLine.color = if (aligned) 0x667BD88F else 0x59F5C531
            guides(cv, fx, fy, fw, fh)
            pLine.color = col; pLine.strokeWidth = 2.5f * d; cv.drawRoundRect(fx, fy, fx + fw, fy + fh, r, r, pLine)
            if (phase == "guide") {
                val tx = (fx + fw / 2).coerceIn(8 * d, L.VW - 8 * d); val ty = (fy + fh / 2).coerceIn(8 * d, L.VH - 8 * d)
                pLine.pathEffect = DashPathEffect(floatArrayOf(5 * d, 6 * d), 0f); pLine.strokeWidth = 2 * d
                cv.drawLine(cx, cy, tx, ty, pLine); pLine.pathEffect = null
                pFill.color = col; cv.drawCircle(tx, ty, 4.5f * d, pFill)
            }
            val name = label.ifEmpty { subj?.name ?: "" }
            if (name.isNotEmpty()) drawChip(cv, name, aligned, fx, fy, fh, L, d)
            if (mode == "live" && phase == "guide" && !busy) {
                if (cam.tracker.lost > 20) setMsg("Perdi a cena. Toca em ✕ e tenta outra vez.", "err")
                else if (aligned) setMsg("Segura…", "ok")
                else {
                    val h = if (abs(dx) > tol * .6f) (if (dx < 0) "para a esquerda" else "para a direita") else ""
                    val v = if (abs(dy) > tol * .6f) (if (dy < 0) "para cima" else "para baixo") else ""
                    setMsg("Aponta " + listOf(h, v).filter { it.isNotEmpty() }.joinToString(" e ") + ".", "")
                }
            }
            val digital = sqrt(cam.srcW.toDouble() * cam.srcH / (f.w * f.h)); val zz = digital * camZoom
            setSub(note.ifEmpty { "${STYLE_LABEL[style]}  ·  ≈ ${(26 * zz).roundToInt()} mm" })
        } else {
            setSub(if (mode == "live") note.ifEmpty { "${(26 * camZoom).roundToInt()} mm" } else "")
        }
        // level: a short line through the centre, as on a camera
        if (mode == "live" && levelOn && levelValid && !zm && phase != "locked") {
            val ok = abs(roll) < 1; val len = min(L.VW, L.VH) * .2f
            cv.save(); cv.translate(cx, cy); cv.rotate((-roll).toFloat())
            pLine.color = if (ok) C.LOCK else C.AMBER; pLine.strokeWidth = 2 * d
            cv.drawLine(-len, 0f, -14 * d, 0f, pLine); cv.drawLine(14 * d, 0f, len, 0f, pLine); cv.restore()
        }
        if (!zm && phase != "locked") {
            pLine.color = if (aligned) C.LOCK else 0xE6FFFFFF.toInt(); pLine.strokeWidth = 1.5f * d
            cv.drawLine(cx - 7 * d, cy, cx + 7 * d, cy, pLine); cv.drawLine(cx, cy - 7 * d, cx, cy + 7 * d, pLine)
        }
        if (phase == "scan") drawScan(cv, L, d, now)
    }

    private fun guides(cv: Canvas, fx: Float, fy: Float, fw: Float, fh: Float) {
        when (style) {
            "simetrico", "centro" -> { cv.drawLine(fx + fw / 2, fy, fx + fw / 2, fy + fh, pLine); cv.drawLine(fx, fy + fh / 2, fx + fw, fy + fh / 2, pLine) }
            "aurea" -> for (p in floatArrayOf(.382f, .618f)) { cv.drawLine(fx + fw * p, fy, fx + fw * p, fy + fh, pLine); cv.drawLine(fx, fy + fh * p, fx + fw, fy + fh * p, pLine) }
            "diagonal" -> { cv.drawLine(fx, fy, fx + fw, fy + fh, pLine); cv.drawLine(fx + fw, fy, fx, fy + fh, pLine) }
            "moldura" -> { val ix = fw * .14f; val iy = fh * .14f; cv.drawRect(fx + ix, fy + iy, fx + fw - ix, fy + fh - iy, pLine) }
            else -> for (i in 1..2) { cv.drawLine(fx + fw * i / 3, fy, fx + fw * i / 3, fy + fh, pLine); cv.drawLine(fx, fy + fh * i / 3, fx + fw, fy + fh * i / 3, pLine) }
        }
    }

    private fun drawChip(cv: Canvas, name: String, ok: Boolean, fx: Float, fy: Float, fh: Float, L: Lay, d: Float) {
        pChipTxt.typeface = F.monoMed; pChipTxt.textSize = 12 * resources.displayMetrics.scaledDensity
        pChipTxt.color = if (ok) C.LOCK else C.AMBER
        val maxTxt = L.VW * .7f - (8 + 6 + 6 + 8) * d
        var t = name; while (t.length > 1 && pChipTxt.measureText(t) > maxTxt) t = t.dropLast(2) + "…"
        val w = (8 + 6 + 6 + 8) * d + pChipTxt.measureText(t); val h = 24 * d
        val below = fy + fh + 8 * d + 26 * d < L.VH
        val y = if (below) fy + fh + 8 * d else max(8 * d, fy - 32 * d)
        val x = fx.coerceIn(8 * d, max(8 * d, L.VW - 170 * d))
        cv.drawRoundRect(x, y, x + w, y + h, 8 * d, 8 * d, pChipBg)
        pFill.color = pChipTxt.color; cv.drawCircle(x + 8 * d + 3 * d, y + h / 2, 3 * d, pFill)
        val fm = pChipTxt.fontMetrics
        cv.drawText(t, x + (8 + 6 + 6) * d, y + h / 2 - (fm.ascent + fm.descent) / 2, pChipTxt)
    }

    /** Searching: focus brackets that breathe, and a soft light sweeping across (the web version's .scan). */
    private fun drawScan(cv: Canvas, L: Lay, d: Float, now: Long) {
        val t = now - scanAt
        // brackets: inset 12% -> 22% -> 12% over 1.4 s, opacity .95 -> .55
        val pb = (t % 1400) / 1400f; val half = if (pb < .5f) pb * 2 else 2 - pb * 2
        val e = easeScanB.getInterpolation(half)
        val ins = .12f + .10f * e; val alpha = .95f - .40f * e
        val x0 = L.VW * ins; val y0 = L.VH * ins; val x1 = L.VW - x0; val y1 = L.VH - y0
        val l = 28 * d; val th = 2.5f * d
        pScan.color = C.AMBER; pScan.alpha = (alpha * 255).roundToInt()
        pScan.setShadowLayer(6 * d, 0f, 0f, Color.argb((.55f * 255 * alpha).roundToInt(), 245, 197, 49))
        for ((ax, ay, sx, sy) in listOf(floatArrayOf(x0, y0, 1f, 1f), floatArrayOf(x1, y0, -1f, 1f), floatArrayOf(x0, y1, 1f, -1f), floatArrayOf(x1, y1, -1f, -1f))) {
            cv.drawRect(min(ax, ax + sx * l), min(ay, ay + sy * th), max(ax, ax + sx * l), max(ay, ay + sy * th), pScan)
            cv.drawRect(min(ax, ax + sx * th), min(ay, ay + sy * l), max(ax, ax + sx * th), max(ay, ay + sy * l), pScan)
        }
        pScan.clearShadowLayer()
        // light sweep: a 60%-wide band moving from -60% to +138% in 1.9 s, gradient at 105deg
        val ps = easeScanS.getInterpolation((t % 1900) / 1900f)
        val bw = L.VW * .6f; val left = -L.VW * .6f + ps * bw * 3.3f
        val top = -L.VH * .3f; val bot = L.VH * 1.3f
        val a = Math.toRadians(105.0); val dxv = sin(a).toFloat(); val dyv = -cos(a).toFloat()
        val len = abs(bw * dxv) + abs((bot - top) * dyv); val mx = left + bw / 2; val my = (top + bot) / 2
        pSweep.shader = LinearGradient(mx - dxv * len / 2, my - dyv * len / 2, mx + dxv * len / 2, my + dyv * len / 2,
            intArrayOf(0x00F5C531, 0x14F5C531, 0x33FFFFFF, 0x14F5C531, 0x00F5C531), floatArrayOf(0f, .35f, .5f, .65f, 1f), Shader.TileMode.CLAMP)
        cv.drawRect(left, top, left + bw, bot, pSweep)
    }

    // ---------- status line ----------
    private var msgKind = "x"
    private fun setMsg(t: String, kind: String) {
        if (msgEl.text.toString() != t) msgEl.text = t
        if (kind != msgKind) { msgKind = kind; msgEl.setTextColor(when (kind) { "ok" -> C.LOCK; "err" -> C.WARN; "dim" -> C.MUTED; else -> C.AMBER }) }
    }
    private fun setSub(t: String) { if (subEl.text.toString() != t) subEl.text = t }

    private fun ui() {
        val p = phase; val active = p == "scan" || p == "guide" || p == "lock"
        val icon = if (active) "cancel" else if (p == "locked") "again" else "find"
        aiIco.set(icon, if (active) C.FG else C.AMBER)
        aiBtn.contentDescription = when (icon) { "cancel" -> "Cancelar"; "again" -> "Procurar outra foto"; else -> "Encontrar a foto" }
        aiBtn.isEnabled = mode != "idle"; aiBtn.alpha = if (mode == "idle") .35f else 1f
        shutter.isEnabled = mode != "idle" && !busy && !capturing && p != "lock"
        shutter.ok = p == "locked"
        ratioBtn.text = (RATIOS.firstOrNull { abs(it.first - ratio) < 1e-3 } ?: RATIOS[0]).second
        gridBtn.set("grid", if (grid) C.AMBER else C.MUTED)
        levelBtn.set("level", if (levelOn) C.AMBER else C.MUTED)
        renderLenses()
    }

    private fun renderEngine() {
        val e = gem.engine(); val st = gem.state
        engTxt.text = if (e == "gemini") when (st) { "up" -> "gemini"; "down" -> "local · gemini ✕"; "quota" -> "local · sem quota"; "badkey" -> "local · chave ✕"; else -> "gemini…" } else "local"
        val dot = when { e != "gemini" -> C.MUTED; st == "up" -> C.UP; st == "down" || st == "badkey" || st == "quota" -> C.DOWN; else -> C.AMBER }
        (engDot.background as android.graphics.drawable.GradientDrawable).setColor(dot)
        val wait = e == "gemini" && (st == "checking" || st == "unknown")
        if (wait && engBlink == null) engBlink = ObjectAnimator.ofFloat(engDot, "alpha", 1f, .25f).apply { duration = 500; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE; start() }
        if (!wait) { engBlink?.cancel(); engBlink = null; engDot.alpha = 1f }
    }

    private fun engTap() {
        if (gem.state == "quota" && System.currentTimeMillis() < gem.until) { setMsg(gem.why, "err"); return }
        if (set.gkey.isNotEmpty() && gem.engine() == "gemini") {
            setMsg(if (gem.why.isNotEmpty() && gem.state != "up") "Último erro:\n${gem.why}\nA verificar outra vez…" else "A verificar o Gemini…", "")
            lifecycleScope.launch { gem.check(); if (gem.state == "up") setMsg("Gemini a responder (${gem.model}).", "") else if (gem.why.isNotEmpty()) setMsg("Gemini indisponível:\n${gem.why}", "err") }
        } else if (gem.engine() == "local") setMsg(if (set.gkey.isEmpty()) "IA local. Cola uma chave do Gemini nas Definições para ele escolher." else "IA local (o Gemini está desligado nas Definições).", "dim")
    }

    private fun cycleRatio() {
        val i = RATIOS.indexOfFirst { abs(it.first - ratio) < 1e-3 }; ratio = RATIOS[(i + 1) % RATIOS.size].first; set.ratio = ratio.toFloat()
        if (mode == "live" && phase != "idle") clearShot()
        ui()
    }

    // ---------- find the shot ----------
    private suspend fun snapshot(maxSide: Int): Bitmap? = suspendCancellableCoroutine { c -> cam.snapshot(maxSide) { if (c.isActive) c.resume(it) } }
    private suspend fun hiGray(w: Int): Gray? = suspendCancellableCoroutine { c -> cam.hiGray(w) { if (c.isActive) c.resume(it) } }

    private fun onAI() {
        val p = phase
        if (p == "scan" || p == "guide" || p == "lock") { phase = "idle"; clearShot(); return }
        if (p != "locked") seen.clear()   // "different from" only applies when asking for another option on the same scene
        findShot()
    }

    private fun findShot() {
        if (busy || mode != "live" || cam.srcW == 0) return
        resetView()
        busy = true; phase = "scan"; frame = null; note = ""; plan = null; label = ""; subj = null; scanAt = SystemClock.uptimeMillis()
        cam.trackOn = true; ui(); setMsg("A procurar a foto…", "")
        lifecycleScope.launch {
            try {
                val snap = snapshot(1024) ?: throw GErr("no_frame")
                val srcW = cam.srcW.toDouble(); val srcH = cam.srcH.toDouble()
                val t0 = SystemClock.uptimeMillis()
                val eng = gem.engine(); var pl: Plan? = null; var who = ""
                if (eng == "gemini") gem.maybeRecheck()
                if (eng == "gemini") {
                    if (gem.state == "quota") note = "Quota do Gemini esgotada."
                    else if (gem.state == "down" || gem.state == "badkey") note = if (gem.state == "down") "Gemini em baixo." else "Chave do Gemini inválida."
                    else {
                        val b64 = withContext(Dispatchers.Default) { val o = ByteArrayOutputStream(); snap.compress(Bitmap.CompressFormat.JPEG, 85, o); Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP) }
                        val prompt = gem.prompt(snap.width, snap.height, ratio, seen); var last: Throwable = GErr("g_paused"); var q = 0; var n = 0
                        for (m in gem.modelOrder()) {
                            if (gem.pausedNow(m)) { last = GErr("g_paused"); continue }
                            n++
                            try { pl = fromAI(parseJSON(gem.ask(b64, prompt, m))); who = m.removePrefix("gemini-"); if (gem.state != "up" || gem.model != m) gem.setHealth("up", m, ""); break }
                            catch (e: GErr) { last = e; if (e.code == "g_429") q++; if (gem.fatalKey(e)) { gem.setHealth("badkey", "", errCopy(e)); break } }
                            catch (e: Exception) { last = e }
                        }
                        if (pl == null) {
                            gem.at = System.currentTimeMillis()
                            if (q > 0 && q == n) gem.setQuota(errCopy(last)) else if (gem.state != "badkey") gem.setHealth("down", "", errCopy(last))
                            note = errCopy(last)
                        }
                    }
                }
                if (pl == null && note.isNotEmpty() && !note.contains("IA local")) note += " Usei a IA local."
                val look = LOCAL_LOOK[set.persona] ?: LOCAL_LOOK["criativo"]!!
                if (pl == null) { pl = Plan(null, null, look.styles[seen.size % look.styles.size], ""); who = "local" }
                val plan0 = pl!!
                val (fw, fh) = featureSize(srcW, srcH)
                val z0 = cam.zoom; val stops = lensStops()
                var lensTxt = ""
                val c = withContext(Dispatchers.Default) {
                    val small = Bitmap.createScaledBitmap(snap, fw, fh, true); val px = IntArray(fw * fh); small.getPixels(px, 0, fw, 0, 0, fw, fh)
                    val F = features(px, fw, fh, null)
                    var cr = planCrop(F, plan0, srcW, srcH, ratio, gem.persona().range, look.scales)
                    // The size of the frame comes from a real lens: the zoom it needs is snapped to 1x, 3x or 5x,
                    // so the photo is taken by that lens at full resolution and never by a digital crop in between.
                    if (cr != null && stops.isNotEmpty()) {
                        val maxH = min(F.H.toDouble(), F.W / ratio); val s0 = cr.ch / maxH
                        val zRaw = z0 * LENS_FILL / s0
                        val L = stops.minByOrNull { abs(ln(it / zRaw)) }!!
                        val s = (z0 * LENS_FILL / L).coerceIn(.06, 1.0)
                        lensTxt = "lente ${fmtZ(L)}"
                        if (abs(s / s0 - 1) > .03) cr = bestCrop(F, plan0.subj, ratio, plan0.rule, CropOpt(listOf(s), cr.x0 + cr.cw / 2.0, cr.y0 + cr.ch / 2.0, .06)) ?: cr
                    }
                    cr?.let { toFrame(it, F, srcW, srcH, ratio) }
                } ?: throw GErr("no_crop")
                val dt = "%.1f".format((SystemClock.uptimeMillis() - t0) / 1000.0)
                note = (if (note.isNotEmpty()) "$note  " else "") + "$who · $dt s" + (if (lensTxt.isNotEmpty()) " · $lensTxt" else "")"
                if (phase != "scan") return@launch     // cancelled meanwhile
                plan = plan0; subj = plan0.subj; style = plan0.rule; tip = plan0.tip.ifEmpty { LOCAL_TIP[plan0.rule] ?: "" }
                label = plan0.name.ifEmpty { plan0.subj?.name ?: "" }
                frame = c
                seen.add(label.ifEmpty { "zona ${c.x.roundToInt()},${c.y.roundToInt()}" })
                phase = "guide"
            } catch (e: Throwable) { phase = "idle"; cam.trackOn = false; setMsg(errCopy(e), "err") }
            finally { busy = false; ui() }
        }
    }

    /** Aligned: the frame stops where it was and the preview zooms into it; then the right lens takes over. */
    private fun lock() {
        val f = curFrame() ?: return
        val sw = cam.srcW.toDouble(); val sh = cam.srcH.toDouble()
        val w = min(f.w, sw); val h = min(f.h, sh)
        frame = RectD(cl(f.x, 0.0, sw - w), cl(f.y, 0.0, sh - h), w, h)
        cam.trackOn = false; phase = "locked"; zoomed = true; lockAt = SystemClock.uptimeMillis(); alignedSince = 0
        setMsg(tip.ifEmpty { "Enquadrado. Tira a foto." }, "ok"); ui()
        val token = lockAt
        pv.postDelayed({ if (phase == "locked" && lockAt == token) lifecycleScope.launch { opticalSwitch(token) } }, 750)
    }

    /**
     * Phase 1's main point: the photo must come from the lens that fills the frame, at full resolution.
     * The view is frozen, the camera zooms optically, then the frame is found again in the new picture
     * (a different lens sees from a slightly different place), and the live view comes back already aligned.
     */
    private suspend fun opticalSwitch(token: Long) {
        val f = frame ?: return
        val sw = cam.srcW.toDouble(); val sh = cam.srcH.toDouble()
        val z0 = cam.zoom; val k = min(sw / f.w, sh / f.h) * LENS_FILL
        var zt = (z0 * k).toFloat().coerceIn(cam.minZoom, cam.maxZoom)
        // land exactly on the real lens (2.98x would still be a crop of the main camera)
        lensStops().minByOrNull { abs(ln(it / zt)) }?.let { if (abs(ln(it / zt)) < .12) zt = it.toFloat() }
        val zr = zt / z0
        if (zr < 1.25f) { lastMatch = "sem troca de lente (zoom ${"%.1f".format(zt)}×)"; return }
        val g0 = hiGray(320) ?: return
        if (phase != "locked" || lockAt != token) return
        val kg = g0.W / sw
        val TW = 40; val TH = max(16, (TW / ratio).roundToInt())
        val ref = sampleGray(g0, f.x * kg, f.y * kg, f.w * kg, f.h * kg, TW, TH)
        // freeze what the screen shows while the lens changes
        pv.bitmap?.let { freeze.setImageBitmap(it); freeze.alpha = 1f; freeze.visibility = View.VISIBLE }
        phase = "lock"; setMsg("A passar para a lente ${"%.1f".format(zt)}×…", "ok"); ui()
        suspendCancellableCoroutine<Unit> { c -> cam.setZoom(zt) { if (c.isActive) c.resume(Unit) } }
        val s0 = cam.seq; val t0 = SystemClock.uptimeMillis()
        while (cam.seq < s0 + 8 && SystemClock.uptimeMillis() - t0 < 1500) kotlinx.coroutines.delay(30)   // let the lens settle
        if (phase != "lock" || lockAt != token) return
        val g1 = hiGray(320) ?: return
        val zrReal = cam.zoom / z0
        // where the frame would be if both lenses looked from the same point
        val ecx = g1.W / 2.0 + ((f.x + f.w / 2) * kg - g1.W / 2.0) * zrReal; val ecy = g1.H / 2.0 + ((f.y + f.h / 2) * kg - g1.H / 2.0) * zrReal
        val ew = f.w * kg * zrReal; val eh = f.h * kg * zrReal
        val loc = withContext(Dispatchers.Default) { locate(ref, TW, TH, g1, ecx - ew / 2, ecy - eh / 2, ew, eh) }
        if (phase != "lock" || lockAt != token) return
        val k1 = g1.W / sw
        var nx: Double; var ny: Double; var nw: Double
        if (loc != null && loc.err < 30) { nx = loc.x / k1; ny = loc.y / k1; nw = loc.w / k1; lastMatch = "lente ${"%.1f".format(cam.zoom)}× · encaixe ${"%.0f".format(loc.err)}" }
        else { nx = (ecx - ew / 2) / k1; ny = (ecy - eh / 2) / k1; nw = ew / k1; lastMatch = "lente ${"%.1f".format(cam.zoom)}× · sem encaixe (${loc?.err?.roundToInt() ?: -1}), centrado" }
        var nh = nw / ratio
        val cxn = nx + nw / 2; val cyn = ny + nh / 2
        if (nw > sw) { nw = sw; nh = nw / ratio }; if (nh > sh) { nh = sh; nw = nh * ratio }
        frame = RectD(cl(cxn - nw / 2, 0.0, sw - nw), cl(cyn - nh / 2, 0.0, sh - nh), nw, nh)
        lockAt = SystemClock.uptimeMillis() - 1000     // already zoomed: no second animation
        phase = "locked"; setMsg(tip.ifEmpty { "Enquadrado. Tira a foto." }, "ok"); ui()
        freeze.animate().alpha(0f).setDuration(180).withEndAction { freeze.visibility = View.GONE; freeze.setImageDrawable(null) }.start()
    }

    private fun resetView() {
        zoomed = false; applyTransform(1f, 0f, 0f); alignedSince = 0; lockAt = 0
        freeze.animate().cancel(); freeze.visibility = View.GONE; freeze.setImageDrawable(null)
        if (mode == "live" && abs(cam.zoom - homeZoom) > .01f) cam.setZoom(homeZoom)
    }
    private fun clearShot() {
        phase = "idle"; frame = null; subj = null; plan = null; label = ""; cam.trackOn = false; resetView()
        if (mode == "live") setMsg("Aponta e toca em ✦ para encontrar a foto.", "dim")
        ui()
    }

    // ---------- capture ----------
    private fun onShutter() {
        if (busy || capturing || mode == "idle" || phase == "lock") return
        val sw = cam.srcW.toDouble(); val sh = cam.srcH.toDouble(); if (sw <= 0) return
        var f = curFrame()
        if (f == null) { val w = min(sw, sh * ratio); val h = w / ratio; f = RectD((sw - w) / 2, (sh - h) / 2, w, h) }
        val w = min(f.w, sw); val h = min(f.h, sh); val r = RectD(cl(f.x, 0.0, sw - w), cl(f.y, 0.0, sh - h), w, h)
        val lensZoom = cam.zoom; val digital = sqrt(sw * sh / (w * h))
        capturing = true; ui(); setMsg("A tirar a foto…", "ok")
        shutter.animate().scaleX(.92f).scaleY(.92f).setDuration(80).withEndAction { shutter.animate().scaleX(1f).scaleY(1f).setDuration(120).start() }.start()
        cam.takePhoto(r, cam.srcW, cam.srcH) { photo, why ->
            capturing = false
            if (photo == null) { setMsg("A câmara não devolveu a foto ($why).", "err"); ui(); return@takePhoto }
            val info = "${photo.bmp.width}×${photo.bmp.height} · ${"%.1f".format(photo.bmp.width * photo.bmp.height / 1e6)} MP · lente ${"%.1f".format(lensZoom)}× · ≈ ${(26 * digital * lensZoom).roundToInt()} mm"
            val pl = plan
            if (mode == "live") { phase = "idle"; frame = null; cam.trackOn = false; resetView(); setMsg("Aponta e toca em ✦ para encontrar a foto.", "dim") }
            ui()
            openResult(photo.bmp, info + " · ${photo.ms} ms", pl)
        }
    }

    // ---------- result (phase 2 adds filters and borders) ----------
    private fun openResult(bmp: Bitmap, info: String, pl: Plan?) {
        val host = root.parent as FrameLayout
        val v = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.INK); isClickable = true }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dpi(40) }
        val back = FrameLayout(this).apply { background = bg(C.PANEL, dp(21)); addView(Icon(this@MainActivity, "back", C.FG), FrameLayout.LayoutParams(-1, -1)); setOnClickListener { stepBack() } }
        val infoT = text(11f, C.MUTED, F.mono, info).apply { gravity = Gravity.CENTER; maxLines = 2 }
        head.addView(back, LinearLayout.LayoutParams(dpi(42), dpi(42))); head.addView(infoT, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dpi(10); rightMargin = dpi(52) })
        val k = min(1f, 2048f / max(bmp.width, bmp.height))
        val show = if (k < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * k).roundToInt(), (bmp.height * k).roundToInt(), true) else bmp
        val img = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setImageBitmap(show) }
        val sub = text(11f, C.MUTED, F.mono, listOfNotNull(pl?.name?.takeIf { it.isNotEmpty() }, pl?.filter?.let { "filtro sugerido: $it" }, pl?.border?.let { "margem: $it" }).joinToString("  ·  ")).apply { gravity = Gravity.CENTER }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val saved = text(12f, C.MUTED, F.mono, "Filtros e margens: fase 2.").oneLine()
        val save = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; background = bg(C.AMBER, dp(999)); setPadding(dpi(22), dpi(12), dpi(22), dpi(12))
            addView(Icon(this@MainActivity, "check", 0xFF1A1405.toInt(), 18f), LinearLayout.LayoutParams(dpi(18), dpi(18)).apply { rightMargin = dpi(8) })
            addView(text(15f, 0xFF1A1405.toInt(), F.uiSemi, "Guardar"))
        }
        save.setOnClickListener {
            save.isEnabled = false; save.alpha = .5f; saved.text = "A guardar…"
            lifecycleScope.launch {
                val uri = withContext(Dispatchers.IO) { saveJpeg(bmp) }
                if (uri != null) { set.lastPhoto = uri.toString(); refreshThumb(); saved.setTextColor(C.LOCK); saved.text = "Guardada em Imagens/Enquadra." }
                else { save.isEnabled = true; save.alpha = 1f; saved.setTextColor(C.WARN); saved.text = "Não consegui guardar." }
            }
        }
        bar.addView(saved, LinearLayout.LayoutParams(0, -2, 1f)); bar.addView(save)
        v.addView(head, LinearLayout.LayoutParams(-1, -2)); v.addView(img, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dpi(12) })
        v.addView(sub, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(10) }); v.addView(bar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(12) })
        host.addView(v, FrameLayout.LayoutParams(-1, -1)); host.requestApplyInsets()
        v.alpha = 0f; v.animate().alpha(1f).setDuration(160).start()
        resultView = v
    }

    private fun saveJpeg(bmp: Bitmap): Uri? = try {
        val name = "Enquadra_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date()) + ".jpg"
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name); put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Enquadra"); put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)!!
        contentResolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        cv.clear(); cv.put(MediaStore.Images.Media.IS_PENDING, 0); contentResolver.update(uri, cv, null, null)
        uri
    } catch (e: Exception) { null }

    private fun refreshThumb() {
        val u = set.lastPhoto; if (u.isEmpty()) return
        lifecycleScope.launch {
            val b = withContext(Dispatchers.IO) { try { contentResolver.loadThumbnail(Uri.parse(u), Size(160, 160), null) } catch (e: Exception) { null } }
            if (b != null) { thumbImg.setImageBitmap(b); thumbImg.visibility = View.VISIBLE } else { thumbImg.visibility = View.GONE; set.lastPhoto = "" }
        }
    }
    private fun openLastPhoto() {
        val u = set.lastPhoto
        if (u.isEmpty()) { setMsg("Ainda não guardaste nenhuma foto.", "dim"); return }
        try { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(u), "image/jpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
        catch (e: Exception) { setMsg("Não há app para abrir a foto.", "err") }
    }

    // ---------- settings ----------
    private fun lbl(s: String) = text(11f, C.MUTED, F.mono, s.uppercase()).apply { letterSpacing = .08f }
    private fun para(s: String) = text(13f, C.MUTED, F.ui, s).apply { setLineSpacing(0f, 1.3f) }
    private fun field(vararg vs: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        vs.forEachIndexed { i, x -> val w = (x.layoutParams?.width ?: -1); addView(x, LinearLayout.LayoutParams(w, -2).apply { if (i > 0) topMargin = dpi(6) }) }
    }
    private fun input(value: String, hint: String, password: Boolean, lines: Int = 1) = EditText(this).apply {
        setText(value); this.hint = hint; setHintTextColor(0xFF55534E.toInt()); setTextColor(C.FG); typeface = if (password) F.mono else F.ui
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f); background = bg(C.PANEL, dp(10), C.LINE, dpi(1)); setPadding(dpi(12), dpi(11), dpi(12), dpi(11))
        inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            else if (lines > 1) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES else InputType.TYPE_CLASS_TEXT
        if (lines > 1) { minLines = lines; gravity = Gravity.TOP or Gravity.START }
    }
    private fun outText() = text(12f, C.MUTED, F.mono)
    private fun btn(s: String, click: () -> Unit) = text(15f, C.FG, F.uiMed, s).apply {
        background = bg(C.RAISE, dp(12), C.LINE, dpi(1)); setPadding(dpi(16), dpi(11), dpi(16), dpi(11)); setOnClickListener { click() }
    }

    private fun openSettings() {
        val host = root.parent as FrameLayout
        val v = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.INK); isClickable = true }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dpi(40) }
        val back = FrameLayout(this).apply { background = bg(C.PANEL, dp(21)); addView(Icon(this@MainActivity, "back", C.FG), FrameLayout.LayoutParams(-1, -1)); setOnClickListener { stepBack() } }
        head.addView(back, LinearLayout.LayoutParams(dpi(42), dpi(42)))
        head.addView(text(17f, C.FG, F.uiSemi, "Definições"), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dpi(10) })
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dpi(12), 0, dpi(24)) }
        val engineText = para("")
        val healthOut = outText()
        fun refresh() {
            engineText.text = if (gem.engine() == "gemini") "O Gemini escolhe o motivo; a composição fina é calculada no telemóvel."
                else "Motor local, grátis e sem internet: calcula a composição pela luz, linhas e contraste. Não sabe dizer o que vê; a deteção de objetos e os modelos locais chegam na fase 3."
            healthOut.setTextColor(when (gem.state) { "up" -> C.LOCK; "down", "badkey" -> C.WARN; else -> C.MUTED })
            healthOut.text = when (gem.state) { "up" -> "A responder: ${gem.model}."; "checking" -> "A verificar o Gemini…"; else -> gem.why }
            renderEngine()
        }
        val engineSel = Choice(this, listOf("auto" to "Gemini quando está disponível, senão IA local", "local" to "Só IA local (nunca usa o Gemini)"), set.engine) {
            set.engine = it; refresh(); lifecycleScope.launch { gem.check() } }
        body.addView(field(lbl("Quem escolhe a foto"), engineSel, engineText))
        val key = input(set.gkey, "AIza…", true)
        key.setOnFocusChangeListener { _, has -> if (!has) saveKey(key.text.toString()) }
        val warn = text(13f, C.FG, F.ui, "No nível gratuito, a Google pode usar as imagens enviadas para melhorar os produtos dela, e revisores humanos podem vê-las. Evita usar a IA em fotos privadas.").apply {
            background = bg(0x14FF8A5B, dp(10), 0x59FF8A5B, dpi(1)); setPadding(dpi(12), dpi(10), dpi(12), dpi(10)) }
        body.addView(field(lbl("Chave do Gemini"), key, para("Cria uma chave grátis em aistudio.google.com/apikey e cola-a aqui. Fica guardada só neste telemóvel."), warn), lp(20))
        val persona = Choice(this, PERSONAS.map { (k, p) -> k to "${p.label} · ${PERSONA_DESC[k]}" }, set.persona) { set.persona = it }
        body.addView(field(lbl("Olhar do fotógrafo"), persona), lp(20))
        val extra = input(set.extra, "Ex.: gosto de silhuetas e de preto e branco; evita carros.", false, 3)
        extra.setOnFocusChangeListener { _, has -> if (!has) set.extra = extra.text.toString().trim().take(400) }
        body.addView(field(lbl("Instruções tuas para a IA (opcional)"), extra, para("Escreve como falarias com um fotógrafo. A IA segue isto em todas as análises.")), lp(20))
        val model = Choice(this, Gemini.MODELS.map { it to (Gemini.MODEL_LABEL[it] ?: it) }, set.gmodel) { set.gmodel = it }
        body.addView(field(lbl("Modelo"), model, para("Se este modelo estiver em baixo, a app tenta sozinha os outros dois. O ponto ao lado de \"gemini\" no topo fica verde quando o Gemini responde e vermelho quando está em baixo; toca nele para verificar outra vez."), healthOut), lp(20))
        val testOut = outText()
        body.addView(field(btn("Testar ligação") {
            saveKey(key.text.toString())
            if (set.gkey.isEmpty()) { testOut.setTextColor(C.WARN); testOut.text = "Cola primeiro a chave."; return@btn }
            testOut.setTextColor(C.MUTED); testOut.text = "A testar…"
            lifecycleScope.launch {
                try { parseJSON(gem.ask(null, "Answer with JSON only: {\"ok\":true}", set.gmodel, 8000)); testOut.setTextColor(C.LOCK); testOut.text = "Ligação OK com ${set.gmodel}."; gem.check() }
                catch (e: Exception) { testOut.setTextColor(C.WARN); testOut.text = errCopy(e) + ((e as? GErr)?.msg?.takeIf { it.isNotEmpty() }?.let { " (${it.take(90)})" } ?: "") }
            }
        }.apply { layoutParams = LinearLayout.LayoutParams(-2, -2) }, testOut), lp(20))
        val diag = outText().apply { setTextIsSelectable(true) }
        diag.text = "Enquadra nativa ${packageManager.getPackageInfo(packageName, 0).versionName}\n${cam.info()}\n${lastMatch.ifEmpty { "ainda sem troca de lente" }}"
        body.addView(field(lbl("Diagnóstico"), diag, btn("Copiar diagnóstico") {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("diag", diag.text)); diag.setTextColor(C.LOCK)
        }.apply { layoutParams = LinearLayout.LayoutParams(-2, -2) }), lp(20))
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(body) }
        v.addView(head, LinearLayout.LayoutParams(-1, -2)); v.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dpi(12) })
        v.tag = { refresh() }
        refresh()
        host.addView(v, FrameLayout.LayoutParams(-1, -1)); host.requestApplyInsets()
        settingsView = v
    }
    private fun lp(top: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(top) }
    private fun saveKey(k: String) { val t = k.trim(); if (t != set.gkey) { set.gkey = t; gem.state = "unknown"; renderEngine(); lifecycleScope.launch { gem.check() } } }

    // ---------- back ----------
    private fun stepBack() {
        val host = root.parent as FrameLayout
        settingsView?.let { v ->
            (v.findFocus() as? View)?.clearFocus()   // saves the fields being edited
            host.removeView(v); settingsView = null; renderEngine()
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(root.windowToken, 0); return
        }
        resultView?.let { host.removeView(it); resultView = null; return }
        if (phase != "idle") { clearShot(); return }
        finish()
    }

    // ---------- crash log: the next start shows what went wrong ----------
    private fun installCrashLog() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { File(filesDir, "crash.txt").writeText("${java.util.Date()}\n${android.util.Log.getStackTraceString(e)}") } catch (_: Exception) {}
            prev?.uncaughtException(t, e)
        }
    }
    private fun showLastCrash() {
        val f = File(filesDir, "crash.txt"); if (!f.exists()) return
        val txt = f.readText().take(4000); f.delete()
        AlertDialog.Builder(this).setTitle("A app fechou da última vez").setMessage(txt)
            .setPositiveButton("Copiar") { _, _ -> (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("crash", txt)) }
            .setNegativeButton("Fechar", null).show()
    }
}
