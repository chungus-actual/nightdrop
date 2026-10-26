package io.github.chungus_actual.nightdrop

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory

/**
 * The feed server, read only: GET <feed>/ambient once a minute for the plate (weather and a few
 * headlines; docs/feed.md has the shape). Sends nothing but the request line and, if configured, a
 * bearer token. With a CA in the config, that CA is the only one trusted for the feed.
 *
 * A failed fetch keeps the last answer for STALE_MS, then the feed goes away: a calendar row from an
 * hour ago is worse on the wall than no row.
 */
class Feed(private val cfg: Config, private val onData: (Ambient?) -> Unit) {

    data class Item(val tier: Int, val kicker: String, val headline: String, val sub: String, val tone: String)
    data class Ambient(val items: List<Item>, val temp: Int?, val sky: String, val at: Long)

    private val thread = HandlerThread("feed").apply { start() }
    private val bg = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private var last: Ambient? = null

    private val poll = object : Runnable {
        override fun run() {
            get(cfg, "/ambient", 30_000)?.let { last = parse(it) }
            val keep = last?.takeIf { SystemClock.elapsedRealtime() - it.at < STALE_MS }
            main.post { onData(keep) }
            bg.postDelayed(this, EVERY_MS)
        }
    }

    fun start() { if (cfg.feedOn) { bg.removeCallbacks(poll); bg.post(poll) } }
    fun stop() = bg.removeCallbacks(poll)
    fun quit() { stop(); thread.quitSafely() }

    companion object {
        const val EVERY_MS = 60_000L
        const val STALE_MS = 30 * 60_000L

        @Volatile private var tls: Pair<String, SSLSocketFactory>? = null

        /**
         * GET <feed><path>, the body of a 200 or null. The read timeout is long on purpose: a feed
         * server may gather what it says on the request, and its first answer after a restart is slow.
         */
        fun get(cfg: Config, path: String, readMs: Int): JSONObject? {
            if (!cfg.feedOn) return null
            return try {
                val c = URL(cfg.feed + path).openConnection() as HttpURLConnection
                if (c is HttpsURLConnection && cfg.caPem.isNotEmpty()) c.sslSocketFactory = trusting(cfg.caPem)
                if (cfg.token.isNotEmpty()) c.setRequestProperty("Authorization", "Bearer ${cfg.token}")
                c.connectTimeout = 4000
                c.readTimeout = readMs
                try {
                    if (c.responseCode == 200) JSONObject(c.inputStream.bufferedReader().readText()) else null
                } finally {
                    c.disconnect()
                }
            } catch (e: Exception) {
                Log.w(AudioTap.TAG, "feed $path: $e")
                null
            }
        }

        /** A socket factory that trusts the certificates in [pem] and nothing else. Built once per PEM. */
        private fun trusting(pem: String): SSLSocketFactory {
            tls?.takeIf { it.first == pem }?.let { return it.second }
            val certs = CertificateFactory.getInstance("X.509").generateCertificates(pem.byteInputStream())
            val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                certs.forEachIndexed { i, c -> setCertificateEntry("ca$i", c) }
            }
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
            val f = SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }.socketFactory
            tls = pem to f
            return f
        }

        fun parse(o: JSONObject): Ambient {
            val arr = o.optJSONArray("items")
            val items = (0 until (arr?.length() ?: 0)).mapNotNull { i ->
                arr!!.optJSONObject(i)?.let {
                    Item(it.optInt("tier", 3), str(it, "kicker"), str(it, "headline"), str(it, "sub"), str(it, "tone"))
                }
            }.filter { it.headline.isNotBlank() }
            val wx = o.optJSONObject("weather")
            val temp = if (wx != null && wx.has("temp") && !wx.isNull("temp")) wx.optInt("temp") else null
            return Ambient(items, temp, wx?.let { str(it, "sky") } ?: "", SystemClock.elapsedRealtime())
        }

        /** A string field; "" when missing or JSON null (optString would say "null"). */
        fun str(o: JSONObject, key: String): String = if (o.isNull(key)) "" else o.optString(key)
    }
}
