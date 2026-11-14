package com.ispezioni.endoscopio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer

/** Registra in MP4 (H.264) i fotogrammi che arrivano dall'anteprima (NV21 oppure RGBA). */
class FrameRecorder(private val path: String, private val rotationHint: Int) {
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var track = -1
    private var muxStarted = false
    private var t0 = 0L
    private var cw = 0
    private var ch = 0
    private var frames = 0
    private val info = MediaCodec.BufferInfo()
    @Volatile var error: String? = null

    val frameCount get() = frames

    private fun start(w: Int, h: Int) {
        cw = w and 1.inv()
        ch = h and 1.inv()
        val fmt = MediaFormat.createVideoFormat("video/avc", cw, ch)
        fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
        fmt.setInteger(MediaFormat.KEY_FRAME_RATE, 15)
        fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        val c = MediaCodec.createEncoderByType("video/avc")
        c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        c.start()
        codec = c
        muxer = MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also {
            it.setOrientationHint(rotationHint)
        }
        t0 = System.nanoTime()
    }

    @Synchronized
    fun feed(data: ByteArray, w: Int, h: Int, rgba: Boolean) {
        if (error != null) return
        try {
            if (codec == null) start(w, h)
            val c = codec ?: return
            if (w < cw || h < ch) return
            val need = if (rgba) w * h * 4 else w * h * 3 / 2
            if (data.size < need) return
            val idx = c.dequeueInputBuffer(20_000)
            if (idx >= 0) {
                val buf = c.getInputBuffer(idx)!!
                buf.clear()
                val out = ByteArray(cw * ch * 3 / 2)
                if (rgba) rgbaToNv12(data, w, out) else nv21ToNv12(data, w, out)
                buf.put(out)
                c.queueInputBuffer(idx, 0, out.size, (System.nanoTime() - t0) / 1000, 0)
                frames++
            }
            drain(false)
        } catch (t: Throwable) {
            error = t.message ?: t.javaClass.simpleName
        }
    }

    private fun nv21ToNv12(src: ByteArray, w: Int, out: ByteArray) {
        for (y in 0 until ch) System.arraycopy(src, y * w, out, y * cw, cw)
        val base = w * ch0(src, w)
        var o = cw * ch
        for (y in 0 until ch / 2) {
            var i = base + y * w
            var x = 0
            while (x < cw) {
                out[o++] = src[i + 1] // U
                out[o++] = src[i]     // V
                i += 2
                x += 2
            }
        }
    }

    // altezza reale dell'immagine = lunghezza / (w * 1.5)
    private fun ch0(src: ByteArray, w: Int): Int = (src.size * 2 / 3) / w

    private fun rgbaToNv12(src: ByteArray, w: Int, out: ByteArray) {
        for (y in 0 until ch) for (x in 0 until cw) {
            val p = (y * w + x) * 4
            val r = src[p].toInt() and 255
            val g = src[p + 1].toInt() and 255
            val b = src[p + 2].toInt() and 255
            out[y * cw + x] = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte()
        }
        var o = cw * ch
        for (y in 0 until ch step 2) for (x in 0 until cw step 2) {
            val p = (y * w + x) * 4
            val r = src[p].toInt() and 255
            val g = src[p + 1].toInt() and 255
            val b = src[p + 2].toInt() and 255
            out[o++] = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).toByte()
            out[o++] = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).toByte()
        }
    }

    private fun drain(eos: Boolean) {
        val c = codec ?: return
        val m = muxer ?: return
        while (true) {
            val i = c.dequeueOutputBuffer(info, if (eos) 10_000 else 0)
            when {
                i == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!eos) return else if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = m.addTrack(c.outputFormat)
                    m.start()
                    muxStarted = true
                }
                i >= 0 -> {
                    val b = c.getOutputBuffer(i)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxStarted) {
                        b.position(info.offset)
                        b.limit(info.offset + info.size)
                        m.writeSampleData(track, b, info)
                    }
                    c.releaseOutputBuffer(i, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    /** Chiude il file. Ritorna true se ha scritto almeno un fotogramma. */
    @Synchronized
    fun finish(): Boolean {
        var ok = frames > 0 && error == null
        try {
            val c = codec
            if (c != null && ok) {
                val idx = c.dequeueInputBuffer(50_000)
                if (idx >= 0) c.queueInputBuffer(idx, 0, 0, (System.nanoTime() - t0) / 1000, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                var n = 0
                while (n++ < 50) {
                    val i = c.dequeueOutputBuffer(info, 20_000)
                    if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED && !muxStarted) {
                        track = muxer!!.addTrack(c.outputFormat); muxer!!.start(); muxStarted = true
                    } else if (i >= 0) {
                        val b = c.getOutputBuffer(i)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && muxStarted) {
                            b.position(info.offset); b.limit(info.offset + info.size)
                            muxer!!.writeSampleData(track, b, info)
                        }
                        c.releaseOutputBuffer(i, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } catch (t: Throwable) {
            ok = false
        }
        try { codec?.stop() } catch (_: Throwable) {}
        try { codec?.release() } catch (_: Throwable) {}
        try { if (muxStarted) muxer?.stop() } catch (_: Throwable) { ok = false }
        try { muxer?.release() } catch (_: Throwable) {}
        codec = null
        muxer = null
        return ok && muxStarted
    }
}
