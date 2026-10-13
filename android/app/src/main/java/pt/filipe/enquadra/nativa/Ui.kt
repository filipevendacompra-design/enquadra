package pt.filipe.enquadra.nativa

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.PathParser

/** The look of the web version: one dark theme, amber for the AI, green when it is right. */
object C {
    const val INK = 0xFF000000.toInt(); const val PANEL = 0xFF141414.toInt(); const val RAISE = 0xFF232323.toInt()
    const val LINE = 0x1FFFFFFF; const val FG = 0xFFF2F0EA.toInt(); const val MUTED = 0xFF8E8B84.toInt()
    const val AMBER = 0xFFF5C531.toInt(); const val LOCK = 0xFF7BD88F.toInt(); const val WARN = 0xFFFF8A5B.toInt()
    const val UP = 0xFF34C759.toInt(); const val DOWN = 0xFFFF453A.toInt()
}

object F {
    lateinit var ui: Typeface; lateinit var uiMed: Typeface; lateinit var uiSemi: Typeface; lateinit var mono: Typeface; lateinit var monoMed: Typeface
    fun load(c: Context) {
        fun f(id: Int, fb: Typeface) = try { ResourcesCompat.getFont(c, id) ?: fb } catch (e: Exception) { fb }
        ui = f(R.font.grotesk_regular, Typeface.SANS_SERIF); uiMed = f(R.font.grotesk_medium, Typeface.SANS_SERIF)
        uiSemi = f(R.font.grotesk_semibold, Typeface.DEFAULT_BOLD); mono = f(R.font.mono_regular, Typeface.MONOSPACE); monoMed = f(R.font.mono_medium, Typeface.MONOSPACE)
    }
}

fun Context.dp(v: Number): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics)
fun Context.dpi(v: Number): Int = Math.round(dp(v))

fun bg(color: Int, radius: Float, stroke: Int = 0, strokeW: Int = 0) = GradientDrawable().apply {
    setColor(color); cornerRadius = radius; if (strokeW > 0) setStroke(strokeW, stroke)
}

fun Context.text(size: Float, color: Int, tf: Typeface, s: String = ""): TextView = TextView(this).apply {
    setTextSize(TypedValue.COMPLEX_UNIT_SP, size); setTextColor(color); typeface = tf; text = s; includeFontPadding = false
}
fun TextView.oneLine(): TextView { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; return this }

/** Stroked icons drawn from the same 24x24 SVG paths as the web version. */
class Icon(ctx: Context, var kind: String, var color: Int = C.MUTED, private val sizeDp: Float = 22f) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.8f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    companion object {
        private val cache = HashMap<String, Path>()
        fun path(d: String): Path = cache.getOrPut(d) { PathParser.createPathFromPathData(d) }
        val PATHS = mapOf(
            "back" to listOf("M15 5l-7 7 7 7"),
            "level" to listOf("M3 12h7M14 12h7"),
            "gallery" to listOf("M4 16l5-5 4 4 3-3 4 4"),
            "find" to listOf("M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8z", "M19 15l.8 2.2L22 18l-2.2.8L19 21l-.8-2.2L16 18l2.2-.8z"),
            "cancel" to listOf("M6 6l12 12M18 6L6 18"),
            "again" to listOf("M20 12a8 8 0 1 1-2.3-5.6", "M20 4v5h-5"),
            "check" to listOf("M5 12l5 5 9-10"),
            "del" to listOf("M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13")
        )
    }
    fun set(k: String, c: Int) { if (k != kind || c != color) { kind = k; color = c; invalidate() } }
    override fun onDraw(cv: Canvas) {
        val s = context.dp(sizeDp) / 24f
        cv.save(); cv.translate((width - context.dp(sizeDp)) / 2, (height - context.dp(sizeDp)) / 2); cv.scale(s, s)
        p.color = color; p.style = Paint.Style.STROKE
        when (kind) {
            "grid" -> for ((x, y) in listOf(4f to 4f, 13f to 4f, 4f to 13f, 13f to 13f)) cv.drawRoundRect(x, y, x + 7, y + 7, 1.5f, 1.5f, p)
            "dots" -> { p.style = Paint.Style.FILL_AND_STROKE; p.strokeWidth = 1.8f; for (x in listOf(5f, 12f, 19f)) cv.drawCircle(x, 12f, 1.3f, p); p.style = Paint.Style.STROKE }
            "gallery" -> { cv.drawRoundRect(4f, 5f, 20f, 19f, 2f, 2f, p); for (d in PATHS["gallery"]!!) cv.drawPath(path(d), p) }
            "level" -> { for (d in PATHS["level"]!!) cv.drawPath(path(d), p); cv.drawCircle(12f, 12f, 1.2f, p) }
            else -> PATHS[kind]?.forEach { cv.drawPath(path(it), p) }
        }
        cv.restore()
    }
}

/** The shutter: a ring with a disc that shrinks while pressed, green when the frame is locked. */
@SuppressLint("ClickableViewAccessibility")
class Shutter(ctx: Context) : View(ctx) {
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG)
    private var col = C.FG; private var press = 1f
    var ok = false
        set(v) { if (field != v) { field = v; val from = col; val to = if (v) C.LOCK else C.FG
            ValueAnimator.ofArgb(from, to).apply { duration = 200; addUpdateListener { col = it.animatedValue as Int; invalidate() } }.start() } }
    init {
        setOnTouchListener { _, e ->
            when (e.actionMasked) { MotionEvent.ACTION_DOWN -> { press = .9f; invalidate() }; MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { press = 1f; invalidate() } }
            false
        }
    }
    override fun setEnabled(enabled: Boolean) { super.setEnabled(enabled); alpha = if (enabled) 1f else .35f }
    override fun onDraw(cv: Canvas) {
        val sw = context.dp(4); val pad = context.dp(5); val cx = width / 2f; val cy = height / 2f; val r = minOf(width, height) / 2f
        ring.strokeWidth = sw; ring.color = col; disc.color = col
        cv.drawCircle(cx, cy, r - sw / 2, ring)
        cv.drawCircle(cx, cy, (r - sw - pad) * press, disc)
    }
}

/** A row of choices, like a <select>, styled as the web version's fields. */
class Choice(ctx: Context, items: List<Pair<String, String>>, sel: String, val onPick: (String) -> Unit) : android.widget.LinearLayout(ctx) {
    private val rows = ArrayList<Pair<String, TextView>>()
    var value = sel; private set
    init {
        orientation = VERTICAL
        for ((id, label) in items) {
            val t = ctx.text(14f, C.FG, F.ui, label).apply {
                setPadding(ctx.dpi(12), ctx.dpi(11), ctx.dpi(12), ctx.dpi(11)); gravity = Gravity.CENTER_VERTICAL
                setOnClickListener { pick(id); onPick(id) }
            }
            addView(t, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = ctx.dpi(6) })
            rows.add(id to t)
        }
        pick(sel)
    }
    fun pick(id: String) {
        value = id
        for ((k, t) in rows) { val on = k == id
            t.background = bg(C.PANEL, context.dp(10), if (on) C.AMBER else C.LINE, context.dpi(if (on) 1.5 else 1))
            t.setTextColor(if (on) C.FG else C.MUTED) }
    }
}
