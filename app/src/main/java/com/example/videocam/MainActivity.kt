package com.example.videocam

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.content.ContentUris
import android.content.res.ColorStateList
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Size
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.Executors
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

class MainActivity : Activity(), CameraController.Callbacks {

    private class Readout(val box: LinearLayout, val value: TextView)

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        val AMBER = 0xFFD0BCFF.toInt()
        val GREY = 0xFF9E9E9E.toInt()
        val RED = 0xFFFF5252.toInt()
        val BITRATES = listOf(20, 35, 50, 80, 100, 150, 200)
        val PULL_SECONDS = listOf(1, 2, 3, 5, 8, 12, 20)
        val TIMERS = listOf(0, 3, 5, 10)
        val ISO_STEPS = listOf(50, 100, 200, 400, 800, 1600, 3200, 6400)
        val ANTIBAND = listOf("Auto", "50 Hz", "60 Hz", "Off")
        val AUDIO = listOf("Camcorder (default)", "Microphone", "Unprocessed (raw)")
        val PEAK = listOf("Off", "Low", "Medium", "High")
        val JPEGQ = listOf(100, 97, 95, 92, 90, 85)
    }

    private lateinit var store: Store
    private lateinit var cfg: Config
    private lateinit var cam: CameraController

    private lateinit var root: FrameLayout
    private lateinit var transitionCover: View
    private var transitionStartedAt = 0L
    private val finishCameraTransition = Runnable {
        if (::transitionCover.isInitialized) transitionCover.visibility = View.GONE
    }
    private lateinit var tv: TextureView
    private lateinit var falseView: ImageView
    private lateinit var peakView: ImageView
    private lateinit var falseLegend: TextView
    private lateinit var grid: GridOverlayView
    private lateinit var slider: VerticalSlider
    private lateinit var zoomSlider: ZoomSlider
    private lateinit var expSlider: ZoomSlider
    private lateinit var timecode: TextView
    private lateinit var statusText: TextView
    private lateinit var warn: TextView
    private lateinit var lockText: TextView
    private lateinit var countdownText: TextView
    private lateinit var meter: ProgressBar
    private lateinit var recBtn: View
    private lateinit var thumb: ImageView
    private lateinit var focusMark: View
    private lateinit var editPanel: LinearLayout
    private lateinit var panelSizeLabel: TextView
    private lateinit var panelSizeSlider: SeekBar
    private var selectedFrame: DragFrame? = null
    private lateinit var rackPanel: LinearLayout
    private lateinit var rackTargetLabel: TextView
    private lateinit var sharedRackA: TextView
    private lateinit var sharedRackB: TextView
    private lateinit var sharedRackRun: TextView
    private lateinit var sharedRackTime: TextView
    private var rackTarget = 0
    private lateinit var toolsCol: LinearLayout
    private lateinit var modeChip: TextView
    private lateinit var zA: TextView
    private lateinit var zB: TextView
    private lateinit var eA: TextView
    private lateinit var eB: TextView
    private lateinit var aChip: TextView
    private lateinit var bChip: TextView

    private lateinit var formatChip: TextView
    private lateinit var imageChip: TextView
    private lateinit var filtersChip: TextView
    private lateinit var gridChip: TextView
    private lateinit var toolsChip: TextView
    private lateinit var afChip: TextView
    private lateinit var rackChip: TextView
    private lateinit var rackTimeChip: TextView
    private lateinit var t2x: TextView
    private lateinit var tTorch: TextView
    private lateinit var tPeak: TextView
    private lateinit var tFalse: TextView
    private lateinit var tTimer: TextView
    private lateinit var tRot: TextView
    private lateinit var recEdge: EdgeLineView
    private lateinit var audioMeter: AudioMeterView
    private var monitor: MonitorServer? = null
    // Preserve the camera flat-profile setting while laptop monitoring is active.
    private var monitorSavedFlat: Boolean? = null
    private var tap: AudioTap? = null
    private val monitorFrameBusy = AtomicBoolean(false)
    private var monitorBmp: Bitmap? = null
    @Volatile
    private var longLeft = -1
    @Volatile
    private var lastMeterAt = 0L
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null
    private lateinit var chipRow: HorizontalScrollView
    private lateinit var topBar: LinearLayout
    private lateinit var topRow2: FrameLayout
    private lateinit var expLabel: TextView
    private lateinit var exposureSummary: TextView
    private var prevW = 0
    private var prevH = 0
    private var photoOri = -1
    private val readBoxes = ArrayList<View>()
    private lateinit var zoomLabel: TextView
    private lateinit var zoomGo: TextView
    private lateinit var zoomTime: TextView
    private lateinit var zoomLink: TextView
    private lateinit var expGo: TextView
    private lateinit var expTime: TextView

    private lateinit var rMode: Readout
    private lateinit var rIso: Readout
    private lateinit var rShutter: Readout
    private lateinit var rEv: Readout
    private lateinit var rAe: Readout
    private lateinit var rWb: Readout
    private lateinit var exposureControlsChip: TextView
    private var exposureControlsExpanded = false
    private var lastLandscapeRatio = 0
    private lateinit var zoomControlsChip: TextView
    private var zoomControlsExpanded = false
    private lateinit var zoomPresetChips: List<Pair<Float, TextView>>

    private val frames = ArrayList<DragFrame>()
    private val ui = Handler(Looper.getMainLooper())
    private var recStart = 0L
    private var falseOn = false
    private var editing = false
    private var thermal = 0
    private var batteryPct = 100
    private var rackSeconds = 3
    private var rackToB = true
    private var zoomSeconds = 5
    private var zoomToB = true
    private var zoomLinked = false
    private var expSeconds = 5
    private var expToB = true
    private var expAnim: ValueAnimator? = null
    private var expSliderManual: Boolean? = null
    private var cfgDirty = false
    private var lastSliderHapticMs = 0L
    private var countdown = 0
    private var askedPerms = false
    private var lastClip: Uri? = null

    private val procExec = Executors.newSingleThreadExecutor()
    private val fcBusy = AtomicBoolean(false)
    private val pkBusy = AtomicBoolean(false)
    private var fcSw = 90
    private var fcSh = 160
    private var pkSw = 180
    private var pkSh = 320
    private var fcBmp = Bitmap.createBitmap(fcSh, fcSw, Bitmap.Config.ARGB_8888)
    private var fcSrc = Bitmap.createBitmap(fcSw, fcSh, Bitmap.Config.ARGB_8888)
    private var fcSrcPx = IntArray(fcSw * fcSh)
    private var fcDst = IntArray(fcSw * fcSh)
    private val fcLut = IntArray(256) { falseColor(it) }
    private var pkOut = Bitmap.createBitmap(pkSh, pkSw, Bitmap.Config.ARGB_8888)
    private var pkSrc = Bitmap.createBitmap(pkSw, pkSh, Bitmap.Config.ARGB_8888)
    private var pkSrcPx = IntArray(pkSw * pkSh)
    private var pkLum = IntArray(pkSw * pkSh)
    private var pkMask = IntArray(pkSw * pkSh)
    private var pkRes = IntArray(pkSw * pkSh)

    /** Preview copies are portrait-shaped; video is 16:9, photos are 4:3. */
    private fun rebuildSamplers() {
        if (cfg.mode == 1) {
            fcSw = 90; fcSh = 120; pkSw = 180; pkSh = 240
        } else {
            fcSw = 90; fcSh = 160; pkSw = 180; pkSh = 320
        }
        val por = isPortrait()
        fcBmp = if (por) Bitmap.createBitmap(fcSw, fcSh, Bitmap.Config.ARGB_8888)
        else Bitmap.createBitmap(fcSh, fcSw, Bitmap.Config.ARGB_8888)
        fcSrc = Bitmap.createBitmap(fcSw, fcSh, Bitmap.Config.ARGB_8888)
        fcSrcPx = IntArray(fcSw * fcSh)
        fcDst = IntArray(fcSw * fcSh)
        pkOut = if (por) Bitmap.createBitmap(pkSw, pkSh, Bitmap.Config.ARGB_8888)
        else Bitmap.createBitmap(pkSh, pkSw, Bitmap.Config.ARGB_8888)
        pkSrc = Bitmap.createBitmap(pkSw, pkSh, Bitmap.Config.ARGB_8888)
        pkSrcPx = IntArray(pkSw * pkSh)
        pkLum = IntArray(pkSw * pkSh)
        pkMask = IntArray(pkSw * pkSh)
        pkRes = IntArray(pkSw * pkSh)
        if (::falseView.isInitialized) falseView.setImageBitmap(fcBmp)
        if (::peakView.isInitialized) peakView.setImageBitmap(pkOut)
    }

    // ---------- lifecycle ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        store = Store(this)
        cfg = store.load("last") ?: Config()
        lastLandscapeRatio = cfg.ratio
        applyRequestedOrientation()
        buildUi()
        rebuildSamplers()
        cam = CameraController(this, tv, cfg, store, this)
        if (cfg.photoFormat == 3 && !cam.ultraHdrAvailable) cfg.photoFormat = 0
        if (cfg.scene == 3) cfg.scene = 0 // retire legacy multi-frame HDR setting
        // Apply the first-run camera default before advancing the schema version.
        // Existing explicit user lens choices are preserved.
        if (cfg.ver < 1) {
            if (cfg.cameraId.isBlank() && cam.cameraIds.contains("5")) cfg.cameraId = "5"
            cfg.ver = 1
            store.save("last", cfg)
        }
        if (cfg.ver < 2) {
            cfg.bitrateMbps = 50
            cfg.stab = 2
            cfg.saveDng = false
            cfg.scene = 0
            cfg.ver = 2
            store.save("last", cfg)
        }
        if (cfg.ver < 3) {
            // Standard JPEG by default: capture around 8 MP and upscale saved photos to 12.6 MP.
            cfg.photoFormat = 0
            cfg.photoW = 0
            cfg.photoH = 0
            cfg.upscale = true
            cfg.ver = 3
            store.save("last", cfg)
        }
        slider.onChange = { f ->
            cam.cancelRack()
            cam.setFocus(f)
            subtleSliderHaptic(slider)
            refreshUi()
        }
        zoomSlider.onInteraction = { setRackTarget(0) }
        expSlider.onInteraction = { setRackTarget(1) }
        zoomSlider.onChange = { z ->
            cam.cancelZoomPull()
            cam.setZoom(z)
            subtleSliderHaptic(zoomSlider)
        }
        expSlider.onChange = { v ->
            cancelExpPull()
            applyExposureValue(v)
        }
        setupGestures()
        refreshUi()
        registerThermal()
        root.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (root.width > 0) {
                    root.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    applyOrientationLayout()
                    root.post { applyPositions() }
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        hideBars()
        if (hasPerms()) {
            cam.start()
        } else if (!askedPerms) {
            askedPerms = true
            requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), 1)
        } else {
            toast("Camera and microphone permissions are required")
        }
        loadLatestClip()
        updateOrientationMode()
        updateAudioTap()
        ui.post(ticker)
    }

    override fun onPause() {
        ui.removeCallbacks(ticker)
        cancelCountdown()
        cancelExpPull()
        oriListener.disable()
        stopMonitor() // release the Wi-Fi/ADB monitor server when the app leaves the foreground
        tap?.stop() // stop again after stopMonitor updates audio state; never leave mic capture running in background
        if (cfgDirty) store.save("last", cfg)
        cam.stop()
        super.onPause()
    }

    override fun onDestroy() {
        ui.removeCallbacks(finishCameraTransition)
        // Stop all producers before shutting down their worker threads. onPause handles
        // normal backgrounding; this is the final release when the Activity is destroyed.
        ui.removeCallbacks(ticker)
        ui.removeCallbacks(monitorTick)
        cancelCountdown()
        cancelExpPull()
        oriListener.disable()
        stopMonitor()
        tap?.stop()
        tap = null
        if (Build.VERSION.SDK_INT >= 29) {
            thermalListener?.let { listener ->
                (getSystemService(POWER_SERVICE) as PowerManager).removeThermalStatusListener(listener)
            }
        }
        thermalListener = null
        if (::cam.isInitialized) cam.release()
        procExec.shutdownNow()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (event.repeatCount == 0) toggleRec()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun hasPerms() =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    private fun hideBars() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
    }

    // ---------- UI helpers ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun setT(t: TextView, s: String) {
        if (t.text.toString() != s) t.text = s
    }

    private fun setC(t: TextView, c: Int) {
        if (t.currentTextColor != c) t.setTextColor(c)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun lp(w: Int, h: Int, g: Int, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) =
        FrameLayout.LayoutParams(w, h, g).apply { setMargins(l, t, r, b) }

    private fun roundBg(color: Int, radiusDp: Int, stroke: Int? = null) = GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat()
        setColor(color)
        stroke?.let { setStroke(dp(1), it) }
    }

    /** Material 3 surface with native Android ripple feedback. */
    private fun materialSurface(color: Int, radiusDp: Int, stroke: Int? = null, ripple: Int = 0x33D0BCFF): RippleDrawable {
        val shape = roundBg(color, radiusDp, stroke)
        return RippleDrawable(ColorStateList.valueOf(ripple), shape, null)
    }

    private fun chipBg(active: Boolean) = materialSurface(
        if (active) 0xFFD0BCFF.toInt() else 0xFF2A2830.toInt(),
        18,
        if (active) 0xFFD0BCFF.toInt() else 0xFF49454F.toInt(),
        if (active) 0x33FFFFFF else 0x33D0BCFF
    )

    private fun setActive(t: TextView, on: Boolean) {
        t.background = chipBg(on)
        t.setTextColor(if (on) 0xFF381E72.toInt() else 0xFFF3F0E8.toInt())
        t.elevation = dp(if (on) 2 else 0).toFloat()
    }

    private fun chip(text: String, onLong: (() -> Unit)? = null, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(0xFFF3F0E8.toInt())
            textSize = 12f
            letterSpacing = 0.015f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setMinimumHeight(dp(36))
            setMinimumWidth(dp(40))
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = chipBg(false)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            if (onLong != null) setOnLongClickListener {
                onLong()
                true
            }
        }

    private fun readout(label: String, onClick: () -> Unit): Readout {
        val l = TextView(this).apply {
            text = label
            setTextColor(GREY)
            textSize = 9f
            gravity = Gravity.CENTER
        }
        val v = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(5), dp(10), dp(5))
            setMinimumHeight(dp(48))
            background = materialSurface(0xFF211F26.toInt(), 14, 0xFF49454F.toInt())
            elevation = dp(1).toFloat()
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            addView(l)
            addView(v)
        }
        return Readout(box, v)
    }

    private fun row(vararg v: View): HorizontalScrollView {
        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        v.forEach {
            ll.addView(it, LinearLayout.LayoutParams(WRAP, WRAP).apply { setMargins(dp(3), 0, dp(3), 0) })
        }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(ll)
        }
    }

    private fun strip(label: TextView, vararg views: View): LinearLayout {
        val s = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = materialSurface(0xE6211F26.toInt(), 20, 0x5549454F)
            elevation = dp(3).toFloat()
            setPadding(dp(8), dp(5), dp(8), dp(5))
        }
        s.addView(label, LinearLayout.LayoutParams(dp(74), WRAP))
        views.forEach { v ->
            val params = if (v is ZoomSlider) LinearLayout.LayoutParams(dp(if (isPortrait()) 210 else 280), dp(52))
            else LinearLayout.LayoutParams(WRAP, WRAP)
            params.setMargins(dp(2), 0, dp(2), 0)
            s.addView(v, params)
        }
        return s
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(GREY)
        textSize = 10f
        letterSpacing = 0.06f
        setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
    }

    /** Wraps a control in a draggable frame (movable in Edit layout mode) and adds it to the screen. */
    private fun drag(
        key: String, child: View, w: Int, h: Int, g: Int,
        l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0
    ): DragFrame {
        val f = DragFrame(this, key)
        f.onSelected = { selectFrameForResize(f) }
        f.addView(
            child,
            FrameLayout.LayoutParams(if (w == WRAP) WRAP else MATCH, if (h == WRAP) WRAP else MATCH)
        )
        root.addView(f, lp(w, h, g, l, t, r, b))
        frames.add(f)
        return f
    }

    private fun popup(anchor: View, items: List<Pair<String, () -> Unit>>) {
        val pm = PopupMenu(this, anchor)
        items.forEachIndexed { i, p -> pm.menu.add(0, i, i, p.first) }
        pm.setOnMenuItemClickListener { mi ->
            items[mi.itemId].second()
            true
        }
        pm.show()
    }

    private fun choose(title: String, labels: List<String>, onPick: (Int) -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setItems(labels.toTypedArray()) { _, i -> onPick(i) }
            .show()
    }

    private fun fmtZoom(z: Float) = if (z == z.toInt().toFloat()) "${z.toInt()}x" else "%.1fx".format(z)

    // ---------- redesigned format and looks panels ----------

    private fun panelAction(title: String, detail: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = if (detail.isBlank()) title else "$title\n$detail"
            textSize = 13f
            setTextColor(0xFFF3F0E8.toInt())
            setPadding(dp(14), dp(11), dp(14), dp(11))
            background = materialSurface(0xFF29262F.toInt(), 14, 0x5549454F)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun showFormatPanel() {
        val photo = cfg.mode == 1
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(4))
        }
        fun add(title: String, detail: String, action: () -> Unit) {
            box.addView(panelAction(title, detail, action), LinearLayout.LayoutParams(MATCH, WRAP).apply {
                setMargins(0, 0, 0, dp(8))
            })
        }
        if (photo) {
            val size = runCatching { cam.captureSize() }.getOrNull()
            val sizeText = size?.let { "%.1f MP · %d × %d".format(it.width * it.height / 1e6, it.width, it.height) } ?: "Unavailable"
            add("PHOTO SIZE", sizeText) { pickPhotoRes() }
            add("CAPTURE FORMAT", when {
                cfg.photoFormat == 3 && cam.ultraHdrAvailable -> "Native Ultra HDR JPEG_R"
                cam.effMode() == 1 -> "RAW develop"
                else -> "Standard JPEG"
            }) { pickCaptureMode() }
            add("JPEG QUALITY", "${cfg.photoQuality}%") { pickQuality() }
            add("SCENE", arrayOf("Normal", "Night", "Astro")[cfg.scene.coerceIn(0, 2)]) { pickScene() }
            add("LENS", runCatching { cam.cameraLabel(cfg.cameraId) }.getOrDefault("Current lens")) { pickLens() }
        } else {
            add("RESOLUTION", resLabel()) { pickRes() }
            add("ASPECT RATIO", Ratios.NAMES[cfg.ratio.coerceIn(0, Ratios.NAMES.lastIndex)]) { pickAspectRatio() }
            add("FRAME RATE", "${cfg.fps} FPS") { pickFps() }
            add("BITRATE", "${cfg.bitrateMbps} Mbps") { pickBitrate() }
            add("CODEC", codecLabel()) { pickCodec() }
            add("LENS", runCatching { cam.cameraLabel(cfg.cameraId) }.getOrDefault("Current lens")) { pickLens() }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(if (photo) "PHOTO SETTINGS" else "VIDEO FORMAT")
            .setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showLooksDialog() {
        val groups = listOf(
            "Natural" to listOf(0),
            "Inspired" to listOf(18, 10, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29),
            "Cinematic" to listOf(2, 3, 4, 5, 6, 7, 8, 9, 13, 14, 15, 16, 17, 1)
        )
        val names = Look.FILTER_NAMES
        val intensity = if (cfg.mode == 1) cfg.photoFilterIntensity else cfg.filterIntensity
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), dp(8)) }
        val currentLut = TextView(this).apply {
            text = "CURRENT LUT · " + names.getOrElse(cfg.filterId) { "Natural" }
            setTextColor(0xFFF3F0E8.toInt()); textSize = 12f; setPadding(0, dp(4), 0, dp(8))
        }
        groups.forEach { (title, ids) ->
            box.addView(panelAction(title, "") {
                choose(title, ids.map { (if (cfg.filterId == it) "✓ " else "") + names[it] }) { index ->
                    applyLook(ids[index])
                    currentLut.text = "CURRENT LUT · " + names.getOrElse(cfg.filterId) { "Natural" }
                }
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, 0, 0, dp(8)) })
        }
        box.addView(currentLut)
        val label = TextView(this).apply { text = "INTENSITY · " + intensity + "%"; setTextColor(0xFFF3F0E8.toInt()); textSize = 12f }
        box.addView(label)
        box.addView(SeekBar(this).apply {
            max = 100
            progress = intensity
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    label.text = "INTENSITY · " + value + "%"
                    if (fromUser) {
                        if (cfg.mode == 1) cfg.photoFilterIntensity = value else cfg.filterIntensity = value
                        cfgDirty = true
                        if (cfg.mode == 0) streamChanged() else applyLive()
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }, LinearLayout.LayoutParams(MATCH, WRAP))
        MaterialAlertDialogBuilder(this).setTitle("LOOKS").setView(box).setNegativeButton("Done", null).show()
    }
    private fun applyLook(id: Int) {
        if (!idle()) return
        cfg.filterId = id
        cfg.bw = id == 1
        cfg.blue = false
        cfg.glow = false
        cfg.photoLook = 0
        cfg.flat = false
        cfgDirty = true
        if (cfg.mode == 0) streamChanged() else applyLive()
        toast("${Look.FILTER_NAMES[id]} selected")
    }

    private fun toggleExposureControls() {
        if (!exposureControlsExpanded && zoomControlsExpanded) toggleZoomControls()
        exposureControlsExpanded = !exposureControlsExpanded
        exposureControlsChip.text = if (exposureControlsExpanded) "EXP ◂" else "EXP ▸"
        setActive(exposureControlsChip, exposureControlsExpanded)
        val controls = listOf(rMode.box, rIso.box, rShutter.box, rWb.box, rAe.box)
        if (exposureControlsExpanded) {
            controls.forEach { view ->
                view.animate().cancel()
                view.visibility = View.VISIBLE
                view.alpha = 0f
                view.translationX = -dp(18).toFloat()
                view.animate().alpha(1f).translationX(0f).setDuration(180L).start()
            }
        } else {
            controls.forEach { view ->
                view.animate().cancel()
                view.animate().alpha(0f).translationX(-dp(18).toFloat()).setDuration(140L)
                    .withEndAction {
                        view.visibility = View.GONE
                        view.alpha = 1f
                        view.translationX = 0f
                    }.start()
            }
        }
    }

    private fun toggleZoomControls() {
        if (!zoomControlsExpanded && exposureControlsExpanded) toggleExposureControls()
        zoomControlsExpanded = !zoomControlsExpanded
        zoomControlsChip.text = fmtZoom(cam.zoom) + (if (zoomControlsExpanded) " ◂" else " ▸")
        setActive(zoomControlsChip, zoomControlsExpanded)
        zoomPresetChips.forEach { (value, chip) ->
            chip.animate().cancel()
            val supported = value >= cam.zoomRange.lower - 0.01f && value <= cam.zoomHi() + 0.01f
            if (zoomControlsExpanded && cfg.mode == 0 && supported) {
                chip.visibility = View.VISIBLE
                chip.alpha = 0f
                chip.translationX = -dp(16).toFloat()
                chip.animate().alpha(1f).translationX(0f).setDuration(180L).start()
            } else {
                chip.animate().alpha(0f).translationX(-dp(16).toFloat()).setDuration(140L)
                    .withEndAction { chip.visibility = View.GONE; chip.alpha = 1f; chip.translationX = 0f }.start()
            }
        }
        (listOf(zA, zB, zoomGo, zoomTime, zoomLink)).forEach { view ->
            view.animate().cancel()
            if (zoomControlsExpanded && cfg.mode == 0) {
                view.visibility = View.VISIBLE
                view.alpha = 0f
                view.translationX = -dp(16).toFloat()
                view.animate().alpha(1f).translationX(0f).setDuration(180L).start()
            } else {
                view.animate().alpha(0f).translationX(-dp(16).toFloat()).setDuration(140L)
                    .withEndAction { view.visibility = View.GONE; view.alpha = 1f; view.translationX = 0f }.start()
            }
        }
    }

    // ---------- UI building ----------

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, orr, ob ->
            if (r - l != orr - ol || b - t != ob - ot) root.post { relayoutPreview() }
        }
        tv = TextureView(this)
        root.addView(tv, lp(MATCH, MATCH, Gravity.CENTER))
        tv.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (::cam.isInitialized) cam.refreshTransform()
        }
        falseView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            setImageBitmap(fcBmp)
            visibility = View.GONE
        }
        root.addView(falseView, lp(MATCH, MATCH, Gravity.CENTER))
        peakView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            setImageBitmap(pkOut)
            visibility = View.GONE
        }
        root.addView(peakView, lp(MATCH, MATCH, Gravity.CENTER))
        grid = GridOverlayView(this)
        root.addView(grid, lp(MATCH, MATCH, Gravity.CENTER))

        falseLegend = TextView(this).apply {
            text = "FALSE COLOUR   purple <2%   blue shadows   green mid-grey   pink skin   yellow 90%+   red clipped"
            setTextColor(0xFFF3F0E8.toInt())
            textSize = 10f
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = materialSurface(0xE6211F26.toInt(), 14, 0x66494D55)
            elevation = dp(3).toFloat()
            visibility = View.GONE
        }
        root.addView(falseLegend, lp(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, dp(48), 0, 0))

        // ----- top bar: timecode, level meter, format + menus, storage/battery -----
        timecode = TextView(this).apply {
            setTextColor(GREY)
            textSize = 15f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            text = "STBY"
        }
        meter = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        formatChip = chip("FORMAT ▾") { showFormatPanel() }
        imageChip = chip("IMAGE \u25BE") { popup(imageChip, imageItems()) }
        filtersChip = chip("LOOKS ▾") { showLooksDialog() }
        gridChip = chip("GRID \u25BE") { popup(gridChip, gridItems()) }
        toolsChip = chip("TOOLS \u25BE") { popup(toolsChip, toolsItems()) }
        statusText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 11f
            setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
            gravity = Gravity.END
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(0xD91D1B20.toInt())
                cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, dp(22).toFloat(), dp(22).toFloat(), dp(22).toFloat(), dp(22).toFloat())
                setStroke(dp(1), 0x5549454F)
            }
            elevation = dp(4).toFloat()
            setPadding(dp(12), dp(6), dp(12), dp(10))
        }
        top.addView(timecode, LinearLayout.LayoutParams(dp(112), WRAP))
        top.addView(meter, LinearLayout.LayoutParams(dp(70), dp(8)).apply { setMargins(0, 0, dp(8), 0) })
        chipRow = row(formatChip, imageChip, filtersChip, gridChip, toolsChip)
        top.addView(chipRow, LinearLayout.LayoutParams(0, WRAP, 1f))
        top.addView(statusText, LinearLayout.LayoutParams(WRAP, WRAP).apply { setMargins(dp(8), 0, 0, 0) })
        topBar = top
        root.addView(top, lp(MATCH, canvasH(90), Gravity.TOP))
        topRow2 = FrameLayout(this).apply {
            visibility = View.GONE
            setPadding(dp(8), 0, dp(8), 0)
        }
        root.addView(topRow2, lp(MATCH, WRAP, Gravity.TOP, 0, canvasY(90), 0, 0))

        warn = TextView(this).apply {
            setTextColor(RED)
            textSize = 13f
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
            visibility = View.GONE
        }
        root.addView(warn, lp(WRAP, WRAP, Gravity.TOP or Gravity.START, dp(14), dp(50), 0, 0))
        lockText = TextView(this).apply {
            setTextColor(AMBER)
            textSize = 13f
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
        }
        root.addView(lockText, lp(WRAP, WRAP, Gravity.TOP or Gravity.START, dp(14), dp(72), 0, 0))

        // ----- left tool strip -----
        t2x = chip("2x") {
            cam.cancelZoomPull()
            cam.setZoom(if (cam.zoom >= 1.9f) 1f else 2f)
        }
        tTorch = chip("\u26A1") { if (cfg.mode == 1) cycleFlash() else cam.toggleTorch() }
        tTorch.textSize = 18f
        tPeak = chip("PEAK") {
            cfg.peak = if (cfg.peak == 0) 2 else 0
            applyLive()
        }
        tFalse = chip("FALSE") {
            if (cam.recording) {
                toast("False colour is preview-only")
            } else {
                falseOn = !falseOn
                refreshUi()
            }
        }
        tTimer = chip("") {
            cfg.timer = TIMERS[(TIMERS.indexOf(cfg.timer) + 1) % TIMERS.size]
            applyLive()
        }
        tRot = chip("") { toggleOrientation() }
        toolsCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listOf(tTimer, tRot, t2x, tPeak, tFalse, tTorch).forEachIndexed { index, item ->
            item.maxLines = 1
            if (item !== tTorch) item.textSize = 11f
            item.setPadding(dp(4), dp(6), dp(4), dp(6))
            val key = listOf("toolTimer", "toolRotate", "tool2x", "toolPeak", "toolFalse", "toolTorch")[index]
            drag(key, item, dp(70), WRAP, Gravity.START or Gravity.CENTER_VERTICAL, dp(8), dp(20 + index * 58), 0, 0)
        }

        // ----- focus slider + focus tools (right) -----
        slider = VerticalSlider(this)
        drag("focusSlider", slider, dp(56), MATCH, Gravity.END, 0, dp(56), dp(10), dp(14))

        afChip = chip("AF") {
            // AF/MF is a direct toggle. In MF, the focus slider and pull-focus
            // controls become available; the slider itself stays on screen.
            if (cam.manualFocus) {
                cam.setAutoFocus()
            } else {
                cam.setFocus(slider.fraction)
            }
            refreshUi()
        }
        aChip = chip("Set A") {
            slider.markA = slider.fraction
            slider.invalidate()
        }
        bChip = chip("Set B") {
            slider.markB = slider.fraction
            slider.invalidate()
        }
        rackChip = chip("") { doRack() }
        rackTimeChip = chip("") {
            rackSeconds = PULL_SECONDS[(PULL_SECONDS.indexOf(rackSeconds) + 1) % PULL_SECONDS.size]
            refreshUi()
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listOf(afChip, aChip, bChip, rackChip, rackTimeChip).forEach {
            col.addView(it, LinearLayout.LayoutParams(dp(78), WRAP).apply { setMargins(0, dp(3), 0, dp(3)) })
        }
        drag("focusTools", col, WRAP, WRAP, Gravity.END or Gravity.TOP, 0, dp(56), dp(76), 0)

        // ----- bottom stack: readouts + zoom pull + exposure pull -----
        rMode = readout("EXPOSURE") {
            cfg.manualExposure = !cfg.manualExposure
            cam.clearAeLock()
            applyLive()
        }
        rIso = readout("ISO") { pickIso() }
        rShutter = readout("SHUTTER") { pickShutter() }
        rEv = readout("EV") { pickEv() }
        rAe = readout("AE LOCK") { cam.toggleAeLock() }
        rWb = readout("WB") { cam.toggleAwbLock() }
        exposureControlsChip = chip("EXP ▸") { toggleExposureControls() }
        val readRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        listOf(exposureControlsChip, rEv.box, rMode.box, rIso.box, rShutter.box, rWb.box, rAe.box).forEach { view ->
            if (view !== exposureControlsChip) readBoxes.add(view)
            readRow.addView(view, LinearLayout.LayoutParams(WRAP, WRAP).apply { setMargins(dp(3), 0, dp(3), dp(3)) })
        }
        listOf(rMode.box, rIso.box, rShutter.box, rWb.box, rAe.box).forEach { it.visibility = View.GONE }
        val readScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(readRow, ViewGroup.LayoutParams(WRAP, WRAP))
        }

        zoomSlider = ZoomSlider(this)
        zoomLabel = label("ZOOM")
        zA = chip("A") {
            zoomSlider.markA = cam.zoom
            zoomSlider.invalidate()
        }
        zB = chip("B") {
            zoomSlider.markB = cam.zoom
            zoomSlider.invalidate()
        }
        zoomGo = chip("") { doZoomPull() }
        zoomTime = chip("") {
            zoomSeconds = PULL_SECONDS[(PULL_SECONDS.indexOf(zoomSeconds) + 1) % PULL_SECONDS.size]
            refreshUi()
        }
        zoomLink = chip("+FOCUS") {
            zoomLinked = !zoomLinked
            refreshUi()
        }
        zoomControlsChip = chip("ZOOM ▸") { toggleZoomControls() }
        val presetValues = listOf(0.6f, 1f, 2f, 3f)
        zoomPresetChips = presetValues.map { value ->
            value to chip(fmtZoom(value)) {
                cam.setZoom(value.coerceIn(cam.zoomRange.lower, cam.zoomHi()))
                refreshZoomSlider()
                refreshUi()
            }
        }
        zoomPresetChips.forEach { it.second.visibility = View.GONE }
        zoomLabel.setOnClickListener { setRackTarget(0) }
        val zoomStrip = strip(zoomLabel, zoomControlsChip, *zoomPresetChips.map { it.second }.toTypedArray(), zoomSlider)

        expSlider = ZoomSlider(this)
        expLabel = label("EXPOSURE")
        eA = chip("A") {
            expSlider.markA = expSlider.zoom
            expSlider.invalidate()
        }
        eB = chip("B") {
            expSlider.markB = expSlider.zoom
            expSlider.invalidate()
        }
        expGo = chip("") { doExpPull() }
        expTime = chip("") {
            expSeconds = PULL_SECONDS[(PULL_SECONDS.indexOf(expSeconds) + 1) % PULL_SECONDS.size]
            refreshUi()
        }
        expLabel.setOnClickListener { setRackTarget(1) }
        val expStrip = strip(expLabel, expSlider)

        exposureSummary = TextView(this).apply {
            text = "AUTO  ·  ISO --  ·  --"
            setTextColor(0xFFF3F0E8.toInt())
            textSize = 11f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = materialSurface(0xE6211F26.toInt(), 14, 0x5549454F)
            gravity = Gravity.CENTER_VERTICAL
        }
        val readouts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(exposureSummary, LinearLayout.LayoutParams(WRAP, WRAP).apply { setMargins(dp(3), 0, dp(3), dp(4)) })
            addView(readScroll, LinearLayout.LayoutParams(WRAP, WRAP))
        }
        drag("readouts", readouts, WRAP, WRAP, Gravity.START or Gravity.BOTTOM, dp(76), 0, 0, dp(132))
        drag("zoomPanel", zoomStrip, WRAP, WRAP, Gravity.START or Gravity.BOTTOM, dp(76), 0, 0, dp(78))
        drag("exposurePanel", expStrip, WRAP, WRAP, Gravity.START or Gravity.BOTTOM, dp(76), 0, 0, dp(8))

        rackTargetLabel = TextView(this).apply { text = "ZOOM"; gravity = Gravity.CENTER; textSize = 10f; setTextColor(0xFFF3F0E8.toInt()) }
        sharedRackA = chip("A") { setSharedRackMark(true) }
        sharedRackB = chip("B") { setSharedRackMark(false) }
        sharedRackRun = chip("▶") { if (rackTarget == 0) doZoomPull() else doExpPull() }
        sharedRackTime = chip("3s") { rackSeconds = PULL_SECONDS[(PULL_SECONDS.indexOf(rackSeconds) + 1) % PULL_SECONDS.size]; sharedRackTime.text = rackSeconds.toString() + "s"; refreshUi() }
        val rackRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            addView(sharedRackA, LinearLayout.LayoutParams(dp(38), dp(34)).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
            addView(sharedRackB, LinearLayout.LayoutParams(dp(38), dp(34)).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
        }
        val rackRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            addView(sharedRackRun, LinearLayout.LayoutParams(dp(38), dp(34)).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
            addView(sharedRackTime, LinearLayout.LayoutParams(dp(38), dp(34)).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
        }
        rackPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            background = materialSurface(0xE6211F26.toInt(), 12, 0x5549454F); elevation = dp(3).toFloat()
            addView(rackTargetLabel, LinearLayout.LayoutParams(MATCH, dp(22))); addView(rackRow1); addView(rackRow2)
        }
        drag("rackPanel", rackPanel, dp(92), dp(104), Gravity.END or Gravity.BOTTOM, 0, 0, dp(240), dp(8))
        setRackTarget(0)

        // ----- record, thumbnail -----
        recBtn = View(this)
        recBtn.setOnClickListener { shutterHaptic(recBtn); toggleRec() }
        setRecLook(false)
        drag("rec", recBtn, dp(64), dp(64), Gravity.END or Gravity.BOTTOM, 0, 0, dp(88), dp(16))

        thumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(0x99000000.toInt())
                setStroke(dp(2), Color.WHITE)
            }
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            setOnClickListener { openLast() }
        }
        drag("thumb", thumb, dp(52), dp(52), Gravity.END or Gravity.BOTTOM, 0, 0, dp(166), dp(22))

        modeChip = chip("") { switchMode() }
        drag("mode", modeChip, WRAP, WRAP, Gravity.END or Gravity.BOTTOM, 0, 0, dp(70), dp(88))

        countdownText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 96f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setShadowLayer(12f, 0f, 0f, Color.BLACK)
            visibility = View.GONE
        }
        root.addView(countdownText, lp(WRAP, WRAP, Gravity.CENTER))

        focusMark = View(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(dp(2), Color.WHITE)
                cornerRadius = dp(6).toFloat()
            }
            alpha = 0f
        }
        root.addView(focusMark, lp(dp(64), dp(64), Gravity.TOP or Gravity.START))

        // ----- edit layout panel -----
        val done = chip("Done") { setEditing(false) }
        val mirror = chip("Mirror left/right") { mirrorLayout() }
        val reset = chip("Reset") { resetLayout() }
        val editActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            listOf(done, mirror, reset).forEach {
                addView(it, LinearLayout.LayoutParams(WRAP, WRAP).apply { setMargins(dp(4), 0, dp(4), 0) })
            }
        }
        panelSizeLabel = TextView(this).apply {
            text = "PANEL SIZE · 50%"
            setTextColor(0xFFF3F0E8.toInt())
            textSize = 11f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
        }
        panelSizeSlider = SeekBar(this).apply {
            max = 75
            progress = 25 // 25..100 maps to 0.5x..2.0x; 50% is the original size.
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val percent = progress + 25
                    panelSizeLabel.text = "PANEL SIZE · ${percent}%"
                    if (fromUser) selectedFrame?.setPanelSizePercent(percent)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        val sizeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(panelSizeLabel, LinearLayout.LayoutParams(WRAP, WRAP))
            addView(panelSizeSlider, LinearLayout.LayoutParams(dp(150), WRAP))
        }
        editPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            addView(editActions, LinearLayout.LayoutParams(WRAP, WRAP))
            addView(sizeRow, LinearLayout.LayoutParams(WRAP, WRAP))
        }
        root.addView(editPanel, lp(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, dp(70), 0, 0))

        audioMeter = AudioMeterView(this)
        root.addView(audioMeter, lp(dp(6), dp(120), Gravity.START or Gravity.CENTER_VERTICAL, dp(1), 0, 0, 0))
        // Landscape audio meter uses the supplied left-edge 15x300 reference position.
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (!isPortrait() && audioMeter.parent === root) {
                audioMeter.layoutParams = lp(
                    canvasW(10), canvasH(300), Gravity.TOP or Gravity.START,
                    canvasX(15), canvasY(300), 0, 0
                )
            }
        }
        recEdge = EdgeLineView(this).apply {
            visibility = View.GONE
            isClickable = false
            isFocusable = false
        }
        root.addView(recEdge, lp(MATCH, MATCH, Gravity.TOP))
        meter.visibility = View.GONE

        transitionCover = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            isClickable = true
            isFocusable = true
            addView(TextView(this@MainActivity).apply {
                text = "LOADING"
                setTextColor(Color.WHITE)
                textSize = 14f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                letterSpacing = 0.22f
                gravity = Gravity.CENTER
                setShadowLayer(8f, 0f, 0f, Color.BLACK)
            }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        }
        root.addView(transitionCover, lp(MATCH, MATCH, Gravity.CENTER))
        setContentView(root)
    }

    private fun beginCameraTransition() {
        if (!::transitionCover.isInitialized) return
        ui.removeCallbacks(finishCameraTransition)
        transitionStartedAt = SystemClock.elapsedRealtime()
        transitionCover.visibility = View.VISIBLE
        // Never leave the cover up indefinitely if a camera callback is lost.
        ui.postDelayed(finishCameraTransition, 260L)
    }

    private fun completeCameraTransition() {
        if (!::transitionCover.isInitialized || transitionCover.visibility != View.VISIBLE) return
        ui.removeCallbacks(finishCameraTransition)
        val elapsed = SystemClock.elapsedRealtime() - transitionStartedAt
        // A very short minimum masks the surface teardown; readiness ends the transition.
        ui.postDelayed(finishCameraTransition, (100L - elapsed).coerceAtLeast(0L))
    }

    private fun setRecLook(on: Boolean) {
        if (cfg.mode == 1) {
            recBtn.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                val saving = ::cam.isInitialized && (cam.busy || cam.jobs > 0 || longLeft >= 0)
                setColor(if (saving) 0xFF9E9E9E.toInt() else Color.WHITE)
                setStroke(dp(4), if (saving) 0xFFD0BCFF.toInt() else 0xFF424242.toInt())
            }
            return
        }
        recBtn.background = GradientDrawable().apply {
            if (on) {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
            } else {
                shape = GradientDrawable.OVAL
            }
            setColor(0xFFE53935.toInt())
            setStroke(dp(4), Color.WHITE)
        }
    }

    /** Zoom / exposure sliders: as long as the layout allows (video keeps its extra A/B chips, so it stays shorter). */
    private fun applySliderWidths() {
        if (!::zoomSlider.isInitialized || !::expSlider.isInitialized) return
        val p = isPortrait()
        val photo = cfg.mode == 1
        val w = dp(if (p) 210 else 280)
        listOf(zoomSlider, expSlider).forEach {
            val l = it.layoutParams as LinearLayout.LayoutParams
            if (l.width != w) {
                l.width = w
                it.layoutParams = l
            }
        }
    }

    // ---------- portrait / landscape ----------

    private fun isPortrait(): Boolean {
        if (::root.isInitialized && root.width > 0 && root.height > 0) return root.height > root.width
        return resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
    }

    /** Photo mode follows how the phone is held (motion sensor), even with Android's rotation lock on. */
    private val oriListener by lazy {
        object : OrientationEventListener(this@MainActivity) {
            override fun onOrientationChanged(o: Int) {
                if (cfg.mode != 1 || o == OrientationEventListener.ORIENTATION_UNKNOWN) return
                val target = when {
                    o >= 330 || o < 30 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    o in 60..120 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                    o in 240..300 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    else -> return
                }
                if (target == photoOri) return
                if (cam.busy || cam.jobs > 0 || countdown > 0) return
                val wasPortrait = isPortrait()
                photoOri = target
                requestedOrientation = target
                if (!wasPortrait && target != ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
                    // landscape <-> reverse landscape keeps the same screen size, so refit the preview by hand
                    root.postDelayed({
                        if (cfg.mode == 1) {
                            cam.refreshPreviewLayout()
                            loadLatestClip()
                        }
                    }, 400)
                }
            }
        }
    }

    private fun updateOrientationMode() {
        if (cfg.mode == 1) {
            if (oriListener.canDetectOrientation()) oriListener.enable()
        } else {
            oriListener.disable()
            photoOri = -1
            applyRequestedOrientation()
        }
    }

    private fun applyRequestedOrientation() {
        if (cfg.mode == 1 && photoOri != -1) {
            requestedOrientation = photoOri
            return
        }
        requestedOrientation = if (cfg.portrait) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    private fun toggleOrientation() {
        if (!idle()) return
        cancelCountdown()
        cfg.portrait = !cfg.portrait
        if (cfg.portrait) {
            if (cfg.ratio != 0) lastLandscapeRatio = cfg.ratio
            cfg.ratio = 0
        } else {
            cfg.ratio = lastLandscapeRatio
        }
        store.save("last", cfg)
        applyRequestedOrientation()
        refreshUi()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::cam.isInitialized) return
        val l = object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                val wantPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
                if ((root.height > root.width) != wantPortrait) return // not resized yet
                root.viewTreeObserver.removeOnGlobalLayoutListener(this)
                hideBars()
                applyOrientationLayout()
                rebuildSamplers()
                refreshUi()
                if (cfg.mode == 1) {
                    cam.refreshPreviewLayout() // photo mode: no camera restart needed
                } else if (!cam.recording && !cam.busy) {
                    cam.reconfigure()
                }
                loadLatestClip()
                root.post { applyPositions() }
            }
        }
        root.viewTreeObserver.addOnGlobalLayoutListener(l)
        root.requestLayout()
    }

    // Reference-layout helpers: the supplied design is authored on a 2400x1080
    // landscape canvas and scales proportionally to the actual view dimensions.
    private fun canvasX(v: Int) = (v * root.width / 2400f).roundToInt()
    private fun canvasY(v: Int) = (v * root.height / 1080f).roundToInt()
    private fun canvasW(v: Int) = canvasX(v).coerceAtLeast(1)
    private fun canvasH(v: Int) = canvasY(v).coerceAtLeast(1)

    private fun setCanvasFrame(key: String, x: Int, y: Int, w: Int, h: Int) {
        setFrameLp(key, canvasW(w), canvasH(h), Gravity.TOP or Gravity.START,
            canvasX(x), canvasY(y), 0, 0)
    }

    private fun setFrameLp(key: String, w: Int, h: Int, g: Int, l: Int, t: Int, r: Int, b: Int) {
        val f = frames.firstOrNull { it.key == key } ?: return
        f.translationX = 0f
        f.translationY = 0f
        f.layoutParams = lp(w, h, g, l, t, r, b)
    }

    /** Default positions for the current orientation (the user's own dragged positions are applied on top). */
    private fun applyOrientationLayout() {
        val p = isPortrait()
        if (p) {
            listOf("tool2x", "toolPeak", "toolFalse", "toolTorch", "toolTimer", "toolRotate").forEachIndexed { i, key -> setFrameLp(key, dp(70), WRAP, Gravity.START or Gravity.CENTER_VERTICAL, dp(6), dp(20 + i * 58), 0, 0) }
            setFrameLp("focusSlider", dp(56), MATCH, Gravity.END, 0, dp(110), dp(6), dp(260))
            setFrameLp("focusTools", WRAP, WRAP, Gravity.END or Gravity.TOP, 0, dp(110), dp(66), 0)
            // A deliberate bottom dock: each control gets its own lane with enough
            // vertical breathing room for its actual measured height.
            setFrameLp("readouts", WRAP, WRAP, Gravity.START or Gravity.BOTTOM, dp(70), 0, 0, dp(235))
            setFrameLp("zoomPanel", WRAP, WRAP, Gravity.START or Gravity.BOTTOM, dp(70), 0, 0, dp(160))
            setFrameLp("exposurePanel", WRAP, WRAP, Gravity.START or Gravity.BOTTOM, dp(70), 0, 0, dp(85))
            setFrameLp("rackPanel", dp(92), dp(104), Gravity.END or Gravity.BOTTOM, 0, 0, dp(74), dp(150))
            setFrameLp("rec", dp(64), dp(64), Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM, 0, 0, 0, dp(18))
            setFrameLp("thumb", dp(52), dp(52), Gravity.START or Gravity.BOTTOM, dp(20), 0, 0, dp(24))
            setFrameLp("mode", WRAP, WRAP, Gravity.END or Gravity.BOTTOM, 0, 0, dp(14), dp(32))
        } else {
            // Landscape reference canvas: 2400x1080. Every position and size below
            // maps proportionally to the live preview window, not device dp.
            setCanvasFrame("toolTimer", 40, 180, 90, 50)
            setCanvasFrame("toolRotate", 40, 245, 90, 50)
            setCanvasFrame("tool2x", 40, 310, 90, 50)
            setCanvasFrame("toolPeak", 40, 375, 90, 50)
            setCanvasFrame("toolFalse", 40, 440, 90, 50)
            setCanvasFrame("toolTorch", 40, 505, 90, 50)
            setCanvasFrame("focusSlider", 2320, 170, 60, 780)
            setCanvasFrame("focusTools", 2050, 110, 100, 250)
            setCanvasFrame("readouts", 980, 460, 520, 100)
            setCanvasFrame("zoomPanel", 140, 710, 370, 60)
            setCanvasFrame("exposurePanel", 60, 820, 370, 60)
            setCanvasFrame("rackPanel", 1850, 530, 140, 150)
            setCanvasFrame("rec", 1530, 780, 130, 130)
            setCanvasFrame("thumb", 1720, 840, 70, 70)
            setCanvasFrame("mode", 1240, 890, 160, 50)
        }
        readBoxes.forEach {
            val lpp = it.layoutParams as LinearLayout.LayoutParams
            lpp.width = dp(if (p) 58 else 80)
            it.layoutParams = lpp
        }
        listOf(rMode, rIso, rShutter, rEv, rAe, rWb).forEach {
            it.value.textSize = if (p) 12f else 14f
            it.box.setPadding(dp(if (p) 2 else 10), dp(3), dp(if (p) 2 else 10), dp(3))
        }
        exposureControlsChip.textSize = if (p) 11f else 12f
        exposureControlsChip.setPadding(dp(if (p) 8 else 12), dp(7), dp(if (p) 8 else 12), dp(7))
        if (::zoomControlsChip.isInitialized) {
            zoomControlsChip.textSize = if (p) 11f else 12f
            zoomControlsChip.setPadding(dp(if (p) 8 else 12), dp(7), dp(if (p) 8 else 12), dp(7))
        }
        applySliderWidths()
        zoomLabel.visibility = if (p) View.GONE else View.VISIBLE
        expLabel.visibility = if (p) View.GONE else View.VISIBLE
        // chips (format / image / grid / tools) move to their own row in portrait so none are squeezed out
        val par = chipRow.parent as? ViewGroup
        if (p && par !== topRow2) {
            par?.removeView(chipRow)
            topRow2.addView(chipRow, FrameLayout.LayoutParams(MATCH, WRAP))
            topRow2.visibility = View.VISIBLE
        } else if (!p && par !== topBar) {
            par?.removeView(chipRow)
            topBar.addView(chipRow, 2, LinearLayout.LayoutParams(0, WRAP, 1f))
            topRow2.visibility = View.GONE
        }
        (warn.layoutParams as FrameLayout.LayoutParams).topMargin = dp(if (p) 96 else 50)
        (lockText.layoutParams as FrameLayout.LayoutParams).topMargin = dp(if (p) 118 else 72)
        (falseLegend.layoutParams as FrameLayout.LayoutParams).topMargin = dp(if (p) 96 else 48)
        warn.requestLayout()
        lockText.requestLayout()
        falseLegend.requestLayout()
        (timecode.layoutParams as LinearLayout.LayoutParams).width = dp(if (p) 84 else 112)
        (meter.layoutParams as LinearLayout.LayoutParams).width = dp(if (p) 36 else 70)
        timecode.requestLayout()
        meter.requestLayout()
    }

    // ---------- movable layout ----------

    private fun layoutPrefs() = getSharedPreferences(if (isPortrait()) "layout_p" else "layout", MODE_PRIVATE)

    private fun setEditing(on: Boolean) {
        editing = on
        if (!on) {
            selectedFrame?.isSelectedForResize = false
            selectedFrame = null
            savePositions()
        }
        frames.forEach { it.editing = on }
        editPanel.visibility = if (on) View.VISIBLE else View.GONE
        if (on) toast("Drag a panel to move it, tap it to select, then adjust its size.") 
    }

    private fun selectFrameForResize(frame: DragFrame) {
        selectedFrame?.isSelectedForResize = false
        selectedFrame = frame
        frame.isSelectedForResize = true
        val percent = layoutPrefs().getInt("size_${frame.key}", 50).coerceIn(25, 100)
        panelSizeSlider.progress = percent - 25
        panelSizeLabel.text = "PANEL SIZE · ${percent}%"
    }

    private fun savePositions() {
        val ed = layoutPrefs().edit()
        frames.forEach {
            ed.putString(it.key, "${it.x / root.width},${it.y / root.height}")
            ed.putInt("size_${it.key}", it.panelSizePercent)
        }
        ed.apply()
    }

    private fun applyPositions() {
        // The previous layout schema stored overlapping panel coordinates. Rebase those
        // coordinates once so the new Material control dock starts from a clean layout.
        val prefs = layoutPrefs()
        if (prefs.getInt("layout_schema", 0) < 2) {
            prefs.edit().clear().putInt("layout_schema", 2).apply()
        }
        frames.forEach { f ->
            f.setPanelSizePercent(prefs.getInt("size_${f.key}", 50))
            val p = prefs.getString(f.key, null)?.split(",") ?: return@forEach
            if (p.size == 2) {
                val fx = p[0].toFloatOrNull()
                val fy = p[1].toFloatOrNull()
                if (fx != null && fy != null) {
                    f.x = fx * root.width
                    f.y = fy * root.height
                }
            }
            f.post { f.clampIntoParent() }
        }
    }

    private fun mirrorLayout() {
        frames.forEach { it.x = root.width - it.x - it.width * it.scaleX; it.clampIntoParent() }
        savePositions()
    }

    private fun resetLayout() {
        layoutPrefs().edit().clear().apply()
        frames.forEach {
            it.translationX = 0f
            it.translationY = 0f
            it.setPanelSizePercent(50)
        }
        selectedFrame?.let { selectFrameForResize(it) }
        applyOrientationLayout()
    }

    // ---------- menus ----------

    private fun resLabel(): String {
        val base = if (cfg.height == 2160) "4K" else "${cfg.height}p"
        val r = if (::cam.isInitialized) cam.effRatio() else 0
        return if (r > 0) "$base ${Ratios.NAMES[r]}" else base
    }

    private fun codecLabel() = if (cfg.hevc) "H.265" else "H.264"

    private fun videoItems(): List<Pair<String, () -> Unit>> {
        val lens = runCatching { cam.cameraLabel(cfg.cameraId) }.getOrDefault("?")
        val mic = cam.mics().firstOrNull { it.id == cfg.micId }?.let { cam.micLabel(it) } ?: "Auto"
        return buildList<Pair<String, () -> Unit>> {
            add("Resolution: ${resLabel()}" to { pickRes() })
            if (!cfg.portrait) {
                add("Aspect ratio: ${Ratios.NAMES[cfg.ratio.coerceIn(0, Ratios.NAMES.lastIndex)]}" to { pickAspectRatio() })
            }
            add("Frame rate: ${cfg.fps} fps" to { pickFps() })
            add("Bitrate: ${cfg.bitrateMbps} Mbps" to { pickBitrate() })
            add("Codec: ${codecLabel()}" to { pickCodec() })
            add("Lens: $lens" to { pickLens() })
            add("Mic: $mic" to { pickMic() })
            add("Audio source: ${AUDIO[cfg.audioSource.coerceIn(0, 2)]}" to { pickAudio() })
        }
    }

    private fun videoImageItems(): List<Pair<String, () -> Unit>> = listOf(
        "Stabilization: ${if (cfg.stab != 0) "On (OIS + EIS where supported)" else "Off"}" to {
            cfg.stab = if (cfg.stab == 0) 2 else 0
            cfgDirty = true
            applyLive()
        },
        "Noise reduction: ${if (cfg.nrOff) "Off" else "On"}" to {
            cfg.nrOff = !cfg.nrOff
            applyLive()
        },
        "Flat profile: ${if (cfg.flat) "On" else "Off"}" to {
            cfg.flat = !cfg.flat
            applyLive()
        },
        "Anti-flicker: ${ANTIBAND[cfg.antiband.coerceIn(0, 3)]}" to {
            choose("Anti-flicker (Auto exposure only. In Manual, use 1/50 or 1/100 on 50 Hz lights)", ANTIBAND) {
                cfg.antiband = it
                applyLive()
            }
        }
    )

    private fun imageItems(): List<Pair<String, () -> Unit>> =
        if (cfg.mode == 1) photoImageItems() else videoImageItems()

    /** Dedicated Filters category. Saved stills get the full effect; video mode is preview-only. */
    private fun filterItems(): List<Pair<String, () -> Unit>> {
        val presets = listOf(
            "Natural" to 0,
            "Film Stocks · Kodak 2383" to 2,
            "Film Stocks · Kodak 250D" to 3,
            "Film Stocks · Kodak 500T" to 4,
            "Film Stocks · Fuji Eterna" to 5,
            "Cinema · Teal & Orange" to 6,
            "Cinema · Bleach Bypass" to 7,
            "Cinema · Noir" to 9,
            "Vintage · Vintage Fade" to 8,
            "Leica · Leica Colour (inspired)" to 10,
            "Colour · Blue Cinematic" to 11,
            "Colour · Golden Hour" to 12,
            "Dream · Dream Glow" to 13,
            "Effects · Black and White" to 1,
            "Effects · Halation" to 14,
            "Effects · Soft Diffusion" to 15,
            "Effects · Film Grain" to 16,
            "Effects · Anamorphic Flare" to 17,
            "Colour · Zeiss Colours (inspired)" to 18
        )
        val items = presets.map { (label, id) ->
            val title = (if (cfg.filterId == id) "✓ " else "") + label
            title to {
                if (idle()) {
                    cfg.filterId = id
                    cfg.bw = id == 1
                    cfg.blue = false
                    cfg.glow = false
                    cfg.photoLook = 0
                    cfg.flat = false
                    cfgDirty = true
                    // The recording EGL pipeline is created when Camera2 is configured.
                    // Rebuild it whenever the selected video look changes so the encoder
                    // receives the filtered renderer surface, not the raw recorder surface.
                    if (cfg.mode == 0) streamChanged() else applyLive()
                    toast("${Look.FILTER_NAMES[id]} selected")
                }
            }
        }
        return items + ("Intensity: ${cfg.filterIntensity}%" to {
            choose("Filter intensity", (0..100 step 10).map { "$it%" }) {
                cfg.filterIntensity = it * 10
                cfgDirty = true
                if (cfg.mode == 0) streamChanged() else applyLive()
            }
        })
    }

    private fun photoImageItems(): List<Pair<String, () -> Unit>> = listOf(
        "Colour trim for this lens\u2026" to { showTrimDialog() },
        "Match lens colour\u2026" to { matchLensColour() },
        "Reset this lens's colour trim" to {
            store.saveTrim(cfg.cameraId, Trim())
            toast("Colour trim reset for this lens")
        },
        "Noise reduction: ${arrayOf("Minimal", "Standard", "High")[cfg.photoNr.coerceIn(0, 2)]}" to {
            choose("Noise reduction", listOf("Minimal (sharpest, more noise)", "Standard (phone default)", "High (smoother, a bit softer)")) {
                cfg.photoNr = it
                applyLive()
            }
        },
        "Flat profile: ${if (cfg.flat) "On" else "Off"}" to {
            cfg.flat = !cfg.flat
            applyLive()
        },
        "Aux-lens upscale to 12.6 MP: ${if (cfg.upscale) "On" else "Off"}" to {
            cfg.upscale = !cfg.upscale
            applyLive()
        },
        "Anti-flicker: ${ANTIBAND[cfg.antiband.coerceIn(0, 3)]}" to {
            choose("Anti-flicker (Auto exposure only)", ANTIBAND) {
                cfg.antiband = it
                applyLive()
            }
        }
    )

    private fun photoFormatText(): String {
        val sz = runCatching { cam.captureSize() }.getOrNull()
        val mp = if (sz != null) "%.1fMP".format(sz.width * sz.height / 1e6) else "--"
        val m = when {
            cfg.photoFormat == 3 && cam.ultraHdrAvailable && cfg.filterId == 0 && !cfg.flat && !cfg.upscale -> "ULTRA HDR"
            cfg.scene == 1 -> "NIGHT"
            cfg.scene == 2 -> "ASTRO"
            cfg.scene == 3 -> "HDR"
            cam.effMode() == 1 -> "RAW"
            else -> "JPEG"
        }
        val up = if (cfg.upscale) "\u2192 12.6MP" else ""
        return "$mp$up \u00B7 $m \u00B7 Q${cfg.photoQuality} \u25BE"
    }

    private fun videoFormatText() =
        "${resLabel()} \u00B7 ${cfg.fps}${if (cfg.fps > 60) " HS" else ""} \u00B7 ${codecLabel()}" +
            "\u00B7 ${cfg.bitrateMbps} Mbps \u25BE"

    private fun photoItems(): List<Pair<String, () -> Unit>> {
        val sz = runCatching { cam.captureSize() }.getOrNull()
        val mp = if (sz != null) "%.1f MP".format(sz.width * sz.height / 1e6) else "?"
        val mode = when {
            cfg.photoFormat == 3 && cam.ultraHdrAvailable && cfg.filterId == 0 && !cfg.flat && !cfg.upscale -> "Ultra HDR (native JPEG_R)"
            cam.effMode() == 1 -> "RAW develop"
            else -> "Phone JPEG"
        }
        val lens = runCatching { cam.cameraLabel(cfg.cameraId) }.getOrDefault("?")
        return listOf(
            "Photo size: $mp" to { pickPhotoRes() },
            "Capture mode: $mode" to { pickCaptureMode() },
            "JPEG quality: ${cfg.photoQuality}" to { pickQuality() },
            "Scene: ${arrayOf("Normal", "Night (1 s, ISO 1000)", "Astro (5 min, ISO 100)")[cfg.scene.coerceIn(0, 2)]}" to { pickScene() },
            "Lens: $lens" to { pickLens() }
        )
    }

    private fun gridItems(): List<Pair<String, () -> Unit>> = listOf(
        "Grid: ${GridOverlayView.GRID_NAMES[cfg.grid.coerceIn(0, GridOverlayView.GRID_NAMES.size - 1)]}" to {
            choose("Grid", GridOverlayView.GRID_NAMES) {
                cfg.grid = it
                grid.grid = it
                grid.invalidate()
                applyLive()
                toast("Grid: ${GridOverlayView.GRID_NAMES[it]}")
            }
        },
        "Rotate Fibonacci / spiral" to {
            cfg.gridFlip = (cfg.gridFlip + 1) % 4
            applyLive()
        }
    )

    private fun toolsItems(): List<Pair<String, () -> Unit>> {
        val all = toolsItemsAll()
        if (cfg.mode != 1) return all
        return all.filterNot {
            it.first.startsWith("Preview quality") || it.first.startsWith("2x method") ||
                it.first.startsWith("Autofocus:")
        }
    }

    private fun toolsItemsAll(): List<Pair<String, () -> Unit>> = listOf(
        "Self-timer: ${if (cfg.timer == 0) "Off" else "${cfg.timer} s"}" to {
            choose("Self-timer", TIMERS.map { if (it == 0) "Off" else "$it seconds" }) {
                cfg.timer = TIMERS[it]
                applyLive()
            }
        },
        "Focus peaking: ${PEAK[cfg.peak.coerceIn(0, 3)]}" to {
            choose("Focus peaking sensitivity", PEAK) {
                cfg.peak = it
                applyLive()
            }
        },
        "Preview quality: ${cfg.previewP}p" to { pickPreview() },
        "2x method: ${if (cfg.zoomMethod == 0) "Zoom ratio" else "Crop region"}" to {
            cfg.zoomMethod = 1 - cfg.zoomMethod
            applyLive()
        },
        "Autofocus: ${if (cfg.afFast) "Fast" else "Smooth"}" to {
            cfg.afFast = !cfg.afFast
            applyLive()
        },
        "Face autofocus: ${if (!cam.faceAfSupported) "n/a" else if (cfg.faceAf) "On" else "Off"}" to {
            if (!cam.faceAfSupported) {
                toast("Face detection isn't available on this camera")
            } else {
                cfg.faceAf = !cfg.faceAf
                applyLive()
            }
        },
        "Laptop monitor (Wi-Fi / USB): ${if (monitor != null) "On \u2014 tap for connection" else "Off"}" to { toggleMonitor() },
        "USB monitoring commands (Mac)" to { showUsbCommands() },
        "Dolly Zoom (linked zoom + focus): ${if (zoomLinked) "On" else "Off"}" to {
            zoomLinked = !zoomLinked
            refreshUi()
            toast(if (zoomLinked) "Dolly Zoom enabled: set zoom A/B and focus A/B, then run the zoom rack" else "Dolly Zoom disabled")
        },
        "Haptic feedback: ${if (cfg.haptics) "On" else "Off"}" to {
            cfg.haptics = !cfg.haptics
            cfgDirty = true
            toast("Haptic feedback ${if (cfg.haptics) "enabled" else "disabled"}")
            refreshUi()
        },
        "Edit layout" to { setEditing(true) },
        "Phone capabilities" to { showCapabilities() }
    )

    // ---------- refresh ----------

    private fun refreshUi() {
        val photo = cfg.mode == 1
        formatChip.text = "FORMAT ▾"
        setActive(formatChip, false)
        setActive(imageChip, false)
        setActive(filtersChip, cfg.filterId != 0)
        setActive(gridChip, cfg.grid != 0)
        setActive(toolsChip, false)
        modeChip.text = if (photo) "PHOTO MODE" else "VIDEO MODE"
        setActive(modeChip, false)
        val pullVis = if (photo) View.GONE else View.VISIBLE
        if (::rackPanel.isInitialized) rackPanel.visibility = pullVis
        zoomPresetChips.forEach { (value, chip) ->
            val supported = value >= cam.zoomRange.lower - 0.01f && value <= cam.zoomHi() + 0.01f
            chip.visibility = if (cfg.mode == 0 && zoomControlsExpanded && supported) View.VISIBLE else View.GONE
        }
        zoomControlsChip.visibility = if (photo) View.GONE else View.VISIBLE
        if (photo && zoomControlsExpanded) {
            zoomControlsExpanded = false
            zoomControlsChip.text = "ZOOM ▸"
            setActive(zoomControlsChip, false)
        }
        // Focus tools expand downward from AF only while manual focus is active.
        val mfToolsVis = if (!photo && cam.manualFocus) View.VISIBLE else View.GONE
        listOf(aChip, bChip, rackChip, rackTimeChip).forEach { it.visibility = mfToolsVis }
        tTorch.text = if (photo) "\u26A1 " + arrayOf("OFF", "ON", "AUTO")[cfg.flashMode.coerceIn(0, 2)] else "\u26A1"
        tTorch.textSize = if (photo) 11f else 18f
        setRecLook(cam.recording)

        setActive(t2x, cam.zoom >= 1.9f)
        setActive(tPeak, cfg.peak > 0)
        setActive(tFalse, falseOn)
        setActive(tTorch, if (photo) cfg.flashMode != 0 else cam.torch)
        tTimer.text = if (cfg.timer == 0) "TIMER" else "TIMER ${cfg.timer}s"
        tRot.text = if (cfg.portrait) "\u21BB PORT" else "\u21BB LAND"
        setActive(tRot, cfg.portrait)
        tRot.visibility = if (cfg.mode == 1) View.GONE else View.VISIBLE
        applySliderWidths()
        if (::sharedRackTime.isInitialized) sharedRackTime.text = rackSeconds.toString() + "s"
        applyBwLook()
        recEdge.visibility = if (cam.recording) View.VISIBLE else View.GONE
        audioMeter.visibility = if (cfg.mode == 0) View.VISIBLE else View.GONE
        setActive(tTimer, cfg.timer > 0)

        afChip.text = if (cam.manualFocus) "MF" else if (cam.afLocked) "AF LOCK" else "AF"
        setActive(afChip, !cam.manualFocus)
        rackChip.text = if (rackToB) "RACK A\u2192B" else "RACK B\u2192A"
        setActive(rackChip, false)
        rackTimeChip.text = "${rackSeconds}s"
        setActive(rackTimeChip, false)
        slider.isEnabled = cam.minFocusDiopters > 0f
        slider.alpha = if (slider.isEnabled) 1f else 0.4f
        slider.invalidate()

        zoomGo.text = if (zoomToB) "\u25B6 A\u2192B" else "\u25B6 B\u2192A"
        setActive(zoomGo, false)
        zoomTime.text = "${zoomSeconds}s"
        setActive(zoomTime, false)
        setActive(zoomLink, zoomLinked)
        zoomLabel.text = "ZOOM"
        zoomControlsChip.text = fmtZoom(cam.zoom) + (if (zoomControlsExpanded) " ◂" else " ▸")
        setActive(zoomControlsChip, zoomControlsExpanded)
        expGo.text = if (expToB) "\u25B6 A\u2192B" else "\u25B6 B\u2192A"
        setActive(expGo, false)
        expTime.text = "${expSeconds}s"
        setActive(expTime, false)

        lockText.text = listOfNotNull(
            if (cam.aeLock) "AE LOCK" else null,
            if (cam.awbLock) "AWB LOCK" else null
        ).joinToString("   ")

        grid.grid = cfg.grid
        grid.ratio = cam.effRatio()
        grid.flip = cfg.gridFlip
        val showFalse = falseOn && !cam.recording
        falseView.visibility = if (showFalse) View.VISIBLE else View.GONE
        falseLegend.visibility = if (showFalse) View.VISIBLE else View.GONE
        peakView.visibility = if (cfg.peak > 0) View.VISIBLE else View.GONE

        syncExpSlider()
        refreshReadouts()
    }

    private fun refreshReadouts() {
        val manual = cfg.manualExposure
        setT(rMode.value, if (manual) "MANUAL" else "AUTO")
        setC(rMode.value, if (manual) AMBER else Color.WHITE)
        val iso = if (manual) cfg.iso else cam.liveIso
        setT(rIso.value, if (iso > 0) "$iso" else "--")
        setC(rIso.value, if (manual) AMBER else Color.WHITE)
        val sd = if (manual) cfg.shutterDenom else cam.liveShutterDenom
        setT(rShutter.value, if (sd > 0) "1/$sd" else "--")
        setC(rShutter.value, if (manual) AMBER else Color.WHITE)
        setT(rEv.value, if (manual) "--" else "%+.1f".format(cfg.ev * cam.evStepEv))
        setT(rAe.value, if (cam.aeLock) "LOCK" else "OFF")
        setC(rAe.value, if (cam.aeLock) AMBER else Color.WHITE)
        setT(rWb.value, if (cam.awbLock) "LOCK" else "AUTO")
        setC(rWb.value, if (cam.awbLock) AMBER else Color.WHITE)
        val shownIso = if (manual) cfg.iso else cam.liveIso
        val shownShutter = if (manual) cfg.shutterDenom else cam.liveShutterDenom
        val exposureMode = if (manual) "PRO" else "AUTO"
        val exposureParts = mutableListOf(exposureMode)
        if (shownIso > 0) exposureParts.add("ISO $shownIso")
        if (shownShutter > 0) exposureParts.add("1/$shownShutter s")
        if (!manual) exposureParts.add("%+.1f EV".format(cfg.ev * cam.evStepEv))
        setT(exposureSummary, exposureParts.joinToString("  ·  "))
        setC(exposureSummary, if (manual) AMBER else 0xFFF3F0E8.toInt())
    }

    /** Re-points the exposure slider at EV (Auto) or ISO (Manual). */
    private fun syncExpSlider() {
        val manual = cfg.manualExposure
        if (expSliderManual != manual) {
            expSliderManual = manual
            expSlider.markA = null
            expSlider.markB = null
            if (manual) {
                val r = cam.isoRange
                expSlider.configure(
                    r.lower.toFloat(), r.upper.toFloat(), true,
                    listOf(100f, 200f, 400f, 800f, 1600f, 3200f)
                ) { "${it.toInt()}" }
            } else {
                expSlider.configure(-2f, 2f, false, listOf(-2f, -1f, 0f, 1f, 2f)) { "%+.0f".format(it) }
            }
        }
        expSlider.setZoomSilently(if (manual) cfg.iso.toFloat() else cfg.ev * cam.evStepEv)
    }

    private fun refreshZoomSlider() {
        zoomSlider.configure(
            cam.zoomRange.lower, cam.zoomHi(), true, listOf(0.5f, 1f, 2f, 3f, 5f, 7f)
        ) { fmtZoom(it) }
        zoomSlider.setZoomSilently(cam.zoom)
    }

    private fun switchLens(id: String) {
        if (!idle()) return
        cfg.cameraId = id
        store.save("last", cfg)
        cam.reopen()
        refreshUi()
    }

    private fun idle(): Boolean {
        if (::transitionCover.isInitialized && transitionCover.visibility == View.VISIBLE) {
            toast("Camera is switching. Please wait.")
            return false
        }
        if (cam.recording || cam.busy || cam.jobs > 0) {
            toast(if (cam.busy || cam.jobs > 0) "Wait for the photo to finish" else "Stop recording first")
            return false
        }
        return true
    }

    private fun applyLive() {
        store.save("last", cfg)
        cam.updateRepeating()
        refreshUi()
    }

    private fun streamChanged() {
        store.save("last", cfg)
        beginCameraTransition()
        cam.reconfigure()
        refreshUi()
    }

    // ---------- pickers ----------

    private fun pickRes() {
        if (!idle()) return
        val available = cam.sizes
        if (available.isEmpty()) return
        val preferred = listOf(2160, 1440, 1080, 720)
        val choices = preferred.mapNotNull { h ->
            available.firstOrNull { it.height == h }?.let { size ->
                val label = if (h == 2160) "4K" else "${h}p"
                size to "$label  (${size.width}\u00D7${size.height})"
            }
        }
        if (choices.isEmpty()) return
        choose("Resolution", choices.map { it.second }) {
            val size = choices[it].first
            cfg.width = size.width
            cfg.height = size.height
            streamChanged()
        }
    }

    private fun pickAspectRatio() {
        if (!idle()) return
        choose("Aspect ratio", Ratios.NAMES) {
            cfg.ratio = it
            if (it > 0 && cfg.fps > 60) {
                cfg.fps = 30
                toast("Cinematic aspect ratios record up to 60 fps; switched to 30 fps")
            }
            streamChanged()
        }
    }

    private fun pickFps() {
        if (!idle()) return
        val supportedFps = cam.fpsList
        val l = if (cfg.portrait) listOf(24, 30, 60).filter { supportedFps.contains(it) } else supportedFps
        if (l.isEmpty()) return
        choose("Frame rate", l.map { if (it > 60) "$it fps (high-speed, no audio)" else "$it fps" }) {
            cfg.fps = l[it]
            streamChanged()
        }
    }

    private fun pickBitrate() {
        if (!idle()) return
        choose("Bitrate", BITRATES.map { "$it Mbps" }) {
            cfg.bitrateMbps = BITRATES[it]
            streamChanged()
        }
    }

    private fun switchMode() {
        if (!idle()) return
        cancelCountdown()
        cfg.mode = 1 - cfg.mode
        store.save("last", cfg)
        updateOrientationMode()
        updateAudioTap()
        rebuildSamplers()
        beginCameraTransition()
        cam.reconfigure()
        loadLatestClip()
        refreshUi()
    }

    private fun cycleFlash() {
        if (!cam.flashAvail) {
            toast("This lens has no flash")
            return
        }
        cfg.flashMode = (cfg.flashMode + 1) % 3
        applyLive()
    }

    private fun pickPhotoRes() {
        if (!idle()) return
        if (cfg.photoW > 0 && cfg.photoH > 0 &&
            cfg.photoW.toLong() * cfg.photoH < 6_000_000L) {
            cfg.photoW = 0
            cfg.photoH = 0
            streamChanged()
        }
        val allSizes = cam.captureSizes()
        // Photo mode only offers capture sizes of at least 6 megapixels.
        val s = allSizes.filter { it.width.toLong() * it.height >= 6_000_000L }
        if (s.isEmpty()) {
            toast("This lens does not report a photo size of 6 MP or higher")
            return
        }
        if (s.size == 1) {
            cfg.photoW = s[0].width
            cfg.photoH = s[0].height
            streamChanged()
            toast("Only one photo size of 6 MP or higher is available")
            return
        }
        choose(
            "Photo size · 6 MP minimum",
            s.map {
                val big = it.width.toLong() * it.height > cam.lensSafeArea
                "%.1f MP  (%d\u00D7%d)%s".format(it.width * it.height / 1e6, it.width, it.height, if (big) "  \u00B7 main lens only" else "")
            }
        ) {
            cfg.photoW = s[it].width
            cfg.photoH = s[it].height
            if (s[it].width.toLong() * s[it].height > cam.lensSafeArea) {
                toast("At this size the zoom lenses can't shoot, so zoomed photos are cropped from the main lens")
            }
            streamChanged()
        }
    }

    private fun pickScene() {
        if (!idle()) return
        choose(
            "Scene",
            listOf(
                "Normal",
                "Night \u2014 1 s, ISO 1000 (use a tripod)",
                "Astro \u2014 5 min, ISO 100 (tripod, stars will trail slightly)"
            )
        ) {
            cfg.scene = arrayOf(0, 1, 2)[it]
            streamChanged()
        }
    }

    private fun pickCaptureMode() {
        if (!idle()) return
        choose(
            "Capture mode",
            buildList {
                add("Phone JPEG (+ lens colour trim)")
                add("RAW develop \u2014 true colour (lenses with RAW)")
                if (cam.ultraHdrAvailable) add("Ultra HDR \u2014 native JPEG_R with HDR gain map")
            }
        ) {
            cfg.photoFormat = when (it) { 0 -> 0; 1 -> 1; else -> 3 }
            if (cfg.photoFormat == 3) cfg.scene = 0
            cfg.photoW = 0
            cfg.photoH = 0
            streamChanged()
        }
    }

    private fun pickQuality() {
        choose("JPEG quality", JPEGQ.map { "$it" }) {
            cfg.photoQuality = JPEGQ[it]
            applyLive()
        }
    }

    private fun showTrimDialog() {
        val id = cfg.cameraId
        val t = store.trim(id)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(10))
        }
        fun addSlider(title: String, max: Int, start: Int, fmt: (Int) -> String, set: (Int) -> Unit) {
            val lab = TextView(this).apply {
                text = title + ": " + fmt(start)
                setTextColor(Color.WHITE)
            }
            val sb = SeekBar(this).apply {
                this.max = max
                progress = start
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sk: SeekBar, p: Int, fromUser: Boolean) {
                        lab.text = title + ": " + fmt(p)
                        set(p)
                        store.saveTrim(id, t)
                    }

                    override fun onStartTrackingTouch(sk: SeekBar) {}
                    override fun onStopTrackingTouch(sk: SeekBar) {}
                })
            }
            box.addView(lab)
            box.addView(sb)
        }
        addSlider("Saturation", 80, ((t.sat - 0.6f) * 100).roundToInt().coerceIn(0, 80),
            { "%.2f".format(0.6f + it / 100f) }) { t.sat = 0.6f + it / 100f }
        addSlider("Warmth", 200, ((t.warm + 1f) * 100).roundToInt().coerceIn(0, 200),
            { "%+.2f".format((it - 100) / 100f) }) { t.warm = (it - 100) / 100f }
        addSlider("Tint (green \u2190 \u2192 magenta)", 200, ((t.tint + 1f) * 100).roundToInt().coerceIn(0, 200),
            { "%+.2f".format((it - 100) / 100f) }) { t.tint = (it - 100) / 100f }
        addSlider("Contrast", 40, ((t.contrast - 0.8f) * 100).roundToInt().coerceIn(0, 40),
            { "%.2f".format(0.8f + it / 100f) }) { t.contrast = 0.8f + it / 100f }
        MaterialAlertDialogBuilder(this)
            .setTitle("Colour trim: " + runCatching { cam.cameraLabel(id) }.getOrDefault(id))
            .setView(box)
            .setPositiveButton("Done", null)
            .setNeutralButton("Reset") { _, _ ->
                store.saveTrim(id, Trim())
                toast("Reset")
            }
            .show()
    }

    private fun lensOf(name: String): String? = Regex("_L(\\w+)\\.").find(name)?.groupValues?.get(1)

    private fun smallBitmap(uri: Uri): Bitmap? = try {
        contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 8 })
        }
    } catch (e: Exception) {
        null
    }

    private fun matchLensColour() {
        val photos = queryPhotos(30)
        if (photos.size < 2) {
            toast("Take one photo of the same scene with each lens first")
            return
        }
        val labels = photos.map { it.second }
        choose("1) Reference photo (the colours you like, e.g. main lens)", labels) { ri ->
            choose("2) Photo to correct (another lens, same scene)", labels) { ti ->
                applyMatch(photos[ri], photos[ti])
            }
        }
    }

    private fun applyMatch(ref: Pair<Uri, String>, tar: Pair<Uri, String>) {
        val lens = lensOf(tar.second)
        if (lens == null) {
            toast("Can't tell which lens took that photo")
            return
        }
        val rb = smallBitmap(ref.first)
        val tb = smallBitmap(tar.first)
        if (rb == null || tb == null) {
            toast("Couldn't read the photos")
            return
        }
        val rs = ColorTrim.stats(rb)
        val ts = ColorTrim.stats(tb)
        val t = store.trim(lens)
        t.sat = (t.sat * rs.sat / ts.sat).coerceIn(0.6f, 1.4f)
        t.warm = (t.warm + (rs.rbLog - ts.rbLog) / 0.24f).coerceIn(-1f, 1f)
        t.tint = (t.tint - (rs.gLog - ts.gLog) / 0.08f).coerceIn(-1f, 1f)
        store.saveTrim(lens, t)
        toast("Lens $lens trim: saturation %.2f, warmth %+.2f, tint %+.2f".format(t.sat, t.warm, t.tint))
    }

    private fun pickCodec() {
        if (!idle()) return
        choose("Codec", listOf("H.264 (easy to edit)", "H.265 (smaller files)")) {
            cfg.hevc = it == 1
            streamChanged()
        }
    }

    private fun pickAudio() {
        if (!idle()) return
        choose("Audio source", AUDIO) {
            cfg.audioSource = it
            streamChanged()
        }
    }

    private fun pickPreview() {
        if (!idle()) return
        choose("Preview quality (recording is unaffected)", listOf("480p (lowest load)", "720p", "1080p")) {
            cfg.previewP = listOf(480, 720, 1080)[it]
            streamChanged()
        }
    }

    private fun pickLens() {
        if (!idle()) return
        val ids = cam.cameraIds
        if (ids.isEmpty()) return
        choose("Lens", ids.map { cam.cameraLabel(it) }) {
            cfg.cameraId = ids[it]
            store.save("last", cfg)
            beginCameraTransition()
            cam.reopen()
            refreshUi()
        }
    }

    private fun pickMic() {
        if (!idle()) return
        val m = cam.mics()
        choose("Microphone", listOf("Auto") + m.map { cam.micLabel(it) }) {
            cfg.micId = if (it == 0) -1 else m[it - 1].id
            streamChanged()
        }
    }

    private fun pickEv() {
        if (cfg.manualExposure) {
            toast("EV is for Auto exposure")
            return
        }
        val o = cam.evOptions()
        choose("Exposure compensation", o.map { it.second }) {
            cfg.ev = o[it].first
            applyLive()
        }
    }

    private fun pickShutter() {
        if (!cfg.manualExposure) {
            toast("Switch EXPOSURE to Manual to set the shutter")
            return
        }
        val o = cam.shutterOptions()
        if (o.isEmpty()) return
        choose("Shutter speed", o.map { "1/$it  (${(360.0 * cfg.fps / it).roundToInt()}\u00B0)" }) {
            cfg.shutterDenom = o[it]
            applyLive()
        }
    }

    private fun pickIso() {
        if (!cfg.manualExposure) {
            toast("Switch EXPOSURE to Manual to set ISO")
            return
        }
        val r = cam.isoRange
        val o = ISO_STEPS.filter { it >= r.lower && it <= r.upper }
        if (o.isEmpty()) return
        choose("ISO", o.map { "$it" }) {
            cfg.iso = o[it]
            applyLive()
        }
    }

    private fun showCapabilities() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Phone capabilities")
            .setMessage(if (cfg.mode == 1) cam.sensorReport() else cam.capabilitiesReport())
            .setPositiveButton("OK", null)
            .show()
    }

    // ---------- presets / pulls ----------

    private fun savePreset(i: Int) {
        store.save("preset$i", cfg)
        toast("Saved preset P$i")
    }

    private fun loadPreset(i: Int) {
        if (!idle()) return
        val p = store.load("preset$i")
        if (p == null) {
            toast("P$i is empty")
            return
        }
        cfg.copyFrom(p)
        store.save("last", cfg)
        beginCameraTransition()
        cam.reopen()
        refreshUi()
        toast("Loaded P$i")
    }

    private fun doRack() {
        val a = slider.markA
        val b = slider.markB
        if (a == null || b == null) {
            toast("Set focus A and B first")
            return
        }
        if (cam.minFocusDiopters <= 0f) {
            toast("This lens has fixed focus")
            return
        }
        if (rackToB) cam.rack(a, b, rackSeconds * 1000L) else cam.rack(b, a, rackSeconds * 1000L)
        rackToB = !rackToB
        refreshUi()
    }

    private fun setRackTarget(target: Int) {
        rackTarget = target.coerceIn(0, 1)
        if (!::rackTargetLabel.isInitialized) return
        rackTargetLabel.text = if (rackTarget == 0) "ZOOM" else "EXPOSURE"
        setActive(zoomLabel, rackTarget == 0)
        setActive(expLabel, rackTarget == 1)
    }

    private fun setSharedRackMark(isA: Boolean) {
        if (rackTarget == 0) {
            if (isA) zoomSlider.markA = cam.zoom else zoomSlider.markB = cam.zoom
            zoomSlider.invalidate()
            toast("Zoom " + (if (isA) "A" else "B") + " set")
        } else {
            val value = if (cfg.manualExposure) cfg.iso.toFloat() else cfg.ev * cam.evStepEv
            if (isA) expSlider.markA = value else expSlider.markB = value
            expSlider.invalidate()
            toast("Exposure " + (if (isA) "A" else "B") + " set")
        }
        refreshUi()
    }
    private fun doZoomPull() {
        val a = zoomSlider.markA
        val b = zoomSlider.markB
        if (a == null || b == null) {
            toast("Set zoom A and B first")
            return
        }
        val ms = rackSeconds * 1000L
        if (zoomLinked) {
            val fa = slider.markA
            val fb = slider.markB
            if (fa == null || fb == null || cam.minFocusDiopters <= 0f) {
                toast("Set focus A and B first (and use a lens with focus control)")
                return
            }
            if (zoomToB) cam.rack(fa, fb, ms) else cam.rack(fb, fa, ms)
        }
        if (zoomToB) cam.zoomPull(a, b, ms) else cam.zoomPull(b, a, ms)
        zoomToB = !zoomToB
        refreshUi()
    }

    private fun applyExposureValue(v: Float) {
        if (cfg.manualExposure) {
            val ni = v.roundToInt().coerceIn(cam.isoRange.lower, cam.isoRange.upper)
            if (ni == cfg.iso) return
            cfg.iso = ni
        } else {
            val er = cam.evRange
            val ne = (v / cam.evStepEv).roundToInt().coerceIn(er.lower, er.upper)
            if (ne == cfg.ev) return
            cfg.ev = ne
        }
        cfgDirty = true
        cam.updateRepeating()
        refreshReadouts()
    }

    private fun cancelExpPull() {
        expAnim?.cancel()
        expAnim = null
    }

    private fun doExpPull() {
        val a = expSlider.markA
        val b = expSlider.markB
        if (a == null || b == null) {
            toast("Set exposure A and B first")
            return
        }
        val from = if (expToB) a else b
        val to = if (expToB) b else a
        expToB = !expToB
        cancelExpPull()
        val manual = cfg.manualExposure
        expAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = rackSeconds * 1000L
            interpolator = LinearInterpolator()
            addUpdateListener {
                val lin = it.animatedValue as Float
                val t = lin * lin * (3f - 2f * lin) // eases in and out, so the start and end of a pull are not abrupt
                val v = if (manual && from > 0f && to > 0f) from * (to / from).pow(t) else from + (to - from) * t
                applyExposureValue(v)
                expSlider.setZoomSilently(v)
            }
            start()
        }
        refreshUi()
    }

    // ---------- record + timer ----------

    private val countdownTick = object : Runnable {
        override fun run() {
            countdown--
            if (countdown <= 0) {
                countdownText.visibility = View.GONE
                fire()
            } else {
                countdownText.text = "$countdown"
                ui.postDelayed(this, 1000)
            }
        }
    }

    private fun cancelCountdown() {
        ui.removeCallbacks(countdownTick)
        countdown = 0
        if (::countdownText.isInitialized) countdownText.visibility = View.GONE
    }

    private fun toggleRec() {
        if (cam.recording) {
            cam.stopRecording()
            return
        }
        if (countdown > 0) {
            cancelCountdown()
            toast("Timer cancelled")
            return
        }
        if (cfg.timer > 0) {
            countdown = cfg.timer
            countdownText.text = "$countdown"
            countdownText.visibility = View.VISIBLE
            ui.postDelayed(countdownTick, 1000)
        } else {
            fire()
        }
    }

    private fun fire() {
        if (cfg.mode == 1) cam.takePhoto() else cam.startRecording()
    }

    // ---------- gestures ----------

    private fun setupGestures() {
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                tapFocus(e, false)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                tapFocus(e, true)
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (e.x < root.width / 2f) cam.toggleAeLock() else cam.toggleAwbLock()
                return true
            }
        })
        val sd = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                cam.cancelZoomPull()
                cam.setZoom(cam.zoom * d.scaleFactor)
                return true
            }
        })
        root.setOnTouchListener { _, ev ->
            if (!editing) {
                sd.onTouchEvent(ev)
                if (!sd.isInProgress) gd.onTouchEvent(ev)
            }
            true
        }
    }

    private fun tapFocus(e: MotionEvent, lock: Boolean) {
        val nx = (e.x - tv.left) / tv.width
        val ny = (e.y - tv.top) / tv.height
        if (nx !in 0f..1f || ny !in 0f..1f) return
        cam.focusAt(nx, ny, lock)
        showFocusMark(e.x, e.y, lock)
    }

    private fun showFocusMark(x: Float, y: Float, lock: Boolean) {
        val p = focusMark.layoutParams as FrameLayout.LayoutParams
        p.leftMargin = (x - dp(32)).toInt()
        p.topMargin = (y - dp(32)).toInt()
        focusMark.layoutParams = p
        (focusMark.background as GradientDrawable)
            .setStroke(dp(2), if (lock) AMBER else Color.WHITE)
        focusMark.animate().cancel()
        focusMark.alpha = 1f
        focusMark.animate().alpha(0f).setStartDelay(900).setDuration(400).start()
    }

    // ---------- clips ----------

    private fun queryClips(limit: Int): List<Pair<Uri, String>> {
        val res = ArrayList<Pair<Uri, String>>()
        val proj = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION
        )
        try {
            contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj,
                "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?", arrayOf("DCIM/Iris Cam/%"),
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { c ->
                while (c.moveToNext() && res.size < limit) {
                    val id = c.getLong(0)
                    val name = c.getString(1)
                    val secs = c.getLong(2) / 1000
                    res.add(
                        ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id) to
                            "$name  \u00B7  ${secs / 60}:${"%02d".format(secs % 60)}"
                    )
                }
            }
        } catch (_: Exception) {
        }
        return res
    }

    private fun queryPhotos(limit: Int): List<Pair<Uri, String>> {
        val res = ArrayList<Pair<Uri, String>>()
        val proj = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME)
        try {
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj,
                "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Images.Media.MIME_TYPE} LIKE ?",
                arrayOf("DCIM/Iris Cam/%", "image/%"),
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { c ->
                while (c.moveToNext() && res.size < limit) {
                    res.add(
                        ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)) to
                            c.getString(1)
                    )
                }
            }
        } catch (_: Exception) {
        }
        return res
    }

    private fun loadLatestClip() {
        val c = (if (cfg.mode == 1) queryPhotos(1) else queryClips(1)).firstOrNull()
        lastClip = c?.first
        if (c == null) {
            thumb.setImageDrawable(null)
            return
        }
        Thread {
            try {
                val bmp = contentResolver.loadThumbnail(c.first, android.util.Size(192, 192), null)
                runOnUiThread { thumb.setImageBitmap(bmp) }
            } catch (_: Exception) {
            }
        }.start()
    }

    private fun openClip(uri: Uri) {
        val mime = contentResolver.getType(uri) ?: if (cfg.mode == 1) "image/jpeg" else "video/mp4"
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        val photosIntent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .setPackage("com.google.android.apps.photos")
            .addFlags(flags)
        val fallbackIntent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(flags)
        try {
            // Prefer Google Photos explicitly; fall back to Android's normal media handler
            // when Google Photos is not installed or cannot handle this particular item.
            if (photosIntent.resolveActivity(packageManager) != null) {
                startActivity(photosIntent)
            } else {
                startActivity(fallbackIntent)
            }
        } catch (_: Exception) {
            try {
                startActivity(fallbackIntent)
            } catch (_: Exception) {
                toast("No gallery or media app found")
            }
        }
    }

    private fun openLast() {
        val u = lastClip
        if (u == null) toast("No clips yet") else openClip(u)
    }

    private fun showClips() {
        val clips = if (cfg.mode == 1) queryPhotos(30) else queryClips(30)
        if (clips.isEmpty()) {
            toast("No clips yet")
            return
        }
        choose(if (cfg.mode == 1) "Recent photos" else "Recent clips", clips.map { it.second }) { openClip(clips[it].first) }
    }

    // ---------- monitoring ----------

    private fun storageText(): String {
        val free = StatFs(Environment.getExternalStorageDirectory().path).availableBytes
        if (cfg.mode == 1) return "%.0f GB free \u00B7 %d%%".format(free / 1e9, batteryPct)
        val audio = if (cfg.fps > 60) 0L else 256_000L
        val bytesPerSec = (cfg.bitrateMbps * 1_000_000L + audio) / 8
        val secs = free / bytesPerSec
        val t = if (secs >= 3600) "${secs / 3600}h${secs % 3600 / 60}m" else "${secs / 60}m"
        return "%.0f GB \u00B7 %s \u00B7 %d%%".format(free / 1e9, t, batteryPct)
    }

    private fun levelPct(): Int {
        val a = cam.level()
        if (a <= 0) return 0
        val db = 20 * log10(a / 32767.0)
        return ((db + 60) / 60 * 100).roundToInt().coerceIn(0, 100)
    }

    private fun falseColor(v: Int): Int {
        val p = v / 255.0 * 100
        return when {
            p < 2 -> 0xFF7B1FA2.toInt()
            p < 8 -> 0xFF1565C0.toInt()
            p in 38.0..48.0 -> 0xFF2E7D32.toInt()
            p in 55.0..68.0 -> 0xFFF48FB1.toInt()
            p in 90.0..97.0 -> 0xFFFDD835.toInt()
            p > 97 -> 0xFFE53935.toInt()
            else -> Color.rgb(v, v, v)
        }
    }

    /** The preview copy comes out portrait-shaped; turn it into the upright landscape picture on screen. */
    private fun rotateToLandscape(src: IntArray, sw: Int, sh: Int, dst: IntArray, ccw: Boolean) {
        for (y in 0 until sh) {
            for (x in 0 until sw) {
                val v = src[y * sw + x]
                if (ccw) dst[(sw - 1 - x) * sh + y] = v else dst[x * sh + (sh - 1 - y)] = v
            }
        }
    }

    private fun isCcw() = (tv.display?.rotation ?: Surface.ROTATION_90) != Surface.ROTATION_270

    private fun sampleFalseColor() {
        if (!tv.isAvailable || !fcBusy.compareAndSet(false, true)) return
        try {
            tv.getBitmap(fcSrc)
            fcSrc.getPixels(fcSrcPx, 0, fcSw, 0, 0, fcSw, fcSh)
            val ccw = isCcw()
            val por = isPortrait()
            procExec.execute {
                try {
                    for (i in fcSrcPx.indices) {
                        val p = fcSrcPx[i]
                        val l = (77 * ((p shr 16) and 0xFF) + 150 * ((p shr 8) and 0xFF) + 29 * (p and 0xFF)) shr 8
                        fcSrcPx[i] = fcLut[l.coerceIn(0, 255)]
                    }
                    if (por) {
                        fcBmp.setPixels(fcSrcPx, 0, fcSw, 0, 0, fcSw, fcSh)
                    } else {
                        rotateToLandscape(fcSrcPx, fcSw, fcSh, fcDst, ccw)
                        fcBmp.setPixels(fcDst, 0, fcSh, 0, 0, fcSh, fcSw)
                    }
                    runOnUiThread { falseView.invalidate() }
                } catch (_: Throwable) {
                } finally {
                    fcBusy.set(false)
                }
            }
        } catch (e: Exception) {
            fcBusy.set(false)
        }
    }

    /** Focus peaking: red outline on pixels with strong local contrast (coarse, from a small preview copy). */
    private fun samplePeaking() {
        if (!tv.isAvailable || !pkBusy.compareAndSet(false, true)) return
        try {
            tv.getBitmap(pkSrc)
            pkSrc.getPixels(pkSrcPx, 0, pkSw, 0, 0, pkSw, pkSh)
            val ccw = isCcw()
            val por = isPortrait()
            val th = when (cfg.peak) {
                1 -> 140
                2 -> 90
                else -> 55
            }
            procExec.execute {
                try {
                    val w = pkSw
                    val h = pkSh
                    for (i in 0 until w * h) {
                        val p = pkSrcPx[i]
                        pkLum[i] = (77 * ((p shr 16) and 0xFF) + 150 * ((p shr 8) and 0xFF) + 29 * (p and 0xFF)) shr 8
                    }
                    java.util.Arrays.fill(pkMask, 0)
                    for (y in 1 until h - 1) {
                        for (x in 1 until w - 1) {
                            val i = y * w + x
                            val gx = pkLum[i + 1] - pkLum[i - 1]
                            val gy = pkLum[i + w] - pkLum[i - w]
                            if (abs(gx) + abs(gy) > th) pkMask[i] = 0xFFFF1744.toInt()
                        }
                    }
                    if (por) {
                        pkOut.setPixels(pkMask, 0, pkSw, 0, 0, pkSw, pkSh)
                    } else {
                        rotateToLandscape(pkMask, w, h, pkRes, ccw)
                        pkOut.setPixels(pkRes, 0, pkSh, 0, 0, pkSh, pkSw)
                    }
                    runOnUiThread { peakView.invalidate() }
                } catch (_: Throwable) {
                } finally {
                    pkBusy.set(false)
                }
            }
        } catch (e: Exception) {
            pkBusy.set(false)
        }
    }

    private fun registerThermal() {
        if (Build.VERSION.SDK_INT >= 29 && thermalListener == null) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            val listener = PowerManager.OnThermalStatusChangedListener { thermal = it }
            thermalListener = listener
            pm.addThermalStatusListener(mainExecutor, listener)
        }
    }

    private fun updateWarnings() {
        val w = ArrayList<String>()
        val bi = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (bi != null) {
            val lvl = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = bi.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val status = bi.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            val pct = if (lvl >= 0 && scale > 0) lvl * 100 / scale else 100
            batteryPct = pct
            if (pct <= 15 && !charging) w.add("Low battery: $pct%")
        }
        if (Build.VERSION.SDK_INT >= 29) {
            if (thermal >= PowerManager.THERMAL_STATUS_SEVERE) w.add("Phone is overheating \u2014 recording may stop")
            else if (thermal >= PowerManager.THERMAL_STATUS_MODERATE) w.add("Phone is getting hot")
        }
        warn.text = w.joinToString("\n")
        warn.visibility = if (w.isEmpty()) View.GONE else View.VISIBLE
    }

    private var statusCache = ""
    private var statusTick = -100

    private val ticker = object : Runnable {
        var n = 0
        override fun run() {
            n++
            if (cam.recording) {
                val s = (SystemClock.elapsedRealtime() - recStart) / 1000
                setT(timecode, "\u25CF %02d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60))
                setC(timecode, RED)
                if (tap?.running != true) audioMeter.setLevel(levelPct())
            } else {
                val txt = when {
                    longLeft >= 0 -> "%d:%02d".format(longLeft / 60, longLeft % 60)
                    cfg.mode == 1 -> if (cam.busy || cam.jobs > 0) "SAVING" else "PHOTO"
                    else -> "STBY"
                }
                setT(timecode, txt)
                setC(timecode, if (longLeft >= 0) RED else GREY)
                if (tap?.running != true) audioMeter.setLevel(0)
            }
            if (n - statusTick >= 20) {
                statusCache = storageText()
                statusTick = n
            }
            setT(statusText, statusCache)
            if (!cam.manualFocus && cam.minFocusDiopters > 0f && cam.liveFocus >= 0f) {
                slider.setFraction((cam.liveFocus / cam.minFocusDiopters).coerceIn(0f, 1f))
            }
            refreshReadouts()
            if (falseOn && !cam.recording) sampleFalseColor()
            if (cfg.peak > 0) samplePeaking()
            if (n % 8 == 1) updateWarnings()
            if (n % 5 == 0 && cfgDirty) {
                store.save("last", cfg)
                cfgDirty = false
            }
            ui.postDelayed(this, 250)
        }
    }

    // ---------- black & white preview ----------

    private var appliedPreviewFilterId = Int.MIN_VALUE
    private var appliedPreviewFilterIntensity = Int.MIN_VALUE
    private var previewFilterWarningShown = false

    private fun applyBwLook() {
        // Avoid rebuilding ColorMatrix/Paint on every UI refresh; only update when the
        // selected look changes. A preview-layer failure must never take down the camera.
        if (!::tv.isInitialized) return
        if (appliedPreviewFilterId == cfg.filterId &&
            appliedPreviewFilterIntensity == cfg.filterIntensity) return
        try {
            val paint = if (cfg.filterId > 0) Paint().apply {
                colorFilter = Look.previewFilter(cfg.filterId, cfg.filterIntensity)
            } else null
            tv.setLayerPaint(paint)
            appliedPreviewFilterId = cfg.filterId
            appliedPreviewFilterIntensity = cfg.filterIntensity
        } catch (e: RuntimeException) {
            runCatching { tv.setLayerPaint(null) }
            appliedPreviewFilterId = cfg.filterId
            appliedPreviewFilterIntensity = cfg.filterIntensity
            if (!previewFilterWarningShown) {
                previewFilterWarningShown = true
                toast("Preview filter unavailable on this device; camera preview is continuing")
            }
        }
    }

    // ---------- audio level (side meter) and monitor audio ----------

    private var lastMeterPct = 0

    private fun wantTap(): Boolean {
        if (cfg.mode != 0) return false
        if (!cam.recording) return true
        return (monitor?.audioClientCount ?: 0) > 0
    }

    private fun updateAudioTap() {
        if (!::cam.isInitialized) return
        if (wantTap()) {
            val t = tap ?: AudioTap(this) { buf, n, pk -> onAudio(buf, n, pk) }.also { tap = it }
            t.start()
        } else {
            tap?.stop()
        }
    }

    private fun onAudio(buf: ByteArray, n: Int, pk: Int) {
        monitor?.pushAudio(buf, n)
        val now = SystemClock.uptimeMillis()
        if (now - lastMeterAt < 50) return
        lastMeterAt = now
        val db = if (pk > 0) 20f * log10(pk / 32768f) else -90f
        val pct = (((db + 60f) / 60f) * 100f).roundToInt().coerceIn(0, 100)
        ui.post { audioMeter.setLevel(pct) }
    }

    // ---------- Wi-Fi monitor ----------

    private fun localIp(): String? {
        var fallback: String? = null
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in ni.inetAddresses) {
                    if (a is Inet4Address && !a.isLoopbackAddress) {
                        if (ni.name.startsWith("wlan")) return a.hostAddress
                        if (fallback == null) fallback = a.hostAddress
                    }
                }
            }
        } catch (_: Exception) {
        }
        return fallback
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        toast("Copied $label")
    }

    private fun addCopyRow(parent: LinearLayout, title: String, value: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(5), dp(4), dp(5))
        }
        val label = TextView(this).apply {
            text = "$title\n$value"
            setTextColor(Color.WHITE)
            textSize = 12f
            maxLines = 3
            setPadding(0, 0, dp(8), 0)
        }
        row.addView(label, LinearLayout.LayoutParams(0, WRAP, 1f))
        val copy = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Copy"
            isAllCaps = false
            textSize = 12f
            cornerRadius = dp(12)
            setOnClickListener { copyToClipboard(title, value) }
        }
        row.addView(copy, LinearLayout.LayoutParams(WRAP, WRAP))
        parent.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    private fun showUsbCommands() {
        val m = monitor
        val port = m?.port ?: 8080
        val usbLocalPort = 18080
        val url = if (m != null) "http://localhost:$usbLocalPort/?pin=${m.pin}" else "http://localhost:$usbLocalPort"
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        TextView(this).apply {
            text = "Connect your phone to the Mac with USB debugging enabled. Start Laptop Monitor first to get its live PIN."
            setTextColor(Color.WHITE)
            textSize = 13f
        }.also { content.addView(it) }
        addCopyRow(content, "ADB devices command", "adb devices")
        addCopyRow(content, "ADB reverse command", "adb reverse tcp:$usbLocalPort tcp:$port")
        addCopyRow(content, "Laptop monitor URL", url)
        addCopyRow(content, "Disconnect command", "adb reverse --remove tcp:$usbLocalPort")
        MaterialAlertDialogBuilder(this)
            .setTitle("USB monitoring on Mac")
            .setView(content)
            .setNegativeButton(if (m != null) "Turn off monitor" else "Close") { _, _ -> if (m != null) stopMonitor() }
            .setPositiveButton("Done", null)
            .show()
    }

    private fun showMonitorInfo() {
        val m = monitor ?: return
        val ip = localIp()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        TextView(this).apply {
            text = "Laptop camera monitor · PIN ${m.pin}\n\nThe feed is served locally on your network or through USB ADB."
            setTextColor(Color.WHITE)
            textSize = 13f
        }.also { content.addView(it) }
        if (ip != null) {
            addCopyRow(content, "Wi-Fi monitor URL", "http://$ip:${m.port}/?pin=${m.pin}")
        } else {
            TextView(this).apply {
                text = "Wi-Fi address unavailable. Connect both devices to the same network."
                setTextColor(Color.LTGRAY)
                textSize = 12f
            }.also { content.addView(it) }
        }
        addCopyRow(content, "ADB devices command", "adb devices")
        val usbLocalPort = 18080
        addCopyRow(content, "ADB reverse command", "adb reverse tcp:$usbLocalPort tcp:${m.port}")
        addCopyRow(content, "USB monitor URL", "http://localhost:$usbLocalPort/?pin=${m.pin}")
        addCopyRow(content, "Disconnect command", "adb reverse --remove tcp:$usbLocalPort")
        MaterialAlertDialogBuilder(this)
            .setTitle("Laptop camera monitor")
            .setView(content)
            .setNegativeButton("Turn off") { _, _ -> stopMonitor() }
            .setPositiveButton("Done", null)
            .show()
    }

    private fun shutterHaptic(view: View) {
        if (cfg.haptics) view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    private fun subtleSliderHaptic(view: View) {
        if (!cfg.haptics) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastSliderHapticMs >= 65L) {
            lastSliderHapticMs = now
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    private fun toggleMonitor() {
        if (monitor != null) {
            showMonitorInfo()
            return
        }
        val pin = (1000 + java.util.Random().nextInt(9000)).toString()
        val m = MonitorServer(monitorHost, pin)
        if (!m.start()) {
            toast("Couldn't start the monitor")
            return
        }
        monitor = m
        // Laptop monitoring is deliberately ungraded. Request the camera's flat tone curve while
        // the monitor is active; the laptop applies the user's .cube LUT independently.
        if (cfg.mode == 0) {
            monitorSavedFlat = cfg.flat
            if (!cfg.flat) {
                cfg.flat = true
                cam.updateRepeating()
            }
        }
        ui.post(monitorTick)
        updateAudioTap()
        showMonitorInfo()
        refreshUi()
    }

    private fun stopMonitor() {
        ui.removeCallbacks(monitorTick)
        monitor?.stop()
        monitor = null
        monitorSavedFlat?.let { previous ->
            if (cfg.flat != previous) {
                cfg.flat = previous
                if (::cam.isInitialized) cam.updateRepeating()
            }
        }
        monitorSavedFlat = null
        updateAudioTap()
        refreshUi()
    }

    private val monitorTick = object : Runnable {
        override fun run() {
            val m = monitor ?: return
            if (m.videoClients > 0 && tv.isAvailable) grabMonitorFrame(m)
            // 30 fps target. Frames are dropped automatically if capture/encoding falls behind.
            ui.postDelayed(this, if (m.videoClients > 0) 33L else 300L)
        }
    }

    /**
     * Produce a flat 720p/1080p monitoring image. LUT processing intentionally remains on the laptop;
     * the phone only scales/encodes its current preview. Work is moved off the UI thread and a
     * single reusable bitmap avoids the previous per-frame bitmap copy/allocation.
     */
    private fun grabMonitorFrame(m: MonitorServer) {
        if (prevW == 0 || prevH == 0 || !monitorFrameBusy.compareAndSet(false, true)) return
        procExec.execute {
            var rotated: Bitmap? = null
            try {
                val landscape = prevW >= prevH
                // Preserve native preview detail up to 1080p; use 720p when the selected
                // preview stream is smaller so the monitor never invents extra detail.
                val longEdge = if (landscape) prevW else prevH
                val targetLong = if (longEdge >= 1920) 1920 else 1280
                val targetShort = targetLong * 9 / 16
                val bw = if (landscape) targetLong else targetShort
                val bh = if (landscape) targetShort else targetLong
                var b = monitorBmp
                if (b == null || b.width != bw || b.height != bh || b.isRecycled) {
                    b?.recycle()
                    b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                    monitorBmp = b
                }
                if (!tv.isAvailable) return@execute
                tv.getBitmap(b)
                val deg = when (tv.display?.rotation ?: Surface.ROTATION_90) {
                    Surface.ROTATION_90 -> 270
                    Surface.ROTATION_270 -> 90
                    Surface.ROTATION_180 -> 180
                    else -> 0
                }
                val out = if (deg == 0) b else {
                    val mt = Matrix().apply { postRotate(deg.toFloat()) }
                    Bitmap.createBitmap(b, 0, 0, b.width, b.height, mt, false).also { rotated = it }
                }
                val bos = ByteArrayOutputStream(100_000)
                // MJPEG remains compatible with every browser. 1080p is used only when the
                // selected preview itself is at least 1080p; otherwise output remains 720p.
                // H.264/MSE remains a separate encoder/decoder migration.
                out.compress(Bitmap.CompressFormat.JPEG, if (targetLong == 1920) 56 else 60, bos)
                m.pushFrame(bos.toByteArray())
            } catch (_: Throwable) {
            } finally {
                rotated?.recycle()
                monitorFrameBusy.set(false)
            }
        }
    }

    private val monitorHost = object : MonitorServer.Host {
        override fun page(): ByteArray = assets.open("monitor.html").use { it.readBytes() }

        override fun stateJson(): String {
            val manual = cfg.manualExposure
            val o = JSONObject()
            o.put("rec", cam.recording)
            val s = if (cam.recording) (SystemClock.elapsedRealtime() - recStart) / 1000 else 0L
            o.put("tc", "%02d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60))
            o.put("mode", if (cfg.mode == 1) "photo" else "video")
            o.put("zoom", cam.zoom.toDouble())
            o.put("manual", manual)
            o.put("iso", if (manual) cfg.iso else cam.liveIso)
            o.put("shutter", if (manual) cfg.shutterDenom else cam.liveShutterDenom)
            o.put("fps", cfg.fps)
            o.put("res", resLabel())
            o.put("manualFocus", cam.manualFocus)
            val ff = if (cam.manualFocus) cam.focusFraction
            else if (cam.minFocusDiopters > 0f && cam.liveFocus >= 0f) (cam.liveFocus / cam.minFocusDiopters) else 0f
            o.put("focus", ff.coerceIn(0f, 1f).toDouble())
            o.put("focusSupported", cam.minFocusDiopters > 0f)
            o.put("zoomMin", cam.zoomRange.lower.toDouble())
            o.put("zoomMax", cam.zoomHi().toDouble())
            o.put("expManual", manual)
            o.put("expValue", expSlider.zoom.toDouble())
            o.put("expMin", if (manual) cam.isoRange.lower.toDouble() else -2.0)
            o.put("expMax", if (manual) cam.isoRange.upper.toDouble() else 2.0)
            o.put("expUnit", if (manual) "ISO" else "EV")
            o.put("expText", if (manual) "ISO ${cfg.iso}" else "EV ${"%+.1f".format(cfg.ev * cam.evStepEv)}")
            o.put("focusA", slider.markA?.toDouble() ?: JSONObject.NULL)
            o.put("focusB", slider.markB?.toDouble() ?: JSONObject.NULL)
            o.put("zoomA", zoomSlider.markA?.toDouble() ?: JSONObject.NULL)
            o.put("zoomB", zoomSlider.markB?.toDouble() ?: JSONObject.NULL)
            o.put("expA", expSlider.markA?.toDouble() ?: JSONObject.NULL)
            o.put("expB", expSlider.markB?.toDouble() ?: JSONObject.NULL)
            o.put("focusSeconds", rackSeconds)
            o.put("zoomSeconds", zoomSeconds)
            o.put("expSeconds", expSeconds)
            o.put("focusToB", rackToB)
            o.put("zoomToB", zoomToB)
            o.put("expToB", expToB)
            o.put("squeeze", 1.0)
            o.put("battery", batteryPct)
            return o.toString()
        }

        override fun command(name: String, arg: String): String {
            ui.post { runRemote(name, arg) }
            return "ok"
        }

        override fun clientsChanged(video: Int, audio: Int) {
            ui.post { updateAudioTap() }
        }
    }

    private fun runRemote(name: String, arg: String) {
        when (name) {
            "rec" -> if (cfg.mode == 0) {
                if (cam.recording) cam.stopRecording() else cam.startRecording()
            } else {
                cam.takePhoto()
            }
            "af" -> {
                cam.cancelRack()
                cam.setAutoFocus()
                refreshUi()
            }
            "focus" -> arg.toFloatOrNull()?.let {
                if (cam.minFocusDiopters > 0f) {
                    cam.cancelRack()
                    cam.setFocus(it.coerceIn(0f, 1f))
                    slider.setFraction(it.coerceIn(0f, 1f))
                    refreshUi()
                }
            }
            "focusA" -> if (cam.minFocusDiopters > 0f) { slider.markA = cam.focusFraction; slider.invalidate(); refreshUi() }
            "focusB" -> if (cam.minFocusDiopters > 0f) { slider.markB = cam.focusFraction; slider.invalidate(); refreshUi() }
            "focusRack" -> doRack()
            "focusTime" -> arg.toIntOrNull()?.let { rackSeconds = it.coerceIn(1, 12) }
            "zoomA" -> { zoomSlider.markA = cam.zoom; zoomSlider.invalidate(); refreshUi() }
            "zoomB" -> { zoomSlider.markB = cam.zoom; zoomSlider.invalidate(); refreshUi() }
            "zoomPull" -> doZoomPull()
            "zoomTime" -> arg.toIntOrNull()?.let { zoomSeconds = it.coerceIn(1, 12) }
            "exposure" -> arg.toFloatOrNull()?.let { applyExposureValue(it); expSlider.setZoomSilently(it) }
            "expA" -> { expSlider.markA = expSlider.zoom; expSlider.invalidate(); refreshUi() }
            "expB" -> { expSlider.markB = expSlider.zoom; expSlider.invalidate(); refreshUi() }
            "expPull" -> doExpPull()
            "expTime" -> arg.toIntOrNull()?.let { expSeconds = it.coerceIn(1, 12) }
            "zoom" -> arg.toFloatOrNull()?.let { cam.setZoom(it) }
            "manual" -> {
                cfg.manualExposure = !cfg.manualExposure
                cam.clearAeLock()
                applyLive()
            }
            "iso" -> if (cfg.manualExposure) arg.toIntOrNull()?.let {
                cfg.iso = it.coerceIn(cam.isoRange.lower, cam.isoRange.upper)
                cfgDirty = true
                cam.updateRepeating()
            }
            "shutter" -> if (cfg.manualExposure) arg.toIntOrNull()?.let {
                if (it > 0) {
                    cfg.shutterDenom = it
                    cfgDirty = true
                    cam.updateRepeating()
                }
            }
        }
    }

    // ---------- CameraController.Callbacks ----------

    override fun onMessage(msg: String) {
        toast(msg)
        refreshUi()
    }

    override fun onReady() {
        completeCameraTransition()
        store.save("last", cfg)
        refreshZoomSlider()
        expSliderManual = null
        refreshUi()
    }

    override fun onPreviewSize(w: Int, h: Int) {
        prevW = w
        prevH = h
        root.post { relayoutPreview() }
    }

    /** Fit the camera picture into the screen using the screen's real current shape. */
    private fun relayoutPreview() {
        val rw = root.width
        val rh = root.height
        if (rw == 0 || rh == 0 || prevW == 0 || prevH == 0) return
        var ar = if (rh > rw) prevH.toFloat() / prevW else prevW.toFloat() / prevH

        var vw = rw
        var vh = (rw / ar).toInt()
        if (vh > rh) {
            vh = rh
            vw = (rh * ar).toInt()
        }
        tv.layoutParams = lp(vw, vh, Gravity.CENTER)
        falseView.layoutParams = lp(vw, vh, Gravity.CENTER)
        peakView.layoutParams = lp(vw, vh, Gravity.CENTER)
        grid.layoutParams = lp(vw, vh, Gravity.CENTER)
        cam.refreshTransform()
    }

    override fun onRecording(on: Boolean) {
        if (on) recStart = SystemClock.elapsedRealtime()
        setRecLook(on)
        if (!on) loadLatestClip()
        updateAudioTap()
        refreshUi()
    }

    override fun onLongExposure(sec: Int) {
        longLeft = sec
        setRecLook(false)
    }

    override fun onBusy(busy: Boolean) {
        setRecLook(false)
        refreshUi()
    }

    override fun onPhotoSaved(uri: Uri?) {
        loadLatestClip()
    }

    override fun onFocusMoved(fraction: Float) {
        slider.setFraction(fraction)
        afChip.text = "MF"
        setActive(afChip, false)
    }

    override fun onStateChanged() {
        refreshUi()
    }

    override fun onZoomChanged(z: Float) {
        zoomLabel.text = "ZOOM " + fmtZoom(z)
        zoomSlider.setZoomSilently(z)
        setActive(t2x, z >= 1.9f)
    }
}
