package com.example.videocam

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.SegmentationMask
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * Still-photo portrait rendering using an on-device person mask.
 *
 * The person is retained from the original image. Only the background is blurred,
 * with a confidence feather at the subject boundary to reduce hard cut-out edges.
 * Processing is called from CameraController's background photo worker.
 */
object PortraitProcessor {
    private val segmenter by lazy {
        val options = SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
            .enableRawSizeMask()
            .build()
        Segmentation.getClient(options)
    }

    fun apply(source: Bitmap, strength: Int): Bitmap {
        val amount = strength.coerceIn(0, 100)
        if (amount == 0 || source.width < 64 || source.height < 64) return source

        var background: Bitmap? = null
        try {
            val input = InputImage.fromBitmap(source, 0)
            val mask = Tasks.await(segmenter.process(input))
            val maskBuffer = mask.buffer.asReadOnlyBuffer()
            val maskWidth = mask.width
            val maskHeight = mask.height
            if (maskWidth <= 0 || maskHeight <= 0) return source

            background = blurredBackground(source, amount)
            val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
            val srcRow = IntArray(source.width)
            val bgRow = IntArray(source.width)
            val outRow = IntArray(source.width)
            val scaleX = maskWidth.toFloat() / source.width
            val scaleY = maskHeight.toFloat() / source.height
            val featherLow = 0.22f
            val featherHigh = 0.78f

            for (y in 0 until source.height) {
                source.getPixels(srcRow, 0, source.width, 0, y, source.width, 1)
                background.getPixels(bgRow, 0, source.width, 0, y, source.width, 1)
                val my = ((y + 0.5f) * scaleY).toInt().coerceIn(0, maskHeight - 1)
                for (x in 0 until source.width) {
                    val mx = ((x + 0.5f) * scaleX).toInt().coerceIn(0, maskWidth - 1)
                    val index = my * maskWidth + mx
                    val confidence = maskBuffer.get(index).coerceIn(0f, 1f)
                    val t = ((confidence - featherLow) / (featherHigh - featherLow)).coerceIn(0f, 1f)
                    val foreground = t * t * (3f - 2f * t)
                    val a = srcRow[x]
                    val b = bgRow[x]
                    val inv = 1f - foreground
                    val r = ((((a shr 16) and 255) * foreground) + (((b shr 16) and 255) * inv)).toInt().coerceIn(0, 255)
                    val g = ((((a shr 8) and 255) * foreground) + (((b shr 8) and 255) * inv)).toInt().coerceIn(0, 255)
                    val bl = (((a and 255) * foreground) + ((b and 255) * inv)).toInt().coerceIn(0, 255)
                    outRow[x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
                }
                out.setPixels(outRow, 0, source.width, 0, y, source.width, 1)
            }
            return out
        } catch (_: Throwable) {
            // Segmentation can fail on unsupported devices or low-memory conditions.
            // Keep the original photograph rather than failing the capture.
            return source
        } finally {
            background?.recycle()
        }
    }

    private fun blurredBackground(source: Bitmap, strength: Int): Bitmap {
        val radius = max(3f, min(source.width, source.height) * (strength / 100f) * 0.035f)
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
                renderEffect = RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
            }
            Canvas(out).drawBitmap(source, 0f, 0f, paint)
        } else {
            // API 29–30 fallback: a low-resolution background creates a soft, memory-safe blur.
            val factor = if (strength >= 70) 14 else 10
            val small = Bitmap.createScaledBitmap(
                source,
                (source.width / factor).coerceAtLeast(1),
                (source.height / factor).coerceAtLeast(1),
                true
            )
            Canvas(out).drawBitmap(
                small, null, android.graphics.Rect(0, 0, out.width, out.height),
                Paint(Paint.FILTER_BITMAP_FLAG)
            )
            small.recycle()
        }
        return out
    }
}
