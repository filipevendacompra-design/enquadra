package pt.filipe.enquadra.nativa

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Size
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The camera, in one native layer: preview on screen, a small analysis stream for tracking and for the AI,
 * and full-resolution photos. ViewPort makes all three see exactly what the screen shows.
 * "Source" coordinates = the analysis image, upright and cropped to what the preview shows.
 */
class Cam(private val ctx: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    private val shotExec = Executors.newSingleThreadExecutor()
    var camera: Camera? = null; private set
    private var capture: ImageCapture? = null
    private var analysis: ImageAnalysis? = null
    val tracker = Tracker()

    @Volatile var srcW = 0; @Volatile var srcH = 0      // upright, cropped analysis frame
    @Volatile var seq = 0L                               // frames seen
    @Volatile var lastError = ""
    @Volatile var trackOn = false

    private var wantSnap: Pair<Int, (Bitmap?) -> Unit>? = null
    private var wantHiGray: Pair<Int, (Gray?) -> Unit>? = null
    private val lock = Any()

    val minZoom get() = camera?.cameraInfo?.zoomState?.value?.minZoomRatio ?: 1f
    val maxZoom get() = camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1f
    val zoom get() = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f

    fun info(): String {
        val c = capture?.resolutionInfo?.resolution; val a = analysis?.resolutionInfo?.resolution
        return "sensores: ${physList.ifEmpty { "?" }}\ncâmara lógica:$logicalFullRes\nagora: ${lensNow().ifEmpty { "?" }} a ${"%.1f".format(zoom)}×\n" +
            (if (exactZoom) "zoom por frame: sim · " else "zoom por frame: não ($zoomResults relatórios) · ") + "foto ${c?.width ?: "?"}×${c?.height ?: "?"} · análise ${a?.width ?: "?"}×${a?.height ?: "?"} · zoom ${"%.2f".format(minZoom)}–${"%.1f".format(maxZoom)}" +
            (if (tracker.anchorErr >= 0) " · âncora ${"%.0f".format(tracker.anchorErr)}" else "") +
            (if (lastError.isNotEmpty()) "\nerro: $lastError" else "")
    }

    fun start(owner: LifecycleOwner, pv: PreviewView, onReady: () -> Unit, onFail: (String) -> Unit) {
        val f = ProcessCameraProvider.getInstance(ctx)
        f.addListener({
            try {
                val provider = f.get()
                val vp = pv.viewPort ?: throw IllegalStateException("sem viewport")
                val preview = Preview.Builder()
                    .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build())
                    .build().also { it.setSurfaceProvider(pv.surfaceProvider) }
                val anB = ImageAnalysis.Builder()
                    .setResolutionSelector(ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build())
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                listenZoom(anB)
                val an = anB.build()
                an.setAnalyzer(exec) { img -> try { onFrame(img) } catch (e: Throwable) { lastError = "análise: ${e.message}" } finally { img.close() } }
                val cap = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setResolutionSelector(ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY).build())
                    .setJpegQuality(95)
                    .build()
                val group = UseCaseGroup.Builder().setViewPort(vp).addUseCase(preview).addUseCase(an).addUseCase(cap).build()
                provider.unbindAll()
                camera = try {
                    provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, group)
                } catch (e: Exception) {
                    // some phones refuse the three streams together at full size: drop the photo stream size
                    lastError = "bind: ${e.message}"
                    provider.unbindAll()
                    val cap2 = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).setJpegQuality(95).build()
                    val g2 = UseCaseGroup.Builder().setViewPort(vp).addUseCase(preview).addUseCase(an).addUseCase(cap2).build()
                    capture = cap2
                    provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, g2)
                }
                if (capture == null) capture = cap
                analysis = an
                loadPhysical()
                onReady()
            } catch (e: Throwable) { lastError = e.message ?: e.toString(); onFail(lastError) }
        }, ContextCompat.getMainExecutor(ctx))
    }

    // ---- the real zoom of each frame, from the camera's own reports (frames lag the zoom command) ----
    private val zoomAt = java.util.concurrent.ConcurrentSkipListMap<Long, Float>()
    @Volatile var zoomResults = 0
    @Volatile var exactZoom = false
    @androidx.annotation.OptIn(markerClass = [androidx.camera.camera2.interop.ExperimentalCamera2Interop::class])
    private fun listenZoom(b: ImageAnalysis.Builder) {
        if (android.os.Build.VERSION.SDK_INT < 30) return
        androidx.camera.camera2.interop.Camera2Interop.Extender(b).setSessionCaptureCallback(object : android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(s: android.hardware.camera2.CameraCaptureSession, r: android.hardware.camera2.CaptureRequest, res: android.hardware.camera2.TotalCaptureResult) {
                res.get(android.hardware.camera2.CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)?.let { activePhys = it }
                val ts = res.get(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP) ?: return
                val z = res.get(android.hardware.camera2.CaptureResult.CONTROL_ZOOM_RATIO) ?: return
                zoomAt[ts] = z; zoomResults++
                if (zoomAt.size > 90) zoomAt.headMap(ts - 2_000_000_000L).clear()
            }
        })
    }
    private fun frameZoom(ts: Long): Float? {
        zoomAt[ts]?.let { return it }
        val lo = zoomAt.floorEntry(ts); val hi = zoomAt.ceilingEntry(ts)
        val best = listOfNotNull(lo, hi).minByOrNull { kotlin.math.abs(it.key - ts) } ?: return null
        return if (kotlin.math.abs(best.key - ts) < 8_000_000L) best.value else null   // within 8 ms: same frame, other clock rounding
    }

    // ---- which physical camera (sensor) is really in use: tells a true 5x lens from a crop of the 3x ----
    @Volatile var activePhys = ""
    private val physDesc = HashMap<String, String>()
    @androidx.annotation.OptIn(markerClass = [androidx.camera.camera2.interop.ExperimentalCamera2Interop::class])
    private fun loadPhysical() {
        try {
            val id = androidx.camera.camera2.interop.Camera2CameraInfo.from(camera!!.cameraInfo).cameraId
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val ids = cm.getCameraCharacteristics(id).physicalCameraIds
            for (p in ids) {
                val ch = cm.getCameraCharacteristics(p)
                val f = ch.get(android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: continue
                val sz = ch.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                val eq = if (sz != null) (f * 43.27 / Math.hypot(sz.width.toDouble(), sz.height.toDouble())).roundToInt() else 0
                val px = ch.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                physDesc[p] = "≈${eq} mm" + (if (px != null) " ${"%.0f".format(px.width * px.height / 1e6)} MP" else "") + fullRes(ch)
            }
            physList = ids.sortedBy { physDesc[it] ?: "" }.joinToString(", ") { "$it ${physDesc[it]}" }
            logicalFullRes = fullRes(cm.getCameraCharacteristics(id)).ifEmpty { " (Android antigo)" }
        } catch (e: Throwable) { physList = "?" }
    }
    var physList = ""; private set
    var logicalFullRes = ""; private set
    /** Does this camera offer its full-resolution mode (the "50 MP" of the Samsung app) to other apps? */
    private fun fullRes(ch: android.hardware.camera2.CameraCharacteristics): String {
        if (android.os.Build.VERSION.SDK_INT < 31) return ""
        val caps = ch.get(android.hardware.camera2.CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: return ""
        if (!caps.contains(android.hardware.camera2.CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR)) return " (sem modo resolução total)"
        val m = ch.get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
        val big = m?.getOutputSizes(android.graphics.ImageFormat.JPEG)?.maxByOrNull { it.width.toLong() * it.height }
            ?: m?.getOutputSizes(android.graphics.ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width.toLong() * it.height }
        return if (big != null) " (resolução total ${big.width}×${big.height})" else " (resolução total: sim)"
    }
    fun lensNow(): String = if (activePhys.isEmpty()) "" else "sensor $activePhys ${physDesc[activePhys] ?: ""}".trim()

    /**
     * Experiment: take one photo straight from a physical sensor (e.g. "7", the 5x periscope), bypassing the zoom.
     * Rebinds the camera for the shot and restores it after. cb(text) gets what happened.
     */
    @androidx.annotation.OptIn(markerClass = [androidx.camera.camera2.interop.ExperimentalCamera2Interop::class])
    fun testPhysical(owner: LifecycleOwner, pv: PreviewView, physId: String, restore: () -> Unit, cb: (String) -> Unit) {
        val f = ProcessCameraProvider.getInstance(ctx)
        f.addListener({
            val provider = f.get()
            try {
                val vp = pv.viewPort ?: throw IllegalStateException("sem viewport")
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }
                val cb2 = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).setJpegQuality(95)
                androidx.camera.camera2.interop.Camera2Interop.Extender(cb2).setPhysicalCameraId(physId)
                val cap = cb2.build()
                provider.unbindAll()
                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, UseCaseGroup.Builder().setViewPort(vp).addUseCase(preview).addUseCase(cap).build())
                val t0 = System.currentTimeMillis()
                cap.takePicture(shotExec, object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        var txt: String
                        try {
                            val bb = image.planes[0].buffer; bb.rewind(); val bytes = ByteArray(bb.remaining()); bb.get(bytes)
                            val ex = android.media.ExifInterface(java.io.ByteArrayInputStream(bytes))
                            val fl = ex.getAttributeDouble(android.media.ExifInterface.TAG_FOCAL_LENGTH, -1.0)
                            val f35 = ex.getAttributeInt(android.media.ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, -1)
                            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                            txt = "sensor $physId: foto ${o.outWidth}×${o.outHeight}, focal ${"%.2f".format(fl)} mm" + (if (f35 > 0) " (≈$f35 mm)" else "") + ", ${System.currentTimeMillis() - t0} ms"
                        } catch (e: Throwable) { txt = "sensor $physId: foto chegou mas não a li (${e.message})" } finally { image.close() }
                        main.post { cb(txt); restore() }
                    }
                    override fun onError(exc: ImageCaptureException) { main.post { cb("sensor $physId: a câmara recusou (${exc.message})"); restore() } }
                })
            } catch (e: Throwable) { cb("sensor $physId: não foi possível ligar (${e.message})"); restore() }
        }, ContextCompat.getMainExecutor(ctx))
    }

    fun setZoom(z: Float, done: (() -> Unit)? = null) {
        val c = camera ?: run { done?.invoke(); return }
        val fut = c.cameraControl.setZoomRatio(z.coerceIn(minZoom, maxZoom))
        fut.addListener({ done?.invoke() }, ContextCompat.getMainExecutor(ctx))
    }

    /** A sharp colour frame of what the screen shows, longest side `max`. */
    fun snapshot(maxSide: Int, cb: (Bitmap?) -> Unit) { synchronized(lock) { wantSnap = Pair(maxSide, cb) } }
    /** A grey frame `w` pixels wide, for finding the frame after a lens change. */
    fun hiGray(w: Int, cb: (Gray?) -> Unit) { synchronized(lock) { wantHiGray = Pair(w, cb) } }

    // ---- analysis thread ----
    private var ybuf = ByteArray(0)
    private var zPrevFrame = 0.0
    private var zRef = 0.0
    private var zPrevExact = false
    private var anchorTick = 0

    private fun onFrame(img: ImageProxy) {
        val crop = img.cropRect; val rot = img.imageInfo.rotationDegrees
        val upW = if (rot % 180 == 0) crop.width() else crop.height()
        val upH = if (rot % 180 == 0) crop.height() else crop.width()
        srcW = upW; srcH = upH
        val plane = img.planes[0]; val bb = plane.buffer; bb.rewind()
        if (ybuf.size < bb.remaining()) ybuf = ByteArray(bb.remaining())
        bb.get(ybuf, 0, bb.remaining())
        val rs = plane.rowStride; val ps = plane.pixelStride
        val sn: Pair<Int, (Bitmap?) -> Unit>?; val hg: Pair<Int, (Gray?) -> Unit>?
        synchronized(lock) { sn = wantSnap; wantSnap = null; hg = wantHiGray; wantHiGray = null }
        if (trackOn || sn != null) {
            val g = yGray(crop, rot, upW, upH, rs, ps, 128)
            val fz = frameZoom(img.imageInfo.timestamp)
            exactZoom = fz != null
            val zNow = (fz ?: zoom).toDouble()
            if (sn != null || tracker.resetPending) {   // tracking starts from the frame the AI sees
                tracker.reset(); zRef = zNow; tracker.clearAnchor()
                tracker.refHi = yGray(crop, rot, upW, upH, rs, ps, 256)
            }
            tracker.step(g, if (zPrevFrame > 0) zNow / zPrevFrame else 1.0, if (zRef > 0) zNow / zRef else 1.0, fz != null)
            zPrevFrame = zNow
            if (tracker.hasAnchor && (++anchorTick % 3 == 0)) tracker.correct(yGray(crop, rot, upW, upH, rs, ps, 256)); zPrevExact = fz != null
        }
        if (hg != null) { val g = yGray(crop, rot, upW, upH, rs, ps, hg.first); main.post { hg.second(g) } }
        if (sn != null) {
            var out: Bitmap? = null
            try {
                val full = img.toBitmap()
                val k = min(1f, sn.first.toFloat() / max(upW, upH))
                val m = Matrix().apply { postRotate(rot.toFloat()); postScale(k, k) }
                out = Bitmap.createBitmap(full, crop.left, crop.top, crop.width(), crop.height(), m, true)
            } catch (e: Throwable) { lastError = "snapshot: ${e.message}" }
            val o = out; main.post { sn.second(o) }
        }
        seq++
    }

    /** Upright grey of the cropped Y plane, outW wide, each cell the mean of 3x3 taps. */
    private fun yGray(crop: Rect, rot: Int, upW: Int, upH: Int, rs: Int, ps: Int, outW: Int): Gray {
        val W = outW; val H = max(32, (outW.toDouble() * upH / upW).roundToInt())
        val g = FloatArray(W * H); val su = upW.toDouble() / W; val sv = upH.toDouble() / H
        val cw = crop.width(); val chh = crop.height(); val ox = crop.left; val oy = crop.top
        val buf = ybuf
        for (j in 0 until H) for (i in 0 until W) {
            var acc = 0
            for (b in 0 until 3) for (a in 0 until 3) {
                val u = ((i + (a + .5) / 3) * su).toInt().coerceIn(0, upW - 1)
                val v = ((j + (b + .5) / 3) * sv).toInt().coerceIn(0, upH - 1)
                val x: Int; val y: Int
                when (rot) { 90 -> { x = v; y = chh - 1 - u }; 180 -> { x = cw - 1 - u; y = chh - 1 - v }; 270 -> { x = cw - 1 - v; y = u }; else -> { x = u; y = v } }
                acc += buf[(oy + y) * rs + (ox + x) * ps].toInt() and 255
            }
            g[j * W + i] = acc / 9f
        }
        return Gray(g, W, H)
    }

    // ---- photo ----
    class Photo(val bmp: Bitmap, val fullW: Int, val fullH: Int, val ms: Long, val exif: String = "")

    /** Takes a full-resolution photo and cuts out the frame (r in source coordinates, sw x sh). */
    fun takePhoto(r: RectD, sw: Int, sh: Int, cb: (Photo?, String) -> Unit) {
        val cap = capture ?: run { cb(null, "sem câmara"); return }
        val t0 = System.currentTimeMillis()
        cap.takePicture(shotExec, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                var res: Photo? = null; var why = ""
                try {
                    val bb = image.planes[0].buffer; bb.rewind(); val bytes = ByteArray(bb.remaining()); bb.get(bytes)
                    val crop = image.cropRect; val rot = image.imageInfo.rotationDegrees
                    // the JPEG may be smaller/larger than ImageProxy says on some phones: scale the crop to the real size
                    val bo = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bo)
                    val jw = bo.outWidth; val jh = bo.outHeight
                    val kx = jw.toDouble() / image.width; val ky = jh.toDouble() / image.height
                    val cw = crop.width() * kx; val chh = crop.height() * ky; val ox = crop.left * kx; val oy = crop.top * ky
                    val upW = if (rot % 180 == 0) cw else chh; val upH = if (rot % 180 == 0) chh else cw
                    // frame in upright crop pixels
                    val u0 = r.x / sw * upW; val v0 = r.y / sh * upH; val u1 = (r.x + r.w) / sw * upW; val v1 = (r.y + r.h) / sh * upH
                    fun map(u: Double, v: Double): Pair<Double, Double> = when (rot) {
                        90 -> Pair(v, chh - u); 180 -> Pair(cw - u, chh - v); 270 -> Pair(cw - v, u); else -> Pair(u, v) }
                    val a = map(u0, v0); val b = map(u1, v1)
                    val rx0 = (ox + min(a.first, b.first)).roundToInt().coerceIn(0, jw - 1)
                    val ry0 = (oy + min(a.second, b.second)).roundToInt().coerceIn(0, jh - 1)
                    val rx1 = (ox + max(a.first, b.first)).roundToInt().coerceIn(rx0 + 1, jw)
                    val ry1 = (oy + max(a.second, b.second)).roundToInt().coerceIn(ry0 + 1, jh)
                    @Suppress("DEPRECATION")
                    val dec = BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)!!
                    val part = dec.decodeRegion(Rect(rx0, ry0, rx1, ry1), BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
                    dec.recycle()
                    val out = if (rot != 0) Bitmap.createBitmap(part, 0, 0, part.width, part.height, Matrix().apply { postRotate(rot.toFloat()) }, true).also { if (it !== part) part.recycle() } else part
                    // exposure, for the "Ficha técnica" border
                    val ex = try { android.media.ExifInterface(java.io.ByteArrayInputStream(bytes)) } catch (e: Exception) { null }
                    val parts = ArrayList<String>()
                    ex?.getAttributeDouble(android.media.ExifInterface.TAG_F_NUMBER, 0.0)?.takeIf { it > 0 }?.let { parts.add("f/" + "%.1f".format(java.util.Locale.US, it).removeSuffix(".0")) }
                    ex?.getAttributeDouble(android.media.ExifInterface.TAG_EXPOSURE_TIME, 0.0)?.takeIf { it > 0 }?.let { parts.add(if (it >= 1) "%.1fs".format(java.util.Locale.US, it) else "1/${(1 / it).roundToInt()}s") }
                    ex?.getAttributeInt(android.media.ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0)?.takeIf { it > 0 }?.let { parts.add("ISO $it") }
                    res = Photo(out, if (rot % 180 == 0) jw else jh, if (rot % 180 == 0) jh else jw, System.currentTimeMillis() - t0, parts.joinToString("  "))
                } catch (e: Throwable) { why = e.message ?: e.toString() } finally { image.close() }
                val rr = res; val w = why; main.post { cb(rr, w) }
            }
            override fun onError(exc: ImageCaptureException) { val m = exc.message ?: "erro"; main.post { cb(null, m) } }
        })
    }

    fun shutdown() { exec.shutdown(); shotExec.shutdown() }
}
