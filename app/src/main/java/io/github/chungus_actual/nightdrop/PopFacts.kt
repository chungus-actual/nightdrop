package io.github.chungus_actual.nightdrop

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.TypefaceSpan
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import java.net.URLEncoder
import kotlin.random.Random

/**
 * POP FACTS: facts about the song that is playing, floated over the visuals in the spirit of the music
 * video shows that ran trivia bubbles over the clip. They come from the feed server's /popfacts
 * (docs/feed.md): whatever it knows, a fact's `kind` picks its colour and its default label.
 *
 * The look is the visualizer's: a smoked glass card with a hairline rim, the label and rim in the colour
 * of the album art, a slow rise in and a drift up and away instead of a pop.
 *
 * Per song: the first card FIRST_MS in, then one every EVERY_MS, each up for SHOW_MS, at most MAX, never
 * over the rectangles [avoid] returns (the track card, the plate). Paused music shows nothing new.
 */
class PopFacts(
    private val host: FrameLayout,
    private val cfg: Config,
    private val avoid: () -> List<Rect> = { emptyList() },
) {

    data class Fact(val kind: String, val label: String, val text: String)

    private val enabled = cfg.popFacts && cfg.feedOn

    private val ctx = host.context
    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("popfacts").apply { start() }
    private val bg = Handler(thread.looper)
    private var song: String? = null
    private var gen = 0
    private var facts: List<Fact> = emptyList()
    private var shown = 0
    private var ticking = false
    private var card: View? = null
    // kind colours, from the album art when there is some (Palette lifts them to glow on black)
    private var tints = DEFAULT_TINTS

    private val tick = object : Runnable {
        override fun run() {
            ticking = false
            if (shown >= MAX) return
            if (shown < facts.size) {
                show(facts[shown++])
                schedule(EVERY_MS)
            } else {
                schedule(5_000L)   // the facts are not in yet (or the trivia is still coming)
            }
        }
    }

    /** Every now-playing update. A new song resets; pausing stops new cards; playing resumes them. */
    fun onTrack(title: String, artist: String, playing: Boolean, art: Bitmap? = null, album: String = "") {
        if (!enabled) return
        val id = "$artist|$title"
        if (title.isBlank() || artist.isBlank()) { stop(); song = null; return }
        if (art != null) {
            val p = Palette.from(art)
            tints = IntArray(3) { i -> Color.rgb((p[i][0] * 255).toInt(), (p[i][1] * 255).toInt(), (p[i][2] * 255).toInt()) }
        }
        if (id != song) {
            song = id
            gen++
            facts = emptyList()
            shown = 0
            if (art == null) tints = DEFAULT_TINTS
            hide()
            main.removeCallbacks(tick)
            ticking = false
            val g = gen
            bg.post { fetch(title, artist, album, g, 0) }
            if (playing) schedule(FIRST_MS)
        } else if (playing && !ticking && shown < MAX) {
            schedule(EVERY_MS)
        } else if (!playing) {
            main.removeCallbacks(tick)
            ticking = false
        }
    }

    fun stop() {
        main.removeCallbacks(tick)
        ticking = false
        hide()
    }

    fun quit() { stop(); thread.quitSafely() }

    private fun schedule(ms: Long) {
        main.removeCallbacks(tick)
        main.postDelayed(tick, ms)
        ticking = true
    }

    // ---- the feed --------------------------------------------------------------------------------

    private fun fetch(title: String, artist: String, album: String, g: Int, attempt: Int) {
        fun q(s: String) = URLEncoder.encode(s, "UTF-8")
        val got = Feed.get(cfg, "/popfacts?title=${q(title)}&artist=${q(artist)}&album=${q(album)}", 40_000) ?: return
        val arr = got.optJSONArray("facts")
        val list = (0 until (arr?.length() ?: 0)).mapNotNull { i ->
            arr!!.optJSONObject(i)?.let { Fact(Feed.str(it, "kind"), Feed.str(it, "label"), Feed.str(it, "text")) }
        }.filter { it.text.isNotBlank() }
        main.post {
            if (g != gen) return@post
            // keep the ones already shown in place: a re-fetch only adds what arrived since
            val done = facts.take(shown)
            facts = done + list.filter { f -> done.none { it.text == f.text } }
        }
        // once more regardless (a server that counts plays has only just seen this one start),
        // then again only while the server says more is coming (`pending`)
        if ((attempt == 0 || got.optBoolean("pending")) && attempt < 4) {
            bg.postDelayed({ fetch(title, artist, album, g, attempt + 1) }, 20_000L)
        }
    }

    // ---- the card -------------------------------------------------------------------------------

    private fun show(f: Fact) {
        hide()
        if (host.width < 100 || host.height < 100) return
        val tint = when (f.kind) {
            "house" -> tints[0]
            "track" -> tints[1]
            else -> tints[2]
        }
        val label = f.label.ifBlank {
            when (f.kind) {
                "house" -> "In this house"
                "track" -> "About the track"
                else -> "Trivia"
            }
        }
        val tv = TextView(ctx).apply {
            text = SpannableStringBuilder().apply {
                append(label)
                setSpan(ForegroundColorSpan(tint), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(AbsoluteSizeSpan(dp(12)), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(TypefaceSpan("sans-serif-medium"), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                append("\n")
                append(f.text)
            }
            textSize = 18f
            setTextColor(Color.rgb(236, 239, 234))
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setShadowLayer(dp(6).toFloat(), 0f, dp(1).toFloat(), Color.argb(200, 0, 0, 0))
            setLineSpacing(0f, 1.1f)
            letterSpacing = 0.01f
            background = Glass(tint, dp(14).toFloat(), dp(1).toFloat() + 0.5f)
            setPadding(dp(18), dp(12), dp(20), dp(14))
            alpha = 0f
        }
        // the free side first, then a card that fits it: in the screensaver the plate fills most of
        // one half, and a full-width card had nowhere to go (none showed, 2026-10-25 19:39)
        val keep = avoid().map { Rect(it).apply { inset(-dp(16), -dp(16)) } }
        val span = freeSpan(keep)
        tv.maxWidth = minOf(dp(360), span.width() - dp(8))
        if (tv.maxWidth < dp(200)) return
        tv.measure(View.MeasureSpec.makeMeasureSpec(tv.maxWidth + dp(38), View.MeasureSpec.AT_MOST),
                   View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val w = tv.measuredWidth
        val h = tv.measuredHeight
        val spot = place(w, h, keep, span) ?: return
        host.addView(tv, FrameLayout.LayoutParams(w, h).apply { leftMargin = spot.left; topMargin = spot.top })
        // rises a little into place, settles, and later drifts on up as it fades
        tv.translationY = dp(14).toFloat()
        tv.scaleX = 0.97f
        tv.scaleY = 0.97f
        tv.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(700)
            .setInterpolator(DecelerateInterpolator(1.6f)).start()
        card = tv
        main.postDelayed({ if (card === tv) hide() }, SHOW_MS)
    }

    private fun hide() {
        val v = card ?: return
        card = null
        v.animate().alpha(0f).translationY(-dp(18).toFloat()).setDuration(900)
            .setInterpolator(DecelerateInterpolator()).withEndAction { host.removeView(v) }.start()
    }

    /**
     * Where cards may go across: the whole width, or the side a big kept rectangle (the plate, a third
     * of the width or more) leaves free. Small ones (the card, the label) are dodged in place().
     */
    private fun freeSpan(keep: List<Rect>): Rect {
        val m = dp(40)
        var left = m
        var right = host.width - m
        for (k in keep) {
            if (k.width() < host.width / 3) continue
            if (k.centerX() < host.width / 2) left = maxOf(left, k.right) else right = minOf(right, k.left)
        }
        return Rect(left, m, maxOf(left, right), host.height - m)
    }

    /**
     * A spot in [span] that overlaps nothing in [keep]; the top two thirds when the full width is free
     * (the track card sits low), the full height beside a plate. Null after a couple dozen tries.
     */
    private fun place(w: Int, h: Int, keep: List<Rect>, span: Rect): Rect? {
        val wide = span.width() > host.width * 0.8f
        val bottom = if (wide) (host.height * 0.66f).toInt() else span.bottom
        val maxX = span.right - w
        val maxY = bottom - h
        if (maxX < span.left || maxY < span.top) return null
        repeat(24) {
            val x = if (maxX == span.left) maxX else Random.nextInt(span.left, maxX + 1)
            val y = if (maxY == span.top) maxY else Random.nextInt(span.top, maxY + 1)
            val r = Rect(x, y, x + w, y + h)
            if (keep.none { Rect.intersects(it, r) }) return r
        }
        return null
    }

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    /**
     * Smoked glass: a dark body the visuals show through (a shade lighter at the top, like the plate's
     * pool), a hairline rim in the fact's colour, a faint inner wash of the same colour.
     */
    private class Glass(tint: Int, private val r: Float, private val rimW: Float) : Drawable() {
        private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val wash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(22, Color.red(tint), Color.green(tint), Color.blue(tint))
        }
        private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = rimW
            color = Color.argb(150, Color.red(tint), Color.green(tint), Color.blue(tint))
        }
        private val box = RectF()
        private var alpha = 255

        override fun onBoundsChange(b: Rect) {
            box.set(b.left + rimW / 2, b.top + rimW / 2, b.right - rimW / 2, b.bottom - rimW / 2)
            body.shader = android.graphics.LinearGradient(0f, b.top.toFloat(), 0f, b.bottom.toFloat(),
                Color.argb(120, 14, 18, 24), Color.argb(150, 4, 6, 9), android.graphics.Shader.TileMode.CLAMP)
        }

        override fun draw(c: Canvas) {
            c.drawRoundRect(box, r, r, body)
            c.drawRoundRect(box, r, r, wash)
            c.drawRoundRect(box, r, r, rim)
        }

        override fun setAlpha(a: Int) { alpha = a; body.alpha = a; invalidateSelf() }
        override fun setColorFilter(cf: ColorFilter?) { body.colorFilter = cf; rim.colorFilter = cf; wash.colorFilter = cf }
        @Deprecated("Drawable API") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    companion object {
        const val FIRST_MS = 12_000L
        const val EVERY_MS = 20_000L
        const val SHOW_MS = 9_000L
        const val MAX = 8
        // teal, amber, violet (Palette.DEFAULT), lifted for a dark screen
        private val DEFAULT_TINTS = intArrayOf(Color.rgb(127, 214, 207), Color.rgb(227, 178, 119), Color.rgb(183, 156, 255))
    }
}
