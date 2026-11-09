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
    val NAMES = listOf("16:9", "2.39:1", "2.35:1", "21:9", "1.85:1", "2:1", "16:10")
    val VALUES = listOf(0f, 2.39f, 2.35f, 21f / 9f, 1.85f, 2f, 16f / 10f)

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
    var manualExposure: Boolean = false, // video exposure only
    var photoExposureMode: Int = 1, // 0 = Auto, 1 = Smart, 2 = Manual
    var shutterDenom: Int = 50,      // shutter = 1/shutterDenom seconds
    var iso: Int = 100,
    var ev: Int = 0,                 // exposure compensation in camera steps (auto mode)
    var afFast: Boolean = false,
    var faceAf: Boolean = true,
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
    var photoFormat: Int = 0,        // 0 = phone JPEG, 1 = RAW develop, 2 = Ultra HDR 4-frame bracket, 3 = native Ultra HDR JPEG_R
    var saveDng: Boolean = false,
    var photoQuality: Int = 95,
    var photoW: Int = 0,             // 0 = default ~8 MP capture
    var photoH: Int = 0,
    var flashMode: Int = 0,          // 0 off, 1 on, 2 auto
    var photoNrOff: Boolean = false,
    var backgroundColorShiftEnabled: Boolean = false,
    var portraitPhotoModeEnabled: Boolean = false,
    var portraitBlurPercent: Int = 50,
    var bokehEnhancementEnabled: Boolean = false,
    var bokehStrength: Int = 25,
    var bokehGlow: Int = 50,
    var bokehShape: Int = 0,
    var backgroundHueShift: Int = 0,           // neutral hue for grayscale background
    var backgroundSaturation: Int = 0,         // default monochrome background
    var backgroundTemperature: Int = 0,        // neutral temperature
    var portrait: Boolean = false,
    var scene: Int = 0,              // photo scene: 0 normal, 1 night, 2 astro, 3 multi-frame HDR fusion
    var hdrFrames: Int = 4,          // four-frame Ultra HDR bracket
    var upscale: Boolean = true,     // default: upscale 8 MP photos to 12.6 MP
    var bw: Boolean = false,         // photo black and white, high contrast
    var blue: Boolean = false,        // cool blue cinematic photo filter
    var glow: Boolean = false,       // legacy setting migrated to filterId
    var filterId: Int = 27,          // default Sony S-Cinetone-inspired colour profile; 0 = Natural
    var filterIntensity: Int = 100,  // default video look strength, 0..100
    var photoFilterIntensity: Int = 100, // default photo look strength, 0..100
    var photoContrast: Int = 10, // default contrast strength for every lens, 0..100
    var photoContrastByLens: MutableMap<String, Int> = mutableMapOf(), // per-camera photo contrast
    var smartIso: Boolean = true, // auto photo ISO cap at 500
    var fastMovingObjects: Boolean = false, // short-exposure photo mode; main lens only
    var photoLook: Int = 0,          // 0 natural, 1 golden hour, 2 dream glow
    var photoNr: Int = 1,            // photo noise reduction: 0 minimal, 1 standard, 2 high
    var chromaticAberrationCorrection: Boolean = true, // request ISP lateral colour-fringe correction
    var lensKeys: Boolean = false,   // show the 0.5x / 1x / 2.7x shortcut buttons
    var haptics: Boolean = true,     // subtle shutter and manual slider feedback
    var ver: Int = 13
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
        photoExposureMode = o.photoExposureMode
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
        backgroundColorShiftEnabled = o.backgroundColorShiftEnabled
        portraitPhotoModeEnabled = o.portraitPhotoModeEnabled
        portraitBlurPercent = o.portraitBlurPercent
        bokehEnhancementEnabled = o.bokehEnhancementEnabled
        bokehStrength = o.bokehStrength
        bokehGlow = o.bokehGlow
        bokehShape = o.bokehShape
        backgroundHueShift = o.backgroundHueShift
        backgroundSaturation = o.backgroundSaturation
        backgroundTemperature = o.backgroundTemperature
        portrait = o.portrait
        scene = o.scene
        hdrFrames = o.hdrFrames
        upscale = o.upscale
        bw = o.bw
        blue = o.blue
        glow = o.glow
        filterId = o.filterId
        filterIntensity = o.filterIntensity
        photoFilterIntensity = o.photoFilterIntensity
        photoContrast = o.photoContrast
        photoContrastByLens = o.photoContrastByLens.toMutableMap()
        smartIso = o.smartIso
        fastMovingObjects = o.fastMovingObjects
        photoLook = o.photoLook
        photoNr = o.photoNr
        chromaticAberrationCorrection = o.chromaticAberrationCorrection
        lensKeys = o.lensKeys
        haptics = o.haptics
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
        .put("photoExposureMode", photoExposureMode.coerceIn(0, 2))
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
        .put("backgroundColorShiftEnabled", backgroundColorShiftEnabled)
        .put("portraitPhotoModeEnabled", portraitPhotoModeEnabled)
        .put("portraitBlurPercent", portraitBlurPercent.coerceIn(0, 100))
        .put("bokehEnhancementEnabled", bokehEnhancementEnabled)
        .put("bokehStrength", bokehStrength.coerceIn(0, 100))
        .put("bokehGlow", bokehGlow.coerceIn(0, 100))
        .put("bokehShape", bokehShape.coerceIn(0, 3))
        .put("backgroundHueShift", backgroundHueShift.coerceIn(-180, 180))
        .put("backgroundSaturation", backgroundSaturation.coerceIn(0, 200))
        .put("backgroundTemperature", backgroundTemperature.coerceIn(-100, 100))
        .put("portrait", portrait)
        .put("scene", scene)
        .put("hdrFrames", hdrFrames)
        .put("upscale", upscale)
        .put("bw", bw)
        .put("blue", blue)
        .put("glow", glow)
        .put("filterId", filterId)
        .put("filterIntensity", filterIntensity)
        .put("photoFilterIntensity", photoFilterIntensity)
        .put("photoContrast", photoContrast)
        .put("photoContrastByLens", JSONObject().apply { photoContrastByLens.forEach { (lens, value) -> put(lens, value.coerceIn(0, 100)) } })
        .put("smartIso", smartIso)
        .put("fastMovingObjects", fastMovingObjects)
        .put("photoLook", photoLook)
        .put("photoNr", photoNr)
        .put("chromaticAberrationCorrection", chromaticAberrationCorrection)
        .put("lensKeys", lensKeys)
        .put("haptics", haptics)
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
            val storedFilter = o.optInt("filterId", legacyFilter)
            val oldVersion = o.optInt("ver", 0)
            // Upgrade only the old untouched Natural/100% default; preserve explicitly selected looks.
            val migrateDefaultLook = oldVersion < 2 && storedFilter == 0 && o.optInt("filterIntensity", 100) == 100
            val selectedFilter = if (migrateDefaultLook) 18 else storedFilter.coerceIn(0, 30).let { if (it == 30) 0 else it }
            val storedVideoIntensity = o.optInt("filterIntensity", if (migrateDefaultLook) 25 else d.filterIntensity).coerceIn(0, 100)
            val storedPhotoIntensity = o.optInt("photoFilterIntensity", d.photoFilterIntensity).coerceIn(0, 100)
            // Version 3: raise only the untouched previous Zeiss defaults.
            // Any intensity the user had already changed is preserved.
            val selectedIntensity = if (oldVersion < 3 && selectedFilter == 18 && storedVideoIntensity == 25) 50 else storedVideoIntensity
            val selectedPhotoIntensity = if (oldVersion < 3 && selectedFilter == 18 && storedPhotoIntensity == 40) 70 else storedPhotoIntensity
            // Migrate only the untouched previous Zeiss defaults to Sony S-Cinetone at full strength.
            val migrateSonyDefault = oldVersion < 5 && selectedFilter == 18 && selectedIntensity == 50 && selectedPhotoIntensity == 70
            val finalFilterId = if (migrateSonyDefault) 27 else selectedFilter
            val finalVideoIntensity = if (migrateSonyDefault) 100 else selectedIntensity
            val finalPhotoIntensity = if (migrateSonyDefault) 100 else selectedPhotoIntensity
            val lensContrast = o.optJSONObject("photoContrastByLens")?.let { obj ->
                mutableMapOf<String, Int>().apply {
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val stored = obj.optInt(key, d.photoContrast).coerceIn(0, 100)
                        put(key, if (oldVersion < 7 && stored == 40) 10 else stored)
                    }
                }
            } ?: mutableMapOf()
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
                photoExposureMode = o.optInt("photoExposureMode", if (o.optInt("mode", d.mode) == 1 && o.optBoolean("manualExposure", false)) 2 else if (o.optBoolean("smartIso", d.smartIso)) 1 else 0).coerceIn(0, 2),
                shutterDenom = o.optInt("shutterDenom", d.shutterDenom),
                iso = o.optInt("iso", d.iso),
                ev = o.optInt("ev", d.ev),
                afFast = o.optBoolean("afFast", d.afFast),
                faceAf = if (oldVersion < 7) true else o.optBoolean("faceAf", d.faceAf),
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
                photoW = if (oldVersion < 7) 0 else o.optInt("photoW", d.photoW),
                photoH = if (oldVersion < 7) 0 else o.optInt("photoH", d.photoH),
                flashMode = o.optInt("flashMode", d.flashMode),
                photoNrOff = o.optBoolean("photoNrOff", d.photoNrOff),
                backgroundColorShiftEnabled = o.optBoolean("backgroundColorShiftEnabled", d.backgroundColorShiftEnabled),
                portraitPhotoModeEnabled = if (oldVersion < 11) false else o.optBoolean("portraitPhotoModeEnabled", d.portraitPhotoModeEnabled),
                portraitBlurPercent = if (oldVersion < 12) 50 else o.optInt("portraitBlurPercent", d.portraitBlurPercent).coerceIn(0, 100),
                bokehEnhancementEnabled = if (oldVersion < 11) false else o.optBoolean("bokehEnhancementEnabled", d.bokehEnhancementEnabled),
                bokehStrength = if (oldVersion < 13) 25 else o.optInt("bokehStrength", d.bokehStrength).coerceIn(0, 100),
                bokehGlow = (o.optInt("bokehGlow", d.bokehGlow).let { stored -> if (oldVersion < 9 && stored == 40) 50 else stored }).coerceIn(0, 100),
                bokehShape = o.optInt("bokehShape", d.bokehShape).coerceIn(0, 3),
                backgroundHueShift = (o.optInt("backgroundHueShift", d.backgroundHueShift).let { if (it == 24) 0 else it }).coerceIn(-180, 180),
                backgroundSaturation = (o.optInt("backgroundSaturation", d.backgroundSaturation).let { if (it == 115) 0 else it }).coerceIn(0, 200),
                backgroundTemperature = (o.optInt("backgroundTemperature", d.backgroundTemperature).let { if (it == 4) 0 else it }).coerceIn(-100, 100),
                portrait = o.optBoolean("portrait", d.portrait),
                scene = o.optInt("scene", d.scene),
                hdrFrames = 4,
                upscale = if (o.optInt("ver", 0) < 1) true else o.optBoolean("upscale", d.upscale),
                bw = selectedFilter == 1,
                blue = false,
                glow = false,
                filterId = finalFilterId,
                filterIntensity = finalVideoIntensity,
                photoFilterIntensity = finalPhotoIntensity,
                photoContrast = (if (oldVersion < 7 && o.optInt("photoContrast", 40) == 40) 10 else o.optInt("photoContrast", d.photoContrast)).coerceIn(0, 100),
                photoContrastByLens = lensContrast,
                smartIso = o.optBoolean("smartIso", d.smartIso),
                fastMovingObjects = o.optBoolean("fastMovingObjects", d.fastMovingObjects),
                photoLook = 0,
                photoNr = o.optInt("photoNr", if (o.optBoolean("photoNrOff", false)) 0 else d.photoNr),
                chromaticAberrationCorrection = o.optBoolean("chromaticAberrationCorrection", d.chromaticAberrationCorrection),
                lensKeys = o.optBoolean("lensKeys", d.lensKeys),
                haptics = o.optBoolean("haptics", d.haptics),
                ver = maxOf(12, o.optInt("ver", d.ver))
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
