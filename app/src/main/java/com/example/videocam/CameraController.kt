package com.example.videocam

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.DngCreator
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.StreamConfigurationMap
import android.hardware.camera2.params.TonemapCurve
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodecInfo
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import java.text.SimpleDateFormat
import java.util.concurrent.Executors
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class CameraController(
    private val ctx: Context,
    private val tv: TextureView,
    private val cfg: Config,
    private val store: Store,
    private val ui: Callbacks
) {
    interface Callbacks {
        fun onMessage(msg: String)
        fun onReady()
        fun onPreviewSize(w: Int, h: Int)
        fun onRecording(on: Boolean)
        fun onFocusMoved(fraction: Float)
        fun onStateChanged()
        fun onZoomChanged(z: Float)
        fun onBusy(busy: Boolean)
        fun onPhotoSaved(uri: Uri?)
        fun onLongExposure(sec: Int)
        fun onSteadyReady(uri: Uri, log: SteadyLog)
    }

    private class Output(val uri: Uri, val pfd: ParcelFileDescriptor)

    private class Shot(
        val lens: String,
        val format: Int,
        val chars: CameraCharacteristics,
        val rotDeg: Int,
        val quality: Int,
        val expected: Int,
        val bw: Boolean,
        val flat: Boolean,
        val photoLook: Int,
        val hdr: Boolean,
        val upscale: Boolean,
        val glow: Boolean
    ) {
        var image: Image? = null // RAW only
        var result: TotalCaptureResult? = null
        val jpegs = ArrayList<ByteArray>()
        var started = false
    }

    private class ScenePlan(val expNs: Long, val iso: Int, val frames: Int)

    private class HdrPlan(val exp: LongArray, val iso: IntArray)

    private companion object {
        const val SPLIT_BYTES = 3_900_000_000L
    }

    private val manager = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val handler = Handler(Looper.getMainLooper())
    private val cbThread = HandlerThread("cam-results").apply { start() }
    private val cbHandler = Handler(cbThread.looper)

    private fun isMotoEdge50NeoProfile(): Boolean {
        val maker = Build.MANUFACTURER.lowercase(Locale.US)
        val model = Build.MODEL.lowercase(Locale.US)
        // The explicit auxiliary-ID profile is restricted to Motorola's Edge 50 Neo family.
        // Every ID is still checked against CameraManager before it is exposed.
        return maker.contains("motorola") && (model.contains("edge 50 neo") || model.contains("xt2409"))
    }

    private val motoProfileIds = listOf("5", "2", "0", "3", "1")

    val cameraIds: List<String> = try {
        val advertised = manager.cameraIdList.toList()
        val detected = advertised.filter { usable(it) }
        if (isMotoEdge50NeoProfile()) {
            val profile = motoProfileIds.filter { it in advertised && usable(it) }
            (profile + detected.filterNot { it in profile }).distinct()
        } else {
            detected.sortedWith(compareBy({ lensRank(it) }, { it }))
        }
    } catch (e: Exception) {
        emptyList()
    }

    private lateinit var chars: CameraCharacteristics
    private var map: StreamConfigurationMap? = null

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var recSurface: Surface? = null
    private var recorder: MediaRecorder? = null
    private var out: Output? = null
    private var nextOut: Output? = null
    private var started = false
    private var gen = 0
    private var rackAnim: ValueAnimator? = null
    private var lastPreview = Size(1920, 1080)
    private var reader: ImageReader? = null
    private var shot: Shot? = null
    private var warnedLens: String? = null
    private val procExec = Executors.newSingleThreadExecutor()
    private val lock = Any()
    private var crop: CropRenderer? = null
    private val jobCount = java.util.concurrent.atomic.AtomicInteger(0)

    @Volatile
    var busy = false
        private set
    val jobs: Int get() = jobCount.get()
    var manualSensor = false
        private set
    /** Largest still (in pixels) that every physical lens behind this camera can shoot; bigger stills get cropped from the main lens. */
    var lensSafeArea = Long.MAX_VALUE
        private set
    @Volatile
    var liveExpNs = 0L
        private set
    var rawSize: Size? = null
        private set
    var jpegSizes: List<Size> = emptyList()
        private set
    var jprSizes: List<Size> = emptyList()
        private set

    var recording = false
        private set
    var manualFocus = false
        private set
    var focusFraction = 0f
        private set
    var minFocusDiopters = 0f
        private set
    var isoRange: Range<Int> = Range(50, 3200)
        private set
    var sizes: List<Size> = emptyList()
        private set
    var fpsList: List<Int> = listOf(30)
        private set

    var zoom = 1f
        private set
    var zoomRange: Range<Float> = Range(1f, 1f)
        private set
    var aeLock = false
        private set
    var awbLock = false
        private set
    var aeLockAvail = false
        private set
    var awbLockAvail = false
        private set
    var afLocked = false
        private set
    var faceAfSupported = false
        private set
    var evStepEv = 0.33f
        private set
    var torch = false
        private set
    @Volatile
    var liveIso = 0
        private set
    @Volatile
    var liveShutterDenom = 0
        private set
    @Volatile
    var liveFocus = -1f
        private set
    var evRange: Range<Int> = Range(0, 0)
        private set
    var flashAvail = false
        private set
    var monoSupported = false
        private set
    var lastClipUri: Uri? = null
    var lastPhotoUri: Uri? = null

    /** Flagged as a good take while the current recording is running. */
    @Volatile
    var goodPending = false
        private set

    @Volatile
    private var steadyLog: SteadyLog? = null
    private val steadyRec = SteadyRecorder(ctx)
    private var zoomAnim: ValueAnimator? = null

    private var afRegion: MeteringRectangle? = null
    @Volatile
    private var faceRegion: MeteringRectangle? = null

    private val highSpeed get() = cfg.mode == 0 && cfg.fps > 60

    init {
        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                if (started && device == null) openCamera()
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
    }

    // ---------- camera info ----------

    private fun usable(id: String): Boolean = try {
        val c = manager.getCameraCharacteristics(id)
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val m = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val videoSizes = m?.getOutputSizes(MediaRecorder::class.java)
        val previewSizes = m?.getOutputSizes(SurfaceTexture::class.java)
        val hasPreviewOutput = !videoSizes.isNullOrEmpty() || !previewSizes.isNullOrEmpty()
        val backwardCompatible = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE)
        // Some OEM auxiliary IDs omit the backward-compatible flag despite advertising
        // preview/record output surfaces. Accept these only on the narrowly matched profile.
        (backwardCompatible || (isMotoEdge50NeoProfile() && id in motoProfileIds)) && hasPreviewOutput
    } catch (e: Exception) {
        false
    }

    private fun lensRank(id: String): Int = runCatching {
        val c = manager.getCameraCharacteristics(id)
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        if (caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)) return@runCatching 0
        if (c.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_FRONT) return@runCatching 10_000
        val f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return@runCatching 500
        val size = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return@runCatching 500
        val eq = f * 43.27f / hypot(size.width, size.height)
        1 + (abs(eq - 24f) * 10f).roundToInt() // main-camera-equivalent focal length first
    }.getOrDefault(9_999)

    private fun isFront(id: String): Boolean =
        manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
            CameraMetadata.LENS_FACING_FRONT

    fun isFrontCamera(id: String): Boolean = runCatching {
        manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_FRONT
    }.getOrDefault(false)

    fun supportsFps(id: String, fps: Int): Boolean = runCatching {
        val c = manager.getCameraCharacteristics(id)
        if (fps <= 60) {
            c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.any { it.upper == fps } == true
        } else {
            val m = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return@runCatching false
            m.highSpeedVideoSizes?.any { size ->
                m.getHighSpeedVideoFpsRangesFor(size).any { it.upper >= fps }
            } == true
        }
    }.getOrDefault(false)

    /** Upscaling is only useful on auxiliary focal lengths; the main sensor already outputs 12.6 MP. */
    private fun currentLensNeedsUpscale(): Boolean = runCatching {
        val c = manager.getCameraCharacteristics(cfg.cameraId)
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        if (caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)) {
            zoom < 0.90f || zoom > 1.60f
        } else {
            val f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return@runCatching false
            val size = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return@runCatching false
            val eq = f * 43.27f / hypot(size.width, size.height)
            eq < 20f || eq > 40f
        }
    }.getOrDefault(false)

    /** Resolve a shortcut only to a real, advertised physical lens with a close focal-length match. */
    fun cameraIdForShortcut(ratio: Float): String? {
        if (isMotoEdge50NeoProfile()) {
            val exact = when {
                ratio < 0.75f -> "2" // supplied ultrawide ID (0.7x)
                ratio < 1.8f -> "0" // supplied main ID (1x)
                else -> "3"          // supplied telephoto ID (2.7x)
            }
            if (exact in cameraIds && !isFront(exact)) return exact
        }

        fun isLogical(id: String): Boolean = runCatching {
            val caps = manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
        }.getOrDefault(false)

        fun equivalent(id: String): Float? = runCatching {
            val c = manager.getCameraCharacteristics(id)
            val f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
                ?: return@runCatching null
            val size = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                ?: return@runCatching null
            f * 43.27f / hypot(size.width, size.height)
        }.getOrNull()

        val physicalBack = cameraIds.filter { !isFront(it) && !isLogical(it) }
        if (physicalBack.isEmpty()) return null
        val measured = physicalBack.mapNotNull { id -> equivalent(id)?.let { id to it } }
        val baseline = measured.minByOrNull { abs(it.second - 24f) }?.second ?: return null
        val target = baseline * ratio
        val match = measured.minByOrNull { abs(it.second - target) } ?: return null

        // Avoid pretending a single main camera is an ultrawide/telephoto lens.
        // A 30% tolerance accommodates sensor/crop differences across manufacturers.
        val relativeError = abs(match.second - target) / target.coerceAtLeast(1f)
        return match.first.takeIf { relativeError <= 0.30f }
    }

    fun cameraLabel(id: String): String {
        val c = manager.getCameraCharacteristics(id)
        val side = if (isFront(id)) "Front" else "Back"
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        if (caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)) {
            return "$side · multi-camera auto switch (ID $id)"
        }
        val f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
        val sz = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        if (f != null && sz != null) {
            val eq = (f * 43.27f / hypot(sz.width, sz.height)).roundToInt()
            return "$side · ${eq}mm equivalent (ID $id)"
        }
        return "$side camera (ID $id)"
    }

    fun mics(): List<AudioDeviceInfo> =
        (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
            .getDevices(AudioManager.GET_DEVICES_INPUTS).toList()

    fun micLabel(d: AudioDeviceInfo): String {
        val kind = when (d.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
            else -> "Mic"
        }
        return "$kind ${d.productName}"
    }

    private fun findMic(): AudioDeviceInfo? = mics().firstOrNull { it.id == cfg.micId }

    private fun loadChars(id: String) {
        val c = manager.getCameraCharacteristics(id)
        chars = c
        map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        minFocusDiopters = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        isoRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: Range(100, 800)
        aeLockAvail = c.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true
        awbLockAvail = c.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true
        flashAvail = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        evRange = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0)
        monoSupported = c.get(CameraCharacteristics.CONTROL_AVAILABLE_EFFECTS)
            ?.contains(CameraMetadata.CONTROL_EFFECT_MODE_MONO) == true
        c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.let {
            evStepEv = it.numerator.toFloat() / it.denominator.toFloat()
        }
        zoomRange = if (Build.VERSION.SDK_INT >= 30) {
            c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) ?: Range(1f, 1f)
        } else {
            Range(1f, max(1f, c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f))
        }
        zoom = zoom.coerceIn(zoomRange.lower, zoomRange.upper)
        val fd = c.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES)
        faceAfSupported = fd?.contains(CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE) == true &&
            (c.get(CameraCharacteristics.STATISTICS_INFO_MAX_FACE_COUNT) ?: 0) > 0

        val all = LinkedHashSet<Size>()
        (map?.getOutputSizes(MediaRecorder::class.java) ?: map?.getOutputSizes(SurfaceTexture::class.java))
            ?.let { all.addAll(it) }
        map?.highSpeedVideoSizes?.let { all.addAll(it) }
        sizes = all.filter { it.width * 9 == it.height * 16 && it.height in 720..2160 }
            .sortedByDescending { it.width * it.height }

        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        jpegSizes = areaDesc(map?.getOutputSizes(ImageFormat.JPEG))
        rawSize = if (caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW)) {
            areaDesc(map?.getOutputSizes(ImageFormat.RAW_SENSOR)).firstOrNull()
        } else {
            null
        }
        jprSizes = if (Build.VERSION.SDK_INT >= 34) areaDesc(map?.getOutputSizes(ImageFormat.JPEG_R)) else emptyList()
        manualSensor = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
        var safe = Long.MAX_VALUE
        if (Build.VERSION.SDK_INT >= 28) {
            for (pid in c.physicalCameraIds) {
                try {
                    val pm = manager.getCameraCharacteristics(pid).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    val mx = pm?.getOutputSizes(ImageFormat.JPEG)?.maxOfOrNull { it.width.toLong() * it.height }
                    if (mx != null && mx > 0L) safe = min(safe, mx)
                } catch (_: Exception) {
                }
            }
        }
        lensSafeArea = safe
    }

    private fun fpsFor(size: Size): List<Int> {
        val m = map ?: return listOf(30)
        val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?: emptyArray()
        val l = mutableListOf<Int>()
        for (f in listOf(24, 25, 30, 60)) if (ranges.any { it.upper == f }) l.add(f)
        val hs = m.highSpeedVideoSizes
        if (hs != null && hs.contains(size)) {
            for (r in m.getHighSpeedVideoFpsRangesFor(size)) {
                if (r.upper >= 120 && !l.contains(r.upper)) l.add(r.upper)
            }
        }
        if (l.isEmpty()) l.add(30)
        return l.sorted()
    }

    private fun validateConfig() {
        val s = sizes.firstOrNull { it.width == cfg.width && it.height == cfg.height }
            ?: sizes.firstOrNull { it.height <= 2160 }
            ?: sizes.firstOrNull()
            ?: Size(1920, 1080)
        cfg.width = s.width
        cfg.height = s.height
        fpsList = fpsFor(s)
        if (cfg.fps !in fpsList) cfg.fps = fpsList.lastOrNull { it <= 30 } ?: fpsList.first()
        cfg.iso = cfg.iso.coerceIn(isoRange.lower, isoRange.upper)
        chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)?.let {
            cfg.ev = cfg.ev.coerceIn(it.lower, it.upper)
        }
    }

    private fun previewSize(): Size {
        if (highSpeed) return Size(cfg.width, cfg.height)
        val maxH = when (cfg.previewP) {
            480 -> 480
            720 -> 720
            else -> 1080
        }
        val opts = map?.getOutputSizes(SurfaceTexture::class.java)
            ?.filter { it.width * 9 == it.height * 16 && it.height <= maxH } ?: emptyList()
        return opts.maxByOrNull { it.width } ?: Size(1280, 720)
    }

    /** Shutter speeds (as 1/x) this camera can do at the current frame rate. */
    fun shutterOptions(): List<Int> {
        val er = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        if (cfg.mode == 1) {
            return listOf(1, 2, 4, 8, 15, 30, 60, 100, 125, 250, 500, 1000, 2000, 4000, 8000).filter { d ->
                val ns = 1e9 / d
                er == null || (ns >= er.lower && ns <= er.upper)
            }
        }
        return listOf(24, 25, 30, 48, 50, 60, 96, 100, 120, 125, 240, 250, 500, 1000, 2000, 4000, 8000)
            .filter { d ->
                val ns = 1e9 / d
                d >= cfg.fps && (er == null || (ns >= er.lower && ns <= er.upper))
            }
    }

    /** Exposure compensation choices within +/-2 EV as (camera steps, label). */
    fun evOptions(): List<Pair<Int, String>> {
        val r = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: return listOf(0 to "+0.0")
        val out = ArrayList<Pair<Int, String>>()
        for (s in r.lower..r.upper) {
            val ev = s * evStepEv
            if (abs(ev) <= 2.01f) out.add(s to "%+.1f EV".format(ev))
        }
        return out
    }

    /** Zoom shortcut buttons that fit this camera's zoom range. */
    fun zoomPresets(): List<Float> =
        listOf(0.5f, 1f, 2f, 3f, 5f, 7f).filter { it >= zoomRange.lower - 0.01f && it <= zoomRange.upper + 0.01f }

    /** Highest zoom offered in the app. */
    fun zoomHi(): Float = max(zoomRange.lower, min(zoomRange.upper, 7f))

    /** Cinematic shape actually being recorded (0 = the camera's own 16:9). Landscape, normal-speed video only. */
    fun effRatio(): Int {
        if (cfg.mode != 0 || highSpeed) return 0
        val r = tv.display?.rotation ?: Surface.ROTATION_90
        if (r == Surface.ROTATION_0 || r == Surface.ROTATION_180) return 0
        return cfg.ratio.coerceIn(0, Ratios.NAMES.size - 1)
    }

    /** Size of the recorded file. */
    fun outSize(): Size {
        val r = effRatio()
        if (r == 0) return Size(cfg.width, cfg.height)
        return Size(cfg.width, min(Ratios.heightFor(cfg.width, r), cfg.height))
    }

    // ---------- lifecycle ----------

    fun start() {
        started = true
        if (device == null && tv.isAvailable) openCamera()
    }

    fun stop() {
        started = false
        rackAnim?.cancel()
        if (recording) stopRecording()
        closeSession()
        releaseRecorder()
        device?.close()
        device = null
        previewSurface?.release()
        previewSurface = null
    }

    fun reopen() {
        if (recording || busy) return
        manualFocus = false
        afLocked = false
        afRegion = null
        faceRegion = null
        zoom = 1f
        torch = false
        cancelZoomPull()
        closeSession()
        releaseRecorder()
        device?.close()
        device = null
        handler.postDelayed({ if (started && device == null && tv.isAvailable) openCamera() }, 350)
    }

    fun reconfigure() {
        if (!recording && !busy) configureAndStart()
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if (cameraIds.isEmpty()) {
            ui.onMessage("No usable camera found")
            return
        }
        val candidates = (listOf(cfg.cameraId) + cameraIds)
            .filter { it in cameraIds }
            .distinct()
        openCameraCandidate(candidates, 0)
    }

    /**
     * Try the selected camera first, then other CameraManager-advertised cameras if an OEM
     * auxiliary ID is present but cannot actually be opened. This keeps device-specific IDs
     * from making the whole app unusable on a different firmware/build.
     */
    @SuppressLint("MissingPermission")
    private fun openCameraCandidate(candidates: List<String>, index: Int) {
        if (!started) return
        if (index >= candidates.size) {
            ui.onMessage("Couldn't open any detected camera. Check camera permission and close other camera apps.")
            return
        }

        val id = candidates[index]
        val settled = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            loadChars(id)
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(cd: CameraDevice) {
                    if (!settled.compareAndSet(false, true) || !started) {
                        cd.close()
                        return
                    }
                    device = cd
                    if (cfg.cameraId != id) {
                        cfg.cameraId = id
                        store.save("last", cfg)
                        ui.onMessage("Selected lens unavailable; using ${cameraLabel(id)} instead")
                    }
                    configureAndStart()
                }

                override fun onDisconnected(cd: CameraDevice) {
                    val wasActive = device === cd
                    cd.close()
                    if (wasActive) {
                        device = null
                        if (recording) {
                            stopRecording()
                            ui.onMessage("Recording stopped because the camera disconnected")
                        } else {
                            closeSession()
                            releaseRecorder()
                            if (busy) {
                                endLong()
                                finishBusy()
                            }
                        }
                        if (started) {
                            ui.onMessage("Trying another detected lens")
                            handler.postDelayed({ openCameraCandidate(candidates, index + 1) }, 250)
                        }
                    } else if (settled.compareAndSet(false, true) && started) {
                        openCameraCandidate(candidates, index + 1)
                    }
                }

                override fun onError(cd: CameraDevice, error: Int) {
                    val wasActive = device === cd
                    cd.close()
                    if (wasActive) {
                        device = null
                        if (recording) {
                            stopRecording()
                            ui.onMessage("Recording stopped after camera error $error")
                        } else {
                            closeSession()
                            releaseRecorder()
                            if (busy) {
                                endLong()
                                finishBusy()
                            }
                        }
                        if (started) {
                            ui.onMessage("Trying another detected lens")
                            handler.postDelayed({ openCameraCandidate(candidates, index + 1) }, 250)
                        }
                    } else if (settled.compareAndSet(false, true) && started) {
                        openCameraCandidate(candidates, index + 1)
                    } else if (!settled.get()) {
                        ui.onMessage("Camera error $error")
                    }
                }
            }, handler)
        } catch (_: Exception) {
            if (settled.compareAndSet(false, true)) {
                handler.post { openCameraCandidate(candidates, index + 1) }
            }
        }
    }

    // ---------- output files ----------

    private fun newOutput(): Output {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "VID_$stamp.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Iris Cam")
            put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis())
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore insert failed")
        val pfd = ctx.contentResolver.openFileDescriptor(uri, "rw")
            ?: throw IllegalStateException("Can't open output file")
        return Output(uri, pfd)
    }

    private fun finalizeOutput(o: Output) {
        lastClipUri = o.uri
        try {
            o.pfd.close()
        } catch (_: Exception) {
        }
        val v = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
        try {
            ctx.contentResolver.update(o.uri, v, null, null)
        } catch (_: Exception) {
        }
        if (goodPending) {
            goodPending = false
            markGood(o.uri)
        }
    }

    /** A new, empty video file for a stabilised copy. */
    fun newSteadyOutput(): Pair<Uri, ParcelFileDescriptor> {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "VID_${stamp}_STEADY.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Iris Cam")
            put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis())
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore insert failed")
        val pfd = ctx.contentResolver.openFileDescriptor(uri, "rw")
            ?: throw IllegalStateException("Can't open output file")
        return Pair(uri, pfd)
    }

    fun finishSteadyOutput(uri: Uri, ok: Boolean) {
        try {
            if (ok) {
                ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            } else {
                ctx.contentResolver.delete(uri, null, null)
            }
        } catch (_: Exception) {
        }
    }

    /** True if super steady can line the gyro up with this camera's frame times. */
    fun steadyPossible(): String? {
        if (!steadyRec.available) return "This phone has no gyro sensor"
        if (chars.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) != CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME) {
            return "Super steady can't be used: this camera's frame times don't match the gyro clock"
        }
        if ((chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90) != 90) return "Super steady works with the back cameras only"
        return null
    }

    private fun discardOutput(o: Output) {
        try {
            o.pfd.close()
        } catch (_: Exception) {
        }
        try {
            ctx.contentResolver.delete(o.uri, null, null)
        } catch (_: Exception) {
        }
    }

    // ---------- good take flag ----------

    /** Marks the clip being recorded (or the last clip / photo) as a good take. Returns false if there is nothing to flag. */
    fun flagGood(): Boolean {
        if (recording) {
            goodPending = true
            return true
        }
        val u = (if (cfg.mode == 1) lastPhotoUri else lastClipUri) ?: return false
        procExec.execute { markGood(u) }
        return true
    }

    private fun markGood(uri: Uri) {
        try {
            val cr = ctx.contentResolver
            var name: String? = null
            cr.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) name = it.getString(0)
            }
            val v = ContentValues()
            val n = name
            if (n != null && !n.contains("_GOOD")) {
                val dot = n.lastIndexOf('.')
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, if (dot > 0) n.substring(0, dot) + "_GOOD" + n.substring(dot) else n + "_GOOD")
            }
            if (Build.VERSION.SDK_INT >= 30) v.put(MediaStore.MediaColumns.IS_FAVORITE, 1)
            if (v.size() > 0) cr.update(uri, v, null, null)
        } catch (_: Exception) {
        }
    }

    // ---------- recorder ----------

    private fun displayDegrees(): Int = when (tv.display?.rotation ?: Surface.ROTATION_90) {
        Surface.ROTATION_0 -> 0
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        else -> 270
    }

    private fun orientationHint(): Int {
        val sensor = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val deg = displayDegrees()
        return if (isFront(cfg.cameraId)) (sensor + deg) % 360 else (sensor - deg + 360) % 360
    }

    private fun audioSourceId(): Int = when (cfg.audioSource) {
        1 -> MediaRecorder.AudioSource.MIC
        2 -> {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true") {
                MediaRecorder.AudioSource.UNPROCESSED
            } else {
                ui.onMessage("Unprocessed audio isn't supported here, using Camcorder")
                MediaRecorder.AudioSource.CAMCORDER
            }
        }
        else -> MediaRecorder.AudioSource.CAMCORDER
    }

    private fun prepareRecorder() {
        val rec = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()
        try {
            val audio = !highSpeed
            if (audio) rec.setAudioSource(audioSourceId())
            rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            val o = newOutput()
            out = o
            rec.setOutputFile(o.pfd.fileDescriptor)
            rec.setVideoEncodingBitRate(cfg.bitrateMbps * 1_000_000)
            rec.setVideoFrameRate(cfg.fps)
            val os = outSize()
            val cropIt = effRatio() > 0
            rec.setVideoSize(os.width, os.height)
            rec.setVideoEncoder(
                if (cfg.hevc) MediaRecorder.VideoEncoder.HEVC else MediaRecorder.VideoEncoder.H264
            )
            if (audio) {
                rec.setAudioEncodingBitRate(256_000)
                rec.setAudioSamplingRate(48_000)
                rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                findMic()?.let { rec.setPreferredDevice(it) }
            }
            rec.setOrientationHint(if (cropIt) 0 else orientationHint())
            rec.setMaxFileSize(SPLIT_BYTES)
            rec.setOnInfoListener { mr, what, _ -> onRecorderInfo(mr, what) }
            rec.setOnErrorListener { _, what, _ -> ui.onMessage("Recorder error $what") }
            rec.prepare()
            recorder = rec
            if (cropIt) {
                val rot = tv.display?.rotation ?: Surface.ROTATION_90
                crop = CropRenderer(rec.surface, os.width, os.height, cfg.width, cfg.height, rot)
            }
        } catch (e: Exception) {
            recorder = null
            try {
                rec.release()
            } catch (_: Exception) {
            }
            out?.let { discardOutput(it) }
            out = null
            throw e
        }
    }

    private fun onRecorderInfo(mr: MediaRecorder, what: Int) {
        when (what) {
            MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING -> {
                try {
                    val n = newOutput()
                    nextOut = n
                    mr.setNextOutputFile(n.pfd.fileDescriptor)
                } catch (e: Exception) {
                    ui.onMessage("Auto-split failed: ${e.message}")
                }
            }
            MediaRecorder.MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED -> {
                out?.let { finalizeOutput(it) }
                out = nextOut
                nextOut = null
                ui.onMessage("Auto-split: new file started")
            }
            MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED -> {
                if (recording) stopRecording()
            }
        }
    }

    private fun releaseRecorder() {
        recorder?.let {
            try {
                it.reset()
            } catch (_: Exception) {
            }
            try {
                it.release()
            } catch (_: Exception) {
            }
        }
        recorder = null
        recSurface = null
        crop?.release()
        crop = null
        out?.let { discardOutput(it) }
        nextOut?.let { discardOutput(it) }
        out = null
        nextOut = null
    }

    private fun closeSession() {
        gen++
        try {
            session?.close()
        } catch (_: Exception) {
        }
        session = null
        try {
            reader?.close()
        } catch (_: Exception) {
        }
        reader = null
        synchronized(lock) {
            shot?.image?.close()
            shot = null
        }
    }

    // ---------- session ----------

    private fun configureAndStart() {
        if (!started) return
        val cd = device ?: return
        closeSession()
        releaseRecorder()
        aeLock = false
        awbLock = false
        afLocked = false
        afRegion = null
        faceRegion = null
        if (cfg.mode == 1) {
            configurePhoto(cd)
            return
        }
        try {
            validateConfig()
        } catch (e: Exception) {
            ui.onMessage("Config error: ${e.message}")
            return
        }
        val st = tv.surfaceTexture ?: return

        try {
            prepareRecorder()
        } catch (e: Exception) {
            var ok = false
            if (cfg.hevc) {
                cfg.hevc = false
                ui.onMessage("H.265 isn't available with these settings, using H.264")
                try {
                    prepareRecorder()
                    ok = true
                } catch (_: Exception) {
                }
            }
            if (!ok) {
                val hint = if (cfg.fps > 30 && cfg.height >= 2160) " (try 4K30, this phone's encoder may not do 4K60)" else ""
                ui.onMessage("Recorder setup failed: ${e.message}$hint")
                return
            }
        }

        val ps = previewSize()
        lastPreview = ps
        st.setDefaultBufferSize(ps.width, ps.height)
        previewSurface?.release()
        val preview = Surface(st)
        previewSurface = preview
        val rs = (crop?.input ?: recorder?.surface) ?: return
        recSurface = rs
        ui.onPreviewSize(ps.width, ps.height)

        startVideoSession(cd, preview, rs, ++gen)
    }

    @Suppress("DEPRECATION")
    private fun startVideoSession(cd: CameraDevice, preview: Surface, rs: Surface, my: Int) {
        if (my != gen) return
        val cb = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                if (my != gen || device !== cd) {
                    s.close()
                    return
                }
                session = s
                refreshTransform()
                updateRepeating()
                ui.onReady()
            }

            override fun onConfigureFailed(s: CameraCaptureSession) {
                ui.onMessage("Camera rejected this resolution / fps / lens combo")
            }
        }
        try {
            if (highSpeed) {
                cd.createConstrainedHighSpeedCaptureSession(listOf(preview, rs), cb, handler)
            } else {
                cd.createCaptureSession(listOf(preview, rs), cb, handler)
            }
        } catch (e: Exception) {
            ui.onMessage("Session error: ${e.message}")
        }
    }

    /**
     * TextureView shows camera buffers as if the phone were held in portrait, so in landscape the
     * picture comes out turned 90 degrees. This is the standard Camera2 correction.
     */
    /** Re-fit the preview after the screen turned, without restarting the camera. */
    fun refreshPreviewLayout() {
        ui.onPreviewSize(lastPreview.width, lastPreview.height)
        refreshTransform()
    }

    fun refreshTransform() {
        val vw = tv.width.toFloat()
        val vh = tv.height.toFloat()
        if (vw == 0f || vh == 0f) return
        val rot = tv.display?.rotation ?: Surface.ROTATION_90
        val m = Matrix()
        val viewRect = RectF(0f, 0f, vw, vh)
        val bufferRect = RectF(0f, 0f, lastPreview.height.toFloat(), lastPreview.width.toFloat())
        val cx = viewRect.centerX()
        val cy = viewRect.centerY()
        if (rot == Surface.ROTATION_90 || rot == Surface.ROTATION_270) {
            bufferRect.offset(cx - bufferRect.centerX(), cy - bufferRect.centerY())
            m.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = max(vh / lastPreview.height, vw / lastPreview.width)
            m.postScale(scale, scale, cx, cy)
            m.postRotate(90f * (rot - 2), cx, cy)
        } else if (rot == Surface.ROTATION_180) {
            m.postRotate(180f, cx, cy)
        }
        tv.setTransform(m)
    }

    // ---------- capture request ----------

    private fun pickRange(): Range<Int>? {
        val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?: return null
        return ranges.filter { it.upper == cfg.fps }.maxByOrNull { it.lower }
    }

    private fun hsRange(): Range<Int>? {
        val m = map ?: return null
        return m.getHighSpeedVideoFpsRangesFor(Size(cfg.width, cfg.height))
            .filter { it.upper == cfg.fps }.maxByOrNull { it.lower }
    }

    private fun pick(
        b: CaptureRequest.Builder,
        key: CaptureRequest.Key<Int>,
        avail: CameraCharacteristics.Key<IntArray>,
        prefs: List<Int>
    ) {
        val a = chars.get(avail) ?: return
        prefs.firstOrNull { a.contains(it) }?.let { b.set(key, it) }
    }

    private fun buildRequest(): CaptureRequest.Builder? {
        val cd = device ?: return null
        val preview = previewSurface ?: return null
        val rs = recSurface ?: return null
        val b = cd.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        b.addTarget(preview)
        b.addTarget(rs)

        (if (highSpeed) hsRange() else pickRange())?.let {
            b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it)
        }

        // ----- focus -----
        if (manualFocus && minFocusDiopters > 0f) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusFraction * minFocusDiopters)
        } else {
            val modes = when {
                afLocked -> listOf(CameraMetadata.CONTROL_AF_MODE_AUTO)
                cfg.afFast -> listOf(
                    CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                    CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                )
                else -> listOf(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            }
            pick(b, CaptureRequest.CONTROL_AF_MODE, CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES, modes)
            if (!highSpeed && (chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) > 0) {
                (afRegion ?: if (cfg.faceAf) faceRegion else null)?.let {
                    b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(it))
                }
            }
        }
        if (highSpeed) return b // constrained high-speed sessions only accept a small set of keys

        // ----- zoom -----
        if (Build.VERSION.SDK_INT >= 30 && cfg.zoomMethod == 0) {
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
        } else {
            chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.let { a ->
                val w = (a.width() / max(zoom, 1f)).toInt()
                val h = (a.height() / max(zoom, 1f)).toInt()
                val l = a.left + (a.width() - w) / 2
                val t = a.top + (a.height() - h) / 2
                b.set(CaptureRequest.SCALER_CROP_REGION, Rect(l, t, l + w, t + h))
            }
        }

        // ----- exposure -----
        if (cfg.manualExposure) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            var ns = (1e9 / cfg.shutterDenom).toLong()
            chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let {
                ns = ns.coerceIn(it.lower, it.upper)
            }
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, ns)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, cfg.iso.coerceIn(isoRange.lower, isoRange.upper))
        } else {
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, cfg.ev)
            if (aeLock && aeLockAvail) b.set(CaptureRequest.CONTROL_AE_LOCK, true)
        }
        if (awbLock && awbLockAvail) b.set(CaptureRequest.CONTROL_AWB_LOCK, true)
        if (torch && flashAvail) b.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH)
        pick(
            b, CaptureRequest.CONTROL_AE_ANTIBANDING_MODE,
            CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES,
            listOf(
                when (cfg.antiband) {
                    1 -> CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_50HZ
                    2 -> CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_60HZ
                    3 -> CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_OFF
                    else -> CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_AUTO
                }
            )
        )

        // ----- processing: noise reduction on/off, sharpening always off -----
        pick(
            b, CaptureRequest.NOISE_REDUCTION_MODE,
            CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES,
            if (cfg.nrOff) listOf(CameraMetadata.NOISE_REDUCTION_MODE_OFF, CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL)
            else listOf(CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY, CameraMetadata.NOISE_REDUCTION_MODE_FAST)
        )
        pick(
            b, CaptureRequest.EDGE_MODE, CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES,
            listOf(CameraMetadata.EDGE_MODE_OFF)
        )

        // ----- stabilization: one toggle; request OIS + EIS together where supported -----
        pick(
            b, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
            CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION,
            listOf(
                if (cfg.stab != 0) CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON
                else CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF
            )
        )
        pick(
            b, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
            CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES,
            listOf(
                if (cfg.stab != 0 && !cfg.steady) CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
                else CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF
            )
        )

        // ----- face detection for face-priority AF -----
        if (cfg.faceAf && faceAfSupported) {
            b.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE)
        }

        // ----- look: flat (best effort via the tone curve) -----
        if (cfg.flat) {
            val modes = chars.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)
            if (modes?.contains(CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE) == true) {
                val pts = Look.FLAT_POINTS
                b.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)
                b.set(CaptureRequest.TONEMAP_CURVE, TonemapCurve(pts, pts, pts))
            }
        }
        return b
    }

    private val resultCb = object : CameraCaptureSession.CaptureCallback() {
        private var last = 0L

        override fun onCaptureCompleted(
            s: CameraCaptureSession,
            r: CaptureRequest,
            res: TotalCaptureResult
        ) {
            liveIso = res.get(CaptureResult.SENSOR_SENSITIVITY) ?: liveIso
            res.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let {
                if (it > 0) {
                    liveExpNs = it
                    liveShutterDenom = (1e9 / it).roundToInt()
                }
            }
            liveFocus = res.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: -1f
            val sl = steadyLog
            if (sl != null && sl.t0Ns == 0L) {
                res.get(CaptureResult.SENSOR_TIMESTAMP)?.let { sl.t0Ns = it }
            }
            if (!(cfg.faceAf && faceAfSupported)) return
            val now = SystemClock.elapsedRealtime()
            if (now - last < 400) return
            last = now
            val a = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
            val face = res.get(CaptureResult.STATISTICS_FACES)
                ?.maxByOrNull { it.bounds.width() * it.bounds.height() }
            val old = faceRegion
            if (face == null) {
                if (old != null) {
                    faceRegion = null
                    handler.post { updateRepeating() }
                }
                return
            }
            val fb = face.bounds
            val rect = Rect(
                max(fb.left, a.left), max(fb.top, a.top),
                min(fb.right, a.right - 1), min(fb.bottom, a.bottom - 1)
            )
            if (rect.width() <= 0 || rect.height() <= 0) return
            val moved = old == null ||
                abs(old.rect.centerX() - rect.centerX()) > a.width() * 0.04 ||
                abs(old.rect.centerY() - rect.centerY()) > a.height() * 0.04
            if (moved) {
                faceRegion = MeteringRectangle(rect, 1000)
                handler.post { updateRepeating() }
            }
        }
    }

    private fun currentBuilder(): CaptureRequest.Builder? =
        if (cfg.mode == 1) buildPhotoPreview() else buildRequest()

    fun updateRepeating() {
        val s = session ?: return
        try {
            val b = currentBuilder() ?: return
            if (s is CameraConstrainedHighSpeedCaptureSession) {
                s.setRepeatingBurst(s.createHighSpeedRequestList(b.build()), null, handler)
            } else {
                s.setRepeatingRequest(b.build(), resultCb, cbHandler)
            }
        } catch (e: Exception) {
            ui.onMessage("Camera: ${e.message}")
        }
    }


    // ---------- photo mode ----------

    private fun areaDesc(l: Array<Size>?): List<Size> = l?.sortedByDescending { it.width * it.height } ?: emptyList()

    val rawAvail get() = rawSize != null

    /** 0 = phone JPEG, 1 = RAW develop, 3 = native Ultra HDR JPEG_R (Android 14+ and camera-dependent). */
    fun effMode(): Int = if (cfg.photoFormat == 1 && rawAvail && cfg.scene == 0) 1 else 0

    val ultraHdrAvailable: Boolean get() = Build.VERSION.SDK_INT >= 34 && jprSizes.isNotEmpty()

    private fun captureFormat(): Int = when {
        effMode() == 1 -> ImageFormat.RAW_SENSOR
        // JPEG_R must be saved byte-for-byte to preserve its gain map. If a pixel filter
        // is selected, use ordinary JPEG so the requested look is actually rendered.
        cfg.photoFormat == 3 && ultraHdrAvailable &&
            !cfg.glow && !cfg.bw && !cfg.flat && !cfg.upscale &&
            store.trim(cfg.cameraId).isNeutral() -> ImageFormat.JPEG_R
        else -> ImageFormat.JPEG
    }

    /** Sizes the user can pick for the current capture mode. */
    fun captureSizes(): List<Size> = when {
        effMode() == 1 -> listOfNotNull(rawSize)
        captureFormat() == ImageFormat.JPEG_R -> jprSizes
        else -> jpegSizes
    }

    /** With nothing picked, about 12 MP: far quicker to shoot and save than the 50 MP mode. */
    fun captureSize(): Size {
        val l = captureSizes()
        if (l.isEmpty()) return Size(4000, 3000)
        l.firstOrNull { it.width == cfg.photoW && it.height == cfg.photoH }?.let { return it }
        if (effMode() == 1 || captureFormat() == ImageFormat.JPEG_R) return l.first()
        val target = min(12_600_000L, lensSafeArea)
        val ok = l.filter { it.width.toLong() * it.height <= target }
        return ok.firstOrNull { it.width * 3 == it.height * 4 } ?: ok.firstOrNull() ?: l.last()
    }

    private fun previewSizeForPhoto(cap: Size): Size {
        val opts = map?.getOutputSizes(SurfaceTexture::class.java)?.filter { it.height <= 1080 } ?: emptyList()
        val ar = cap.width.toFloat() / cap.height
        val same = opts.filter { abs(it.width.toFloat() / it.height - ar) < 0.03f }
        return same.maxByOrNull { it.width * it.height }
            ?: opts.minByOrNull { abs(it.width.toFloat() / it.height - ar) }
            ?: Size(1280, 720)
    }

    private fun needRotation(): Int {
        val sensor = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val deg = displayDegrees()
        return if (isFront(cfg.cameraId)) (sensor + deg) % 360 else (sensor - deg + 360) % 360
    }

    private fun configurePhoto(cd: CameraDevice) {
        cfg.iso = cfg.iso.coerceIn(isoRange.lower, isoRange.upper)
        cfg.ev = cfg.ev.coerceIn(evRange.lower, evRange.upper)
        if (cfg.scene == 0 && cfg.photoFormat == 1 && effMode() != 1 && warnedLens != cfg.cameraId) {
            warnedLens = cfg.cameraId
            ui.onMessage("This lens has no RAW: using phone JPEG with this lens's colour trim")
        }
        val st = tv.surfaceTexture ?: return
        val cap = captureSize()
        val ps = previewSizeForPhoto(cap)
        lastPreview = ps
        st.setDefaultBufferSize(ps.width, ps.height)
        previewSurface?.release()
        val preview = Surface(st)
        previewSurface = preview

        val rd = ImageReader.newInstance(cap.width, cap.height, captureFormat(), 4)
        rd.setOnImageAvailableListener({ r -> onImage(r) }, cbHandler)
        reader = rd
        ui.onPreviewSize(ps.width, ps.height)

        val my = ++gen
        val cb = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                if (my != gen || device !== cd) {
                    s.close()
                    return
                }
                session = s
                refreshTransform()
                updateRepeating()
                ui.onReady()
            }

            override fun onConfigureFailed(s: CameraCaptureSession) {
                ui.onMessage("Camera rejected this lens / photo format combination")
            }
        }
        try {
            @Suppress("DEPRECATION")
            cd.createCaptureSession(listOf(preview, rd.surface), cb, handler)
        } catch (e: Exception) {
            ui.onMessage("Session error: ${e.message}")
        }
    }

    private fun applyPhotoControls(b: CaptureRequest.Builder, capture: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) b.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)

        // focus
        if (manualFocus && minFocusDiopters > 0f) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusFraction * minFocusDiopters)
        } else {
            pick(
                b, CaptureRequest.CONTROL_AF_MODE, CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES,
                listOf(
                    if (afLocked) CameraMetadata.CONTROL_AF_MODE_AUTO
                    else CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                )
            )
            if ((chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) > 0) {
                (afRegion ?: if (cfg.faceAf) faceRegion else null)?.let {
                    b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(it))
                }
            }
        }

        // exposure and flash
        val flash = capture && flashAvail && cfg.flashMode != 0
        if (cfg.manualExposure) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            var ns = (1e9 / cfg.shutterDenom).toLong()
            chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let { ns = ns.coerceIn(it.lower, it.upper) }
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, ns)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, cfg.iso.coerceIn(isoRange.lower, isoRange.upper))
        } else {
            b.set(
                CaptureRequest.CONTROL_AE_MODE,
                when {
                    flash && cfg.flashMode == 1 -> CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH
                    flash && cfg.flashMode == 2 -> CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH
                    else -> CameraMetadata.CONTROL_AE_MODE_ON
                }
            )
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, cfg.ev)
            if (aeLock && aeLockAvail) b.set(CaptureRequest.CONTROL_AE_LOCK, true)
        }
        if (flash && cfg.flashMode == 1) b.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_SINGLE)
        // zero-shutter-lag: lets the phone use a frame it already has, so the shot happens the instant you tap
        if (Build.VERSION.SDK_INT >= 33 && effMode() == 0 && cfg.scene == 0 && !cfg.manualExposure && cfg.flashMode == 0 && zoom <= 1.01f &&
            chars.availableCaptureRequestKeys.contains(CaptureRequest.CONTROL_ENABLE_ZSL)
        ) {
            b.set(CaptureRequest.CONTROL_ENABLE_ZSL, true)
        }
        if (awbLock && awbLockAvail) b.set(CaptureRequest.CONTROL_AWB_LOCK, true)

        pick(
            b, CaptureRequest.CONTROL_AE_ANTIBANDING_MODE,
            CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES,
            listOf(
                when (cfg.antiband) {
                    1 -> CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_50HZ
                    2 -> CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_60HZ
                    3 -> CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_OFF
                    else -> CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_AUTO
                }
            )
        )
        val nrLevel = if (cfg.scene in 1..2) 2 else cfg.photoNr
        if (nrLevel == 0) {
            pick(
                b, CaptureRequest.NOISE_REDUCTION_MODE,
                CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES,
                listOf(CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL, CameraMetadata.NOISE_REDUCTION_MODE_OFF)
            )
        } else if (nrLevel >= 2) {
            pick(
                b, CaptureRequest.NOISE_REDUCTION_MODE,
                CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES,
                listOf(CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY, CameraMetadata.NOISE_REDUCTION_MODE_FAST)
            )
        }
        if (!capture && cfg.flat) {
            // the preview shows the flat look; the saved photo gets the same curve afterwards (Look.kt)
            val modes = chars.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)
            if (modes?.contains(CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE) == true) {
                val pts = Look.FLAT_POINTS
                b.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)
                b.set(CaptureRequest.TONEMAP_CURVE, TonemapCurve(pts, pts, pts))
            }
        }
        if (!capture && cfg.faceAf && faceAfSupported) {
            b.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE)
        }
        if (capture) {
            b.set(CaptureRequest.JPEG_QUALITY, cfg.photoQuality.coerceIn(50, 100).toByte())
            b.set(CaptureRequest.JPEG_ORIENTATION, if (effMode() == 1) 0 else needRotation())
            if (effMode() == 1) {
                val modes = chars.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES)
                if (modes?.contains(CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON) == true) {
                    b.set(
                        CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
                        CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON
                    )
                }
            }
        }
    }

    private fun buildPhotoPreview(): CaptureRequest.Builder? {
        val cd = device ?: return null
        val preview = previewSurface ?: return null
        val b = cd.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        b.addTarget(preview)
        applyPhotoControls(b, false)
        return b
    }

    /** Seconds left of a long exposure (night / astro), or -1 when none is running. */
    @Volatile
    var longLeftSec = -1
        private set
    private var longEndAt = 0L

    private val longTick = object : Runnable {
        override fun run() {
            if (longEndAt == 0L) return
            val left = ((longEndAt - SystemClock.elapsedRealtime() + 999) / 1000).toInt()
            longLeftSec = left.coerceAtLeast(0)
            ui.onLongExposure(longLeftSec)
            if (left > 0) handler.postDelayed(this, 500)
        }
    }

    private fun endLong() {
        longEndAt = 0L
        longLeftSec = -1
        handler.removeCallbacks(longTick)
        ui.onLongExposure(-1)
    }

    /** Stops a night / astro exposure that is still running. */
    fun cancelCapture() {
        if (longEndAt == 0L) return
        try {
            session?.abortCaptures()
        } catch (_: Exception) {
        }
        synchronized(lock) {
            shot?.image?.close()
            shot = null
        }
        endLong()
        finishBusy()
    }

    fun takePhoto() {
        if (cfg.mode != 1) return
        if (busy) {
            if (longEndAt != 0L) cancelCapture()
            return
        }
        val s = session ?: return
        if (reader == null) return
        busy = true
        ui.onBusy(true)
        val useFlash = flashAvail && cfg.flashMode != 0 && !cfg.manualExposure && cfg.scene == 0
        if (useFlash) {
            try {
                val b = buildPhotoPreview() ?: throw IllegalStateException("no preview")
                applyPhotoControls(b, true)
                b.set(
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START
                )
                s.capture(b.build(), null, handler)
            } catch (_: Exception) {
            }
            handler.postDelayed({ doCapture() }, 900)
        } else {
            doCapture()
        }
    }

    private fun finishBusy() {
        busy = false
        handler.post { ui.onBusy(false) }
    }

    /** Night = 1 s at ISO 1000. Astro = 5 min at ISO 100 (several shorter frames if the phone's longest exposure is shorter). */
    private fun scenePlan(): ScenePlan? {
        if (cfg.scene !in 1..2 || !manualSensor) return null
        val er = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: return null
        val maxFrame = chars.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION) ?: er.upper
        val cap = min(er.upper, maxFrame)
        val wantNs = if (cfg.scene == 1) 1_000_000_000L else 300_000_000_000L
        val iso = (if (cfg.scene == 1) 1000 else 100).coerceIn(isoRange.lower, isoRange.upper)
        val per = min(wantNs, cap).coerceAtLeast(er.lower)
        val n = ((wantNs + per - 1) / per).toInt().coerceIn(1, 60)
        return ScenePlan(per, iso, n)
    }

    /** HDR: three frames, about 4x darker / normal / 4x brighter, from the exposure the preview is using. */
    private fun hdrPlan(): HdrPlan? {
        val er = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: return null
        var e0: Long
        var s0: Int
        if (cfg.manualExposure) {
            e0 = (1e9 / cfg.shutterDenom).toLong()
            s0 = cfg.iso
        } else {
            e0 = liveExpNs
            s0 = liveIso
        }
        if (!manualSensor || e0 <= 0L || s0 <= 0) return null
        e0 = e0.coerceIn(er.lower, er.upper)
        s0 = s0.coerceIn(isoRange.lower, isoRange.upper)
        // dark frame: 4x less light, shorter exposure first, then lower ISO
        val eD = max(er.lower.toDouble(), e0 / 4.0).toLong().coerceAtLeast(1L)
        val usedD = e0.toDouble() / eD
        val sD = max(isoRange.lower.toDouble(), s0 / (4.0 / usedD)).toInt().coerceAtLeast(1)
        val dark = usedD * (s0.toDouble() / sD)
        // bright frame: 4x more light, ISO first (same hand-shake blur), then exposure up to about 1/10 s
        val sB = min(isoRange.upper.toDouble(), s0 * 4.0).toInt().coerceAtLeast(s0)
        val usedB = sB.toDouble() / s0
        val cap = min(er.upper.toDouble(), max(e0.toDouble(), 100_000_000.0))
        val eB = min(cap, e0 * (4.0 / usedB)).toLong().coerceAtLeast(e0)
        val bright = (eB.toDouble() / e0) * usedB
        if (dark < 1.5 && bright < 1.5) return null
        return if (cfg.hdrFrames == 2) {
            // Two-frame mode brackets around the current exposure: highlight-safe dark + shadow-safe bright.
            HdrPlan(longArrayOf(eD, eB), intArrayOf(sD, sB))
        } else {
            HdrPlan(longArrayOf(eD, e0, eB), intArrayOf(sD, s0, sB))
        }
    }

    private fun doCapture() {
        val cd = device
        val s = session
        val rd = reader
        if (cd == null || s == null || rd == null) {
            finishBusy()
            return
        }
        try {
            val fmt = captureFormat()
            if (cfg.photoFormat == 3 && ultraHdrAvailable && fmt != ImageFormat.JPEG_R) {
                handler.post { ui.onMessage("Photo filters are active; using standard JPEG so the selected look is applied. Turn filters off for native Ultra HDR.") }
            }
            val plan = scenePlan()
            if (cfg.scene in 1..2 && plan == null) {
                handler.post { ui.onMessage("This lens has no manual exposure, so night / astro can't be used on it") }
                finishBusy()
                return
            }
            val hplan = if (cfg.scene == 3) hdrPlan() else null
            if (cfg.scene == 3 && hplan == null) {
                handler.post { ui.onMessage("Not enough exposure range for HDR here, taking a normal photo") }
            }
            val reqs = ArrayList<CaptureRequest>()
            val count = hplan?.exp?.size ?: plan?.frames ?: 1
            for (i in 0 until count) {
                val b = cd.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                b.addTarget(rd.surface)
                applyPhotoControls(b, true)
                if (hplan != null) {
                    b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                    b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, hplan.exp[i])
                    b.set(CaptureRequest.SENSOR_SENSITIVITY, hplan.iso[i])
                    b.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
                    if (awbLockAvail) b.set(CaptureRequest.CONTROL_AWB_LOCK, true)
                } else if (plan != null) {
                    b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                    b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, plan.expNs)
                    b.set(CaptureRequest.SENSOR_FRAME_DURATION, plan.expNs)
                    b.set(CaptureRequest.SENSOR_SENSITIVITY, plan.iso)
                    b.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
                    if (awbLockAvail) b.set(CaptureRequest.CONTROL_AWB_LOCK, true)
                }
                reqs.add(b.build())
            }
            val sh = Shot(
                cfg.cameraId, fmt, chars, needRotation(), cfg.photoQuality, reqs.size,
                cfg.bw, cfg.flat && !cfg.bw, cfg.photoLook, hplan != null, cfg.upscale && currentLensNeedsUpscale(), cfg.glow
            )
            synchronized(lock) { shot = sh }
            if (plan != null && plan.expNs * reqs.size >= 1_500_000_000L) {
                longEndAt = SystemClock.elapsedRealtime() + (plan.expNs * reqs.size) / 1_000_000L + 800L
                handler.post(longTick)
            }
            if (reqs.size == 1) s.capture(reqs[0], captureCb, cbHandler) else s.captureBurst(reqs, captureCb, cbHandler)
        } catch (e: Exception) {
            synchronized(lock) { shot = null }
            endLong()
            ui.onMessage("Couldn't take photo: ${e.message}")
            finishBusy()
        }
    }

    private val captureCb = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
            synchronized(lock) {
                shot?.result = res
                maybeProcess()
            }
        }

        override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, f: CaptureFailure) {
            synchronized(lock) {
                shot?.image?.close()
                shot = null
            }
            handler.post {
                endLong()
                ui.onMessage("Capture failed")
            }
            finishBusy()
        }
    }

    private fun onImage(r: ImageReader) {
        val img = try {
            r.acquireNextImage()
        } catch (e: Exception) {
            null
        } ?: return
        synchronized(lock) {
            val sh = shot
            if (sh == null) {
                img.close()
            } else if (sh.format == ImageFormat.RAW_SENSOR) {
                if (sh.image != null) img.close() else sh.image = img
                maybeProcess()
            } else {
                // copy the JPEG out straight away so the reader is free for the next shot
                try {
                    val buf = img.planes[0].buffer
                    val bytes = ByteArray(buf.remaining())
                    buf.get(bytes)
                    sh.jpegs.add(bytes)
                } catch (_: Exception) {
                }
                img.close()
                maybeProcess()
            }
        }
    }

    /** Must be called while holding [lock]. */
    private fun maybeProcess() {
        val sh = shot ?: return
        if (sh.started) return
        if (sh.format == ImageFormat.RAW_SENSOR) {
            if (sh.image == null || sh.result == null) return
        } else if (sh.jpegs.size < sh.expected) {
            return
        }
        sh.started = true
        shot = null
        handler.post { endLong() }
        if (sh.format != ImageFormat.RAW_SENSOR) {
            // the picture is safely in memory: let the shutter be used again while it is written to storage
            busy = false
            handler.post { ui.onBusy(false) }
        }
        jobCount.incrementAndGet()
        procExec.execute { processShot(sh) }
    }

    /** Applies look, lens colour trim and optional upscaling to an upright bitmap, recycling what it replaces. */
    private fun finishLook(bmp: Bitmap, sh: Shot, trim: Trim): Bitmap {
        var out = bmp
        val styled = Look.apply(out, sh.bw, sh.flat, sh.photoLook)
        if (styled !== out) {
            out.recycle()
            out = styled
        }
        if (sh.glow) {
            val bloomed = Look.glow(out)
            out.recycle()
            out = bloomed
        }
        if (!trim.isNeutral()) {
            val t = ColorTrim.apply(out, trim, 0)
            out.recycle()
            out = t
        }
        if (sh.upscale) {
            val u = Look.upscale(out, 12_600_000L)
            if (u !== out) {
                out.recycle()
                out = u
            }
        }
        return out
    }

    private fun processShot(sh: Shot) {
        var saved: Uri? = null
        try {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val base = "IMG_${stamp}_L${sh.lens}"
            val trim = store.trim(sh.lens)
            if (Build.VERSION.SDK_INT >= 34 && sh.format == ImageFormat.JPEG_R) {
                // Preserve the camera-generated Ultra HDR gain map byte-for-byte. Re-encoding
                // through Bitmap/JPEG would destroy the HDR metadata and reduce highlight range.
                saved = saveBytes(sh.jpegs.first(), "$base.jpg")
            } else if (sh.format == ImageFormat.RAW_SENSOR) {
                val img = sh.image!!
                val res = sh.result!!
                val dev = RawDeveloper.develop(img, sh.chars, res, trim)
                img.close()
                sh.image = null
                val out = finishLook(rotateBy(dev, sh.rotDeg), sh, Trim())
                saved = saveBitmap(out, "$base.jpg", sh.quality)
                out.recycle()
            } else if (sh.hdr && sh.jpegs.size >= 2) {
                val merged = HdrFusion.merge(sh.jpegs) ?: throw IllegalStateException("Couldn't combine the HDR frames")
                val out = finishLook(rotateBy(merged, sh.rotDeg), sh, trim)
                saved = saveBitmap(out, "$base.jpg", sh.quality)
                out.recycle()
            } else if (sh.jpegs.size > 1) {
                // astro: several long frames added together (linear light) to make up the full exposure
                val sum = FrameStack.sum(sh.jpegs) ?: throw IllegalStateException("Couldn't combine the frames")
                val out = finishLook(rotateBy(sum, sh.rotDeg), sh, trim)
                saved = saveBitmap(out, "$base.jpg", sh.quality)
                out.recycle()
            } else {
                val bytes = sh.jpegs[0]
                saved = if (sh.bw || sh.flat || sh.upscale || sh.glow || !trim.isNeutral()) {
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    val out = finishLook(rotateBy(bmp, sh.rotDeg), sh, trim)
                    val u = saveBitmap(out, "$base.jpg", sh.quality)
                    out.recycle()
                    u
                } else {
                    saveBytes(bytes, "$base.jpg")
                }
            }
        } catch (t: Throwable) {
            try {
                sh.image?.close()
            } catch (_: Exception) {
            }
            val msg = t.message ?: t.javaClass.simpleName
            handler.post { ui.onMessage("Photo failed: $msg") }
        } finally {
            if (sh.format == ImageFormat.RAW_SENSOR) busy = false
            jobCount.decrementAndGet()
            val u = saved
            if (u != null) lastPhotoUri = u
            handler.post {
                ui.onBusy(false)
                if (u != null) ui.onPhotoSaved(u)
            }
        }
    }

    private fun rotateBy(b: Bitmap, deg: Int): Bitmap {
        if (deg == 0) return b
        val m = Matrix()
        m.postRotate(deg.toFloat())
        val r = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, false)
        b.recycle()
        return r
    }

    private fun newImageUri(name: String, mime: String): Uri {
        val v = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Iris Cam")
            put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        return ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)
            ?: throw IllegalStateException("Can't create file")
    }

    private fun finishImageUri(uri: Uri) {
        val v = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
        ctx.contentResolver.update(uri, v, null, null)
    }

    private fun saveBytes(bytes: ByteArray, name: String): Uri {
        val uri = newImageUri(name, "image/jpeg")
        ctx.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
        finishImageUri(uri)
        return uri
    }

    private fun saveBitmap(bmp: Bitmap, name: String, q: Int): Uri {
        val uri = newImageUri(name, "image/jpeg")
        ctx.contentResolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.JPEG, q, it) }
        finishImageUri(uri)
        return uri
    }

    /** Sensor details per lens, shown in Phone capabilities while in photo mode. */
    fun sensorReport(): String {
        val sb = StringBuilder()
        for (id in cameraIds) {
            try {
                val c = manager.getCameraCharacteristics(id)
                val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
                val m = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                sb.append("CAMERA ").append(id).append("  ").append(cameraLabel(id)).append('\n')
                c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)?.let {
                    sb.append("  Sensor pixels: ").append(it.width).append(" x ").append(it.height).append('\n')
                }
                c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.let {
                    sb.append("  Sensor size: ").append("%.2f x %.2f mm".format(it.width, it.height)).append('\n')
                }
                c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.let {
                    sb.append("  Focal length: ").append(it.joinToString(", ")).append(" mm\n")
                }
                c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.let {
                    sb.append("  Aperture: f/").append(it.joinToString(", ")).append('\n')
                }
                c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)?.let {
                    val n = arrayOf("RGGB", "GRBG", "GBRG", "BGGR", "RGB").getOrElse(it) { "?" }
                    sb.append("  Colour filter: ").append(n).append('\n')
                }
                sb.append("  White level: ").append(c.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 0).append('\n')
                c.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)?.let {
                    val l = (0 until 4).map { i -> it.getOffsetForIndex(i and 1, i shr 1) }
                    sb.append("  Black levels: ").append(l.joinToString(", ")).append('\n')
                }
                c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let {
                    sb.append("  ISO range: ").append(it.lower).append(" to ").append(it.upper).append('\n')
                }
                c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let {
                    sb.append("  Shutter range: 1/").append((1e9 / it.lower).roundToInt())
                        .append(" to ").append("%.1f s".format(it.upper / 1e9)).append('\n')
                }
                sb.append("  Closest focus: ").append(c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f)
                    .append(" diopters\n")
                sb.append("  Manual exposure: ")
                    .append(if (caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)) "yes" else "no").append('\n')
                val raw = m?.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width * it.height }
                sb.append("  RAW: ")
                    .append(if (caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW) && raw != null) "yes ($raw)" else "no")
                    .append('\n')
                m?.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width * it.height }?.let {
                    sb.append("  Biggest JPEG: ").append(it).append('\n')
                }
                if (Build.VERSION.SDK_INT >= 34) {
                    val u = m?.getOutputSizes(ImageFormat.JPEG_R)
                    sb.append("  Ultra HDR (JPEG_R): ")
                        .append(if (u != null && u.isNotEmpty()) "yes" else "no").append('\n')
                }
                if (Build.VERSION.SDK_INT >= 30) {
                    c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let {
                        sb.append("  Zoom range: ").append(it.lower).append("x to ").append(it.upper).append("x\n")
                    }
                }
                val logical = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
                if (logical && Build.VERSION.SDK_INT >= 28) {
                    sb.append("  Lenses inside: ").append(c.physicalCameraIds.joinToString(", ")).append('\n')
                }
                if (Build.VERSION.SDK_INT >= 31) {
                    try {
                        val ext = manager.getCameraExtensionCharacteristics(id).supportedExtensions
                        val names = ext.map {
                            when (it) {
                                0 -> "Auto"
                                1 -> "Face retouch"
                                2 -> "Portrait blur"
                                3 -> "HDR"
                                4 -> "Night"
                                else -> "#$it"
                            }
                        }
                        sb.append("  Built-in modes for apps: ")
                            .append(if (names.isEmpty()) "none" else names.joinToString(", ")).append('\n')
                    } catch (_: Exception) {
                    }
                }
                sb.append('\n')
            } catch (e: Exception) {
                sb.append("CAMERA ").append(id).append(": ").append(e.message).append("\n\n")
            }
        }
        return sb.toString().ifBlank { "No cameras found" }
    }

    // ---------- capabilities ----------

    fun capabilitiesReport(): String {
        val sb = StringBuilder()
        for (id in cameraIds) {
            val c = manager.getCameraCharacteristics(id)
            sb.append("CAMERA ").append(id).append("  (").append(cameraLabel(id)).append(")\n")
            val level = when (c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
                CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "Legacy"
                CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "Limited"
                CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "Full"
                CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "Level 3"
                else -> "Unknown"
            }
            val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            fun yn(x: Int) = if (caps.contains(x)) "yes" else "no"
            sb.append("  Hardware level: ").append(level).append('\n')
            sb.append("  Manual exposure: ").append(yn(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)).append('\n')
            sb.append("  RAW capture: ").append(yn(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW)).append('\n')
            sb.append("  High-speed video: ")
                .append(yn(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO)).append('\n')
            val logical = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
            sb.append("  Multi-lens camera: ").append(if (logical) "yes" else "no").append('\n')
            if (logical && Build.VERSION.SDK_INT >= 28) {
                sb.append("  Lenses inside: ").append(c.physicalCameraIds.joinToString(", ")).append('\n')
            }
            if (Build.VERSION.SDK_INT >= 30) {
                c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let {
                    sb.append("  Zoom range: ").append(it.lower).append("x to ").append(it.upper).append("x\n")
                }
                val bokeh = c.get(CameraCharacteristics.CONTROL_AVAILABLE_EXTENDED_SCENE_MODE_CAPABILITIES)
                sb.append("  Built-in blur (bokeh) for apps: ")
                    .append(if (bokeh.isNullOrEmpty()) "no" else "yes").append('\n')
            }
            sb.append("  Closest focus: ")
                .append(c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f).append(" diopters\n")
            sb.append("  Face detection: up to ")
                .append(c.get(CameraCharacteristics.STATISTICS_INFO_MAX_FACE_COUNT) ?: 0).append(" faces\n")
            sb.append("  Flashlight: ")
                .append(if (c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) "yes" else "no").append('\n')
            val m = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            m?.getOutputSizes(MediaRecorder::class.java)
                ?.filter { it.width * 9 == it.height * 16 }
                ?.sortedByDescending { it.width }?.take(4)
                ?.let { sb.append("  Biggest video sizes: ").append(it.joinToString(", ")).append('\n') }
            m?.highSpeedVideoSizes?.takeIf { it.isNotEmpty() }
                ?.let { sb.append("  High-speed sizes: ").append(it.joinToString(", ")).append('\n') }
            sb.append('\n')
        }
        return sb.toString().ifBlank { "No cameras found" }
    }

    // ---------- zoom ----------

    fun setZoom(z: Float) {
        if (highSpeed || session == null) return
        zoom = z.coerceIn(zoomRange.lower, zoomHi())
        updateRepeating()
        ui.onZoomChanged(zoom)
    }

    fun cancelZoomPull() {
        zoomAnim?.cancel()
        zoomAnim = null
    }

    /** Steady zoom move from one ratio to another over ms (even steps in ratio, good for dolly zoom). */
    fun zoomPull(from: Float, to: Float, ms: Long) {
        if (highSpeed || session == null) return
        cancelZoomPull()
        val a = max(from, 0.01f)
        val b = max(to, 0.01f)
        zoomAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ms
            interpolator = LinearInterpolator()
            addUpdateListener {
                val t = (it.animatedValue as Float).toDouble()
                setZoom((a * Math.pow((b / a).toDouble(), t)).toFloat())
            }
            start()
        }
    }

    fun toggleTorch() {
        when {
            !flashAvail -> ui.onMessage("This camera has no flashlight")
            highSpeed -> ui.onMessage("Torch isn't available at 120 fps")
            else -> {
                torch = !torch
                updateRepeating()
                ui.onStateChanged()
            }
        }
    }

    // ---------- locks ----------

    fun toggleAeLock() {
        when {
            highSpeed -> ui.onMessage("Locks aren't available at 120 fps")
            cfg.manualExposure -> ui.onMessage("Exposure is already fixed in Manual mode")
            !aeLockAvail -> ui.onMessage("This camera can't lock exposure")
            else -> {
                aeLock = !aeLock
                updateRepeating()
                ui.onStateChanged()
            }
        }
    }

    fun toggleAwbLock() {
        when {
            highSpeed -> ui.onMessage("Locks aren't available at 120 fps")
            !awbLockAvail -> ui.onMessage("This camera can't lock white balance")
            else -> {
                awbLock = !awbLock
                updateRepeating()
                ui.onStateChanged()
            }
        }
    }

    fun clearAeLock() {
        aeLock = false
    }

    // ---------- focus ----------

    fun setFocus(f: Float) {
        manualFocus = true
        afLocked = false
        afRegion = null
        focusFraction = f.coerceIn(0f, 1f)
        updateRepeating()
        ui.onFocusMoved(focusFraction)
    }

    fun setAutoFocus() {
        cancelRack()
        manualFocus = false
        afLocked = false
        afRegion = null
        updateRepeating()
        ui.onStateChanged()
    }

    /** Tap to focus. nx, ny are 0..1 across the video image. lock = hold focus there. */
    fun focusAt(nx: Float, ny: Float, lock: Boolean) {
        if (highSpeed || session == null) return
        if ((chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) <= 0) {
            ui.onMessage("Tap-to-focus isn't supported by this camera")
            return
        }
        val a = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        cancelRack()
        manualFocus = false
        val sensor = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val x: Float
        val y: Float
        when ((sensor - displayDegrees() + 360) % 360) {
            180 -> {
                x = 1f - nx
                y = 1f - ny
            }
            90 -> {
                x = ny
                y = 1f - nx
            }
            270 -> {
                x = 1f - ny
                y = nx
            }
            else -> {
                x = nx
                y = ny
            }
        }
        // the picture is a centred crop of the sensor area when its shape differs
        val ar = lastPreview.width.toFloat() / lastPreview.height
        val aa = a.width().toFloat() / a.height()
        val cw = if (ar >= aa) a.width() else (a.height() * ar).toInt()
        val chh = if (ar >= aa) (a.width() / ar).toInt() else a.height()
        val left = a.left + (a.width() - cw) / 2
        val top = a.top + (a.height() - chh) / 2
        val cx = left + (x * cw).toInt()
        val cy = top + (y * chh).toInt()
        val half = (a.width() * 0.06f).toInt()
        val r = Rect(
            max(cx - half, a.left), max(cy - half, a.top),
            min(cx + half, a.right - 1), min(cy + half, a.bottom - 1)
        )
        afRegion = MeteringRectangle(r, 1000)
        afLocked = lock
        updateRepeating()
        if (lock) triggerAf()
        ui.onStateChanged()
    }

    private fun triggerAf() {
        val s = session ?: return
        try {
            val b = currentBuilder() ?: return
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            s.capture(b.build(), null, handler)
        } catch (_: Exception) {
        }
    }

    fun cancelRack() {
        rackAnim?.cancel()
        rackAnim = null
    }

    fun rack(from: Float, to: Float, ms: Long) {
        cancelRack()
        rackAnim = ValueAnimator.ofFloat(from, to).apply {
            duration = ms
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { setFocus(it.animatedValue as Float) }
            start()
        }
    }

    // ---------- recording ----------

    fun startRecording() {
        if (recording || session == null) return
        try {
            recorder?.start()
            crop?.active = true
            recording = true
            goodPending = false
            if (cfg.steady && steadyPossible() == null) {
                val l = SteadyLog()
                val f = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
                val w = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.width
                if (f != null && w != null && w > 0f) l.fPx = f / w * 3840f * zoom
                steadyLog = l
                if (!steadyRec.start(l)) steadyLog = null
            }
            ui.onRecording(true)
        } catch (e: Exception) {
            ui.onMessage("Couldn't start recording: ${e.message}")
            configureAndStart()
        }
    }

    fun stopRecording() {
        if (!recording) return
        recording = false
        crop?.active = false
        try {
            session?.stopRepeating()
            session?.abortCaptures()
        } catch (_: Exception) {
        }
        var ok = true
        try {
            recorder?.stop()
        } catch (e: RuntimeException) {
            ok = false
        }
        val sl = steadyLog
        steadyLog = null
        steadyRec.stop()
        val finished = out
        out?.let { if (ok) finalizeOutput(it) else discardOutput(it) }
        if (ok && sl != null && finished != null) ui.onSteadyReady(finished.uri, sl)
        nextOut?.let { discardOutput(it) }
        out = null
        nextOut = null
        releaseRecorder()
        ui.onRecording(false)
        if (!ok) ui.onMessage("Recording too short, discarded")
        configureAndStart()
    }

    /** Peak mic amplitude since last call (0..32767). Only valid while recording. */
    fun level(): Int = try {
        if (recording) recorder?.maxAmplitude ?: 0 else 0
    } catch (e: Exception) {
        0
    }
}
