package com.example.videocam

import android.graphics.Bitmap
import android.graphics.Color
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenter
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Photo-only background colour grading. ML Kit supplies a soft foreground confidence
 * mask; only the inverse mask receives the hue/saturation/temperature adjustment.
 */
object PhotoBackgroundColorShift {
    private val segmenter: SubjectSegmenter by lazy {
        SubjectSegmentation.getClient(
            SubjectSegmenterOptions.Builder().enableForegroundConfidenceMask().build()
        )
    }

    fun apply(
        source: Bitmap,
        hueShiftDegrees: Int,
        saturationPercent: Int,
        temperature: Int
    ): Bitmap? {
        if (source.width < 2 || source.height < 2) return null

        val maxSide = 1024
        val scale = min(1f, maxSide.toFloat() / max(source.width, source.height))
        val segW = max(1, (source.width * scale).roundToInt())
        val segH = max(1, (source.height * scale).roundToInt())
        val segBitmap = if (segW == source.width && segH == source.height) {
            source
        } else {
            Bitmap.createScaledBitmap(source, segW, segH, true)
        }

        try {
            val result = Tasks.await(
                segmenter.process(InputImage.fromBitmap(segBitmap, 0)),
                60,
                TimeUnit.SECONDS
            )
            val maskBuffer = result.foregroundConfidenceMask ?: return null
            val maskView = maskBuffer.duplicate()
            val mask = FloatArray(segW * segH)
            if (maskView.remaining() < mask.size) return null
            maskView.get(mask)

            // Run the full-resolution background grade in a fragment shader. The
            // original CPU pixel path below remains the fallback for unsupported GPUs.
            if (mask.count { it >= 0.5f }.toFloat() / mask.size.coerceAtLeast(1) >= 0.01f) {
                val gpu = runCatching {
                    PhotoGpuProcessor.shiftBackground(
                        source, mask, segW, segH,
                        hueShiftDegrees, saturationPercent, temperature
                    )
                }.getOrNull()
                if (gpu != null) return gpu
            }

            val width = source.width
            val height = source.height
            val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val srcRow = IntArray(width)
            val outRow = IntArray(width)
            val hsv = FloatArray(3)
            var foregroundPixels = 0

            val hueShift = hueShiftDegrees.coerceIn(-180, 180).toFloat()
            val satScale = saturationPercent.coerceIn(0, 200) / 100f
            val warmShift = temperature.coerceIn(-100, 100) * 0.22f

            for (y in 0 until height) {
                source.getPixels(srcRow, 0, width, 0, y, width, 1)
                val fy = (((y + 0.5f) * segH / height) - 0.5f)
                    .coerceIn(0f, (segH - 1).toFloat())
                val y0 = fy.toInt()
                val y1 = min(segH - 1, y0 + 1)
                val wy = fy - y0

                for (x in 0 until width) {
                    val fx = (((x + 0.5f) * segW / width) - 0.5f)
                        .coerceIn(0f, (segW - 1).toFloat())
                    val x0 = fx.toInt()
                    val x1 = min(segW - 1, x0 + 1)
                    val wx = fx - x0

                    val a = mask[y0 * segW + x0]
                    val b = mask[y0 * segW + x1]
                    val c = mask[y1 * segW + x0]
                    val d = mask[y1 * segW + x1]
                    val top = a + (b - a) * wx
                    val bottom = c + (d - c) * wx
                    val confidence = (top + (bottom - top) * wy).coerceIn(0f, 1f)

                    // Smooth transition protects hair, clothing edges and fine details.
                    val t = ((confidence - 0.16f) / 0.68f).coerceIn(0f, 1f)
                    val fg = t * t * (3f - 2f * t)
                    if (fg > 0.8f) foregroundPixels++

                    val original = srcRow[x]
                    val backgroundWeight = 1f - fg
                    if (backgroundWeight <= 0.001f ||
                        (hueShift == 0f && satScale == 1f && warmShift == 0f)
                    ) {
                        outRow[x] = original
                        continue
                    }

                    Color.RGBToHSV(
                        Color.red(original),
                        Color.green(original),
                        Color.blue(original),
                        hsv
                    )
                    hsv[0] = (hsv[0] + hueShift + 360f) % 360f
                    hsv[1] = (hsv[1] * satScale).coerceIn(0f, 1f)
                    val shifted = Color.HSVToColor(Color.alpha(original), hsv)

                    val r = (Color.red(shifted) + warmShift).coerceIn(0f, 255f)
                    val g = Color.green(shifted).toFloat()
                    val bCh = (Color.blue(shifted) - warmShift).coerceIn(0f, 255f)
                    val alpha = Color.alpha(original)
                    val rr = (Color.red(original) * fg + r * backgroundWeight).roundToInt().coerceIn(0, 255)
                    val gg = (Color.green(original) * fg + g * backgroundWeight).roundToInt().coerceIn(0, 255)
                    val bb = (Color.blue(original) * fg + bCh * backgroundWeight).roundToInt().coerceIn(0, 255)
                    outRow[x] = Color.argb(alpha, rr, gg, bb)
                }
                output.setPixels(outRow, 0, width, 0, y, width, 1)
            }

            // Reject an empty/invalid mask rather than applying a colour cast to the whole photo.
            if (foregroundPixels < (width.toLong() * height / 100L).toInt()) {
                output.recycle()
                return null
            }
            return output
        } finally {
            if (segBitmap !== source) segBitmap.recycle()
        }
    }
}
