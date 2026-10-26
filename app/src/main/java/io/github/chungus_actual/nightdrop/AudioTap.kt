package io.github.chungus_actual.nightdrop

import android.media.audiofx.Visualizer
import android.os.SystemClock
import android.util.Log
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The room's sound, from Android's global output-mix visualizer (audio session 0): whatever is playing,
 * Spotify included, without the playing app's cooperation. Allowed with RECORD_AUDIO +
 * MODIFY_AUDIO_SETTINGS (checked on Android 9; some devices refuse session 0, and then `error` says
 * so). About 20 captures a second of 1024 samples each.
 *
 * Everything the renderer reads is a plain field. The capture thread writes it and the GL thread reads
 * it; a torn read is one frame of slightly wrong colour, which nobody can see, so there is no lock.
 */
class AudioTap {
    val wave = FloatArray(BANDS)   // waveform, -1..1, downsampled to BANDS points
    val spec = FloatArray(BANDS)   // log-spaced spectrum, normalised (about 0..1.5)
    @Volatile var bass = 0f
    @Volatile var mid = 0f
    @Volatile var treb = 0f
    @Volatile var lastSound = 0L   // uptimeMillis of the last capture with real signal in it
    @Volatile var error: String? = null

    @Volatile private var beatFlag = false
    private var vis: Visualizer? = null
    private var avgBass = 0f
    private var lastBeat = 0L
    private var agc = 1f           // running peak, so a quiet track still fills the screen

    fun start() {
        if (vis != null) return
        try {
            val v = Visualizer(0)
            v.enabled = false
            v.captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
            v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(vz: Visualizer, w: ByteArray, rate: Int) = onWave(w)
                override fun onFftDataCapture(vz: Visualizer, f: ByteArray, rate: Int) = onFft(f)
            }, Visualizer.getMaxCaptureRate(), true, true)
            v.enabled = true
            vis = v
            error = null
            Log.i(TAG, "visualizer on: ${v.captureSize} samples, ${Visualizer.getMaxCaptureRate() / 1000} Hz")
        } catch (e: Throwable) {
            error = e.message ?: e.toString()
            Log.w(TAG, "visualizer failed", e)
        }
    }

    fun stop() {
        vis?.let {
            try { it.enabled = false; it.release() } catch (_: Throwable) {}
        }
        vis = null
    }

    /** A beat since the last call? Consumed by the renderer, once. */
    fun takeBeat(): Boolean {
        val b = beatFlag
        beatFlag = false
        return b
    }

    private fun onWave(w: ByteArray) {
        val n = w.size
        var loud = 0
        for (i in 0 until BANDS) {
            val s = ((w[i * n / BANDS].toInt() and 0xFF) - 128) / 128f
            wave[i] = wave[i] * 0.35f + s * 0.65f
            if (abs(s) > 0.03f) loud++
        }
        if (loud > 3) lastSound = SystemClock.uptimeMillis()
    }

    private fun onFft(f: ByteArray) {
        // f[0] = DC, f[1] = Nyquist, then (re, im) pairs for bins 1..n/2-1
        val half = f.size / 2
        if (half < 8) return
        val mags = FloatArray(half)
        mags[0] = abs(f[0].toFloat())
        for (k in 1 until half) {
            val re = f[2 * k].toFloat()
            val im = f[2 * k + 1].toFloat()
            mags[k] = sqrt(re * re + im * im)
        }
        // log-spaced bands: the low end gets as much of the screen as the top octave
        val bands = FloatArray(BANDS)
        var peak = 0f
        for (b in 0 until BANDS) {
            val lo = max(1, floor(half.toDouble().pow(b.toDouble() / BANDS)).toInt())
            val hi = max(lo + 1, floor(half.toDouble().pow((b + 1).toDouble() / BANDS)).toInt()).coerceAtMost(half)
            var sum = 0f
            for (k in lo until hi) sum += mags[k]
            // higher bands are quieter in real music; tilt them up so the treble moves too
            bands[b] = sum / (hi - lo) * (1f + b / 24f)
            peak = max(peak, bands[b])
        }
        agc = max(agc * 0.996f, max(peak, 4f))
        for (b in 0 until BANDS) {
            val v = bands[b] / agc * 1.4f
            spec[b] = if (v > spec[b]) v else spec[b] * 0.82f + v * 0.18f
        }
        bass = avg(0, 6)
        mid = avg(6, 30)
        treb = avg(30, BANDS)
        // a beat: the bass well above its own recent average, at most four a second
        val now = SystemClock.uptimeMillis()
        avgBass = avgBass * 0.94f + bass * 0.06f
        if (bass > avgBass * 1.35f && bass > 0.28f && now - lastBeat > 250) {
            lastBeat = now
            beatFlag = true
        }
    }

    private fun avg(from: Int, to: Int): Float {
        var s = 0f
        for (i in from until to) s += spec[i]
        return s / (to - from)
    }

    companion object {
        const val BANDS = 64
        const val TAG = "Nightdrop"
    }
}
