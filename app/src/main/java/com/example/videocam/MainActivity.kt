package com.example.videocam

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.content.ContentUris
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
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import android.widget.Button
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
        val AMBER = 0xFFFFB300.toInt()
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
    private lateinit var toolsCol: LinearLayout
    private lateinit var modeChip: TextView
    private lateinit var zA: TextView
    private lateinit var zB: TextView
    private lateinit var eA: TextView
    private lateinit var eB: TextView
    private lateinit var aChip: TextView
    private lateinit var bChip: TextView
    private val lensChips = LinkedHashMap<String, TextView>()

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
    private lateinit var lensRow: LinearLayout
    private val lensKeyChips = ArrayList<Pair<TextView, Float>>()
    private var monitor: MonitorServer? = null
    private var monitorEncoder: MonitorH264Encoder? = null
    private var monitorEncoderWidth = 0
    private var monitorEncoderHeight = 0
    private var monitorH264Failed = false
    // Preserve the camera flat-profile setting while laptop monitoring is active.
    private var monitorSavedFlat: Boolean? = null
    private var tap: AudioTap? = null
    private val monitorFrameBusy = AtomicBoolean(false)
    private var monitorBmp: Bitmap? = null
    @Volatile
    private var steadyPct = -1
    @Volatile
    private var longLeft = -1
    @Volatile
    private var lastMeterAt = 0L
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null
    private val steadyExec = Executors.newSingleThreadExecutor()
    private lateinit var chipRow: HorizontalScrollView
    private lateinit var topBar: LinearLayout
    private lateinit var topRow2: FrameLayout
    private lateinit var expLabel: TextView
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
        cfg.steady = false // legacy software stabilization pipeline is retired
        applyRequestedOrientation()
        buildUi()
        rebuildSamplers()
        cam = CameraController(this, tv, cfg, store, this)
        if (cfg.photoFormat == 3 && !cam.ultraHdrAvailable) cfg.photoFormat = 0
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
            // New default: standard JPEG. Ultra HDR and RAW remain available as explicit modes.
            cfg.photoFormat = 0
            cfg.ver = 3
            store.save("last", cfg)
        }
        slider.onChange = { f ->
            cam.cancelRack()
            cam.setFocus(f)
            subtleSliderHaptic(slider)
            refreshUi()
        }
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
        steadyExec.shutdown()
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

    private fun roundBg(color: Int, radiusDp: Int) = GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat()
        setColor(color)
    }

    private fun chipBg(active: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(18).toFloat()
        setColor(if (active) AMBER else 0xFF252830.toInt())
        setStroke(dp(1), if (active) AMBER else 0xFF494B52.toInt())
    }

    private fun setActive(t: TextView, on: Boolean) {
        t.background = chipBg(on)
        t.setTextColor(if (on) Color.BLACK else Color.WHITE)
    }

    private fun chip(text: String, onLong: (() -> Unit)? = null, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = chipBg(false)
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
            setPadding(dp(10), dp(3), dp(10), dp(3))
            background = roundBg(0xFF1B1D22.toInt(), 12)
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
            background = roundBg(0xEE1B1D22.toInt(), 16)
            setPadding(dp(6), dp(3), dp(6), dp(3))
        }
        s.addView(label, LinearLayout.LayoutParams(dp(74), WRAP))
        views.forEach { v ->
            val params = if (v is ZoomSlider) LinearLayout.LayoutParams(dp(210), dp(44))
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
        setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
    }

    /** Wraps a control in a draggable frame (movable in Edit layout mode) and adds it to the screen. */
    private fun drag(
        key: String, child: View, w: Int, h: Int, g: Int,
        l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0
    ): DragFrame {
        val f = DragFrame(this, key)
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
            setTextColor(Color.WHITE)
            textSize = 10f
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
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
        formatChip = chip("") { popup(formatChip, if (cfg.mode == 1) photoItems() else videoItems()) }
        imageChip = chip("IMAGE \u25BE") { popup(imageChip, imageItems()) }
        filtersChip = chip("FILTERS ▾") { popup(filtersChip, filterItems()) }
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
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xCC000000.toInt(), 0x00000000))
            setPadding(dp(12), dp(4), dp(12), dp(12))
        }
        top.addView(timecode, LinearLayout.LayoutParams(dp(112), WRAP))
        top.addView(meter, LinearLayout.LayoutParams(dp(70), dp(8)).apply { setMargins(0, 0, dp(8), 0) })
        chipRow = row(formatChip, imageChip, filtersChip, gridChip, toolsChip)
        top.addView(chipRow, LinearLayout.LayoutParams(0, WRAP, 1f))
        top.addView(statusText, LinearLayout.LayoutParams(WRAP, WRAP).apply { setMargins(dp(8), 0, 0, 0) })
        topBar = top
        root.addView(top, lp(MATCH, WRAP, Gravity.TOP))
        topRow2 = FrameLayout(this).apply {
            visibility = View.GONE
            setPadding(dp(8), 0, dp(8), 0)
        }
        root.addView(topRow2, lp(MATCH, WRAP, Gravity.TOP, 0, dp(44), 0, 0))

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
        listOf(t2x, tPeak, tFalse, tTorch, tTimer, tRot).forEach {
            it.maxLines = 1
            if (it !== tTorch) it.textSize = 11f
            it.setPadding(dp(4), dp(6), dp(4), dp(6))
            toolsCol.addView(it, LinearLayout.LayoutParams(dp(70), WRAP).apply { setMargins(0, dp(3), 0, dp(3)) })
        }
        drag("tools", toolsCol, WRAP, WRAP, Gravity.START or Gravity.CENTER_VERTICAL, dp(8), dp(20), 0, 0)

        // ----- focus slider + focus tools (right) -----
        slider = VerticalSlider(this)
        drag("focusSlider", slider, dp(56), MATCH, Gravity.END, 0, dp(56), dp(10), dp(14))

        afChip = chip("AF") {
            cam.setAutoFocus()
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
        val readRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        listOf(rMode, rIso, rShutter, rEv, rAe, rWb).forEach {
            readBoxes.add(it.box)
            readRow.addView(it.box, LinearLayout.LayoutParams(dp(80), WRAP).apply { setMargins(dp(3), 0, dp(3), dp(3)) })
        }

        zoomSlider = ZoomSlider(this)
        zoomLabel = label("ZOOM 1.0x")
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
        val zoomStrip = strip(zoomLabel, zA, zB, zoomSlider, zoomGo, zoomTime, zoomLink)
        lensRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
        }
        for ((name, ratio) in listOf("0.5x" to 0.5f, "1x" to 1f, "2.7x" to 2.7f)) {
            lateinit var c: TextView
            c = chip(name) { showLensDropdown(c, ratio) }
            c.maxLines = 1
            c.textSize = 12f
            c.setPadding(dp(8), dp(5), dp(8), dp(5))
            lensKeyChips.add(Pair(c, ratio))
            lensRow.addView(c, LinearLayout.LayoutParams(dp(52), WRAP).apply { setMargins(dp(2), 0, dp(2), dp(3)) })
        }

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
        val expStrip = strip(expLabel, eA, eB, expSlider, expGo, expTime)

        val stack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(readRow, LinearLayout.LayoutParams(WRAP, WRAP))
            addView(lensRow, LinearLayout.LayoutParams(WRAP, WRAP).apply { setMargins(dp(80), 0, 0, 0) })
            addView(zoomStrip, LinearLayout.LayoutParams(WRAP, WRAP).apply { setMargins(0, 0, 0, dp(3)) })
            addView(expStrip, LinearLayout.LayoutParams(WRAP, WRAP))
        }
        drag("bottomStack", stack, WRAP, WRAP, Gravity.START or Gravity.BOTTOM, dp(76), 0, 0, dp(8))

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
        editPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            listOf(done, mirror, reset).forEach {
                addView(it, LinearLayout.LayoutParams(WRAP, WRAP).apply { setMargins(dp(4), 0, dp(4), 0) })
            }
        }
        root.addView(editPanel, lp(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, dp(70), 0, 0))

        audioMeter = AudioMeterView(this)
        root.addView(audioMeter, lp(dp(6), dp(120), Gravity.START or Gravity.CENTER_VERTICAL, dp(1), 0, 0, 0))
        recEdge = EdgeLineView(this).apply {
            visibility = View.GONE
            isClickable = false
            isFocusable = false
        }
        root.addView(recEdge, lp(MATCH, MATCH, Gravity.TOP))
        meter.visibility = View.GONE

        setContentView(root)
    }

    private fun setRecLook(on: Boolean) {
        if (cfg.mode == 1) {
            recBtn.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                val saving = ::cam.isInitialized && (cam.busy || cam.jobs > 0 || longLeft >= 0)
                setColor(if (saving) 0xFF9E9E9E.toInt() else Color.WHITE)
                setStroke(dp(4), if (saving) 0xFFFFB300.toInt() else 0xFF424242.toInt())
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
        val w = dp(if (!p) (if (photo) 340 else 210) else (if (photo) 290 else 120))
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
            setFrameLp("tools", WRAP, WRAP, Gravity.START or Gravity.CENTER_VERTICAL, dp(6), dp(20), 0, 0)
            setFrameLp("focusSlider", dp(56), MATCH, Gravity.END, 0, dp(110), dp(6), dp(260))
            setFrameLp("focusTools", WRAP, WRAP, Gravity.END or Gravity.TOP, 0, dp(110), dp(66), 0)
            setFrameLp("bottomStack", MATCH, WRAP, Gravity.START or Gravity.BOTTOM, 0, 0, 0, dp(100))
            setFrameLp("rec", dp(64), dp(64), Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM, 0, 0, 0, dp(18))
            setFrameLp("thumb", dp(52), dp(52), Gravity.START or Gravity.BOTTOM, dp(20), 0, 0, dp(24))
            setFrameLp("mode", WRAP, WRAP, Gravity.END or Gravity.BOTTOM, 0, 0, dp(14), dp(32))
        } else {
            setFrameLp("tools", WRAP, WRAP, Gravity.START or Gravity.CENTER_VERTICAL, dp(8), dp(20), 0, 0)
            setFrameLp("focusSlider", dp(56), MATCH, Gravity.END, 0, dp(56), dp(10), dp(14))
            setFrameLp("focusTools", WRAP, WRAP, Gravity.END or Gravity.TOP, 0, dp(56), dp(76), 0)
            setFrameLp("bottomStack", WRAP, WRAP, Gravity.START or Gravity.BOTTOM, dp(76), 0, 0, dp(8))
            setFrameLp("rec", dp(64), dp(64), Gravity.END or Gravity.BOTTOM, 0, 0, dp(88), dp(16))
            setFrameLp("thumb", dp(52), dp(52), Gravity.END or Gravity.BOTTOM, 0, 0, dp(166), dp(22))
            setFrameLp("mode", WRAP, WRAP, Gravity.END or Gravity.BOTTOM, 0, 0, dp(70), dp(88))
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
        frames.forEach { it.editing = on }
        editPanel.visibility = if (on) View.VISIBLE else View.GONE
        if (on) toast("Drag any control. Tap Done when finished.") else savePositions()
    }

    private fun savePositions() {
        val ed = layoutPrefs().edit()
        frames.forEach { ed.putString(it.key, "${it.x / root.width},${it.y / root.height}") }
        ed.apply()
    }

    private fun applyPositions() {
        frames.forEach { f ->
            val p = layoutPrefs().getString(f.key, null)?.split(",") ?: return@forEach
            if (p.size == 2) {
                val fx = p[0].toFloatOrNull()
                val fy = p[1].toFloatOrNull()
                if (fx != null && fy != null) {
                    f.x = fx * root.width
                    f.y = fy * root.height
                }
            }
        }
    }

    private fun mirrorLayout() {
        frames.forEach { it.x = root.width - it.x - it.width }
        savePositions()
    }

    private fun resetLayout() {
        layoutPrefs().edit().clear().apply()
        frames.forEach {
            it.translationX = 0f
            it.translationY = 0f
        }
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
        return listOf(
            "Resolution: ${resLabel()}" to { pickRes() },
            "Frame rate: ${cfg.fps} fps" to { pickFps() },
            "Bitrate: ${cfg.bitrateMbps} Mbps" to { pickBitrate() },
            "Codec: ${codecLabel()}" to { pickCodec() },
            "Lens: $lens" to { pickLens() },
            "Mic: $mic" to { pickMic() },
            "Audio source: ${AUDIO[cfg.audioSource.coerceIn(0, 2)]}" to { pickAudio() }
        )
    }

    private fun videoImageItems(): List<Pair<String, () -> Unit>> = listOf(
        "Stabilization: ${if (cfg.stab != 0) "On (OIS + EIS where supported)" else "Off"}" to {
            cfg.stab = if (cfg.stab == 0) 2 else 0
            cfg.steady = false
            cfgDirty = true
            applyLive()
        },
        "Anamorphic de-squeeze: ${Squeeze.NAMES[cfg.squeeze.coerceIn(0, Squeeze.NAMES.size - 1)]}" to {
            choose("Anamorphic de-squeeze (preview and monitor only; the file stays squeezed)", Squeeze.NAMES) {
                cfg.squeeze = it
                applyLive()
                relayoutPreview()
            }
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

    /** Shared filter presets; photo effects are rendered on capture and video effects in the recorder GPU path. */
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
            "Colour · Vivid" to 18,
            "Retro · VHS" to 19
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
                    applyFilterChange()
                    toast(if (cfg.mode == 0 && id != 0) "${Look.FILTER_NAMES[id]} will be baked into recorded video" else "${Look.FILTER_NAMES[id]} selected")
                }
            }
        }
        return items + ("Intensity: ${cfg.filterIntensity}%" to {
            choose("Filter intensity", (0..100 step 10).map { "$it%" }) {
                cfg.filterIntensity = it * 10
                cfgDirty = true
                applyFilterChange()
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
            "Scene: ${arrayOf("Normal", "Night (1 s, ISO 1000)", "Astro (5 min, ISO 100)", "HDR (${cfg.hdrFrames} frames)")[cfg.scene.coerceIn(0, 3)]}" to { pickScene() },
            "HDR merge: ${cfg.hdrFrames} frames" to { pickHdrFrames() },
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
        "Lens shortcuts (0.5x / 1x / 2.7x): ${if (cfg.lensKeys) "On" else "Off"}" to {
            cfg.lensKeys = !cfg.lensKeys
            cfgDirty = true
            refreshUi()
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
        formatChip.text = if (photo) photoFormatText() else videoFormatText()
        setActive(formatChip, false)
        setActive(imageChip, false)
        setActive(filtersChip, cfg.filterId != 0)
        setActive(gridChip, cfg.grid != 0)
        setActive(toolsChip, false)
        modeChip.text = if (photo) "PHOTO \u21C4 VIDEO" else "VIDEO \u21C4 PHOTO"
        setActive(modeChip, false)
        val pullVis = if (photo) View.GONE else View.VISIBLE
        listOf(zA, zB, zoomGo, zoomTime, zoomLink, eA, eB, expGo, expTime, aChip, bChip, rackChip, rackTimeChip)
            .forEach { it.visibility = pullVis }
        tTorch.text = if (photo) "\u26A1 " + arrayOf("OFF", "ON", "AUTO")[cfg.flashMode.coerceIn(0, 2)] else "\u26A1"
        tTorch.textSize = if (photo) 11f else 18f
        setRecLook(cam.recording)

        setActive(t2x, cam.zoom >= 1.9f)
        lensChips.forEach { (id, c) -> setActive(c, cfg.cameraId == id) }
        lensKeyChips.forEach { (c, r) -> setActive(c, abs(cam.zoom - r) < 0.08f) }
        setActive(tPeak, cfg.peak > 0)
        setActive(tFalse, falseOn)
        setActive(tTorch, if (photo) cfg.flashMode != 0 else cam.torch)
        tTimer.text = if (cfg.timer == 0) "TIMER" else "TIMER ${cfg.timer}s"
        tRot.text = if (cfg.portrait) "\u21BB PORT" else "\u21BB LAND"
        setActive(tRot, cfg.portrait)
        tRot.visibility = if (cfg.mode == 1) View.GONE else View.VISIBLE
        applySliderWidths()
        lensRow.visibility = if (cfg.lensKeys) View.VISIBLE else View.GONE
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
        zoomLabel.text = "ZOOM " + fmtZoom(cam.zoom)
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

    private fun addLensChips() {
        var idx = 0
        for ((id, name) in listOf("2" to "0.7x", "0" to "1x", "3" to "2.7x")) {
            if (!cam.cameraIds.contains(id)) continue
            val c = chip(name) { switchLens(id) }
            c.maxLines = 1
            c.textSize = 12f
            c.setPadding(dp(4), dp(6), dp(4), dp(6))
            lensChips[id] = c
            toolsCol.addView(c, idx++, LinearLayout.LayoutParams(dp(70), WRAP).apply { setMargins(0, dp(3), 0, dp(3)) })
        }
    }

    private fun showLensDropdown(anchor: View, shortcutRatio: Float) {
        if (!idle()) return
        val suggested = cam.cameraIdForShortcut(shortcutRatio)

        // A shortcut selects the matching physical camera immediately when one is genuinely
        // advertised. Do not silently map an unavailable auxiliary lens to the main camera.
        if (suggested != null) {
            switchLens(suggested)
            return
        }

        // On devices without a matching auxiliary lens, retain the normal camera picker.
        val ids = cam.cameraIds
        if (ids.isEmpty()) {
            toast("No compatible camera lens was detected")
            return
        }
        val menu = PopupMenu(this, anchor)
        ids.forEachIndexed { index, id ->
            menu.menu.add(0, index, index, cam.cameraLabel(id))
        }
        menu.setOnMenuItemClickListener { item ->
            ids.getOrNull(item.itemId)?.let { switchLens(it) }
            true
        }
        menu.show()
    }

    private fun switchLens(id: String) {
        if (!idle()) return
        var target = id
        if (cfg.fps == 60 && !cam.supportsFps(id, 60)) {
            val fallback = cam.cameraIds.firstOrNull { !cam.isFrontCamera(it) && cam.supportsFps(it, 60) }
            if (fallback != null) {
                target = fallback
                toast("Selected lens does not support 60 fps; switched to the compatible main camera")
            } else {
                cfg.fps = 30
                toast("Selected lens does not support 60 fps; recording rate changed to 30 fps")
            }
        }
        cfg.cameraId = target
        store.save("last", cfg)
        cam.reopen()
        refreshUi()
    }

    private fun idle(): Boolean {
        if (cam.recording || cam.busy || cam.jobs > 0) {
            toast(if (cam.busy || cam.jobs > 0) "Wait for the photo to finish" else "Stop recording first")
            return false
        }
        return true
    }

    private fun applyFilterChange() {
        store.save("last", cfg)
        // CropRenderer is attached to the recording session when it is created.
        // Rebuild that session after a filter/intensity change so the recorder
        // receives the graded GPU surface instead of its unprocessed surface.
        if (cfg.mode == 0) cam.reconfigure() else cam.updateRepeating()
        refreshUi()
    }

    private fun applyLive() {
        store.save("last", cfg)
        cam.updateRepeating()
        refreshUi()
    }

    private fun streamChanged() {
        store.save("last", cfg)
        cam.reconfigure()
        refreshUi()
    }

    // ---------- pickers ----------

    private fun pickRes() {
        if (!idle()) return
        val s = cam.sizes
        if (s.isEmpty()) return
        val items = ArrayList<Triple<Size, Int, String>>()
        for (sz in s) {
            val base = if (sz.height == 2160) "4K" else "${sz.height}p"
            items.add(Triple(sz, 0, "$base 16:9  (${sz.width}\u00D7${sz.height})"))
            if (sz.height >= 1080) {
                for (r in 1 until Ratios.NAMES.size) {
                    val h = minOf(Ratios.heightFor(sz.width, r), sz.height)
                    items.add(Triple(sz, r, "$base ${Ratios.NAMES[r]}  (${sz.width}\u00D7$h)"))
                }
            }
        }
        choose("Resolution", items.map { it.third }) {
            val (sz, r, _) = items[it]
            cfg.width = sz.width
            cfg.height = sz.height
            cfg.ratio = r
            if (r > 0 && cfg.fps > 60) {
                cfg.fps = 30
                toast("Cinematic shapes record up to 60 fps, switched to 30 fps")
            } else if (r > 0 && isPortrait()) {
                toast("Cinematic shapes are used in landscape")
            }
            streamChanged()
        }
    }

    private fun pickFps() {
        if (!idle()) return
        val l = cam.fpsList
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
        val s = cam.captureSizes()
        if (s.size < 2) {
            toast(if (cam.effMode() == 1) "RAW uses one fixed size" else "Only one size on this lens")
            return
        }
        choose(
            "Photo size",
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
                "HDR \u2014 ${cfg.hdrFrames} frames, highlight and shadow fusion",
                "Night \u2014 1 s, ISO 1000 (use a tripod)",
                "Astro \u2014 5 min, ISO 100 (tripod, stars will trail slightly)"
            )
        ) {
            cfg.scene = arrayOf(0, 3, 1, 2)[it]
            if (cfg.scene == 3 && cfg.photoFormat != 0) {
                // Multi-frame fusion currently outputs a standard JPEG. Native JPEG_R is a
                // separate single-capture path and must not silently discard bracket frames.
                cfg.photoFormat = 0
                toast("Multi-frame HDR uses JPEG fusion; switched capture format to Phone JPEG")
            }
            streamChanged()
        }
    }

    private fun pickHdrFrames() {
        if (!idle()) return
        choose("HDR merge frames", listOf("2 frames · faster capture", "3 frames · wider exposure bracket")) {
            cfg.hdrFrames = if (it == 0) 2 else 3
            applyLive()
            toast("HDR will merge ${cfg.hdrFrames} exposures")
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

    private fun doZoomPull() {
        val a = zoomSlider.markA
        val b = zoomSlider.markB
        if (a == null || b == null) {
            toast("Set zoom A and B first")
            return
        }
        val ms = zoomSeconds * 1000L
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
            duration = expSeconds * 1000L
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
                "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? OR ${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?", arrayOf("Movies/VideoCam%", "Movies/Iris Cam%"),
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
                "(${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? OR ${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?) AND ${MediaStore.Images.Media.MIME_TYPE} = ?",
                arrayOf("Pictures/VideoCam%", "Pictures/Iris Cam%", "image/jpeg"),
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
        val base = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            // straight into Google Photos, so Android has nothing to ask about
            startActivity(Intent(base).setPackage("com.google.android.apps.photos"))
        } catch (_: Exception) {
            try {
                startActivity(base)
            } catch (e: Exception) {
                toast("No viewer app found")
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
                    steadyPct >= 0 -> "STEADY $steadyPct%"
                    cfg.mode == 1 -> if (cam.busy || cam.jobs > 0) "SAVING" else "PHOTO"
                    else -> "STBY"
                }
                setT(timecode, txt)
                setC(timecode, if (longLeft >= 0 || steadyPct >= 0) RED else GREY)
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

    private fun applyBwLook() {
        // TextureView preview grade; the GPU recorder renderer applies the matching grade to saved video.
        tv.setLayerPaint(
            if (cfg.filterId > 0) Paint().apply {
                colorFilter = Look.previewFilter(cfg.filterId, cfg.filterIntensity)
            } else null
        )
    }

    private fun toggleSteady() {
        if (!idle()) return
        if (!cfg.steady) {
            val why = cam.steadyPossible()
            if (why != null) {
                toast(why)
                return
            }
            cfg.steady = true
            if (cam.sizes.any { it.height == 2160 }) {
                cfg.width = 3840
                cfg.height = 2160
            }
            cfg.fps = 30
            cfg.ratio = 0
            cfg.stab = 2
            toast("Super steady on: records 4K30, then makes a stabilised 1080p copy. Keep the zoom still while filming.")
        } else {
            cfg.steady = false
        }
        streamChanged()
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

    /**
     * Show connection details as selectable text with an explicit copy button beside
     * every URL/terminal command. This is more reliable on touchscreens than relying
     * on tapping a list row or trying to select text inside an AlertDialog message.
     */
    private fun showCopyDialog(
        title: String,
        intro: String,
        entries: List<Pair<String, String>>,
        turnOff: (() -> Unit)? = null
    ) {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 8, 20, 4)
        }
        val introView = TextView(this).apply {
            text = intro
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 12)
        }
        body.addView(introView)
        entries.forEach { (label, value) ->
            val group = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 8, 0, 10)
            }
            val heading = TextView(this).apply {
                text = label
                textSize = 13f
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(AMBER)
            }
            val code = TextView(this).apply {
                text = value
                textSize = 13f
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
                setTextColor(Color.WHITE)
                setPadding(0, 6, 0, 6)
            }
            val copy = Button(this).apply {
                text = "COPY"
                isAllCaps = true
                setOnClickListener { copyToClipboard(label, value) }
            }
            group.addView(heading)
            group.addView(code)
            group.addView(copy)
            body.addView(group)
            val divider = View(this).apply {
                setBackgroundColor(0x44FFFFFF)
                layoutParams = LinearLayout.LayoutParams(MATCH, 1)
            }
            body.addView(divider)
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(body, ViewGroup.LayoutParams(MATCH, WRAP))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(scroll)
            .setNegativeButton(if (turnOff != null) "Turn off monitor" else "Close") { _, _ -> turnOff?.invoke() }
            .show()
    }

    private fun showUsbCommands() {
        val m = monitor
        val port = m?.port ?: 8080
        val url = if (m != null) "http://localhost:$port/?pin=${m.pin}" else "http://localhost:$port"
        val entries = listOf(
            "List connected Android devices" to "adb devices",
            "Enable USB port forwarding" to "adb reverse tcp:$port tcp:$port",
            "Open monitor on your Mac" to url,
            "Remove USB port forwarding" to "adb reverse --remove tcp:$port"
        )
        showCopyDialog(
            "USB monitoring on Mac",
            "Enable Developer options and USB debugging on your phone, connect a data-capable USB cable to your Mac, then authorize the Mac. Start Laptop Monitor first to get the live PIN and full URL. Tap COPY beside any command, or long-press the selectable text.",
            entries,
            if (m != null) ({ stopMonitor() }) else null
        )
    }

    private fun showMonitorInfo() {
        val m = monitor ?: return
        val ip = localIp()
        val wifiUrl = if (ip == null) null else "http://$ip:${m.port}/?pin=${m.pin}"
        val localUrl = "http://localhost:${m.port}/?pin=${m.pin}"
        val intro = buildString {
            append("PIN: ${m.pin}\n\n")
            if (wifiUrl != null) append("Wi-Fi: connect your Mac and phone to the same network and open the Wi-Fi URL below.\n\n")
            else append("Wi-Fi address unavailable. Connect both devices to the same network and try again.\n\n")
            append("USB-C: enable USB debugging, connect the phone to your Mac, authorize it, then run the ADB reverse command below. Open the localhost URL afterwards.\n\n")
            append("The monitor feed is served locally; this server does not upload it to the cloud.")
        }
        val entries = buildList {
            if (wifiUrl != null) add("Wi-Fi monitor URL" to wifiUrl)
            add("List connected Android devices" to "adb devices")
            add("Enable USB port forwarding" to "adb reverse tcp:${m.port} tcp:${m.port}")
            add("USB monitor URL (localhost)" to localUrl)
            add("Remove USB port forwarding" to "adb reverse --remove tcp:${m.port}")
        }
        showCopyDialog("Laptop camera monitor", intro, entries) { stopMonitor() }
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
        monitorH264Failed = false
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
        monitorEncoder?.close()
        monitorEncoder = null
        monitorEncoderWidth = 0
        monitorEncoderHeight = 0
        monitorH264Failed = false
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
                tv.getBitmap(b) ?: return@execute
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
                // H.264 is the primary monitor transport. MJPEG remains available as a
                // compatibility fallback for browsers without WebCodecs.
                if (m.h264ClientCount > 0 && !monitorH264Failed) {
                    try {
                        if (monitorEncoder == null || monitorEncoderWidth != out.width || monitorEncoderHeight != out.height) {
                            monitorEncoder?.close()
                            monitorEncoder = MonitorH264Encoder(out.width, out.height) { packet -> m.pushH264(packet) }
                            monitorEncoderWidth = out.width
                            monitorEncoderHeight = out.height
                        }
                        monitorEncoder?.encode(out)
                    } catch (e: Throwable) {
                        monitorEncoder?.close()
                        monitorEncoder = null
                        monitorH264Failed = true
                        m.pushH264(byteArrayOf(3))
                    }
                } else if (m.h264ClientCount == 0 && monitorEncoder != null) {
                    monitorEncoder?.close()
                    monitorEncoder = null
                    monitorEncoderWidth = 0
                    monitorEncoderHeight = 0
                }
                if (m.mjpegClients > 0 || (m.h264ClientCount > 0 && monitorH264Failed)) {
                    val bos = ByteArrayOutputStream(100_000)
                    out.compress(Bitmap.CompressFormat.JPEG, if (targetLong == 1920) 56 else 60, bos)
                    m.pushFrame(bos.toByteArray())
                }
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
            o.put("squeeze", Squeeze.VALUES[cfg.squeeze.coerceIn(0, Squeeze.VALUES.size - 1)].toDouble())
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
        if (cfg.mode == 0 && rh <= rw) ar *= Squeeze.VALUES[cfg.squeeze.coerceIn(0, Squeeze.VALUES.size - 1)]
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

    override fun onSteadyReady(uri: Uri, log: SteadyLog) {
        steadyExec.execute {
            val out = try {
                cam.newSteadyOutput()
            } catch (e: Exception) {
                ui.post { toast("Couldn't create the stabilised file") }
                return@execute
            }
            val err = SteadyProcessor(this@MainActivity).process(uri, log, out.second) { pct -> steadyPct = pct }
            try {
                out.second.close()
            } catch (_: Exception) {
            }
            cam.finishSteadyOutput(out.first, err == null)
            steadyPct = -1
            ui.post {
                if (err != null) toast("Super steady failed: $err") else loadLatestClip()
            }
        }
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
        lensKeyChips.forEach { (c, r) -> setActive(c, abs(z - r) < 0.08f) }
    }
}
