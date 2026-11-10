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

/** Soft subject mask + intentionally mild background defocus for still photos. */
object PhotoPortraitBlur {
    private val segmenter: SubjectSegmenter by lazy {
        SubjectSegmentation.getClient(SubjectSegmenterOptions.Builder().enableForegroundConfidenceMask().build())
    }

    fun apply(source: Bitmap, percent: Int, detectedFaceCount: Int = 0): Bitmap? {
        val manual = percent.coerceIn(0, 100) != 40
        if (source.width < 2 || source.height < 2) return source.copy(Bitmap.Config.ARGB_8888, true)
        if (manual && percent.coerceIn(0, 100) == 0) return source.copy(Bitmap.Config.ARGB_8888, true)
        if (!manual && detectedFaceCount <= 0) return source.copy(Bitmap.Config.ARGB_8888, true)
        val scale = min(1f, 1024f / max(source.width, source.height))
        val sw = max(1, (source.width * scale).roundToInt())
        val sh = max(1, (source.height * scale).roundToInt())
        val small = if (sw == source.width && sh == source.height) source else Bitmap.createScaledBitmap(source, sw, sh, true)
        try {
            val result = Tasks.await(segmenter.process(InputImage.fromBitmap(small, 0)), 60, TimeUnit.SECONDS)
            val buf = result.foregroundConfidenceMask?.duplicate() ?: return null
            val mask = FloatArray(sw * sh)
            if (buf.remaining() < mask.size) return null
            buf.get(mask)
            val foregroundRatio = mask.count { it >= 0.5f }.toFloat() / mask.size.coerceAtLeast(1)
            // Slider position 40% means Auto. A single face with a small foreground
            // footprint is treated as a person in a wide landscape composition.
            val amount = if (manual) percent.coerceIn(0, 100) else when {
                detectedFaceCount >= 2 -> 35
                detectedFaceCount == 1 && foregroundRatio < 0.20f -> 40
                detectedFaceCount == 1 -> 80
                else -> 0
            }
            if (amount == 0) return source.copy(Bitmap.Config.ARGB_8888, true)

            // The slider scales the low-radius scale-down/up blur linearly.
            val downsample = (100 - (amount * 0.72f)).roundToInt().coerceIn(12, 100)
            val bw = max(1, source.width * downsample / 100)
            val bh = max(1, source.height * downsample / 100)
            val tiny = Bitmap.createScaledBitmap(source, bw, bh, true)
            val blur = Bitmap.createScaledBitmap(tiny, source.width, source.height, true)
            tiny.recycle()
            // The subject mask is still produced by ML Kit; the full-resolution
            // blend is delegated to GLES. Keep the established CPU blend as fallback.
            if (foregroundRatio >= 0.01f) {
                val gpu = runCatching {
                    PhotoGpuProcessor.blendPortrait(source, blur, mask, sw, sh)
                }.getOrNull()
                if (gpu != null) {
                    blur.recycle()
                    return gpu
                }
            }
            val w = source.width; val h = source.height
            val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val srcRow = IntArray(w); val blurRow = IntArray(w); val outRow = IntArray(w)
            var foregroundCount = 0
            for (y in 0 until h) {
                source.getPixels(srcRow, 0, w, 0, y, w, 1)
                blur.getPixels(blurRow, 0, w, 0, y, w, 1)
                val fy = ((y + 0.5f) * sh / h - 0.5f).coerceIn(0f, (sh-1).toFloat())
                val y0=fy.toInt(); val y1=min(sh-1,y0+1); val wy=fy-y0
                for (x in 0 until w) {
                    val fx=((x+0.5f)*sw/w-0.5f).coerceIn(0f,(sw-1).toFloat())
                    val x0=fx.toInt(); val x1=min(sw-1,x0+1); val wx=fx-x0
                    val top=mask[y0*sw+x0]+(mask[y0*sw+x1]-mask[y0*sw+x0])*wx
                    val bottom=mask[y1*sw+x0]+(mask[y1*sw+x1]-mask[y1*sw+x0])*wx
                    val confidence=(top+(bottom-top)*wy).coerceIn(0f,1f)
                    val t=((confidence-0.18f)/0.62f).coerceIn(0f,1f)
                    val fg=t*t*(3f-2f*t)
                    if(fg>0.8f) foregroundCount++
                    val orig=srcRow[x]; val softened=blurRow[x]; val bg=1f-fg
                    outRow[x]=Color.argb(Color.alpha(orig),
                        (Color.red(orig)*fg+Color.red(softened)*bg).roundToInt().coerceIn(0,255),
                        (Color.green(orig)*fg+Color.green(softened)*bg).roundToInt().coerceIn(0,255),
                        (Color.blue(orig)*fg+Color.blue(softened)*bg).roundToInt().coerceIn(0,255))
                }
                output.setPixels(outRow,0,w,0,y,w,1)
            }
            blur.recycle()
            if(foregroundCount < (w.toLong()*h/100L).toInt()) { output.recycle(); return null }
            return output
        } finally {
            if(small !== source) small.recycle()
        }
    }
}
