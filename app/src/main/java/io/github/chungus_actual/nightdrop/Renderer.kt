package io.github.chungus_actual.nightdrop

import android.graphics.Bitmap
import android.opengl.GLES30.*
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.os.SystemClock
import android.util.Log
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Two feedback buffers at a modest resolution (the trails are soft anyway, and the Tegra X1 has a 4K
 * TV to fill), ping-ponged each frame through the current preset, then one composite pass up to the
 * screen. Audio comes from [audio]; album art arrives through [setArt] from the UI thread.
 *
 * Quiet room: when nothing has made a sound for a few seconds the inputs fall back to slow sines, so
 * the screen breathes instead of freezing on the last trail.
 */
class Renderer(private val audio: AudioTap) : GLSurfaceView.Renderer {

    @Volatile var preset = 0
    @Volatile var onError: ((String) -> Unit)? = null

    private val programs = IntArray(Shaders.PRESETS.size)
    private var composite = 0
    private val fbo = IntArray(2)
    private val tex = IntArray(2)
    private var cur = 0
    private var artTex = 0
    private var audioTex = 0       // 64x2 R8: waveform row, spectrum row (see Shaders.COMMON)
    private val audioBytes = java.nio.ByteBuffer.allocateDirect(AudioTap.BANDS * 2)
    private var hasArt = false
    @Volatile private var pendingArt: Bitmap? = null
    @Volatile private var pendingNoArt = false
    private var fbW = 1280
    private var fbH = 720
    private var viewW = 1
    private var viewH = 1
    private val t0 = SystemClock.uptimeMillis()

    // what the shader sees, smoothed toward the audio
    private var bass = 0f
    private var mid = 0f
    private var treb = 0f
    private var beat = 0f
    private val wave = FloatArray(AudioTap.BANDS)
    private val spec = FloatArray(AudioTap.BANDS)
    private val pal = arrayOf(Palette.DEFAULT[0].copyOf(), Palette.DEFAULT[1].copyOf(), Palette.DEFAULT[2].copyOf())
    @Volatile private var palTarget = Palette.DEFAULT
    private var fade = 0f

    fun setArt(bmp: Bitmap?) {
        palTarget = Palette.from(bmp)
        if (bmp == null) { pendingNoArt = true; return }
        val side = 512
        pendingArt = if (bmp.width > side || bmp.height > side)
            Bitmap.createScaledBitmap(bmp, side, side * bmp.height / max(1, bmp.width), true) else bmp
    }

    /** One triangle that overhangs the screen on two sides: (-1,-1), (3,-1), (-1,3). */
    private var tri = 0

    private fun drawTri() {
        glBindBuffer(GL_ARRAY_BUFFER, tri)
        glEnableVertexAttribArray(0)
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 0, 0)
        glDrawArrays(GL_TRIANGLES, 0, 3)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val b = IntArray(1)
        glGenBuffers(1, b, 0)
        tri = b[0]
        val verts = java.nio.ByteBuffer.allocateDirect(6 * 4).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
        verts.put(floatArrayOf(-1f, -1f, 3f, -1f, -1f, 3f)).position(0)
        glBindBuffer(GL_ARRAY_BUFFER, tri)
        glBufferData(GL_ARRAY_BUFFER, 6 * 4, verts, GL_STATIC_DRAW)
        try {
            for (i in programs.indices) programs[i] = program(Shaders.VERTEX, Shaders.PRESETS[i], Shaders.NAMES[i])
            composite = program(Shaders.VERTEX, Shaders.COMPOSITE, "composite")
        } catch (e: RuntimeException) {
            Log.e(AudioTap.TAG, "shader", e)
            onError?.invoke(e.message ?: "shader failed")
        }
        val t = IntArray(1)
        glGenTextures(1, t, 0)
        artTex = t[0]
        glBindTexture(GL_TEXTURE_2D, artTex)
        params(GL_LINEAR)
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE,
            java.nio.ByteBuffer.allocateDirect(4).put(byteArrayOf(30, 90, 90, -1)).position(0))
        hasArt = false
        glGenTextures(1, t, 0)
        audioTex = t[0]
        glBindTexture(GL_TEXTURE_2D, audioTex)
        params(GL_LINEAR)
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1)
        glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, AudioTap.BANDS, 2, 0, GL_RED, GL_UNSIGNED_BYTE, null)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewW = width
        viewH = height
        fbH = min(720, height)
        fbW = fbH * width / max(1, height)
        if (tex[0] != 0) { glDeleteTextures(2, tex, 0); glDeleteFramebuffers(2, fbo, 0) }
        glGenTextures(2, tex, 0)
        glGenFramebuffers(2, fbo, 0)
        for (i in 0..1) {
            glBindTexture(GL_TEXTURE_2D, tex[i])
            params(GL_LINEAR)
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, fbW, fbH, 0, GL_RGBA, GL_UNSIGNED_BYTE, null)
            glBindFramebuffer(GL_FRAMEBUFFER, fbo[i])
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex[i], 0)
            glClearColor(0f, 0f, 0f, 1f)
            glClear(GL_COLOR_BUFFER_BIT)
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        Log.i(AudioTap.TAG, "surface ${width}x$height, feedback ${fbW}x$fbH")
    }

    override fun onDrawFrame(gl: GL10?) {
        pendingArt?.let {
            glBindTexture(GL_TEXTURE_2D, artTex)
            params(GL_LINEAR)
            GLUtils.texImage2D(GL_TEXTURE_2D, 0, it, 0)
            hasArt = true
            pendingArt = null
        }
        if (pendingNoArt) { hasArt = false; pendingNoArt = false }
        val t = (SystemClock.uptimeMillis() - t0) / 1000f
        feed(t)
        audioBytes.position(0)
        for (i in 0 until AudioTap.BANDS) audioBytes.put(((wave[i] * 0.5f + 0.5f).coerceIn(0f, 1f) * 255f).toInt().toByte())
        for (i in 0 until AudioTap.BANDS) audioBytes.put(((spec[i] * 0.5f).coerceIn(0f, 1f) * 255f).toInt().toByte())
        audioBytes.position(0)
        glBindTexture(GL_TEXTURE_2D, audioTex)
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, AudioTap.BANDS, 2, GL_RED, GL_UNSIGNED_BYTE, audioBytes)

        val p = programs[preset.coerceIn(0, programs.size - 1)]
        if (p != 0) {
            glBindFramebuffer(GL_FRAMEBUFFER, fbo[1 - cur])
            glViewport(0, 0, fbW, fbH)
            glUseProgram(p)
            glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, tex[cur])
            glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, artTex)
            glActiveTexture(GL_TEXTURE2); glBindTexture(GL_TEXTURE_2D, audioTex)
            glUniform1i(loc(p, "uPrev"), 0)
            glUniform1i(loc(p, "uArt"), 1)
            glUniform1i(loc(p, "uAudio"), 2)
            glUniform2f(loc(p, "uRes"), fbW.toFloat(), fbH.toFloat())
            glUniform1f(loc(p, "uTime"), t)
            glUniform1f(loc(p, "uBass"), bass)
            glUniform1f(loc(p, "uMid"), mid)
            glUniform1f(loc(p, "uTreb"), treb)
            glUniform1f(loc(p, "uBeat"), beat)
            glUniform1f(loc(p, "uHasArt"), if (hasArt) 1f else 0f)
            glUniform3fv(loc(p, "uC1"), 1, pal[0], 0)
            glUniform3fv(loc(p, "uC2"), 1, pal[1], 0)
            glUniform3fv(loc(p, "uC3"), 1, pal[2], 0)
            drawTri()
            cur = 1 - cur
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glViewport(0, 0, viewW, viewH)
        if (composite != 0) {
            fade = min(1f, fade + 0.02f)
            glUseProgram(composite)
            glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, tex[cur])
            glUniform1i(loc(composite, "uTex"), 0)
            glUniform1f(loc(composite, "uTime"), t)
            glUniform1f(loc(composite, "uFade"), fade)
            drawTri()
        }
    }

    /** Ease the shader inputs toward the audio, or toward a slow breath when the room is quiet. */
    private fun feed(t: Float) {
        val quiet = SystemClock.uptimeMillis() - audio.lastSound > 3000
        val tb = if (quiet) 0.22f + 0.12f * sin(t * 0.7f) else audio.bass
        val tm = if (quiet) 0.18f + 0.10f * sin(t * 0.45f + 1f) else audio.mid
        val tt = if (quiet) 0.12f + 0.08f * sin(t * 0.33f + 2f) else audio.treb
        bass += (tb - bass) * 0.35f
        mid += (tm - mid) * 0.3f
        treb += (tt - treb) * 0.3f
        beat = if (!quiet && audio.takeBeat()) 1f else beat * 0.88f
        for (i in 0 until AudioTap.BANDS) {
            val ts = if (quiet) 0.15f + 0.1f * sin(t * 0.5f + i * 0.2f) else audio.spec[i]
            spec[i] += (ts - spec[i]) * 0.4f
            val tw = if (quiet) 0.15f * sin(t * 1.3f + i * 0.35f) else audio.wave[i]
            wave[i] += (tw - wave[i]) * 0.5f
        }
        val target = palTarget
        for (c in 0..2) for (k in 0..2) pal[c][k] += (target[c][k] - pal[c][k]) * 0.02f
    }

    private val locs = HashMap<Long, Int>()
    private fun loc(p: Int, name: String): Int =
        locs.getOrPut((p.toLong() shl 32) or name.hashCode().toLong().and(0xffffffffL)) {
            glGetUniformLocation(p, name)
        }

    private fun params(filter: Int) {
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
    }

    private fun shader(type: Int, src: String, name: String): Int {
        val s = glCreateShader(type)
        glShaderSource(s, src)
        glCompileShader(s)
        val ok = IntArray(1)
        glGetShaderiv(s, GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("$name: ${glGetShaderInfoLog(s)}")
        return s
    }

    private fun program(vs: String, fs: String, name: String): Int {
        val p = glCreateProgram()
        glAttachShader(p, shader(GL_VERTEX_SHADER, vs, "$name vertex"))
        glAttachShader(p, shader(GL_FRAGMENT_SHADER, fs, name))
        glLinkProgram(p)
        val ok = IntArray(1)
        glGetProgramiv(p, GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("$name link: ${glGetProgramInfoLog(p)}")
        return p
    }
}
