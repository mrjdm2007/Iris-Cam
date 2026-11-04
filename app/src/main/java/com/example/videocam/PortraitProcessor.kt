package com.example.videocam

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.SegmentationMask
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import java.nio.ByteOrder
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
        var inferenceBitmap: Bitmap? = null
        try {
            // Bound the ML mask to 1280 px on the long edge. Raw-size masks at 50 MP
            // would consume hundreds of MB and can cause capture-time OOMs.
            val inferScale = min(1f, 1280f / max(source.width, source.height))
            val inferW = (source.width * inferScale).toInt().coerceAtLeast(1)
            val inferH = (source.height * inferScale).toInt().coerceAtLeast(1)
            val inference = if (inferW != source.width || inferH != source.height) {
                Bitmap.createScaledBitmap(source, inferW, inferH, true).also { inferenceBitmap = it }
            } else source
            val input = InputImage.fromBitmap(inference, 0)
            val mask = Tasks.await(segmenter.process(input))
            val maskBuffer = mask.buffer.asReadOnlyBuffer().order(ByteOrder.nativeOrder())
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
                    val confidence = maskBuffer.getFloat(index * Float.SIZE_BYTES).coerceIn(0f, 1f)
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
            inferenceBitmap?.recycle()
        }
    }

    private fun blurredBackground(source: Bitmap, strength: Int): Bitmap {
        // A downsample/upsample blur works consistently on API 29+ and avoids applying
        // RenderEffect to Paint (RenderEffect is a View/RenderNode effect, not a Paint property).
        // Keep the working image small to limit peak memory on high-resolution camera frames.
        val factor = when {
            strength >= 80 -> 18
            strength >= 55 -> 14
            strength >= 30 -> 10
            else -> 7
        }
        val smallW = (source.width / factor).coerceAtLeast(1)
        val smallH = (source.height / factor).coerceAtLeast(1)
        var small = Bitmap.createScaledBitmap(source, smallW, smallH, true)
        // Two additional low-resolution resampling passes soften block edges while keeping
        // the blur radius proportional to the selected strength.
        repeat(2) {
            val w = (small.width * 0.72f).toInt().coerceAtLeast(1)
            val h = (small.height * 0.72f).toInt().coerceAtLeast(1)
            val reduced = Bitmap.createScaledBitmap(small, w, h, true)
            if (reduced !== small) small.recycle()
            val enlarged = Bitmap.createScaledBitmap(reduced, smallW, smallH, true)
            if (enlarged !== reduced) reduced.recycle()
            small = enlarged
        }
        val out = Bitmap.createScaledBitmap(small, source.width, source.height, true)
        if (small !== out) small.recycle()
        return out
    }
}
