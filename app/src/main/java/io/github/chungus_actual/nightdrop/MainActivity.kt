package io.github.chungus_actual.nightdrop

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Full screen: the visual, a track card that slides in on each new song (and on Up/Down/Info), and a
 * small label naming the preset when it changes. A new song is a new scene; a track that runs past
 * Config.cycleMinutes (a DJ mix) moves on by itself. Left/Right (and the media skip keys on a remote)
 * change preset by hand. Back leaves.
 */
class MainActivity : Activity() {

    private lateinit var gl: GLSurfaceView
    private lateinit var renderer: Renderer
    private lateinit var now: NowPlaying
    private val audio = AudioTap()
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var cfg: Config
    private val cycleMs get() = cfg.cycleMinutes * 60_000L

    private lateinit var card: LinearLayout
    private lateinit var cardArt: ImageView
    private lateinit var cardTitle: TextView
    private lateinit var cardArtist: TextView
    private lateinit var cardAlbum: TextView
    private lateinit var label: TextView
    private lateinit var status: TextView
    private lateinit var pops: PopFacts
    private var song: String? = null      // title + artist of the track on screen

    private val cycle = Runnable { step(1, announce = true) }
    private val hideCard = Runnable { card.animate().alpha(0f).translationY(24f).setDuration(600).start() }
    private val hideLabel = Runnable { label.animate().alpha(0f).setDuration(600).start() }
    // The screen is held on only while something plays. Held forever, a paused song left the
    // visuals idling on the OLED and the screensaver could never start (2026-10-25 18:14, paused,
    // nothing took over). IDLE_MS after the music stops the system's own timer runs again.
    private var sleepPending = false       // not Handler.hasCallbacks: that is API 29, minSdk is 28
    private val letSleep = Runnable {
        sleepPending = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    private fun sleepLater() { ui.removeCallbacks(letSleep); ui.postDelayed(letSleep, IDLE_MS); sleepPending = true }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        cfg = Config.load(this)
        renderer = Renderer(audio)
        renderer.onError = { msg -> ui.post { showStatus("Shader: $msg") } }
        gl = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            setEGLConfigChooser(8, 8, 8, 8, 0, 0)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        val root = FrameLayout(this)
        root.addView(gl)
        // pop facts float over the visuals, under the card and the label (PopFacts.kt)
        val popLayer = FrameLayout(this)
        root.addView(popLayer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT))
        pops = PopFacts(popLayer, cfg) {
            listOfNotNull(
                Rect().also { card.getHitRect(it) }.takeIf { card.alpha > 0.05f },
                Rect().also { label.getHitRect(it) },
            )
        }
        root.addView(buildCard(), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(48), 0, 0, dp(40)) })
        label = text(18f, Color.argb(200, 230, 236, 234)).apply { alpha = 0f; letterSpacing = 0.12f }
        root.addView(label, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END).apply { setMargins(0, dp(28), dp(40), 0) })
        status = text(16f, Color.argb(220, 236, 170, 120)).apply { visibility = View.GONE; maxLines = 3 }
        root.addView(status, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START).apply { setMargins(dp(40), dp(28), 0, 0) })
        setContentView(root)
        now = NowPlaying(this) { t -> ui.post { showTrack(t) } }
    }

    override fun onResume() {
        super.onResume()
        gl.onResume()
        // the microphone permission is the audio tap; the video one is the screensaver's reels
        val ask = listOf(Manifest.permission.RECORD_AUDIO, VIDEO_PERMISSION)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (ask.isNotEmpty()) requestPermissions(ask.toTypedArray(), 1)
        if (Manifest.permission.RECORD_AUDIO !in ask) startAudio()
        now.start()
        if (!now.granted) {
            showStatus("No track info: grant notification access over adb\n" +
                "cmd notification allow_listener $packageName/${MediaListener::class.java.name}")
        }
        // just opened: awake for now, released if nothing plays within IDLE_MS
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sleepLater()
        ui.removeCallbacks(cycle)
        ui.postDelayed(cycle, cycleMs)
        step(0, announce = true)
    }

    override fun onPause() {
        pops.stop()
        ui.removeCallbacks(cycle)
        ui.removeCallbacks(letSleep)
        sleepPending = false
        now.stop()
        audio.stop()
        gl.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        pops.quit()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        val i = perms.indexOf(Manifest.permission.RECORD_AUDIO)
        if (i < 0) return
        if (results.getOrNull(i) == PackageManager.PERMISSION_GRANTED) startAudio()
        else showStatus("No audio permission: the visuals will idle")
    }

    private fun startAudio() {
        audio.start()
        audio.error?.let { showStatus("Audio tap: $it") }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> step(1, true)
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND -> step(-1, true)
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_INFO,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> flashCard(8000)
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    private fun step(by: Int, announce: Boolean) {
        val n = Shaders.PRESETS.size
        renderer.preset = ((renderer.preset + by) % n + n) % n
        ui.removeCallbacks(cycle)
        ui.postDelayed(cycle, cycleMs)
        if (announce) {
            label.text = Shaders.NAMES[renderer.preset].uppercase()
            label.animate().alpha(1f).setDuration(300).start()
            ui.removeCallbacks(hideLabel)
            ui.postDelayed(hideLabel, 2500)
        }
    }

    private fun showTrack(t: NowPlaying.Track?) {
        renderer.setArt(t?.art)
        pops.onTrack(t?.title.orEmpty(), t?.artist.orEmpty(), t?.playing == true, t?.art, t?.album.orEmpty())
        if (t?.playing == true) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            ui.removeCallbacks(letSleep)
            sleepPending = false
        } else if (!sleepPending) {
            sleepLater()
        }
        if (t != null && t.title.isNotEmpty()) {
            val id = t.title + "\u0000" + t.artist
            if (song != null && id != song) step(1, announce = true)
            song = id
        }
        if (t == null || t.title.isEmpty()) {
            card.animate().alpha(0f).setDuration(400).start()
            return
        }
        cardTitle.text = t.title
        cardArtist.text = t.artist
        cardAlbum.text = t.album
        // a single's album is its own title; saying it twice is noise
        cardAlbum.visibility = if (t.album.isEmpty() || t.album.equals(t.title, ignoreCase = true)) View.GONE else View.VISIBLE
        if (t.art != null) { cardArt.setImageBitmap(t.art); cardArt.visibility = View.VISIBLE }
        else cardArt.visibility = View.GONE
        flashCard(12000)
    }

    private fun flashCard(ms: Long) {
        if (cardTitle.text.isNullOrEmpty()) return
        card.animate().alpha(1f).translationY(0f).setDuration(500).start()
        ui.removeCallbacks(hideCard)
        ui.postDelayed(hideCard, ms)
    }

    private fun showStatus(msg: String) {
        status.text = msg
        status.visibility = View.VISIBLE
    }

    private fun buildCard(): View {
        card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(18), dp(28), dp(18))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.argb(150, 10, 18, 20))
                setStroke(dp(1), Color.argb(60, 120, 200, 200))
            }
            alpha = 0f
            translationY = 24f
        }
        cardArt = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true }
        card.addView(cardArt, LinearLayout.LayoutParams(dp(132), dp(132)).apply { marginEnd = dp(22) })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cardTitle = text(30f, Color.rgb(236, 240, 238)).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
        cardArtist = text(21f, Color.rgb(150, 214, 210))
        cardAlbum = text(16f, Color.argb(170, 220, 226, 224))
        // WRAP_CONTENT stated outright: a vertical LinearLayout's default is MATCH_PARENT, and a TextView of
        // fixed width never asks for a relayout on setText, so each new track was ellipsized at the width
        // the previous one left behind ("TH...", "HED...", 2026-10-25)
        for (t in listOf(cardTitle, cardArtist, cardAlbum)) {
            col.addView(t, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        card.addView(col)
        return card
    }

    private fun text(sp: Float, color: Int) = TextView(this).apply {
        textSize = sp
        setTextColor(color)
        setShadowLayer(8f, 0f, 2f, Color.argb(160, 0, 0, 0))
        maxLines = 1
        maxWidth = dp(760)
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val IDLE_MS = 3 * 60_000L
        /** Reading the reels: READ_MEDIA_VIDEO from Android 13, the storage permission before it. */
        val VIDEO_PERMISSION: String =
            if (android.os.Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
            else Manifest.permission.READ_EXTERNAL_STORAGE
    }
}
