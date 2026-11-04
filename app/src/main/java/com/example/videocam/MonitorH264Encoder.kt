package com.example.videocam

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Low-latency H.264 monitor encoder.
 *
 * The monitor path deliberately uses a modest 720p/20fps-compatible input size. Camera preview
 * frames are converted to YUV420 and fed to the hardware AVC encoder. Packets are framed for
 * the monitor WebSocket and decoded by the browser's WebCodecs API.
 */
class MonitorH264Encoder(
    private val width: Int,
    private val height: Int,
    private val onPacket: (ByteArray) -> Unit
) {
    private val codec: MediaCodec
    private val colorFormat: Int
    private val pixels = IntArray(width * height)
    private val info = MediaCodec.BufferInfo()
    private var configSent = false
    private var closed = false
    private var lastFrameNs = 0L

    init {
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0) {
            "H.264 dimensions must be positive even numbers"
        }
        val selected = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals("video/avc", true) } }
            .mapNotNull { ci ->
                val caps = try { ci.getCapabilitiesForType("video/avc") } catch (_: Exception) { null }
                val formats = caps?.colorFormats ?: return@mapNotNull null
                val preferred = when {
                    formats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) ->
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
                    formats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) ->
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
                    else -> return@mapNotNull null
                }
                ci to preferred
            }.firstOrNull() ?: throw IllegalStateException("No H.264 encoder with byte-buffer YUV420 input")
        colorFormat = selected.second
        codec = MediaCodec.createByCodecName(selected.first.name)
        val fmt = MediaFormat.createVideoFormat("video/avc", width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
            setInteger(MediaFormat.KEY_BIT_RATE, if (width >= 1280) 2_000_000 else 1_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 20)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        }
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    @Synchronized
    fun encode(bitmap: Bitmap) {
        if (closed || bitmap.width != width || bitmap.height != height) return
        val nowNs = System.nanoTime()
        if (lastFrameNs != 0L && nowNs - lastFrameNs < 50_000_000L) {
            drain()
            return
        }
        lastFrameNs = nowNs
        val inputIndex = codec.dequeueInputBuffer(0)
        if (inputIndex >= 0) {
            val input = codec.getInputBuffer(inputIndex)
            if (input != null) {
                input.clear()
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                writeYuv420(pixels, input)
                val pts = nowNs / 1000L
                codec.queueInputBuffer(inputIndex, 0, input.position(), pts, 0)
            } else {
                codec.queueInputBuffer(inputIndex, 0, 0, nowNs / 1000L, 0)
            }
        }
        drain()
    }

    private fun writeYuv420(argb: IntArray, out: ByteBuffer) {
        val ySize = width * height
        val chromaSize = ySize / 4
        val yPlane = 0
        val uPlane = if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) ySize else ySize
        val vPlane = if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) ySize + chromaSize else ySize + 1
        for (row in 0 until height) {
            for (col in 0 until width) {
                val c = argb[row * width + col]
                val r = (c shr 16) and 255
                val g = (c shr 8) and 255
                val b = c and 255
                val y = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(0, 255)
                out.put(yPlane + row * width + col, y.toByte())
            }
        }
        for (row in 0 until height step 2) {
            for (col in 0 until width step 2) {
                var us = 0
                var vs = 0
                for (dy in 0..1) for (dx in 0..1) {
                    val c = argb[(row + dy) * width + col + dx]
                    val r = (c shr 16) and 255
                    val g = (c shr 8) and 255
                    val b = c and 255
                    us += (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255)
                    vs += (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255)
                }
                val ci = (row / 2) * (width / 2) + col / 2
                val u = (us / 4).toByte()
                val v = (vs / 4).toByte()
                if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) {
                    out.put(uPlane + ci, u)
                    out.put(vPlane + ci, v)
                } else {
                    val uv = ySize + (row / 2) * width + col
                    out.put(uv, u)
                    out.put(uv + 1, v)
                }
            }
        }
        out.position(ySize + chromaSize * 2)
    }

    private fun drain() {
        while (!closed) {
            val index = codec.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    sendConfig(codec.outputFormat)
                }
                index >= 0 -> {
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer != null && info.size > 0) {
                        val data = ByteArray(info.size)
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        buffer.get(data)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            sendConfigFromBuffer(data)
                        } else {
                            if (!configSent) sendConfig(codec.outputFormat)
                            val key = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                            val avcc = toAvcc(data)
                            if (avcc.isNotEmpty()) {
                                val packet = ByteBuffer.allocate(10 + avcc.size).order(ByteOrder.BIG_ENDIAN)
                                packet.put(2)
                                packet.put(if (key) 1 else 0)
                                packet.putLong(info.presentationTimeUs)
                                packet.put(avcc)
                                onPacket(packet.array())
                            }
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                }
            }
        }
    }

    private fun sendConfig(format: MediaFormat) {
        if (configSent) return
        val all = ArrayList<ByteArray>()
        try { format.getByteBuffer("csd-0")?.let { all.addAll(extractNals(copyBuffer(it))) } } catch (_: Exception) {}
        try { format.getByteBuffer("csd-1")?.let { all.addAll(extractNals(copyBuffer(it))) } } catch (_: Exception) {}
        val sps = all.firstOrNull { nalType(it) == 7 }
        val pps = all.firstOrNull { nalType(it) == 8 }
        if (sps != null && pps != null) sendConfig(sps, pps)
    }

    private fun sendConfigFromBuffer(data: ByteArray) {
        val nals = extractNals(data)
        val sps = nals.firstOrNull { nalType(it) == 7 }
        val pps = nals.firstOrNull { nalType(it) == 8 }
        if (sps != null && pps != null) sendConfig(sps, pps)
    }

    private fun sendConfig(sps: ByteArray, pps: ByteArray) {
        if (configSent || sps.size < 4 || sps.size > 65535 || pps.size > 65535) return
        val avcc = ByteBuffer.allocate(11 + sps.size + pps.size).order(ByteOrder.BIG_ENDIAN)
        avcc.put(1)
        avcc.put(sps[1])
        avcc.put(sps[2])
        avcc.put(sps[3])
        avcc.put(0xFF.toByte())
        avcc.put(0xE1.toByte())
        avcc.putShort(sps.size.toShort())
        avcc.put(sps)
        avcc.put(1)
        avcc.putShort(pps.size.toShort())
        avcc.put(pps)
        val packet = ByteBuffer.allocate(5 + avcc.position()).order(ByteOrder.BIG_ENDIAN)
        packet.put(1)
        packet.putShort(width.toShort())
        packet.putShort(height.toShort())
        avcc.flip()
        packet.put(avcc)
        onPacket(packet.array())
        configSent = true
    }

    private fun copyBuffer(src: ByteBuffer): ByteArray {
        val b = src.duplicate()
        val bytes = ByteArray(b.remaining())
        b.get(bytes)
        return bytes
    }

    private fun nalType(nal: ByteArray): Int = if (nal.isNotEmpty()) nal[0].toInt() and 0x1F else -1

    private fun extractNals(data: ByteArray): List<ByteArray> {
        val starts = ArrayList<Pair<Int, Int>>()
        var i = 0
        while (i + 3 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                starts.add(i to 3); i += 3
            } else if (i + 4 < data.size && data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                starts.add(i to 4); i += 4
            } else i++
        }
        if (starts.isNotEmpty()) {
            val result = ArrayList<ByteArray>()
            for (n in starts.indices) {
                val begin = starts[n].first + starts[n].second
                val end = if (n + 1 < starts.size) starts[n + 1].first else data.size
                if (end > begin) result.add(data.copyOfRange(begin, end))
            }
            return result
        }
        // Some encoders expose CSD / access units as four-byte length-prefixed NAL units.
        val lengthPrefixed = ArrayList<ByteArray>()
        var pos = 0
        while (pos + 4 <= data.size) {
            val len = ((data[pos].toInt() and 255) shl 24) or
                ((data[pos + 1].toInt() and 255) shl 16) or
                ((data[pos + 2].toInt() and 255) shl 8) or
                (data[pos + 3].toInt() and 255)
            if (len <= 0 || pos + 4 + len > data.size) {
                lengthPrefixed.clear()
                break
            }
            lengthPrefixed.add(data.copyOfRange(pos + 4, pos + 4 + len))
            pos += 4 + len
        }
        return if (lengthPrefixed.isNotEmpty() && pos == data.size) lengthPrefixed
        else if (data.isNotEmpty()) listOf(data) else emptyList()
    }

    private fun toAvcc(data: ByteArray): ByteArray {
        val nals = extractNals(data).filter { it.isNotEmpty() }
        if (nals.isEmpty()) return ByteArray(0)
        val total = nals.sumOf { 4 + it.size }
        val out = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN)
        nals.forEach { out.putInt(it.size); out.put(it) }
        return out.array()
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        try { codec.stop() } catch (_: Exception) {}
        try { codec.release() } catch (_: Exception) {}
    }
}
