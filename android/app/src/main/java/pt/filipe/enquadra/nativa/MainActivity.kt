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
    private val MAIN_MAX = 2.9        // above this the phone switches to the 3x module (soft on the S26 Ultra)
    private val LENS_FILL = .9        // after the zoom the frame fills 90% of the view: room to find it again if the phone moved
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
    private lateinit var stillView: ImageView
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
        renderEngine(); ui(); refreshThumb(); installDrag()
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
        stillView = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; visibility = View.GONE }
        stage.addView(pv, FrameLayout.LayoutParams(-1, -1)); stage.addView(stillView, FrameLayout.LayoutParams(-1, -1)); stage.addView(freeze, FrameLayout.LayoutParams(-1, -1))
        stage.addView(ov, FrameLayout.LayoutParams(-1, -1)); stage.addView(nocam, FrameLayout.LayoutParams(-1, -1))

        // status, lenses, controls
        val hud = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val status = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; minimumHeight = dpi(40) }
        msgEl = text(13f, C.AMBER, F.monoMed).apply { gravity = Gravity.CENTER }
        subEl = text(11f, C.MUTED, F.mono).apply { gravity = Gravity.CENTER; minHeight = dpi(15); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
        status.addView(msgEl, LinearLayout.LayoutParams(-1, -2)); status.addView(subEl, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(2) })
        lenses = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; minimumHeight = dpi(36) }
        val controls = FrameLayout(this)
        thumb = FrameLayout(this).apply { background = bg(C.PANEL, dp(12)); clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(12)) } }
            setOnClickListener { openGallery() }; contentDescription = "Galeria" }
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
                    homeZoom = 1f.coerceIn(cam.minZoom, cam.maxZoom); cam.setZoom(homeZoom)
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
    private fun fits(sj: Subj, L: Double, z0: Float, srcW: Double, srcH: Double, k: Double): Boolean {
        val maxW = min(srcW, srcH * ratio); val fw = z0 * LENS_FILL / L * maxW / srcW; val fh = fw * srcW / ratio / srcH
        return sj.box[2] <= fw * k && sj.box[3] <= fh * k
    }
    /** Gemini's lens, stepped down only if its own subject would not fit. */
    private fun fitLens(L: Double, stops: List<Double>, sj: Subj?, z0: Float, srcW: Double, srcH: Double): Double {
        if (sj == null) return L
        // Gemini knew the frame sizes when it chose; its subject box is loose, so only step down when it is clearly too big
        return stops.filter { it <= L + 1e-3 }.sortedDescending().firstOrNull { fits(sj, it, z0, srcW, srcH, 1.35) } ?: stops.min()
    }
    /** 3x unless the subject asks for 5x, or would not fit in a 3x frame (then 1x). */
    private fun pickLens(zRaw: Double, stops: List<Double>, sj: Subj?, z0: Float, srcW: Double, srcH: Double): Double {
        val has3 = stops.any { abs(it - 3) < .01 }; val has5 = stops.any { abs(it - 5) < .01 }
        if (!has3) return stops.minByOrNull { abs(ln(it / zRaw)) }!!
        if (has5 && (zRaw > 4.2 || (sj != null && fits(sj, 5.0, z0, srcW, srcH, .75)))) return 5.0
        if (sj != null) {
            val maxW = min(srcW, srcH * ratio); val fw = z0 * LENS_FILL / 3.0 * maxW / srcW; val fh = fw * srcW / ratio / srcH
            if (sj.box[2] > fw * .95 || sj.box[3] > fh * .95) return 1.0
        }
        return 3.0
    }
    /** The phone's real lenses (S26 Ultra: 1x, 3x, 5x); zooms between them are digital crops. */
    private fun lensStops(): List<Double> = listOf(1.0, 3.0, 5.0).filter { it >= cam.minZoom - 1e-3 && it <= cam.maxZoom + 1e-3 }
    private fun fmtZ(z: Double) = if (abs(z - z.roundToInt()) < .05) "${z.roundToInt()}×" else "%.1f×".format(z)

    /** While aiming, tap a lens to take the shot with it: the frame keeps its centre and takes that lens's size. */
    private fun chooseLens(L: Double) {
        val f = frame ?: return
        val sw = cam.srcW.toDouble(); val sh = cam.srcH.toDouble()
        val w = min(sw, sh * ratio) * scanZoom * LENS_FILL / L; val h = w / ratio
        if (w > sw || h > sh) return
        val cx = f.x + f.w / 2; val cy = f.y + f.h / 2
        frame = RectD(cl(cx - w / 2, 0.0, sw - w), cl(cy - h / 2, 0.0, sh - h), w, h)
        frame?.let { cam.tracker.setAnchor(it.x / sw, it.y / sh, it.w / sw, it.h / sh) }
        targetZoom = L; note = note.replace(Regex("lente [^·]*$"), "lente ${fmtZ(L)} (tua)").let { if (it.contains("(tua)")) it else "$it · lente ${fmtZ(L)} (tua)" }
        lastEffR = -1; renderLenses()
    }
    private var scanZoom = 1.0

    private fun pillText(v: Double) = when {
        v < .995 -> "%.1f".format(v).removePrefix("0").replace(",", ".")
        abs(v - v.roundToInt()) < .05 -> v.roundToInt().toString()
        else -> "%.1f".format(v).replace(",", ".")
    } + "×"
    private fun renderLenses() {
        lenses.removeAllViews()
        if (mode != "live" || phase == "scan") return
        val idle = phase == "idle"; val aiming = phase == "guide"
        val vals = if (aiming) lensStops().map { it.toFloat() } else lensValues()
        val eff = if (idle) homeZoom.toDouble() else if (aiming) targetZoom else effZoom
        val active = vals.lastOrNull { it <= eff + .05 } ?: vals.firstOrNull()
        for (v in vals) {
            val on = active != null && abs(v - active) < 1e-3
            val label = if (on && !idle && !aiming) pillText(eff) else pillText(v.toDouble())
            val t = text(11f, if (on) C.AMBER else C.FG, F.monoMed, label).apply {
                gravity = Gravity.CENTER; background = bg(if (on) C.RAISE else C.PANEL, dp(18))
                if (idle) setOnClickListener { homeZoom = v; cam.setZoom(v); renderLenses(); ui() }
                if (aiming) setOnClickListener { chooseLens(v.toDouble()) }
            }
            lenses.addView(t, LinearLayout.LayoutParams(if (on && !idle && label.length > 3) dpi(48) else dpi(36), dpi(36)).apply { leftMargin = dpi(4); rightMargin = dpi(4) })
        }
    }

    // ---------- geometry ----------
    private class Lay(val s: Float, val ox: Float, val oy: Float, val VW: Float, val VH: Float)
    private var still: Bitmap? = null
    private fun srcWi() = if (mode == "photo") still?.width ?: 0 else cam.srcW
    private fun srcHi() = if (mode == "photo") still?.height ?: 0 else cam.srcH
    private fun layout(): Lay {
        val VW = stage.width.toFloat(); val VH = stage.height.toFloat(); val w = srcWi().toFloat(); val h = srcHi().toFloat()
        if (w <= 0 || h <= 0) return Lay(1f, 0f, 0f, VW, VH)
        val s = if (mode == "photo") min(VW / w, VH / h) else max(VW / w, VH / h)
        return Lay(s, (VW - w * s) / 2, (VH - h * s) / 2, VW, VH)
    }
    private fun tracking() = mode == "live" && (phase == "scan" || phase == "guide" || phase == "lock")
    private var tsX: Double? = null; private var tsY = 0.0; private var tsS = 1.0; private var tsT = 0L
    private fun smoothTrack() {
        if (!tracking()) { tsX = null; tsS = 1.0; return }
        val p = cam.tracker.prev; val k = cam.srcW.toDouble() / (p?.W ?: 128)
        val tx = cam.tracker.dx * k; val ty = cam.tracker.dy * k; val ts = cam.tracker.S; val now = SystemClock.uptimeMillis()
        val x = tsX
        if (x == null) { tsX = tx; tsY = ty; tsS = ts } else { val a = 1 - exp(-(now - tsT) / 70.0); tsX = x + (tx - x) * a; tsY += (ty - tsY) * a; tsS += (ts - tsS) * a }
        tsT = now
    }
    /** The frame where the scene is now: it follows the camera's movement and its zoom. */
    private fun trackedFrame(): RectD? {
        val f = frame ?: return null
        val cx = cam.srcW / 2.0; val cy = cam.srcH / 2.0; val S = tsS
        return RectD(cx + (f.x - cx) * S + (tsX ?: 0.0), cy + (f.y - cy) * S + tsY, f.w * S, f.h * S)
    }
    private fun curFrame(): RectD? = if (tracking()) trackedFrame() else frame

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
    private var lockFrom = floatArrayOf(1f, 0f, 0f)
    private var zG = 1f; private var zgT = 0L; private var guideDist0 = 0f
    private var targetZoom = 3.0; private var zS = 1.0; private var lastCmd = 1.0; private var lastCmdT = 0L; private var lockTrackUntil = 0L
    private var effZoom = 1.0; private var lastEffR = -1

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
        if (grid && !zm && phase != "locked") { pLine.color = 0x2EFFFFFF; pLine.strokeWidth = 1 * d; pLine.pathEffect = null
            for (i in 1..2) { cv.drawLine(L.VW * i / 3, 0f, L.VW * i / 3, L.VH, pLine); cv.drawLine(0f, L.VH * i / 3, L.VW, L.VH * i / 3, pLine) } }
        val camZoom = cam.zoom
        val f = if (phase == "zoom") frame?.let { fr -> val k = zoomNow / zoomFrom; val ccx = cam.srcW / 2.0; val ccy = cam.srcH / 2.0
            RectD(ccx + (fr.x - ccx) * k, ccy + (fr.y - ccy) * k, fr.w * k, fr.h * k) }
            else if (phase == "locked" && refAt > 0 && refFrom != null) frame?.let { fr -> val a = refFrom!!
                val t = ((SystemClock.uptimeMillis() - refAt) / 450.0).coerceIn(0.0, 1.0); val e = if (t < .5) 2 * t * t else 1 - (-2 * t + 2).pow(2) / 2
                if (t >= 1) { refAt = 0; refFrom = null }
                RectD(a.x + (fr.x - a.x) * e, a.y + (fr.y - a.y) * e, a.w + (fr.w - a.w) * e, a.h + (fr.h - a.h) * e) }
            else curFrame()
        var aligned = false
        if (f != null) {
            var fx = L.ox + f.x.toFloat() * L.s; var fy = L.oy + f.y.toFloat() * L.s; var fw = f.w.toFloat() * L.s; var fh = f.h.toFloat() * L.s
            val dx = fx + fw / 2 - cx; val dy = fy + fh / 2 - cy; val dist = hypot(dx, dy); val tol = min(L.VW, L.VH) * .05f
            if (phase == "guide") {
                aligned = dist < tol || autoLock
                if (aligned) { if (alignedSince == 0L) alignedSince = now; if (now - alignedSince > 350) lock() } else alignedSince = 0
            } else aligned = phase == "locked" || phase == "lock" || phase == "zoom"
            // Framed's sequence: centred → hold still a moment → the camera zooms smoothly until the frame fills the view
            var ease = 0f; var frameA = 1f
            when (phase) {
                "lock" -> if (now - lockAt > HOLD_MS) startZoom(now)
                "zoom" -> {
                    val t = ((now - zoomStart) / ZOOM_MS.toFloat()).coerceIn(0f, 1f)
                    val e = if (t < .5f) 4 * t * t * t else 1 - (-2 * t + 2).pow(3) / 2
                    zoomNow = zoomFrom * (targetZoom / zoomFrom).pow(e.toDouble())     // even speed to the eye: interpolate in log
                    if ((abs(zoomNow / lastCmd - 1) > .004 && now - lastCmdT > 33) || t >= 1f) { cam.setZoom(zoomNow.toFloat()); lastCmd = zoomNow; lastCmdT = now }
                    ease = e; frameA = 1 - (t / .45f).coerceAtMost(1f)
                    if (t >= 1f) finishZoom()
                }
                "locked" -> if (mode != "photo") { ease = 1f; frameA = if (refined) 1f else 0f }
            }
            effZoom = camZoom.toDouble()
            val effR = (effZoom * 10).roundToInt()
            if (effR != lastEffR) { lastEffR = effR; renderLenses() }
            val col = if (aligned) C.LOCK else C.AMBER
            val r = 16 * d
            dimPath.reset(); dimPath.fillType = Path.FillType.EVEN_ODD
            dimPath.addRect(0f, 0f, L.VW, L.VH, Path.Direction.CW); dimPath.addRoundRect(fx, fy, fx + fw, fy + fh, r, r, Path.Direction.CW)
            pDim.color = Color.argb(((.38f + .2f * ease) * 255).roundToInt(), 0, 0, 0); cv.drawPath(dimPath, pDim)
            if (frameA > 0f) {
                pLine.pathEffect = null; pLine.strokeWidth = 1 * d; pLine.color = if (aligned) 0x667BD88F else 0x59F5C531
                pLine.alpha = (pLine.alpha * frameA).roundToInt(); guides(cv, fx, fy, fw, fh)
                pLine.color = col; pLine.alpha = (255 * frameA).roundToInt(); pLine.strokeWidth = 2.5f * d; cv.drawRoundRect(fx, fy, fx + fw, fy + fh, r, r, pLine)
                pLine.alpha = 255
            }
            if (phase == "guide") {
                val tx = (fx + fw / 2).coerceIn(8 * d, L.VW - 8 * d); val ty = (fy + fh / 2).coerceIn(8 * d, L.VH - 8 * d)
                pLine.pathEffect = DashPathEffect(floatArrayOf(5 * d, 6 * d), 0f); pLine.strokeWidth = 2 * d
                cv.drawLine(cx, cy, tx, ty, pLine); pLine.pathEffect = null
                pFill.color = col; cv.drawCircle(tx, ty, 4.5f * d, pFill)
            }
            val name = label.ifEmpty { subj?.name ?: "" }
            if (name.isNotEmpty()) {
                if (phase == "zoom" || phase == "locked") drawChip(cv, name, true, cx - 60 * d, cy + 16 * d, 0f, L, d)
                else drawChip(cv, name, aligned, fx, fy, fh, L, d)
            }
            if (mode == "live" && phase == "guide" && !busy) {
                if (cam.tracker.lost > 20) setMsg("Perdi a cena. Toca em ✕ e tenta outra vez.", "err")
                else if (aligned) setMsg("Segura…", "ok")
                else {
                    val h = if (abs(dx) > tol * .6f) (if (dx < 0) "para a esquerda" else "para a direita") else ""
                    val v = if (abs(dy) > tol * .6f) (if (dy < 0) "para cima" else "para baixo") else ""
                    setMsg("Aponta " + listOf(h, v).filter { it.isNotEmpty() }.joinToString(" e ") + ".", "")
                }
            }
            val digital = sqrt(srcWi().toDouble() * srcHi() / (f.w * f.h)); val zz = digital * (if (mode == "photo") 1f else camZoom)
            setSub(note.ifEmpty { "${STYLE_LABEL[style]}  ·  ≈ ${(26 * zz).roundToInt()} mm" })
        } else {
            setSub(if (mode == "live") note.ifEmpty { "${(26 * camZoom).roundToInt()} mm" } else "")
        }
        // level: a short line through the centre, as on a camera
        if (mode == "live" && levelOn && levelValid && !zm && phase != "locked" && phase != "zoom") {
            val ok = abs(roll) < 1; val len = min(L.VW, L.VH) * .2f
            cv.save(); cv.translate(cx, cy); cv.rotate((-roll).toFloat())
            pLine.color = if (ok) C.LOCK else C.AMBER; pLine.strokeWidth = 2 * d
            cv.drawLine(-len, 0f, -14 * d, 0f, pLine); cv.drawLine(14 * d, 0f, len, 0f, pLine); cv.restore()
        }
        if (!zm && mode == "live") {
            pLine.color = if (aligned || phase == "locked") C.LOCK else 0xE6FFFFFF.toInt(); pLine.strokeWidth = 1.5f * d
            cv.drawLine(cx - 7 * d, cy, cx + 7 * d, cy, pLine); cv.drawLine(cx, cy - 7 * d, cx, cy + 7 * d, pLine)
        }
        if (phase == "scan") drawScan(cv, L, d, now)
    }

    /**
     * Aiming = zooming: the closer the frame gets to the centre, the closer the real camera zoom gets to the lens
     * chosen for the shot. The phone switches lenses by itself on the way, as when you pinch to zoom.
     */
    private fun driveZoom(distRef: Float, tol: Float, now: Long) {
        if (guideDist0 <= 0f) guideDist0 = max(distRef, tol * 3)
        val prog = (1 - (distRef - tol) / (guideDist0 - tol)).coerceIn(0f, 1f).toDouble()
        val z0 = homeZoom.toDouble(); val zt = targetZoom
        val zWant = z0 * (zt / z0).pow(prog.pow(1.3))
        val dt = (now - zgT).coerceIn(0, 100); zgT = now
        zS += (zWant - zS) * (1 - exp(-dt / 220.0))
        if (abs(zS / lastCmd - 1) > .006 && now - lastCmdT > 40) { cam.setZoom(zS.toFloat()); lastCmd = zS; lastCmdT = now }
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
        val p = phase; val active = p == "scan" || p == "guide" || p == "lock" || p == "zoom"
        val icon = if (active) "cancel" else if (p == "locked") "again" else "find"
        aiIco.set(icon, if (active) C.FG else C.AMBER)
        aiBtn.contentDescription = when (icon) { "cancel" -> "Cancelar"; "again" -> "Procurar outra foto"; else -> "Encontrar a foto" }
        aiBtn.isEnabled = mode != "idle"; aiBtn.alpha = if (mode == "idle") .35f else 1f
        shutter.isEnabled = mode != "idle" && !busy && !capturing && p != "lock" && p != "zoom"
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
        if (p == "scan" || p == "guide" || p == "lock" || p == "zoom") { phase = "idle"; clearShot(); return }
        if (p != "locked") seen.clear()   // "different from" only applies when asking for another option on the same scene
        findShot()
    }

    private fun findShot() {
        if (busy || (mode != "live" && mode != "photo") || srcWi() == 0) return
        val photo = mode == "photo"
        resetView()
        busy = true; phase = "scan"; frame = null; note = ""; plan = null; label = ""; subj = null; scanAt = SystemClock.uptimeMillis()
        cam.trackOn = !photo; ui(); setMsg("A procurar a foto…", "")
        lifecycleScope.launch {
            try {
                val snap = (if (photo) still?.let { b -> val k = min(1f, 1600f / max(b.width, b.height)); Bitmap.createScaledBitmap(b, max(1, (b.width * k).roundToInt()), max(1, (b.height * k).roundToInt()), true) } else snapshot(1600)) ?: throw GErr("no_frame")
                val srcW = srcWi().toDouble(); val srcH = srcHi().toDouble()
                val t0 = SystemClock.uptimeMillis()
                val z0 = cam.zoom; val stops = if (photo) emptyList() else lensStops(); targetZoom = z0.toDouble(); scanZoom = z0.toDouble()
                val has5 = stops.any { abs(it - 5) < .01 }
                val look = LOCAL_LOOK[set.persona] ?: LOCAL_LOOK["criativo"]!!
                // 1. the phone proposes real, well-built crops of the scene
                val (fw, fh) = featureSize(srcW, srcH)
                val F = withContext(Dispatchers.Default) {
                    val small = Bitmap.createScaledBitmap(snap, fw, fh, true); val px = IntArray(fw * fh); small.getPixels(px, 0, fw, 0, 0, fw, fh)
                    features(px, fw, fh, null)
                }
                val s5 = if (has5) (z0 * LENS_FILL / 5.0).takeIf { it in .06..0.9 } else null
                val sizes = (listOf(.3, .42, .58, .78) + look.scales).map { cl(it, .12, .95) }.distinct()
                val cands = withContext(Dispatchers.Default) { candidates(F, ratio, sizes, s5) }
                if (cands.isEmpty()) throw GErr("no_crop")
                // 2. Gemini, as a picture editor, picks the most artistic one
                val eng = gem.engine(); var pick = -1; var who = ""
                var res: org.json.JSONObject? = null
                if (eng == "gemini") gem.maybeRecheck()
                if (eng == "gemini") {
                    if (gem.state == "quota") note = "Quota do Gemini esgotada."
                    else if (gem.state == "down" || gem.state == "badkey") note = if (gem.state == "down") "Gemini em baixo." else "Chave do Gemini inválida."
                    else {
                        setMsg("O Gemini está a escolher a melhor foto…", "")
                        val b64 = withContext(Dispatchers.Default) {
                            val sheet = contactSheet(snap, F, cands, ratio)
                            val o = ByteArrayOutputStream(); sheet.compress(Bitmap.CompressFormat.JPEG, 82, o); Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP) }
                        val prompt = gem.curatorPrompt(cands.size, seen, cands.mapIndexedNotNull { i, c -> if (c.tele) i + 1 else null })
                        var last: Throwable = GErr("g_paused"); var q = 0; var n = 0
                        for (m in gem.modelOrder()) {
                            if (gem.pausedNow(m)) { last = GErr("g_paused"); continue }
                            n++
                            try {
                                val j = parseJSON(gem.ask(b64, prompt, m, 16000))
                                val b = j.optInt("best", 0)
                                if (b < 1 || b > cands.size) throw GErr("bad_json")
                                res = j; pick = b - 1; who = m.removePrefix("gemini-")
                                if (gem.state != "up" || gem.model != m) gem.setHealth("up", m, ""); break
                            }
                            catch (e: GErr) { last = e; if (e.code == "g_429") q++; if (gem.fatalKey(e)) { gem.setHealth("badkey", "", errCopy(e)); break } }
                            catch (e: Exception) { last = e }
                        }
                        if (pick < 0) {
                            gem.at = System.currentTimeMillis()
                            if (q > 0 && q == n) gem.setQuota(errCopy(last)) else if (gem.state != "badkey") gem.setHealth("down", "", errCopy(last))
                            note = errCopy(last)
                        }
                    }
                }
                if (pick < 0) {
                    if (note.isNotEmpty() && !note.contains("IA local")) note += " Usei a IA local."
                    pick = seen.size % cands.size; who = "local"          // ✦ again walks through the next candidates
                }
                val cand = cands[pick]
                val j = res
                val filter = j?.optString("filter")?.takeIf { it in FILTER_IDS }; val border = j?.optString("border")?.takeIf { it in BORDER_IDS }
                val plan0 = Plan(null, null, cand.style, j?.optString("tip", "")?.take(70) ?: "", filter, border, j?.optString("subject", "")?.lowercase()?.take(36) ?: "")
                plan0.idea = j?.optString("idea", "")?.take(120) ?: ""
                val c = toFrame(cand.crop, F, srcW, srcH, ratio)
                // 3. the lens: the 5x when the chosen photo is the 5x size, else the main camera (up to 2.9x, then a crop)
                var lensTxt = ""
                if (stops.isNotEmpty()) {
                    val maxH = min(F.H.toDouble(), F.W / ratio); val zF = z0 * LENS_FILL / (cand.crop.ch / maxH)
                    targetZoom = if (cand.tele || (has5 && abs(ln(zF / 5.0)) < .12)) 5.0 else min(zF, MAIN_MAX).coerceAtLeast(z0.toDouble())
                    lensTxt = if (targetZoom >= 4.9) "lente 5×" else if (zF > MAIN_MAX) "principal ${fmtZ(targetZoom)} + recorte" else "principal ${fmtZ(targetZoom)}"
                }
                val dt = "%.1f".format((SystemClock.uptimeMillis() - t0) / 1000.0)
                note = (if (plan0.idea.isNotEmpty()) "${plan0.idea}  ·  " else "") + (if (note.isNotEmpty()) "$note  " else "") +
                    "$who · foto ${pick + 1} de ${cands.size} · $dt s" + (if (lensTxt.isNotEmpty()) " · $lensTxt" else "")
                j?.optString("why")?.takeIf { it.isNotEmpty() }?.let { lastMatch = "porquê: $it" }
                if (phase != "scan") return@launch     // cancelled meanwhile
                plan = plan0; subj = plan0.subj; style = plan0.rule; tip = plan0.tip.ifEmpty { LOCAL_TIP[plan0.rule] ?: "" }
                label = plan0.name.ifEmpty { plan0.subj?.name ?: "" }
                frame = c; zS = cam.zoom.toDouble(); lastCmd = zS; guideDist0 = 0f
                if (!photo) cam.tracker.setAnchor(c.x / srcW, c.y / srcH, c.w / srcW, c.h / srcH)
                seen.add(label.ifEmpty { "zona ${c.x.roundToInt()},${c.y.roundToInt()}" })
                if (photo) { phase = "locked"; lockAt = SystemClock.uptimeMillis(); setMsg(tip.ifEmpty { "Arrasta o quadrado se quiseres. Toca no obturador." }, "ok") }
                else phase = "guide"
            } catch (e: Throwable) { phase = "idle"; cam.trackOn = false; setMsg(errCopy(e), "err") }
            finally { busy = false; ui() }
        }
    }

    /** Aligned: the frame stops where it was and the preview zooms into it; then the right lens takes over. */
    private val HOLD_MS = 650L; private val ZOOM_MS = 750L
    private var zoomStart = 0L; private var zoomFrom = 1.0; private var zoomNow = 1.0

    /** Centred: hold still a moment (the frame keeps following), then zoom. */
    private fun lock() {
        if (curFrame() == null) return
        phase = "lock"; lockAt = SystemClock.uptimeMillis(); alignedSince = 0
        setMsg("Centrado — a enquadrar…", "ok"); ui()
        // remember what the frame shows right now: after the zoom we look for exactly this
        val token = lockAt; zoomRef = null
        lifecycleScope.launch {
            val g = hiGray(320) ?: return@launch
            val t = trackedFrame() ?: return@launch
            if (phase != "lock" || lockAt != token) return@launch
            val kg = g.W / cam.srcW.toDouble(); val TW = 40; val TH = max(16, (TW / ratio).roundToInt())
            zoomRef = sampleGray(g, t.x * kg, t.y * kg, t.w * kg, t.h * kg, TW, TH); zoomRefFrame = t
        }
    }
    private var zoomRef: FloatArray? = null; private var zoomRefFrame: RectD? = null
    private fun startZoom(now: Long) {
        val t = trackedFrame() ?: return
        val sw = cam.srcW.toDouble(); val sh = cam.srcH.toDouble(); val w = min(t.w, sw); val h = min(t.h, sh)
        frame = RectD(cl(t.x, 0.0, sw - w), cl(t.y, 0.0, sh - h), w, h)
        cam.trackOn = false; zoomFrom = cam.zoom.toDouble(); zoomNow = zoomFrom; zoomStart = now
        // the zoom that makes the frame fill the photo area (snapped to the lens chosen with it)
        val maxW = min(sw, sh * ratio); val zFill = zoomFrom * maxW / w
        // the lens was chosen with the frame; a tracking error must never send the zoom elsewhere (it once went to 10x)
        lastMatch = "zoom ${fmtZ(targetZoom)} (quadrado pedia ${"%.1f".format(zFill)}×)"
        phase = "zoom"; setMsg("A enquadrar a foto…", "ok"); ui()
    }
    private fun finishZoom() {
        val sw = cam.srcW.toDouble(); val sh = cam.srcH.toDouble()
        // where the frame should be after a perfect zoom about the centre
        val k = targetZoom / zoomFrom; val f0 = frame ?: return
        val ew = min(f0.w * k, min(sw, sh * ratio)); val eh = ew / ratio
        val cx = sw / 2 + (f0.x + f0.w / 2 - sw / 2) * k; val cy = sh / 2 + (f0.y + f0.h / 2 - sh / 2) * k
        frame = RectD(cl(cx - ew / 2, 0.0, sw - ew), cl(cy - eh / 2, 0.0, sh - eh), ew, eh)
        phase = "locked"; lockAt = SystemClock.uptimeMillis(); refAt = 0; refined = false
        lastMatch = "$lastMatch · sensor ${cam.lensNow()}"
        setMsg(tip.ifEmpty { "Pronto. Toca no obturador." }, "ok"); ui()
        val token = lockAt
        lifecycleScope.launch { relocate(token) }
    }

    /**
     * After the zoom, find the frame's content again in the new picture (the hand moved, the lens changed)
     * and put the frame exactly on it. This is what keeps the photo on the subject you aligned.
     */
    private suspend fun relocate(token: Long) {
        val ref = zoomRef ?: return; val r0 = zoomRefFrame ?: return
        kotlinx.coroutines.delay(200)                                  // let the last zoom step land
        val g = hiGray(320) ?: return
        if (phase != "locked" || lockAt != token) return
        val sw = cam.srcW.toDouble(); val sh = cam.srcH.toDouble(); val kg = g.W / sw
        val k = cam.zoom / zoomFrom
        val ew = r0.w * k * kg; val eh = r0.h * k * kg
        val ex = g.W / 2.0 + ((r0.x + r0.w / 2) * kg - g.W / 2.0) * k - ew / 2; val ey = g.H / 2.0 + ((r0.y + r0.h / 2) * kg - g.H / 2.0) * k - eh / 2
        val TW = 40; val TH = max(16, (TW / ratio).roundToInt())
        val loc = withContext(Dispatchers.Default) { locate(ref, TW, TH, g, ex, ey, ew, eh) }
        if (phase != "locked" || lockAt != token) return
        if (loc == null || loc.err > 28) { lastMatch = "$lastMatch · reencontro falhou (${loc?.err?.roundToInt() ?: -1})"; return }
        var w = min(loc.w / kg, min(sw, sh * ratio)); var h = w / ratio
        if (h > sh) { h = sh; w = h * ratio }
        val ncx = (loc.x + loc.w / 2) / kg; val ncy = (loc.y + loc.h / 2) / kg
        val nf = RectD(cl(ncx - w / 2, 0.0, sw - w), cl(ncy - h / 2, 0.0, sh - h), w, h)
        refFrom = frame; refAt = SystemClock.uptimeMillis(); frame = nf
        lastMatch = "$lastMatch · reencontrado (${"%.0f".format(loc.err)})"
    }

    // ---------- final touch: Gemini looks at the zoomed shot and adjusts the crop itself ----------
    private var refAt = 0L; private var refFrom: RectD? = null; private var refined = false
    private suspend fun refineShot(token: Long) {
        val sw = cam.srcW.toDouble(); val sh = cam.srcH.toDouble()
        val full = frame ?: return
        android.util.Log.i("Enquadra", "refine: snapshot")
        val snap = snapshot(1024) ?: run { setMsg(tip.ifEmpty { "Enquadrado. Tira a foto." }, "ok"); return }
        android.util.Log.i("Enquadra", "refine: snapshot ${snap.width}x${snap.height}")
        var box: RectD? = null; var t2 = ""
        if (gem.engine() == "gemini" && gem.state == "up") {
            try {
                val b64 = withContext(Dispatchers.Default) { val o = ByteArrayOutputStream(); snap.compress(Bitmap.CompressFormat.JPEG, 85, o); Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP) }
                val j = parseJSON(gem.ask(b64, gem.refinePrompt(snap.width, snap.height, ratio, label.ifEmpty { subj?.name ?: "o motivo" }), gem.model.ifEmpty { set.gmodel }, 9000))
                val a = j.optJSONArray("shot_box")
                if (a != null && a.length() >= 4) {
                    val n = { i: Int -> cl(a.optDouble(i, 0.0) / 1000, 0.0, 1.0) }
                    val y0 = min(n(0), n(2)); val x0 = min(n(1), n(3)); val y1 = max(n(0), n(2)); val x1 = max(n(1), n(3))
                    box = RectD(x0 * sw, y0 * sh, (x1 - x0) * sw, (y1 - y0) * sh)
                }
                t2 = j.optString("tip", "").take(60)
            } catch (_: Exception) {}
        }
        if (box == null) {
            // no Gemini: the local engine nudges the crop inside the view (small moves only)
            box = withContext(Dispatchers.Default) {
                val (fw, fh) = featureSize(sw, sh)
                val small = Bitmap.createScaledBitmap(snap, fw, fh, true); val px = IntArray(fw * fh); small.getPixels(px, 0, fw, 0, 0, fw, fh)
                val F = features(px, fw, fh, null)
                bestCrop(F, null, ratio, style, CropOpt(listOf(.86, .93, 1.0), fw / 2.0, fh / 2.0, .05))?.let { toFrame(it, F, sw, sh, ratio) }
            }
        }
        android.util.Log.i("Enquadra", "refine: box=$box phase=$phase")
        if (phase != "locked" || lockAt != token) return
        val b = box
        if (b != null) {
            // exact format, at least 80% of the full view, inside the view
            val maxW = min(sw, sh * ratio)
            var w = max(b.w, b.h * ratio).coerceIn(maxW * .8, maxW); var h = w / ratio
            if (h > sh) { h = sh; w = h * ratio }
            val cx = b.x + b.w / 2; val cy = b.y + b.h / 2
            val nf = RectD(cl(cx - w / 2, 0.0, sw - w), cl(cy - h / 2, 0.0, sh - h), w, h)
            val moved = abs(nf.x - full.x) + abs(nf.y - full.y) + abs(nf.w - full.w)
            if (moved > maxW * .02) { refFrom = full; refAt = SystemClock.uptimeMillis(); frame = nf; refined = true }
        }
        // the frame is final now: no more instructions to move or tilt the phone, only about the moment
        val moveWords = Regex("(?i)inclin|rod[ae]|mov|apont|diagonal|desloc|sobe|baix|aproxim|afast|enquadr|vir[ae]|esquerda|direita|cima|centr|simetri|terç|regra|compos")
        val tipNow = t2.takeIf { it.isNotBlank() && !moveWords.containsMatchIn(it) }
        setMsg(tipNow ?: "Pronto. Toca no obturador.", "ok")
        note = (if (note.isNotEmpty()) "$note · " else "") + (if (refined) "afinado" else "sem ajuste")
    }

    private fun resetView() {
        zoomed = false; applyTransform(1f, 0f, 0f); alignedSince = 0; lockAt = 0; zG = 1f; guideDist0 = 0f; zgT = 0; lastEffR = -1
        lockTrackUntil = 0; zS = homeZoom.toDouble(); lastCmd = zS
        freeze.animate().cancel(); freeze.visibility = View.GONE; freeze.setImageDrawable(null)
        if (mode == "live" && abs(cam.zoom - homeZoom) > .01f) cam.setZoom(homeZoom)
    }
    private fun clearShot() {
        phase = "idle"; frame = null; subj = null; plan = null; label = ""; cam.trackOn = false; refined = false; refAt = 0; resetView()
        if (mode == "live") setMsg("Aponta e toca em ✦ para encontrar a foto.", "dim")
        ui()
    }

    // ---------- capture ----------
    private fun onShutter() {
        if (busy || capturing || mode == "idle" || phase == "lock" || phase == "zoom") return
        if (mode == "photo") { shootStill(); return }
        val sw = cam.srcW.toDouble(); val sh = cam.srcH.toDouble(); if (sw <= 0) return
        var f = curFrame()
        if (f == null) { val w = min(sw, sh * ratio); val h = w / ratio; f = RectD((sw - w) / 2, (sh - h) / 2, w, h) }
        val w = min(f.w, sw); val h = min(f.h, sh); val r = RectD(cl(f.x, 0.0, sw - w), cl(f.y, 0.0, sh - h), w, h)
        val lensZoom = cam.zoom; val digital = sqrt(sw * sh / (w * h))
        capturing = true; ui(); setMsg("A tirar a foto…", "ok")
        shutter.animate().scaleX(.92f).scaleY(.92f).setDuration(80).withEndAction { shutter.animate().scaleX(1f).scaleY(1f).setDuration(120).start() }.start()
        val pl = plan; val st = style; val lbl0 = label
        cam.takePhoto(r, cam.srcW, cam.srcH) { photo, why ->
            capturing = false
            if (photo == null) { setMsg("A câmara não devolveu a foto ($why).", "err"); ui(); return@takePhoto }
            val eq = (26 * digital * lensZoom).roundToInt()
            val lensTxt = "lente ${fmtZ(lensZoom.toDouble())}" + cam.lensNow().let { if (it.isNotEmpty()) " · $it" else "" }
            val rec = Rec(System.currentTimeMillis(), System.currentTimeMillis(), digital * lensZoom, lbl0.ifEmpty { pl?.subj?.name ?: "" }, st,
                pl?.filter ?: "clean", pl?.border ?: "none", "$eq mm  ${photo.exif}".trim() + "|" + lensTxt, photo.bmp.width, photo.bmp.height)
            if (mode == "live") { phase = "idle"; frame = null; cam.trackOn = false; resetView(); setMsg("Guardada na galeria da app.", "ok") }
            ui()
            storeAndOpen(rec, photo.bmp, "${photo.ms} ms")
        }
    }
    /** Imported photo: cut the frame out of the original. */
    private fun shootStill() {
        val b = still ?: return
        val f = frame ?: run { findShot(); return }
        val x = f.x.roundToInt().coerceIn(0, b.width - 1); val y = f.y.roundToInt().coerceIn(0, b.height - 1)
        val w = f.w.roundToInt().coerceIn(1, b.width - x); val h = f.h.roundToInt().coerceIn(1, b.height - y)
        val crop = Bitmap.createBitmap(b, x, y, w, h)
        val rec = Rec(System.currentTimeMillis(), System.currentTimeMillis(), sqrt(b.width.toDouble() * b.height / (w * h)), label.ifEmpty { plan?.subj?.name ?: "" }, style,
            plan?.filter ?: "clean", plan?.border ?: "none", "|foto importada", w, h)
        storeAndOpen(rec, crop, "")
    }
    private fun storeAndOpen(rec: Rec, bmp: Bitmap, extra: String) {
        lifecycleScope.launch { withContext(Dispatchers.IO) { try { shots.add(bmp, rec) } catch (_: Exception) {} }; refreshThumb() }
        openResult(rec, bmp, extra)
    }

    // ---------- result: filters and borders, as in the web version ----------
    private val shots by lazy { Shots(this) }
    private var resJob: kotlinx.coroutines.Job? = null
    private var curRec: Rec? = null

    private fun infoOf(rec: Rec): ShotInfo {
        val parts = rec.lens.split("|"); val settings = parts[0].ifEmpty { "—" }; val lensDesc = parts.getOrElse(1) { "" }
        val d = java.util.Date(rec.date)
        val ds = java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.US).format(d)
        val label = rec.subj.ifEmpty { STYLE_LABEL[rec.style] ?: "Enquadra" }
        val cam = (android.os.Build.MANUFACTURER.replaceFirstChar { it.uppercase() } + " " + android.os.Build.MODEL).trim()
        return ShotInfo(cam, listOf(lensDesc, STYLE_LABEL[rec.style] ?: "").filter { it.isNotEmpty() }.joinToString(" · "), settings, ds, label, d)
    }

    private fun openResult(rec: Rec, bmp: Bitmap, extra: String) {
        resultView?.let { (root.parent as FrameLayout).removeView(it) }
        curRec = rec
        val host = root.parent as FrameLayout
        val v = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.INK); isClickable = true }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dpi(40) }
        val back = iconBtn("back") { stepBack() }
        val mp = rec.w * rec.h / 1e6
        val infoT = text(11f, C.MUTED, F.mono, listOf(rec.subj, (if (mp >= 1) "%.1f".format(mp) else "%.2f".format(mp)) + " MP", rec.lens.substringAfter("|"), extra).filter { it.isNotEmpty() }.joinToString("  ·  ")).apply { gravity = Gravity.CENTER; maxLines = 2 }
        var arm = false
        val del = iconBtn("del") {}
        del.setOnClickListener {
            if (!arm) { arm = true; del.background = bg(C.WARN, dp(21)); (del.getChildAt(0) as Icon).set("del", 0xFF1A0D05.toInt()); del.postDelayed({ arm = false; del.background = bg(C.PANEL, dp(21)); (del.getChildAt(0) as Icon).set("del", C.FG) }, 3000); setResultNote("Toca outra vez para apagar.", C.WARN); return@setOnClickListener }
            shots.delete(rec.id); curRec = null; refreshThumb(); galleryView?.let { renderGallery() }; closeResult()
        }
        head.addView(back, LinearLayout.LayoutParams(dpi(42), dpi(42))); head.addView(infoT, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dpi(10); rightMargin = dpi(10) }); head.addView(del, LinearLayout.LayoutParams(dpi(42), dpi(42)))
        val img = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        // the preview is rendered from a smaller copy; the full size only when saving
        val k = min(1f, 1600f / max(bmp.width, bmp.height))
        val base = if (k < 1f) Bitmap.createScaledBitmap(bmp, max(1, (bmp.width * k).roundToInt()), max(1, (bmp.height * k).roundToInt()), true) else bmp
        fun rerender() {
            resJob?.cancel()
            resJob = lifecycleScope.launch {
                val out = withContext(Dispatchers.Default) { render(base, rec.filter, rec.border, infoOf(rec)) }
                img.setImageBitmap(out)
            }
        }
        // filters: thumbnails made from the photo itself
        val fRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val fScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(fRow) }
        val fItems = ArrayList<Pair<String, LinearLayout>>()
        val tk = 116f / min(bmp.width, bmp.height)
        val thumbBase = Bitmap.createScaledBitmap(bmp, max(1, (bmp.width * tk).roundToInt()), max(1, (bmp.height * tk).roundToInt()), true)
        fun markF() { for ((id, it) in fItems) { val on = id == rec.filter; (it.getChildAt(0) as ImageView).background = bg(0, dp(8), if (on) C.AMBER else 0, dpi(2)); (it.getChildAt(1) as TextView).setTextColor(if (on) C.AMBER else C.MUTED) } }
        for (f in FILTERS) {
            val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setPadding(dpi(2), dpi(2), dpi(2), dpi(2)); clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(8)) } } }
            val item = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                addView(iv, LinearLayout.LayoutParams(dpi(58), dpi(58)))
                addView(text(10f, C.MUTED, F.monoMed, f.label.uppercase()).apply { letterSpacing = .06f }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpi(5) })
                setOnClickListener { rec.filter = f.id; markF(); rerender(); shots.put(rec) } }
            fRow.addView(item, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dpi(10) }); fItems.add(f.id to item)
            lifecycleScope.launch { val t = withContext(Dispatchers.Default) { filtered(thumbBase, f.id) }; iv.setImageBitmap(t) }
        }
        markF()
        // borders
        val bRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val bScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(bRow) }
        val bItems = ArrayList<Pair<String, LinearLayout>>()
        fun markB() { for ((id, it) in bItems) { val on = id == rec.border; (it.getChildAt(0) as BorderIcon).on = on; (it.getChildAt(1) as TextView).setTextColor(if (on) C.AMBER else C.MUTED) } }
        for ((id, lab) in BORDERS) {
            val item = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; minimumWidth = dpi(52)
                addView(BorderIcon(this@MainActivity, id), LinearLayout.LayoutParams(dpi(30), dpi(30)))
                addView(text(10f, C.MUTED, F.monoMed, lab).apply { letterSpacing = .04f }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpi(5) })
                setOnClickListener { rec.border = id; markB(); rerender(); shots.put(rec) } }
            bRow.addView(item, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dpi(10) }); bItems.add(id to item)
        }
        markB()
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val note = text(12f, C.MUTED, F.mono, "").oneLine(); resNote = note
        val share = text(15f, C.FG, F.ui, "Partilhar").apply { background = bg(0, dp(999), C.LINE, dpi(1)); setPadding(dpi(16), dpi(11), dpi(16), dpi(11)) }
        val save = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; background = bg(C.AMBER, dp(999)); setPadding(dpi(22), dpi(12), dpi(22), dpi(12))
            addView(Icon(this@MainActivity, "check", 0xFF1A1405.toInt(), 18f), LinearLayout.LayoutParams(dpi(18), dpi(18)).apply { rightMargin = dpi(8) })
            addView(text(15f, 0xFF1A1405.toInt(), F.uiSemi, "Guardar"))
        }
        fun export(then: (Uri) -> Unit) {
            save.isEnabled = false; share.isEnabled = false; save.alpha = .5f; setResultNote("A preparar a foto…", C.MUTED)
            lifecycleScope.launch {
                val uri = withContext(Dispatchers.Default) { try { saveJpeg(render(bmp, rec.filter, rec.border, infoOf(rec))) } catch (e: Throwable) { null } }
                save.isEnabled = true; share.isEnabled = true; save.alpha = 1f
                if (uri == null) { setResultNote("Não consegui guardar a foto.", C.WARN); return@launch }
                rec.savedUri = uri.toString(); shots.put(rec); then(uri)
            }
        }
        save.setOnClickListener { export { setMsg("Guardada na galeria (Imagens › Enquadra).", "ok"); closeResult() } }
        share.setOnClickListener { export { u -> setResultNote("", C.MUTED)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Partilhar")) } }
        bar.addView(share); bar.addView(note, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dpi(10); rightMargin = dpi(10) }); bar.addView(save)
        v.addView(head, LinearLayout.LayoutParams(-1, -2)); v.addView(img, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dpi(12) })
        v.addView(fScroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(12) }); v.addView(bScroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(12) })
        v.addView(bar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(12) })
        host.addView(v, FrameLayout.LayoutParams(-1, -1)); host.requestApplyInsets()
        v.alpha = 0f; v.animate().alpha(1f).setDuration(160).start()
        resultView = v
        if (rec.filter != "clean" || rec.border != "none") setResultNote("Look sugerido pela IA.", C.MUTED)
        rerender()
    }
    private var resNote: TextView? = null
    private fun setResultNote(t: String, c: Int) { resNote?.let { it.text = t; it.setTextColor(c) } }
    private fun closeResult() { resultView?.let { (root.parent as FrameLayout).removeView(it) }; resultView = null; curRec = null; resNote = null }
    private fun iconBtn(kind: String, click: () -> Unit) = FrameLayout(this).apply {
        background = bg(C.PANEL, dp(21)); addView(Icon(this@MainActivity, kind, C.FG), FrameLayout.LayoutParams(-1, -1)); setOnClickListener { click() } }

    private fun saveJpeg(bmp: Bitmap): Uri? = try {
        val name = "Enquadra_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date()) + ".jpg"
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name); put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Enquadra"); put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)!!
        contentResolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.JPEG, 94, it) }
        cv.clear(); cv.put(MediaStore.Images.Media.IS_PENDING, 0); contentResolver.update(uri, cv, null, null)
        uri
    } catch (e: Exception) { null }

    private fun refreshThumb() {
        lifecycleScope.launch {
            val b = withContext(Dispatchers.IO) { shots.all().firstOrNull()?.let { shots.thumb(it.id) } }
            if (b != null) { thumbImg.setImageBitmap(b); thumbImg.visibility = View.VISIBLE } else thumbImg.visibility = View.GONE
        }
    }

    // ---------- gallery: the app's own photos, grouped by day ----------
    private var galleryView: View? = null
    private var galGrid: LinearLayout? = null
    private fun openGallery() {
        val host = root.parent as FrameLayout
        val v = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.INK); isClickable = true }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dpi(40) }
        head.addView(iconBtn("back") { stepBack() }, LinearLayout.LayoutParams(dpi(42), dpi(42)))
        head.addView(text(17f, C.FG, F.uiSemi, "Galeria"), LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dpi(10) })
        head.addView(text(15f, C.FG, F.ui, "Importar").apply { background = bg(0, dp(999), C.LINE, dpi(1)); setPadding(dpi(16), dpi(11), dpi(16), dpi(11)); setOnClickListener { pickPhoto.launch("image/*") } })
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dpi(8)) }
        val scroll = ScrollView(this).apply { addView(grid) }
        v.addView(head, LinearLayout.LayoutParams(-1, -2)); v.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dpi(12) })
        v.addView(text(12f, C.MUTED, F.mono, "As fotos ficam guardadas só nesta app. Abre uma e carrega em Guardar para a teres na galeria do telemóvel."), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(8) })
        host.addView(v, FrameLayout.LayoutParams(-1, -1)); host.requestApplyInsets()
        galleryView = v; galGrid = grid
        renderGallery()
    }
    private fun renderGallery() {
        val grid = galGrid ?: return
        grid.removeAllViews()
        val recs = shots.all()
        if (recs.isEmpty()) { grid.addView(text(15f, C.MUTED, F.ui, "Ainda não há fotos. Tira uma com a câmara da app.").apply { gravity = Gravity.CENTER; setPadding(dpi(12), dpi(40), dpi(12), dpi(40)) }, LinearLayout.LayoutParams(-1, -2)); return }
        val dayFmt = java.text.SimpleDateFormat("EEEE, d 'de' MMMM", java.util.Locale("pt", "PT")); val key = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
        val today = key.format(java.util.Date()); val yest = key.format(java.util.Date(System.currentTimeMillis() - 86400000L))
        val hm = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
        var lastDay = ""; var row: LinearLayout? = null
        for (r in recs) {
            val d = java.util.Date(r.date); val k = key.format(d)
            if (k != lastDay) {
                lastDay = k; row = null
                val name = if (k == today) "Hoje" else if (k == yest) "Ontem" else dayFmt.format(d)
                grid.addView(text(11f, C.MUTED, F.mono, name.uppercase()).apply { letterSpacing = .08f; setPadding(0, dpi(if (grid.childCount == 0) 0 else 14), 0, dpi(8)) }, LinearLayout.LayoutParams(-1, -2))
            }
            val cur0 = row
            val rw: LinearLayout = if (cur0 == null || cur0.childCount >= 2) LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }.also { grid.addView(it, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dpi(18) }) } else cur0
            row = rw
            val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val ph = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = bg(C.PANEL, dp(12)); clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(12)) } } }
            val cardW = (resources.displayMetrics.widthPixels - dpi(32) - dpi(12)) / 2
            card.addView(ph, LinearLayout.LayoutParams(-1, (cardW * 1.25f).roundToInt()))
            card.addView(text(13f, C.FG, F.uiMed, r.subj.ifEmpty { "foto" }).oneLine(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(7) })
            card.addView(text(11f, C.MUTED, F.mono, hm.format(d) + (if (r.zoom > 0) "  ·  ${(26 * r.zoom).roundToInt()} mm" else "")), LinearLayout.LayoutParams(-1, -2))
            card.setOnClickListener { val b = shots.load(r.id); if (b != null) openResult(r, b, "") else setMsg("Não consegui abrir essa foto.", "err") }
            val second = rw.childCount == 1
            rw.addView(card, LinearLayout.LayoutParams(0, -2, 1f).apply { if (second) leftMargin = dpi(12) })
            lifecycleScope.launch { val t = withContext(Dispatchers.IO) { shots.thumb(r.id) }; ph.setImageBitmap(t) }
        }
        val last = row
        if (last != null && last.childCount == 1) last.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = dpi(12) })
    }

    // ---------- photo mode: frame a photo from the phone's gallery ----------
    private val pickPhoto = registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) importPhoto(uri) }
    private fun importPhoto(uri: Uri) {
        lifecycleScope.launch {
            val b = withContext(Dispatchers.IO) {
                try { android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(contentResolver, uri)) { dec, info, _ ->
                    val s = info.size; val k = min(1f, 4096f / max(s.width, s.height))
                    if (k < 1f) dec.setTargetSize((s.width * k).roundToInt(), (s.height * k).roundToInt())
                    dec.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE } } catch (e: Exception) { null }
            }
            if (b == null) { setMsg("Não consegui abrir essa imagem.", "err"); return@launch }
            galleryView?.let { (root.parent as FrameLayout).removeView(it) }; galleryView = null; galGrid = null
            clearShot()
            still = b; mode = "photo"; stillView.setImageBitmap(b); stillView.visibility = View.VISIBLE; pv.alpha = 0f
            seen.clear(); ui(); findShot()
        }
    }
    private fun leavePhoto() {
        still = null; stillView.setImageDrawable(null); stillView.visibility = View.GONE; pv.alpha = 1f
        mode = if (cam.srcW > 0) "live" else "idle"; clearShot()
    }
    private var drag: FloatArray? = null
    @SuppressLint("ClickableViewAccessibility")
    private fun installDrag() {
        ov.setOnTouchListener { _, e ->
            if (mode != "photo" || busy) return@setOnTouchListener false
            val f = frame ?: return@setOnTouchListener false
            val L = layout()
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    val fx = L.ox + f.x * L.s; val fy = L.oy + f.y * L.s
                    if (e.x < fx || e.y < fy || e.x > fx + f.w * L.s || e.y > fy + f.h * L.s) return@setOnTouchListener false
                    drag = floatArrayOf(e.x, e.y, f.x.toFloat(), f.y.toFloat()); true
                }
                android.view.MotionEvent.ACTION_MOVE -> { val d = drag ?: return@setOnTouchListener false
                    frame = RectD(cl(d[2] + (e.x - d[0]) / L.s.toDouble(), 0.0, srcWi() - f.w), cl(d[3] + (e.y - d[1]) / L.s.toDouble(), 0.0, srcHi() - f.h), f.w, f.h); true }
                else -> { drag = null; true }
            }
        }
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
        val lensOut = outText().apply { setTextIsSelectable(true) }
        body.addView(field(lbl("Teste das lentes"), para("Passa por cada zoom e mostra que sensor o telemóvel usa de facto. Se o 5× disser o mesmo sensor que o 3×, a Samsung não dá a lente 5× a esta app."),
            btn("Testar lentes") {
                lensOut.setTextColor(C.MUTED); lensOut.text = "A testar…"
                lifecycleScope.launch {
                    val zs = listOf(cam.minZoom.toDouble(), 1.0, 2.0, 2.9, 3.0, 4.0, 5.0, 7.0, 10.0).filter { it >= cam.minZoom - 1e-3 && it <= cam.maxZoom + 1e-3 }.distinct()
                    val sb = StringBuilder()
                    for (z in zs) {
                        suspendCancellableCoroutine<Unit> { c -> cam.setZoom(z.toFloat()) { if (c.isActive) c.resume(Unit) } }
                        kotlinx.coroutines.delay(900)
                        sb.append("${"%.1f".format(z)}× → ${cam.lensNow().ifEmpty { "sensor ?" }}\n"); lensOut.text = sb.toString()
                    }
                    cam.setZoom(homeZoom); lensOut.setTextColor(C.FG); lensOut.text = sb.toString() + "sensores: ${cam.physList}\ncâmara lógica:${cam.logicalFullRes}"
                }
            }.apply { layoutParams = LinearLayout.LayoutParams(-2, -2) }, lensOut), lp(20))
        val physOut = outText().apply { setTextIsSelectable(true) }
        body.addView(field(lbl("Teste: tele 5× direta"), para("Tenta tirar uma foto diretamente do sensor 7 (a periscópica 5×), sem passar pelo zoom. A câmara desliga-se por um momento."),
            btn("Testar sensor 7") {
                physOut.setTextColor(C.MUTED); physOut.text = "A testar…"
                val id = cam.physList.split(", ").maxByOrNull { Regex("≈(\\d+) mm").find(it)?.groupValues?.get(1)?.toIntOrNull() ?: 0 }?.substringBefore(" ")?.ifEmpty { null } ?: "7"
                cam.testPhysical(this, pv, id, restore = { startCamera() }) { r -> physOut.setTextColor(C.FG); physOut.text = r + "\n(o sensor 6, a 3×, tem ≈66 mm; o 7 tem ≈113 mm)" }
            }.apply { layoutParams = LinearLayout.LayoutParams(-2, -2) }, physOut), lp(20))
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
        resultView?.let { closeResult(); galleryView?.let { renderGallery() }; return }
        galleryView?.let { host.removeView(it); galleryView = null; galGrid = null; return }
        if (mode == "photo") { leavePhoto(); return }
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
