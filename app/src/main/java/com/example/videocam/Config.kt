package com.example.videocam

import android.content.Context
import org.json.JSONObject
import kotlin.math.abs

/** Per-lens colour adjustment for photos. Neutral = sat 1, warm 0, tint 0, contrast 1. */
data class Trim(
    var sat: Float = 1f,        // saturation 0.6 .. 1.4
    var warm: Float = 0f,       // -1 (cooler) .. +1 (warmer)
    var tint: Float = 0f,       // -1 (greener) .. +1 (more magenta)
    var contrast: Float = 1f    // 0.8 .. 1.2
) {
    fun isNeutral() = abs(sat - 1f) < 0.005f && abs(warm) < 0.005f && abs(tint) < 0.005f && abs(contrast - 1f) < 0.005f
    fun toText() = "$sat,$warm,$tint,$contrast"

    companion object {
        fun fromText(s: String?): Trim {
            val p = s?.split(",")?.mapNotNull { it.toFloatOrNull() } ?: return Trim()
            return if (p.size == 4) Trim(p[0], p[1], p[2], p[3]) else Trim()
        }
    }
}

/** Cinematic frame shapes offered as real recording resolutions. Index 0 = the camera's own 16:9. */
/** Anamorphic adapter squeeze factors for the de-squeezed preview. */
object Squeeze {
    val NAMES = listOf("Off", "1.33x", "1.5x", "1.8x", "2.0x")
    val VALUES = listOf(1f, 1.33f, 1.5f, 1.8f, 2f)
}

object Ratios {
    val NAMES = listOf("16:9", "2.39:1", "2.35:1", "21:9", "1.85:1", "2:1")
    val VALUES = listOf(0f, 2.39f, 2.35f, 21f / 9f, 1.85f, 2f)

    /** Height for a recording of this width in this shape, rounded to a multiple of 8 for the encoder. */
    fun heightFor(width: Int, idx: Int): Int {
        val ar = VALUES.getOrElse(idx) { 0f }
        if (ar <= 0f) return 0
        return (Math.round(width / ar / 8f) * 8)
    }
}

data class Config(
    var cameraId: String = "",
    var width: Int = 3840,
    var height: Int = 2160,
    var fps: Int = 30,
    var bitrateMbps: Int = 50,
    var hevc: Boolean = false,       // false = H.264, true = H.265
    var nrOff: Boolean = true,
    var stab: Int = 2,               // 0 = off, 2 = OIS + EIS where supported
    var flat: Boolean = false,
    var manualExposure: Boolean = false,
    var shutterDenom: Int = 50,      // shutter = 1/shutterDenom seconds
    var iso: Int = 100,
    var ev: Int = 0,                 // exposure compensation in camera steps (auto mode)
    var afFast: Boolean = false,
    var faceAf: Boolean = false,
    var micId: Int = -1,
    var audioSource: Int = 0,        // 0 = camcorder, 1 = mic, 2 = unprocessed
    var grid: Int = 0,
    var ratio: Int = 0,              // cinematic shape of the video resolution (index into Ratios), 0 = 16:9
    var gridFlip: Int = 0,
    var previewP: Int = 1080,        // preview stream height: 720 or 1080
    var antiband: Int = 0,           // 0 = auto, 1 = 50 Hz, 2 = 60 Hz, 3 = off
    var zoomMethod: Int = 0,         // 0 = zoom ratio, 1 = crop region
    var peak: Int = 0,               // focus peaking: 0 = off, 1 = low, 2 = medium, 3 = high
    var timer: Int = 0,              // self-timer seconds (0, 3, 5, 10)
    var mode: Int = 0,               // 0 = video, 1 = photo
    var photoFormat: Int = 0,        // 0 = phone JPEG (default), 1 = RAW develop, 3 = native Ultra HDR JPEG_R
    var saveDng: Boolean = false,
    var photoQuality: Int = 95,
    var photoW: Int = 0,             // 0 = default ~8 MP capture
    var photoH: Int = 0,
    var flashMode: Int = 0,          // 0 off, 1 on, 2 auto
    var photoNrOff: Boolean = false,
    var portrait: Boolean = false,
    var scene: Int = 0,              // photo scene: 0 normal, 1 night, 2 astro
    var upscale: Boolean = true,     // default: upscale 8 MP photos to 12.6 MP
    var bw: Boolean = false,         // photo black and white, high contrast
    var blue: Boolean = false,        // cool blue cinematic photo filter
    var glow: Boolean = false,       // legacy setting migrated to filterId
    var filterId: Int = 0,           // selected cinematic photo filter; 0 = Natural
    var filterIntensity: Int = 100,  // selected filter strength, 0..100
    var photoLook: Int = 0,          // 0 natural, 1 golden hour, 2 dream glow
    var photoNr: Int = 1,            // photo noise reduction: 0 minimal, 1 standard, 2 high
    var lensKeys: Boolean = false,   // show the 0.5x / 1x / 2.7x shortcut buttons
    var haptics: Boolean = true,     // subtle shutter and manual slider feedback
    var steady: Boolean = false,     // super steady: record 4K with gyro log, make a stabilised 1080p copy
    var ver: Int = 0
) {
    fun copyFrom(o: Config) {
        cameraId = o.cameraId
        width = o.width
        height = o.height
        fps = o.fps
        bitrateMbps = o.bitrateMbps
        hevc = o.hevc
        nrOff = o.nrOff
        stab = o.stab
        flat = o.flat
        manualExposure = o.manualExposure
        shutterDenom = o.shutterDenom
        iso = o.iso
        ev = o.ev
        afFast = o.afFast
        faceAf = o.faceAf
        micId = o.micId
        audioSource = o.audioSource
        grid = o.grid
        ratio = o.ratio
        gridFlip = o.gridFlip
        previewP = o.previewP
        antiband = o.antiband
        zoomMethod = o.zoomMethod
        peak = o.peak
        timer = o.timer
        mode = o.mode
        photoFormat = o.photoFormat
        saveDng = o.saveDng
        photoQuality = o.photoQuality
        photoW = o.photoW
        photoH = o.photoH
        flashMode = o.flashMode
        photoNrOff = o.photoNrOff
        portrait = o.portrait
        scene = o.scene
        upscale = o.upscale
        bw = o.bw
        blue = o.blue
        glow = o.glow
        filterId = o.filterId
        filterIntensity = o.filterIntensity
        photoLook = o.photoLook
        photoNr = o.photoNr
        lensKeys = o.lensKeys
        haptics = o.haptics
        steady = o.steady
        ver = o.ver
    }

    fun toJson(): String = JSONObject()
        .put("cameraId", cameraId)
        .put("width", width)
        .put("height", height)
        .put("fps", fps)
        .put("bitrateMbps", bitrateMbps)
        .put("hevc", hevc)
        .put("nrOff", nrOff)
        .put("stab", stab)
        .put("flat", flat)
        .put("manualExposure", manualExposure)
        .put("shutterDenom", shutterDenom)
        .put("iso", iso)
        .put("ev", ev)
        .put("afFast", afFast)
        .put("faceAf", faceAf)
        .put("micId", micId)
        .put("audioSource", audioSource)
        .put("grid", grid)
        .put("ratio", ratio)
        .put("gridFlip", gridFlip)
        .put("previewP", previewP)
        .put("antiband", antiband)
        .put("zoomMethod", zoomMethod)
        .put("peak", peak)
        .put("timer", timer)
        .put("mode", mode)
        .put("photoFormat", photoFormat)
        .put("saveDng", saveDng)
        .put("photoQuality", photoQuality)
        .put("photoW", photoW)
        .put("photoH", photoH)
        .put("flashMode", flashMode)
        .put("photoNrOff", photoNrOff)
        .put("portrait", portrait)
        .put("scene", scene)
        .put("upscale", upscale)
        .put("bw", bw)
        .put("blue", blue)
        .put("glow", glow)
        .put("filterId", filterId)
        .put("filterIntensity", filterIntensity)
        .put("photoLook", photoLook)
        .put("photoNr", photoNr)
        .put("lensKeys", lensKeys)
        .put("haptics", haptics)
        .put("steady", steady)
        .put("ver", ver)
        .toString()

    companion object {
        fun fromJson(s: String): Config {
            val d = Config()
            val o = JSONObject(s)
            val legacyFilter = when {
                o.optBoolean("bw", d.bw) -> 1
                o.optBoolean("blue", d.blue) -> 11
                o.optBoolean("glow", d.glow) -> 14
                o.optInt("photoLook", d.photoLook) == 1 -> 12
                o.optInt("photoLook", d.photoLook) == 2 -> 13
                else -> 0
            }
            val selectedFilter = o.optInt("filterId", legacyFilter).coerceIn(0, 17)
            return Config(
                cameraId = o.optString("cameraId", d.cameraId),
                width = o.optInt("width", d.width),
                height = o.optInt("height", d.height),
                fps = o.optInt("fps", d.fps),
                bitrateMbps = o.optInt("bitrateMbps", d.bitrateMbps),
                hevc = o.optBoolean("hevc", d.hevc),
                nrOff = o.optBoolean("nrOff", d.nrOff),
                stab = o.optInt("stab", d.stab),
                flat = o.optBoolean("flat", d.flat),
                manualExposure = o.optBoolean("manualExposure", d.manualExposure),
                shutterDenom = o.optInt("shutterDenom", d.shutterDenom),
                iso = o.optInt("iso", d.iso),
                ev = o.optInt("ev", d.ev),
                afFast = o.optBoolean("afFast", d.afFast),
                faceAf = o.optBoolean("faceAf", d.faceAf),
                micId = o.optInt("micId", d.micId),
                audioSource = o.optInt("audioSource", d.audioSource),
                grid = o.optInt("grid", d.grid),
                ratio = o.optInt("ratio", d.ratio),
                gridFlip = o.optInt("gridFlip", d.gridFlip),
                previewP = o.optInt("previewP", d.previewP),
                antiband = o.optInt("antiband", d.antiband),
                zoomMethod = o.optInt("zoomMethod", d.zoomMethod),
                peak = o.optInt("peak", d.peak),
                timer = o.optInt("timer", d.timer),
                mode = o.optInt("mode", d.mode),
                photoFormat = o.optInt("photoFormat", d.photoFormat),
                saveDng = o.optBoolean("saveDng", d.saveDng),
                photoQuality = o.optInt("photoQuality", d.photoQuality),
                photoW = o.optInt("photoW", d.photoW),
                photoH = o.optInt("photoH", d.photoH),
                flashMode = o.optInt("flashMode", d.flashMode),
                photoNrOff = o.optBoolean("photoNrOff", d.photoNrOff),
                portrait = o.optBoolean("portrait", d.portrait),
                scene = o.optInt("scene", d.scene),
                upscale = if (o.optInt("ver", 0) < 1) true else o.optBoolean("upscale", d.upscale),
                bw = selectedFilter == 1,
                blue = false,
                glow = false,
                filterId = selectedFilter,
                filterIntensity = o.optInt("filterIntensity", 100).coerceIn(0, 100),
                photoLook = 0,
                photoNr = o.optInt("photoNr", if (o.optBoolean("photoNrOff", false)) 0 else d.photoNr),
                lensKeys = o.optBoolean("lensKeys", d.lensKeys),
                haptics = o.optBoolean("haptics", d.haptics),
                steady = o.optBoolean("steady", d.steady),
                ver = maxOf(1, o.optInt("ver", d.ver))
            )
        }
    }
}

class Store(ctx: Context) {
    private val p = ctx.getSharedPreferences("videocam", Context.MODE_PRIVATE)

    fun save(key: String, c: Config) {
        p.edit().putString(key, c.toJson()).apply()
    }

    fun trim(lensId: String): Trim = Trim.fromText(p.getString("trim:$lensId", null))

    fun saveTrim(lensId: String, t: Trim) {
        p.edit().putString("trim:$lensId", t.toText()).apply()
    }

    fun load(key: String): Config? =
        p.getString(key, null)?.let { runCatching { Config.fromJson(it) }.getOrNull() }
}
