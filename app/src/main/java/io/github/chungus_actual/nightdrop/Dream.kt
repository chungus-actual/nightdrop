package io.github.chungus_actual.nightdrop

import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.media.MediaPlayer
import android.opengl.GLSurfaceView
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.format.DateFormat
import android.text.style.RelativeSizeSpan
import android.util.Log
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * The screensaver. Two backdrops, chosen by what the room is doing:
 *  - music playing: the visualizer (the same Renderer as the app), a new scene each song;
 *  - otherwise: the reels, every video in the configured folder (Config.reels), one scene at a time.
 * Over either, the PLATE: the time, the day, the weather and a few headlines from the feed server (Feed),
 * and what is playing. It moves to another corner every few minutes, so an OLED never holds it.
 *
 * Pick it in Settings (Screen saver) or over adb:
 *   settings put secure screensaver_components <package>/io.github.chungus_actual.nightdrop.Dream
 */
class Dream : DreamService() {

    private enum class Mode { NONE, VIDEO, VISUALS }

    private val ui = Handler(Looper.getMainLooper())
    private val audio = AudioTap()
    private var mode = Mode.NONE

    private lateinit var stage: FrameLayout       // the backdrop's one view lives here
    private lateinit var curtain: View            // black, faded over the swap between backdrops
    private lateinit var plate: LinearLayout
    private lateinit var clock: TextView
    private lateinit var day: TextView
    private lateinit var feed: LinearLayout
    private lateinit var playing: LinearLayout
    private lateinit var playArt: ImageView
    private lateinit var playTitle: TextView
    private lateinit var playArtist: TextView
    private lateinit var pops: PopFacts

    private var renderer: Renderer? = null
    private var player: MediaPlayer? = null
    private var reels: List<File> = emptyList()
    private var reel = 0
    private var reelFails = 0
    private var holder: SurfaceHolder? = null

    private var now: NowPlaying? = null
    private var cfg = Config()
    private var feedClient: Feed? = null
    private var track: NowPlaying.Track? = null
    private var song: String? = null
    private var ambient: Feed.Ambient? = null
    private var anchor = 0

    private val tick = object : Runnable {
        override fun run() {
            drawClock()
            ui.postDelayed(this, 1000L - System.currentTimeMillis() % 1000L)
        }
    }
    private val drift = object : Runnable {
        override fun run() {
            plate.animate().alpha(0f).setDuration(1500).withEndAction {
                anchor = (anchor + 1) % ANCHORS.size
                place()
                plate.animate().alpha(1f).setDuration(1500).start()
            }.start()
            ui.postDelayed(this, cfg.driftSeconds * 1000L)
        }
    }
    // a pause between songs, or a skip, should not flash the reels up for a second
    private val toVideo = Runnable { if (reels.isNotEmpty()) swap(Mode.VIDEO) }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = true
        cfg = Config.load(this)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        stage = FrameLayout(this)
        root.addView(stage, match())
        curtain = View(this).apply { setBackgroundColor(Color.BLACK); alpha = 1f }
        root.addView(curtain, match())
        // pop facts over the visualizer while music plays, never over the plate (PopFacts.kt)
        val popLayer = FrameLayout(this)
        root.addView(popLayer, match())
        pops = PopFacts(popLayer, cfg) { listOf(android.graphics.Rect().also { plate.getHitRect(it) }) }
        root.addView(buildPlate(), FrameLayout.LayoutParams(WRAP, WRAP))
        setContentView(root)
        anchor = Random.nextInt(ANCHORS.size)
        place()
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        reels = findReels()
        reel = if (reels.isEmpty()) 0 else Random.nextInt(reels.size)
        now = NowPlaying(this) { t -> ui.post { onTrack(t) } }.also { it.start() }
        if (track?.playing != true) swap(if (reels.isNotEmpty()) Mode.VIDEO else Mode.VISUALS)
        feedClient = Feed(cfg) { a -> ambient = a; drawFeed() }.also { it.start() }
        ui.post(tick)
        ui.postDelayed(drift, cfg.driftSeconds * 1000L)
    }

    override fun onDreamingStopped() {
        ui.removeCallbacksAndMessages(null)
        now?.stop(); now = null
        feedClient?.quit(); feedClient = null
        swap(Mode.NONE)
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        pops.quit()
        ui.removeCallbacksAndMessages(null)
        releasePlayer()
        audio.stop()
        super.onDetachedFromWindow()
    }

    // ---- what is playing ------------------------------------------------------------------------

    private fun onTrack(t: NowPlaying.Track?) {
        track = t
        val on = t != null && t.playing && t.title.isNotEmpty()
        pops.onTrack(t?.title.orEmpty(), t?.artist.orEmpty(), on, t?.art, t?.album.orEmpty())
        if (on) {
            ui.removeCallbacks(toVideo)
            swap(Mode.VISUALS)
            val id = t!!.title + "\u0000" + t.artist
            if (id != song) {
                // a new track is a new scene (the first one keeps its random pick)
                if (song != null) renderer?.let { it.preset = (it.preset + 1) % Shaders.PRESETS.size }
                song = id
            }
        } else if (mode == Mode.VISUALS) {
            ui.removeCallbacks(toVideo)
            ui.postDelayed(toVideo, PAUSE_MS)
        }
        renderer?.setArt(t?.art)
        drawPlaying()
    }

    // ---- the backdrop -------------------------------------------------------------------------

    private fun swap(to: Mode) {
        if (to == mode) return
        mode = to
        if (to == Mode.NONE) { clearStage(); return }
        curtain.animate().cancel()
        curtain.animate().alpha(1f).setDuration(400).withEndAction {
            clearStage()
            if (to == Mode.VISUALS) mountVisuals() else mountVideo()
            curtain.animate().alpha(0f).setDuration(1200).setStartDelay(150).start()
        }.start()
    }

    private fun clearStage() {
        releasePlayer()
        audio.stop()
        renderer = null
        stage.removeAllViews()
    }

    private fun mountVisuals() {
        val r = Renderer(audio).also { renderer = it }
        r.preset = Random.nextInt(Shaders.PRESETS.size)
        r.setArt(track?.art)
        stage.addView(GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            setEGLConfigChooser(8, 8, 8, 8, 0, 0)
            setRenderer(r)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }, match())
        audio.start()
    }

    private fun mountVideo() {
        val sv = SurfaceView(this)
        sv.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) { holder = h; play(h) }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
            override fun surfaceDestroyed(h: SurfaceHolder) { holder = null; ui.removeCallbacks(nextScene); releasePlayer() }
        })
        stage.addView(sv, match())
    }

    /**
     * One SCENE, looped, for Config.sceneMinutes, then another at random. A reel is meant to loop
     * seamlessly (tools/make_reel.py makes one from any video), so the loop point never shows.
     */
    private fun play(h: SurfaceHolder) {
        releasePlayer()
        ui.removeCallbacks(nextScene)
        if (reels.isEmpty()) return
        val f = reels[reel % reels.size]
        player = MediaPlayer().apply {
            try {
                setDisplay(h)
                setDataSource(f.path)
                setVolume(0f, 0f)
                isLooping = true
                setOnPreparedListener {
                    it.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
                    it.start()
                    reelFails = 0
                    ui.postDelayed(nextScene, cfg.sceneMinutes * 60_000L)
                }
                setOnErrorListener { _, what, extra ->
                    Log.w(AudioTap.TAG, "reel ${f.name}: error $what/$extra")
                    nextReelOrGiveUp(h)
                    true
                }
                prepareAsync()
                Log.i(AudioTap.TAG, "reel ${f.name}")
            } catch (e: Exception) {
                Log.w(AudioTap.TAG, "reel ${f.name}: $e")
                ui.post { nextReelOrGiveUp(h) }
            }
        }
    }

    /** The scene timer: a different scene, picked at random. */
    private val nextScene = Runnable {
        val h = holder
        if (mode == Mode.VIDEO && h != null && reels.size > 1) {
            var next = Random.nextInt(reels.size)
            while (next == reel % reels.size) next = Random.nextInt(reels.size)
            reel = next
            play(h)
        }
    }

    /** Skip a reel that will not play; if none will, the visuals take the screen for this dream. */
    private fun nextReelOrGiveUp(h: SurfaceHolder) {
        releasePlayer()
        reel++
        if (++reelFails >= reels.size) {
            reels = emptyList()
            swap(Mode.VISUALS)
        } else {
            ui.postDelayed({ if (mode == Mode.VIDEO) play(h) }, 1000)
        }
    }

    private fun releasePlayer() {
        player?.let { p ->
            try { p.setOnCompletionListener(null); p.setOnErrorListener(null); p.reset(); p.release() } catch (_: Exception) {}
        }
        player = null
    }

    private fun findReels(): List<File> {
        if (checkSelfPermission(MainActivity.VIDEO_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            Log.w(AudioTap.TAG, "no storage permission: the reels are off, visuals only")
            return emptyList()
        }
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStorageDirectory(), cfg.reels)
        return dir.listFiles { f -> f.isFile && f.length() > 0 && f.extension.lowercase() in REEL_TYPES }
            ?.sortedBy { it.name } ?: emptyList()
    }

    // ---- the plate ------------------------------------------------------------------------------

    private fun buildPlate(): View {
        plate = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(56), dp(44), dp(56), dp(44))
        }
        // a soft dark pool under the words: the reels have bright skies and the visuals have flashes
        plate.background = Pool()
        clock = text(84f, BONE, "sans-serif-thin").apply { includeFontPadding = false }
        day = text(20f, Color.argb(215, 236, 239, 234), "sans-serif-light")
        feed = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        playing = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        playArt = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        val words = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        playTitle = text(19f, BONE, "sans-serif-medium")
        playArtist = text(16f, TEAL, "sans-serif")
        words.addView(playTitle, wrap())
        words.addView(playArtist, wrap())
        playing.addView(playArt, LinearLayout.LayoutParams(dp(60), dp(60)).apply { marginEnd = dp(16) })
        playing.addView(words, wrap())

        plate.addView(clock, wrap())
        plate.addView(day, wrap().apply { topMargin = dp(2) })
        plate.addView(feed, wrap().apply { topMargin = dp(22) })
        plate.addView(playing, wrap().apply { topMargin = dp(22) })
        return plate
    }

    /** Into the current corner, a little off the last spot so no pixel holds the same glyph for long. */
    private fun place() {
        val g = ANCHORS[anchor]
        val end = g and Gravity.HORIZONTAL_GRAVITY_MASK == Gravity.END
        val lp = plate.layoutParams as FrameLayout.LayoutParams
        lp.gravity = g
        val mx = dp(8 + Random.nextInt(28))
        val my = dp(4 + Random.nextInt(20))
        lp.setMargins(mx, my, mx, my)
        plate.layoutParams = lp
        val align = if (end) Gravity.END else Gravity.START
        plate.gravity = align
        feed.gravity = align
        for (i in 0 until feed.childCount) (feed.getChildAt(i) as? TextView)?.gravity = align
        clock.gravity = align
        day.gravity = align
        // the art sits on the outer edge: left in a left corner, right in a right one
        playing.layoutDirection = if (end) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        for (v in listOf(playTitle, playArtist)) v.gravity = align
    }

    private fun drawClock() {
        val d = Date()
        val t = if (DateFormat.is24HourFormat(this)) SimpleDateFormat("H:mm", Locale.getDefault()).format(d)
        else {
            val hm = SimpleDateFormat("h:mm", Locale.getDefault()).format(d)
            val ap = SimpleDateFormat("a", Locale.getDefault()).format(d).lowercase()
            SpannableString("$hm $ap").apply {
                setSpan(RelativeSizeSpan(0.28f), hm.length, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        if (clock.text.toString() != t.toString()) clock.text = t
        val a = ambient
        val date = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(d)
        val wx = if (a?.temp != null) "   ${a.temp}°" + (if (a.sky.isNotEmpty()) " ${a.sky.lowercase()}" else "") else ""
        val line = date + wx
        if (day.text.toString() != line) {
            day.text = SpannableString(line).apply {
                if (wx.isNotEmpty()) setSpan(android.text.style.ForegroundColorSpan(TEAL), date.length, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    private fun drawFeed() {
        drawClock()
        feed.removeAllViews()
        val items = ambient?.items.orEmpty().take(cfg.plateItems)
        val end = ANCHORS[anchor] and Gravity.HORIZONTAL_GRAVITY_MASK == Gravity.END
        val align = if (end) Gravity.END else Gravity.START
        items.forEachIndexed { i, it ->
            val hero = i == 0 && it.tier == 1
            val kick = text(13f, toneColor(it.tone), "sans-serif-condensed").apply {
                text = it.kicker
                letterSpacing = 0.06f
                gravity = align
            }
            val head = text(if (hero) 25f else 19f, if (hero) BONE else Color.argb(225, 236, 239, 234),
                if (hero) "sans-serif-medium" else "sans-serif").apply {
                text = it.headline
                maxLines = 2
                gravity = align
            }
            feed.addView(kick, wrap().apply { if (i > 0) topMargin = dp(14) })
            feed.addView(head, wrap())
            if (it.sub.isNotBlank()) {
                feed.addView(text(15f, Color.argb(165, 236, 239, 234), "sans-serif-light").apply {
                    text = it.sub
                    gravity = align
                }, wrap())
            }
        }
        feed.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun drawPlaying() {
        val t = track
        if (t == null || !t.playing || t.title.isEmpty()) {
            playing.visibility = View.GONE
            return
        }
        playTitle.text = t.title
        playArtist.text = t.artist
        if (t.art != null) { playArt.setImageBitmap(t.art); playArt.visibility = View.VISIBLE }
        else playArt.visibility = View.GONE
        playing.visibility = View.VISIBLE
    }

    private fun toneColor(tone: String) = when (tone) {
        "needs", "fault" -> Color.rgb(240, 150, 100)
        "live" -> TEAL
        else -> AMBER
    }

    private fun text(sp: Float, color: Int, family: String) = TextView(this).apply {
        textSize = sp
        setTextColor(color)
        typeface = Typeface.create(family, Typeface.NORMAL)
        setShadowLayer(10f, 0f, 2f, Color.argb(190, 0, 0, 0))
        maxLines = 1
        maxWidth = dp(470)
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun wrap() = LinearLayout.LayoutParams(WRAP, WRAP)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val PAUSE_MS = 20_000L
        val REEL_TYPES = setOf("mp4", "mkv", "webm", "mov")
        val ANCHORS = intArrayOf(Gravity.BOTTOM or Gravity.START, Gravity.TOP or Gravity.END,
                                 Gravity.BOTTOM or Gravity.END, Gravity.TOP or Gravity.START)
        val BONE = Color.rgb(236, 239, 234)
        val TEAL = Color.rgb(127, 214, 207)
        val AMBER = Color.rgb(227, 178, 119)
        private const val WRAP = FrameLayout.LayoutParams.WRAP_CONTENT
        private fun match() = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
    }
}

/**
 * The dark pool under the plate: an ellipse inscribed in the plate's bounds, fading to nothing AT those
 * bounds. A GradientDrawable's radial gradient is a circle, so on a plate wider than tall it was still
 * visibly dark at the left and right edges and the box showed as a rectangle (2026-10-25, first look).
 */
private class Pool : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var alpha = 255

    override fun draw(c: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val r = b.height() / 2f
        val ink = intArrayOf(Color.argb(150 * alpha / 255, 4, 10, 12), Color.argb(95 * alpha / 255, 4, 10, 12),
                             Color.argb(0, 4, 10, 12))
        paint.shader = RadialGradient(0f, 0f, r, ink, floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        c.save()
        c.translate(b.exactCenterX(), b.exactCenterY())
        c.scale(b.width() / b.height().toFloat(), 1f)
        c.drawCircle(0f, 0f, r, paint)
        c.restore()
    }

    override fun setAlpha(a: Int) { alpha = a; invalidateSelf() }
    override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
    @Deprecated("Drawable API") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
