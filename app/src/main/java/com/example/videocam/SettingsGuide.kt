package com.example.videocam

import android.app.Activity
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Searchable explanations for the controls exposed by Iris Cam. */
object SettingsGuide {
    private data class Entry(val section: String, val title: String, val detail: String)

    private val entries = listOf(
        Entry("Capture", "Photo / Video mode", "Switches between still-photo capture and video recording. Photo mode uses the selected photo format and image-processing path; video mode uses the selected resolution, frame rate, codec and bitrate."),
        Entry("Capture", "Shutter button", "In Photo mode, captures a still image. In Video mode, starts or stops recording. The thumbnail opens the most recently saved media."),
        Entry("Capture", "Self-timer", "Delays capture by the selected number of seconds. Choose Off, 3, 5 or 10 seconds. Useful when the phone is mounted or you want to avoid touching it during capture."),
        Entry("Capture", "Photo size", "Selects a still-image output size supported by the active camera. Higher resolution can preserve more detail but may increase processing time and file size."),
        Entry("Capture", "JPEG quality", "Controls JPEG compression quality. Higher values preserve more detail and create larger files; lower values reduce file size with more compression."),
        Entry("Exposure", "AUTO / MANUAL", "AUTO lets the camera choose ISO and shutter speed; both values remain visible but cannot be edited. MANUAL gives you direct control of ISO and shutter speed. Exposure compensation is available in AUTO where supported."),
        Entry("Exposure", "ISO", "Controls sensor sensitivity. Lower ISO generally produces cleaner images; higher ISO helps in dim scenes but can add noise. In AUTO it is read-only; in MANUAL tap the ISO value to choose a supported value."),
        Entry("Exposure", "Shutter speed", "Controls how long the sensor collects light. Longer exposure brightens static scenes but can blur motion or camera shake. In AUTO it is read-only; in MANUAL tap the shutter value to select a supported duration."),
        Entry("Exposure", "Exposure compensation (EV)", "In AUTO, biases the camera brighter or darker without directly setting ISO and shutter speed. The available range and step size depend on the active camera."),
        Entry("Exposure", "Exposure slider", "In AUTO, adjusts exposure compensation. In MANUAL, adjusts ISO within the active camera's supported range. Its A/B marks can be used by the shared rack controls."),
        Entry("Exposure", "AE Lock", "Locks automatic exposure when supported, helping keep brightness consistent while reframing. It is separate from MANUAL exposure."),
        Entry("Exposure", "White balance lock", "Locks the current automatic white-balance estimate when supported. This helps prevent colour temperature from changing while reframing."),
        Entry("Focus", "AF / MF", "AF asks the camera to focus automatically. MF enables manual focus control where the lens supports focus-distance adjustment. Some phone lenses have fixed focus."),
        Entry("Focus", "Focus slider", "Moves focus distance on supported lenses. The usable range depends on the camera hardware. Focus A/B marks store two positions for a timed rack."),
        Entry("Focus", "Focus peaking", "Highlights edges that appear in focus to help judge manual focus. Sensitivity can be Off, Low, Medium or High. It is a visual aid, not a guarantee of sharpness."),
        Entry("Focus", "Face autofocus", "Requests face-aware autofocus on cameras that advertise support. Unsupported cameras report that limitation."),
        Entry("Zoom", "Zoom slider", "Changes the camera zoom ratio across the range supported by the active lens. Optical and digital ranges vary by phone and camera."),
        Entry("Zoom", "Zoom presets", "Quickly selects common zoom ratios when those values are within the active camera's supported range."),
        Entry("Zoom", "Rack A/B", "Stores two zoom positions and smoothly moves between them over the selected duration. The shared rack selector determines whether A/B marks apply to Zoom or Exposure."),
        Entry("Zoom", "Shared rack indicator", "The small dot beside ZOOM or EXPOSURE identifies which slider currently owns the shared A/B rack controls. Select the corresponding slider to change the target."),
        Entry("Zoom", "Dolly Zoom", "Links the zoom rack with the focus rack so zoom and focus move together. Set both A/B pairs before running the linked move. The result depends on the scene and lens."),
        Entry("Image", "Image format", "Chooses among photo output formats supported by the active camera and processing path. Some multi-frame or filter combinations require standard JPEG instead of native Ultra HDR."),
        Entry("Image", "RAW / DNG", "When available, saves sensor data with less in-camera rendering for later development. RAW files are larger and may look flat until processed."),
        Entry("Image", "Native Ultra HDR", "Uses the camera's JPEG_R output when supported. It can preserve a camera-generated HDR gain map for compatible viewers and displays. Rendering depends on Android, the viewer and display settings."),
        Entry("Image", "Multi-frame HDR", "Combines bracketed exposures to retain more highlight and shadow information. The current fusion output is standard JPEG, not a newly generated Ultra HDR gain-map file."),
        Entry("Image", "Photo noise reduction", "Controls still-photo noise reduction. Stronger reduction can smooth noise but may also remove fine texture."),
        Entry("Looks", "Looks / filters", "Applies the selected colour profile or creative look. Intensity controls strength where available. Some looks are applied during photo or video processing rather than to the live preview."),
        Entry("Looks", "Contrast", "Adjusts saved-photo contrast. Higher values increase separation between bright and dark tones; excessive contrast can hide shadow or highlight detail."),
        Entry("Looks", "Vividness / saturation", "Changes colour intensity. Higher saturation can make colours more vivid but may make skin tones and bright colours look less natural."),
        Entry("Looks", "Glow", "Adds a soft highlight-oriented bloom to supported saved-photo output. It is not a depth-based portrait blur and does not alter the live preview."),
        Entry("Looks", "Black and White", "Renders the image without colour. The tonal result depends on scene lighting and the selected processing profile."),
        Entry("Looks", "Blue cinematic filter", "Applies a cool blue colour grade. It does not estimate depth or blur the background."),
        Entry("Preview", "False colour", "Maps brightness ranges to distinct colours to help inspect exposure. These colours are an analysis overlay, not the colours saved in the photo."),
        Entry("Preview", "Grid", "Displays a composition guide over the preview. It helps align horizons and subjects but does not change the captured image."),
        Entry("Preview", "Rotate grid", "Rotates supported grid or spiral overlays without rotating the camera image."),
        Entry("Video", "Resolution", "Sets recording frame dimensions. Higher resolutions capture more spatial detail but require more storage, processing and device bandwidth."),
        Entry("Video", "Frame rate", "Sets how many frames are recorded each second. Higher frame rates can make motion smoother but may require more light and storage."),
        Entry("Video", "Bitrate", "Controls encoded data per second. Higher bitrate can preserve more detail in complex scenes but creates larger files."),
        Entry("Video", "H.264 / H.265", "Selects the video codec where supported. H.265 can reduce file size at similar quality but may be less compatible with older devices and software."),
        Entry("Video", "Stabilization", "Requests supported optical and electronic stabilization from the camera. Availability and effectiveness depend on phone, lens, resolution and frame rate."),
        Entry("Video", "Audio source", "Selects the Android audio input mode. Available sources and sound characteristics depend on the phone's microphones and Android implementation."),
        Entry("Video", "Audio meter", "Shows incoming audio level to help identify very quiet or overly loud recording levels. It does not change microphone gain by itself."),
        Entry("Video", "Anti-banding", "Helps reduce flicker caused by artificial lighting. Auto, 50 Hz, 60 Hz and Off are available; choose the mains frequency used in your region if Auto does not work well."),
        Entry("Video", "Preview quality", "Chooses the camera preview stream size. This affects preview and monitoring workload; it is separate from recording resolution."),
        Entry("Video", "2x method", "Chooses whether the 2x shortcut uses camera zoom ratio or a crop-region method. Quality and availability depend on the camera implementation."),
        Entry("Utilities", "Torch / flash", "In video mode, toggles the torch where supported. In photo mode, cycles flash between Off, On and Auto."),
        Entry("Utilities", "Storage and battery", "Shows estimates of available storage, recording time and battery level. Actual recording time depends on bitrate, codec, resolution and device activity."),
        Entry("Utilities", "Wireless laptop monitor", "Starts a local Wi-Fi camera monitor. Connect the phone and Mac to the same Wi-Fi network, then open the displayed URL and PIN in a browser. Wired ADB monitoring is not supported."),
        Entry("Utilities", "Edit layout", "Lets you reposition supported panels and adjust their size. Portrait and landscape positions are saved separately. Reset restores defaults."),
        Entry("Utilities", "Phone capabilities", "Lists camera capabilities reported by Android for the active device. Support can differ between lenses and recording modes.")
    )

    fun show(activity: Activity) {
        val pad = (16 * activity.resources.displayMetrics.density).toInt()
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        val search = EditText(activity).apply {
            hint = "Search settings and features"
            isSingleLine = true
        }
        content.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val results = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(activity).apply { addView(results) }
        content.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (440 * activity.resources.displayMetrics.density).toInt()))
        fun render(query: String) {
            results.removeAllViews()
            val q = query.trim().lowercase()
            val matches = entries.filter { q.isEmpty() || it.title.lowercase().contains(q) || it.section.lowercase().contains(q) || it.detail.lowercase().contains(q) }
            if (matches.isEmpty()) {
                results.addView(TextView(activity).apply { text = "No matches. Try another setting name or related term."; setTextColor(Color.LTGRAY); setPadding(pad, pad, pad, pad) })
            }
            var section = ""
            matches.forEach { entry ->
                if (entry.section != section) {
                    section = entry.section
                    results.addView(TextView(activity).apply {
                        text = section.uppercase()
                        setTextColor(0xFFD0BCFF.toInt())
                        textSize = 11f
                        setPadding(pad / 2, pad, pad / 2, pad / 4)
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                    })
                }
                results.addView(TextView(activity).apply {
                    text = entry.title + "  ›"
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    setPadding(pad, pad * 3 / 4, pad, pad * 3 / 4)
                    setBackgroundColor(0xFF29272E.toInt())
                    setOnClickListener {
                        MaterialAlertDialogBuilder(activity).setTitle(entry.title).setMessage(entry.detail).setPositiveButton("Close", null).show()
                    }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, pad / 8, 0, pad / 8) })
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { render(s?.toString() ?: "") }
            override fun afterTextChanged(s: Editable?) {}
        })
        render("")
        MaterialAlertDialogBuilder(activity).setTitle("IRIS CAM · SETTINGS GUIDE").setView(content).setPositiveButton("Done", null).show()
    }
}
