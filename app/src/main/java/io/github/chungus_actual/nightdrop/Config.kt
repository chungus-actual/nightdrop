package io.github.chungus_actual.nightdrop

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * The few settings Nightdrop has, from `nightdrop.json`. Looked for in two places, first hit wins:
 *
 *  1. the app's own folder on shared storage, `Android/data/<package>/files/nightdrop.json`
 *     (`adb push nightdrop.json /sdcard/Android/data/<package>/files/`), so a stock APK can be set up;
 *  2. `assets/nightdrop.json`, baked in at build time with `-Pbake=<dir>` (see app/build.gradle.kts).
 *
 * Neither present: the defaults below, which need no network at all (no feed, no pop facts).
 * A key left out keeps its default. A bad file is logged and ignored, never fatal.
 */
data class Config(
    /** Base URL of a feed server: GET <feed>/ambient for the plate, <feed>/popfacts for the facts. "" = none. */
    val feed: String = "",
    /** A CA to trust for the feed instead of the system's: inline PEM, or a file name beside the config. */
    val ca: String = "",
    /** Sent as `Authorization: Bearer <token>` when set. */
    val token: String = "",
    val popFacts: Boolean = true,
    /** The screensaver's videos: a folder under shared storage. */
    val reels: String = "Movies/Reels",
    /** How long one reel plays (looped) before another is picked. */
    val sceneMinutes: Int = 5,
    /** Feed items on the plate. */
    val plateItems: Int = 3,
    /** How often the plate moves to another corner (an OLED should never hold one image for long). */
    val driftSeconds: Int = 150,
    /** A track that plays longer than this (a DJ mix) moves to the next preset by itself. */
    val cycleMinutes: Int = 6,
    /** [ca] resolved to PEM text; "" = the system's trust store. */
    val caPem: String = "",
) {
    /** A feed is set and can be trusted: a CA that was named but not found turns the feed off. */
    val feedOn: Boolean get() = feed.isNotEmpty() && (ca.isEmpty() || caPem.isNotEmpty())

    companion object {
        const val FILE = "nightdrop.json"

        fun load(ctx: Context): Config {
            val local = ctx.getExternalFilesDir(null)?.let { File(it, FILE) }
            if (local != null && local.isFile) {
                parse(local.readText()) { name -> File(local.parentFile, name).takeIf { it.isFile }?.readText() }
                    ?.let { return it.also { Log.i(AudioTap.TAG, "config: $local") } }
            }
            val baked = try { ctx.assets.open(FILE).bufferedReader().use { it.readText() } } catch (_: Exception) { null }
            if (baked != null) {
                parse(baked) { name ->
                    try { ctx.assets.open(name).bufferedReader().use { it.readText() } } catch (_: Exception) { null }
                }?.let { return it.also { Log.i(AudioTap.TAG, "config: built in") } }
            }
            return Config()
        }

        private fun parse(text: String, sibling: (String) -> String?): Config? = try {
            val o = JSONObject(text)
            val d = Config()
            val ca = o.optString("ca", d.ca).trim()
            val pem = when {
                ca.isEmpty() -> ""
                ca.contains("-----BEGIN") -> ca
                else -> sibling(ca) ?: "".also { Log.w(AudioTap.TAG, "config: CA file $ca not found, the feed is off") }
            }
            Config(
                feed = o.optString("feed", d.feed).trim().trimEnd('/'),
                ca = ca,
                token = o.optString("token", d.token).trim(),
                popFacts = o.optBoolean("popFacts", d.popFacts),
                reels = o.optString("reels", d.reels).trim().trim('/'),
                sceneMinutes = o.optInt("sceneMinutes", d.sceneMinutes).coerceIn(1, 240),
                plateItems = o.optInt("plateItems", d.plateItems).coerceIn(0, 6),
                driftSeconds = o.optInt("driftSeconds", d.driftSeconds).coerceIn(30, 3600),
                cycleMinutes = o.optInt("cycleMinutes", d.cycleMinutes).coerceIn(1, 120),
                caPem = pem,
            )
        } catch (e: Exception) {
            Log.w(AudioTap.TAG, "config: unreadable, using the defaults: $e")
            null
        }
    }
}
