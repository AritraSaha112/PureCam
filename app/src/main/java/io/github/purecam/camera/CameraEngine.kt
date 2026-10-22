package io.github.purecam.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ExifInterface
import android.media.Image
import android.media.ImageReader
import android.media.MediaActionSound
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class LensCaps(
    val manualSensor: Boolean = false,
    val isoRange: IntRange = 100..100,
    val expRange: LongRange = 1_000_000L..1_000_000L,
    val minFocus: Float = 0f,
    val evRange: IntRange = 0..0,
    val evStep: Float = 1f,
    val flash: Boolean = false,
    val raw: Boolean = false,
    val awbModes: List<Int> = listOf(CameraMetadata.CONTROL_AWB_MODE_AUTO),
    val maxZoom: Float = 1f,
)

/** All state the UI draws. Written from the camera thread and read by Compose. */
class CamUi {
    var lenses by mutableStateOf<List<Lens>>(emptyList())
    var blocked by mutableStateOf<Set<String>>(emptySet())
    var lens by mutableStateOf<Lens?>(null)
    var caps by mutableStateOf(LensCaps())
    var previewAspect by mutableFloatStateOf(0.75f)
    var ready by mutableStateOf(false)
    var capturing by mutableStateOf(false)
    var countdown by mutableIntStateOf(0)
    var shutterBlink by mutableIntStateOf(0)
    var thumb by mutableStateOf<Bitmap?>(null)
    var lastUri by mutableStateOf<Uri?>(null)
    var focusPoint by mutableStateOf<Offset?>(null)
    var focusLocked by mutableStateOf(false)
    var zoom by mutableFloatStateOf(1f)
    var liveIso by mutableIntStateOf(0)
    var liveExposure by mutableLongStateOf(0L)
    var rotation by mutableIntStateOf(0)

    // Pro (manual) controls; null = automatic
    var pro by mutableStateOf(false)
    var iso by mutableStateOf<Int?>(null)
    var exposure by mutableStateOf<Long?>(null)
    var focus by mutableStateOf<Float?>(null)
    var awb by mutableIntStateOf(CameraMetadata.CONTROL_AWB_MODE_AUTO)
    var ev by mutableIntStateOf(0)

    // Settings
    var natural by mutableStateOf(true)
    var raw by mutableStateOf(false)
    var grid by mutableStateOf(true)
    var sound by mutableStateOf(true)
    var volumeShutter by mutableStateOf(true)
    var uwCorrection by mutableStateOf(false)
    var flash by mutableStateOf(Flash.OFF)
    var timer by mutableIntStateOf(0)
}

class CameraEngine(private val app: Context) {
    private val cm = app.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("PureCam-camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }
    private val scope = CoroutineScope(SupervisorJob() + handler.asCoroutineDispatcher())
    private val mutex = Mutex()
    private val main = Handler(Looper.getMainLooper())
    private val prefs = Settings(app)
    private val sound = MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) }
    val ui = CamUi()

    @Volatile private var texture: SurfaceTexture? = null
    @Volatile private var resumed = false
    @Volatile private var previewPending = false
    @Volatile private var orientation = 0

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var jpegReader: ImageReader? = null
    private var rawReader: ImageReader? = null
    private var openLens: Lens? = null
    private var lastIso = 100
    private var lastExposure = 16_666_666L
    private var afRegion: MeteringRectangle? = null
    private var afResetJob: Job? = null
    private var lastUiUpdate = 0L

    init {
        val lenses = LensCatalog.discover(cm)
        ui.lenses = lenses
        ui.natural = prefs.natural
        ui.raw = prefs.raw
        ui.grid = prefs.grid
        ui.sound = prefs.sound
        ui.volumeShutter = prefs.volumeShutter
        ui.uwCorrection = prefs.uwCorrection
        ui.flash = prefs.flash
        ui.timer = prefs.timer
        val lens = lenses.firstOrNull { it.key == prefs.lastLens } ?: LensCatalog.main(lenses, front = false) ?: lenses.firstOrNull()
        ui.lens = lens
        lens?.let { ui.caps = capsOf(it) }
        scope.launch(Dispatchers.IO) {
            val uri = Saver.latest(app) ?: return@launch
            val t = Saver.thumbnail(app, uri)
            main.post { ui.lastUri = uri; ui.thumb = t }
        }
    }

    // ------------------------------------------------------------------ lifecycle

    fun onResume() { resumed = true; reopen() }

    fun onPause() {
        resumed = false
        scope.launch { mutex.withLock { closeLocked() } }
    }

    fun onSurfaceAvailable(t: SurfaceTexture) { texture = t; reopen() }

    /** The TextureView keeps ownership off; we release the texture after the camera is closed. */
    fun onSurfaceDestroyed(t: SurfaceTexture) {
        texture = null
        scope.launch { mutex.withLock { closeLocked(); t.release() } }
    }

    fun setDeviceOrientation(degrees: Int) {
        val r = ((degrees + 45) / 90 * 90) % 360
        if (r == orientation) return
        orientation = r
        var target = -r
        val cur = ui.rotation
        while (target - cur > 180) target -= 360
        while (target - cur < -180) target += 360
        ui.rotation = target
    }

    // ------------------------------------------------------------------ user actions

    fun selectLens(l: Lens) {
        if (l.key == ui.lens?.key) return
        ui.lens = l
        prefs.lastLens = l.key
        ui.zoom = 1f
        ui.iso = null; ui.exposure = null; ui.focus = null
        ui.caps = capsOf(l)
        clearFocus()
        reopen()
    }

    fun flip() {
        val cur = ui.lens ?: return
        val avail = ui.lenses.filter { it.key !in ui.blocked }
        LensCatalog.main(avail, front = !cur.front)?.let { selectLens(it) }
    }

    fun setZoom(z: Float) {
        val nz = z.coerceIn(1f, ui.caps.maxZoom)
        if (abs(nz - ui.zoom) < 0.005f) return
        ui.zoom = nz
        requestPreview()
    }

    fun setFlash(f: Flash) { ui.flash = f; prefs.flash = f; requestPreview() }
    fun setTimer(t: Int) { ui.timer = t; prefs.timer = t }
    fun setGrid(v: Boolean) { ui.grid = v; prefs.grid = v }
    fun setSound(v: Boolean) { ui.sound = v; prefs.sound = v }
    fun setVolumeShutter(v: Boolean) { ui.volumeShutter = v; prefs.volumeShutter = v }
    fun setNatural(v: Boolean) { ui.natural = v; prefs.natural = v; requestPreview() }
    fun setUwCorrection(v: Boolean) { ui.uwCorrection = v; prefs.uwCorrection = v; requestPreview() }
    fun setRaw(v: Boolean) { ui.raw = v; prefs.raw = v; reopen() }

    fun setPro(v: Boolean) {
        ui.pro = v
        if (!v) { ui.iso = null; ui.exposure = null; ui.focus = null; ui.awb = CameraMetadata.CONTROL_AWB_MODE_AUTO; ui.ev = 0 }
        requestPreview()
    }
    fun setIso(v: Int?) { ui.iso = v; requestPreview() }
    fun setExposure(v: Long?) { ui.exposure = v; requestPreview() }
    fun setFocus(v: Float?) { ui.focus = v; if (v != null) clearFocus(); requestPreview() }
    fun setAwb(v: Int) { ui.awb = v; requestPreview() }
    fun setEv(v: Int) { ui.ev = v; requestPreview() }

    /** Tap-to-focus/meter. nx, ny are 0..1 positions on the (portrait) preview. */
    fun tapFocus(nx: Float, ny: Float) {
        val l = ui.lens ?: return
        ui.focusPoint = Offset(nx, ny)
        ui.focusLocked = false
        scope.launch {
            mutex.withLock {
                afRegion = meteringRect(l, nx, ny)
                startPreviewLocked()
                if (ui.focus == null) triggerAfLocked()
            }
        }
        afResetJob?.cancel()
        afResetJob = scope.launch {
            delay(5000)
            mutex.withLock { afRegion = null; main.post { ui.focusPoint = null }; startPreviewLocked() }
        }
    }

    fun shutter() {
        if (ui.capturing || ui.countdown > 0 || !ui.ready) return
        scope.launch {
            val t = ui.timer
            if (t > 0) {
                for (i in t downTo 1) { main.post { ui.countdown = i }; delay(1000) }
                main.post { ui.countdown = 0 }
            }
            mutex.withLock { captureLocked() }
        }
    }

    private fun clearFocus() {
        afResetJob?.cancel()
        ui.focusPoint = null
        scope.launch { mutex.withLock { afRegion = null } }
    }

    private fun toast(msg: String) = main.post { Toast.makeText(app, msg, Toast.LENGTH_LONG).show() }

    // ------------------------------------------------------------------ open / close

    private fun reopen() {
        scope.launch {
            mutex.withLock {
                closeLocked()
                val t = texture
                val l = ui.lens
                if (!resumed || t == null || l == null) return@withLock
                if (ContextCompat.checkSelfPermission(app, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return@withLock
                try {
                    openLocked(l, t)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    closeLocked()
                    val busy = e is CameraAccessException && e.reason in setOf(
                        CameraAccessException.CAMERA_IN_USE, CameraAccessException.MAX_CAMERAS_IN_USE,
                        CameraAccessException.CAMERA_DISABLED, CameraAccessException.CAMERA_DISCONNECTED,
                    )
                    val fallback = LensCatalog.main(ui.lenses.filter { it.key !in ui.blocked && it.key != l.key }, l.front)
                        ?: LensCatalog.main(ui.lenses.filter { it.key !in ui.blocked && it.key != l.key }, !l.front)
                    if (busy || fallback == null) {
                        toast("Camera is busy or unavailable. Close other camera apps and try again.")
                        return@withLock
                    }
                    ui.blocked = ui.blocked + l.key
                    toast("The ${l.label}× lens can't be used by other apps on this phone. Switched to ${fallback.label}×.")
                    ui.lens = fallback
                    ui.caps = capsOf(fallback)
                    prefs.lastLens = fallback.key
                    try { openLocked(fallback, t) } catch (e2: Exception) {
                        closeLocked(); toast("Couldn't open the camera: ${e2.message}")
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun openLocked(l: Lens, t: SurfaceTexture) {
        val ch = l.chars
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: error("No stream configuration")
        val caps = capsOf(l)
        val jpegSize = pick43(map.getOutputSizes(ImageFormat.JPEG))
        val prevSize = pickPreview(map.getOutputSizes(SurfaceTexture::class.java), jpegSize)
        t.setDefaultBufferSize(prevSize.width, prevSize.height)
        val ps = Surface(t).also { previewSurface = it }
        val jr = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2).also { jpegReader = it }
        val rr = if (caps.raw && ui.raw) {
            val rs = map.getOutputSizes(ImageFormat.RAW_SENSOR).maxBy { it.width.toLong() * it.height }
            ImageReader.newInstance(rs.width, rs.height, ImageFormat.RAW_SENSOR, 2)
        } else null
        rawReader = rr

        val dev = openDevice(l.openId)
        device = dev
        openLens = l
        session = try {
            createSession(dev, listOfNotNull(ps, jr.surface, rr?.surface), l.physicalId)
        } catch (e: Exception) {
            if (rr == null) throw e
            rr.close(); rawReader = null
            toast("RAW isn't available on this lens; saving JPEG only.")
            createSession(dev, listOf(ps, jr.surface), l.physicalId)
        }
        val so = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val aspect = if (so % 180 == 0) prevSize.width.toFloat() / prevSize.height else prevSize.height.toFloat() / prevSize.width
        main.post { ui.previewAspect = aspect; ui.caps = caps; ui.ready = true }
        startPreviewLocked()
    }

    private suspend fun openDevice(id: String): CameraDevice = suspendCancellableCoroutine { cont ->
        cm.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(d: CameraDevice) {
                if (cont.isActive) cont.resume(d) else d.close()
            }
            override fun onDisconnected(d: CameraDevice) {
                d.close()
                if (cont.isActive) cont.resumeWithException(CameraAccessException(CameraAccessException.CAMERA_DISCONNECTED))
                else lost()
            }
            override fun onError(d: CameraDevice, error: Int) {
                d.close()
                if (cont.isActive) cont.resumeWithException(IllegalStateException("Camera error $error"))
                else lost()
            }
        }, handler)
    }

    private fun lost() {
        toast("The camera was interrupted. Reopen the app to continue.")
        scope.launch { mutex.withLock { closeLocked() } }
    }

    private suspend fun createSession(dev: CameraDevice, surfaces: List<Surface>, physicalId: String?): CameraCaptureSession =
        suspendCancellableCoroutine { cont ->
            val outs = surfaces.map { s -> OutputConfiguration(s).apply { if (physicalId != null) setPhysicalCameraId(physicalId) } }
            val cb = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) { if (cont.isActive) cont.resume(s) }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("Session configuration failed"))
                }
            }
            dev.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outs, executor, cb))
        }

    private fun closeLocked() {
        runCatching { session?.close() }
        session = null
        runCatching { device?.close() }
        device = null
        jpegReader?.close(); jpegReader = null
        rawReader?.close(); rawReader = null
        previewSurface?.release(); previewSurface = null
        openLens = null
        main.post { ui.ready = false }
    }

    // ------------------------------------------------------------------ requests

    private fun requestPreview() {
        if (previewPending) return
        previewPending = true
        scope.launch { mutex.withLock { previewPending = false; startPreviewLocked() } }
    }

    private fun startPreviewLocked() {
        val dev = device ?: return
        val s = session ?: return
        val ps = previewSurface ?: return
        try {
            val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(ps)
            applyCommon(b, still = false)
            s.setRepeatingRequest(b.build(), previewCallback, handler)
        } catch (_: Exception) {
            // session is closing; a new one will start its own preview
        }
    }

    private fun triggerAfLocked() {
        val dev = device ?: return
        val s = session ?: return
        val ps = previewSurface ?: return
        try {
            val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(ps)
            applyCommon(b, still = false)
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            s.capture(b.build(), previewCallback, handler)
        } catch (_: Exception) {
        }
    }

    private val previewCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
            res.get(CaptureResult.SENSOR_SENSITIVITY)?.let { lastIso = it }
            res.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { lastExposure = it }
            val now = SystemClock.uptimeMillis()
            if (now - lastUiUpdate > 200) {
                lastUiUpdate = now
                val i = lastIso
                val e = lastExposure
                main.post { ui.liveIso = i; ui.liveExposure = e }
            }
            if (afRegion != null) {
                val st = res.get(CaptureResult.CONTROL_AF_STATE)
                if (st == CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED || st == CameraMetadata.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) {
                    main.post { ui.focusLocked = true }
                }
            }
        }
    }

    private fun pick(avail: IntArray?, vararg order: Int): Int? =
        if (avail == null) null else order.firstOrNull { it in avail }

    private fun manualExposureActive(caps: LensCaps) = caps.manualSensor && (ui.iso != null || ui.exposure != null)

    /** Settings shared by preview and still capture, including the processing switches. */
    private fun applyCommon(b: CaptureRequest.Builder, still: Boolean) {
        val l = openLens ?: return
        val ch = l.chars
        val caps = capsOf(l)
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)

        // Focus
        val afModes = ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
        val manualFocus = ui.focus
        val region = afRegion
        when {
            manualFocus != null && caps.minFocus > 0f -> {
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                b.set(CaptureRequest.LENS_FOCUS_DISTANCE, manualFocus)
            }
            region != null && CameraMetadata.CONTROL_AF_MODE_AUTO in afModes -> {
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
                if ((ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) > 0) b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
            }
            CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE in afModes ->
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            CameraMetadata.CONTROL_AF_MODE_OFF in afModes ->
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
        }

        // Exposure + flash
        val flash = if (caps.flash) ui.flash else Flash.OFF
        if (manualExposureActive(caps)) {
            val exp = (ui.exposure ?: lastExposure).coerceIn(caps.expRange)
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, (ui.iso ?: lastIso).coerceIn(caps.isoRange))
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exp)
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, max(exp, 33_333_333L))
            b.set(
                CaptureRequest.FLASH_MODE,
                when {
                    flash == Flash.TORCH -> CameraMetadata.FLASH_MODE_TORCH
                    still && flash == Flash.ON -> CameraMetadata.FLASH_MODE_SINGLE
                    else -> CameraMetadata.FLASH_MODE_OFF
                },
            )
        } else {
            b.set(
                CaptureRequest.CONTROL_AE_MODE,
                when (flash) {
                    Flash.AUTO -> CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH
                    Flash.ON -> CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH
                    else -> CameraMetadata.CONTROL_AE_MODE_ON
                },
            )
            b.set(CaptureRequest.FLASH_MODE, if (flash == Flash.TORCH) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF)
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, ui.ev.coerceIn(caps.evRange))
            if (region != null && (ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0) > 0) {
                b.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
            }
        }

        // White balance
        b.set(CaptureRequest.CONTROL_AWB_MODE, if (ui.awb in caps.awbModes) ui.awb else CameraMetadata.CONTROL_AWB_MODE_AUTO)

        // Digital zoom (crop) within this lens
        if (l.physicalId == null && ui.zoom > 1.001f) b.set(CaptureRequest.SCALER_CROP_REGION, cropRect(l))

        // Optical stabilisation is mechanical, so it stays on
        val ois = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
        pick(ois, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)?.let { b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, it) }
        b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)

        // Processing
        if (ui.natural) {
            pick(
                ch.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES),
                CameraMetadata.NOISE_REDUCTION_MODE_OFF, CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL, CameraMetadata.NOISE_REDUCTION_MODE_FAST,
            )?.let { b.set(CaptureRequest.NOISE_REDUCTION_MODE, it) }
            pick(
                ch.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES),
                CameraMetadata.EDGE_MODE_OFF, CameraMetadata.EDGE_MODE_FAST,
            )?.let { b.set(CaptureRequest.EDGE_MODE, it) }
            pick(
                ch.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES),
                CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_OFF, CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_FAST,
            )?.let { b.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, it) }
            // A plain, global sRGB curve instead of the phone's punchy tone curve
            val tm = ch.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES) ?: intArrayOf()
            if (CameraMetadata.TONEMAP_MODE_PRESET_CURVE in tm) {
                b.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_PRESET_CURVE)
                b.set(CaptureRequest.TONEMAP_PRESET_CURVE, CameraMetadata.TONEMAP_PRESET_CURVE_SRGB)
            } else if (CameraMetadata.TONEMAP_MODE_GAMMA_VALUE in tm) {
                b.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_GAMMA_VALUE)
                b.set(CaptureRequest.TONEMAP_GAMMA, 2.2f)
            }
            val dc = ch.get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES)
            val dcMode = if (ui.uwCorrection) {
                pick(dc, CameraMetadata.DISTORTION_CORRECTION_MODE_HIGH_QUALITY, CameraMetadata.DISTORTION_CORRECTION_MODE_FAST)
            } else {
                pick(dc, CameraMetadata.DISTORTION_CORRECTION_MODE_OFF)
            }
            if (dcMode != null) b.set(CaptureRequest.DISTORTION_CORRECTION_MODE, dcMode)
        } else if (still) {
            pick(ch.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES), CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                ?.let { b.set(CaptureRequest.NOISE_REDUCTION_MODE, it) }
            pick(ch.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES), CameraMetadata.EDGE_MODE_HIGH_QUALITY)
                ?.let { b.set(CaptureRequest.EDGE_MODE, it) }
        }

        if (still) {
            b.set(CaptureRequest.JPEG_QUALITY, 100.toByte())
            b.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation(l))
        }
    }

    // ------------------------------------------------------------------ capture

    private suspend fun captureLocked() {
        val dev = device ?: return
        val s = session ?: return
        val l = openLens ?: return
        val jr = jpegReader ?: return
        val rr = rawReader
        val caps = capsOf(l)
        main.post { ui.capturing = true }
        val usesFlash = caps.flash && !manualExposureActive(caps) && (ui.flash == Flash.AUTO || ui.flash == Flash.ON)
        var jpegImg: Image? = null
        var rawImg: Image? = null
        try {
            if (usesFlash) precaptureLocked(dev, s)

            jr.acquireLatestImage()?.close()
            rr?.acquireLatestImage()?.close()
            val jpegD = CompletableDeferred<Image>()
            val rawD = CompletableDeferred<Image>()
            jr.setOnImageAvailableListener({ r -> r.acquireNextImage()?.let { if (!jpegD.complete(it)) it.close() } }, handler)
            rr?.setOnImageAvailableListener({ r -> r.acquireNextImage()?.let { if (!rawD.complete(it)) it.close() } }, handler)
            val resultD = CompletableDeferred<TotalCaptureResult>()

            val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            b.addTarget(jr.surface)
            rr?.let { b.addTarget(it.surface) }
            applyCommon(b, still = true)
            val playSound = ui.sound
            s.capture(b.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureStarted(session: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) {
                    if (playSound) sound.play(MediaActionSound.SHUTTER_CLICK)
                    main.post { ui.shutterBlink++ }
                }
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    resultD.complete(result)
                }
                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    resultD.completeExceptionally(IllegalStateException("Capture failed (${failure.reason})"))
                }
            }, handler)

            val result = withTimeout(10_000) { resultD.await() }
            jpegImg = withTimeout(10_000) { jpegD.await() }
            if (rr != null) rawImg = withTimeoutOrNull(10_000) { rawD.await() }

            val buf = jpegImg.planes[0].buffer
            val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
            jpegImg.close(); jpegImg = null

            val name = "PURE_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            val rotation = jpegOrientation(l)
            val raw = rawImg
            val uri = withContext(Dispatchers.IO) {
                val u = Saver.saveJpeg(app, name, bytes)
                if (raw != null) {
                    Saver.saveDng(app, name) { out ->
                        val dng = DngCreator(l.chars, result)
                        try {
                            dng.setOrientation(exifOrientation(rotation))
                            dng.writeImage(out, raw)
                        } finally {
                            dng.close()
                        }
                    }
                }
                u
            }
            val thumb = withContext(Dispatchers.IO) { Saver.thumbnail(app, uri) }
            main.post { ui.lastUri = uri; if (thumb != null) ui.thumb = thumb }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            toast("Photo not saved: ${e.message}")
        } finally {
            jpegImg?.close()
            rawImg?.close()
            jr.setOnImageAvailableListener(null, null)
            rr?.setOnImageAvailableListener(null, null)
            if (usesFlash) startPreviewLocked()
            main.post { ui.capturing = false }
        }
    }

    /** Lets auto-exposure measure the scene with the pre-flash before a flash shot. */
    private suspend fun precaptureLocked(dev: CameraDevice, s: CameraCaptureSession) {
        val ps = previewSurface ?: return
        val done = CompletableDeferred<Unit>()
        var frames = 0
        val cb = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                frames++
                val ae = result.get(CaptureResult.CONTROL_AE_STATE)
                if (frames > 3 && ae != CameraMetadata.CONTROL_AE_STATE_PRECAPTURE && ae != CameraMetadata.CONTROL_AE_STATE_SEARCHING) {
                    done.complete(Unit)
                }
            }
        }
        val rep = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(ps); applyCommon(this, false) }
        s.setRepeatingRequest(rep.build(), cb, handler)
        val trig = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(ps)
            applyCommon(this, false)
            set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START)
        }
        s.capture(trig.build(), cb, handler)
        withTimeoutOrNull(2000) { done.await() }
    }

    // ------------------------------------------------------------------ helpers

    private fun capsOf(l: Lens): LensCaps {
        val ch = l.chars
        val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val manual = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val iso = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val exp = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val ev = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        return LensCaps(
            manualSensor = manual && iso != null && exp != null,
            isoRange = if (iso != null) iso.lower..iso.upper else 100..100,
            expRange = if (exp != null) exp.lower..min(exp.upper, 30_000_000_000L) else 1_000_000L..1_000_000L,
            minFocus = if (manual) ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f else 0f,
            evRange = if (ev != null) ev.lower..ev.upper else 0..0,
            evStep = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 1f,
            flash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
            raw = l.physicalId == null &&
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW in caps &&
                map?.outputFormats?.contains(ImageFormat.RAW_SENSOR) == true,
            awbModes = (ch.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: intArrayOf(CameraMetadata.CONTROL_AWB_MODE_AUTO)).toList(),
            maxZoom = if (l.physicalId == null) (ch.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f).coerceIn(1f, 10f) else 1f,
        )
    }

    private fun cropRect(l: Lens): Rect {
        val a = l.chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return Rect(0, 0, 1, 1)
        val z = ui.zoom.coerceAtLeast(1f)
        val w = (a.width() / z).roundToInt()
        val h = (a.height() / z).roundToInt()
        val left = (a.width() - w) / 2
        val top = (a.height() - h) / 2
        return Rect(left, top, left + w, top + h)
    }

    private fun meteringRect(l: Lens, nx: Float, ny: Float): MeteringRectangle {
        val so = l.chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val x = if (l.front) 1f - nx else nx
        val (sx, sy) = when (so) {
            90 -> ny to 1f - x
            180 -> 1f - x to 1f - ny
            270 -> 1f - ny to x
            else -> x to ny
        }
        val crop = if (ui.zoom > 1.001f && l.physicalId == null) cropRect(l)
        else l.chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.let { Rect(0, 0, it.width(), it.height()) } ?: Rect(0, 0, 1, 1)
        val half = (min(crop.width(), crop.height()) * 0.07f).roundToInt().coerceAtLeast(10)
        val cx = (crop.left + sx * crop.width()).roundToInt()
        val cy = (crop.top + sy * crop.height()).roundToInt()
        val r = Rect(
            (cx - half).coerceAtLeast(crop.left), (cy - half).coerceAtLeast(crop.top),
            (cx + half).coerceAtMost(crop.right - 1), (cy + half).coerceAtMost(crop.bottom - 1),
        )
        return MeteringRectangle(r, MeteringRectangle.METERING_WEIGHT_MAX - 1)
    }

    private fun jpegOrientation(l: Lens): Int {
        val so = l.chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val d = if (l.front) -orientation else orientation
        return (so + d + 360) % 360
    }

    private fun exifOrientation(deg: Int) = when (deg) {
        90 -> ExifInterface.ORIENTATION_ROTATE_90
        180 -> ExifInterface.ORIENTATION_ROTATE_180
        270 -> ExifInterface.ORIENTATION_ROTATE_270
        else -> ExifInterface.ORIENTATION_NORMAL
    }

    private fun ratio(s: Size) = s.width.toFloat() / s.height

    private fun pick43(sizes: Array<Size>): Size {
        val fourThree = sizes.filter { abs(ratio(it) - 4f / 3f) < 0.02f }
        return (fourThree.ifEmpty { sizes.toList() }).maxBy { it.width.toLong() * it.height }
    }

    private fun pickPreview(sizes: Array<Size>, still: Size): Size {
        val target = ratio(still)
        val same = sizes.filter { abs(ratio(it) - target) < 0.02f && it.width <= 2048 && it.height <= 1536 }
        if (same.isNotEmpty()) return same.maxBy { it.width.toLong() * it.height }
        return sizes.filter { it.width <= 1920 && it.height <= 1440 }.maxByOrNull { it.width.toLong() * it.height } ?: sizes.first()
    }
}
