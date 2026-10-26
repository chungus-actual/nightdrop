package io.github.chungus_actual.nightdrop

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max

/**
 * Three colours for the shaders, pulled from the album art: the hues the cover is actually made of,
 * weighted by how saturated and bright each pixel is, then lifted so they glow on a dark screen.
 * Without art: deep teal, warm amber and violet.
 */
object Palette {
    val DEFAULT = arrayOf(
        floatArrayOf(0.10f, 0.62f, 0.62f),   // teal
        floatArrayOf(0.96f, 0.62f, 0.26f),   // amber
        floatArrayOf(0.62f, 0.34f, 0.96f))   // violet

    fun from(bmp: Bitmap?): Array<FloatArray> {
        if (bmp == null || bmp.width < 2 || bmp.height < 2) return DEFAULT
        val s = Bitmap.createScaledBitmap(bmp, 24, 24, true)
        val bins = Array(12) { FloatArray(4) }          // r, g, b, weight per 30 degrees of hue
        val hsv = FloatArray(3)
        for (y in 0 until 24) for (x in 0 until 24) {
            val c = s.getPixel(x, y)
            Color.colorToHSV(c, hsv)
            val w = hsv[1] * hsv[2]
            if (w < 0.08f) continue                      // greys and near-black say nothing
            val b = bins[(hsv[0] / 30f).toInt().coerceIn(0, 11)]
            b[0] += Color.red(c) / 255f * w
            b[1] += Color.green(c) / 255f * w
            b[2] += Color.blue(c) / 255f * w
            b[3] += w
        }
        val top = bins.filter { it[3] > 0.5f }.sortedByDescending { it[3] }.take(3)
            .map { glow(floatArrayOf(it[0] / it[3], it[1] / it[3], it[2] / it[3])) }
        return Array(3) { i -> top.getOrNull(i) ?: DEFAULT[i] }
    }

    private fun glow(c: FloatArray): FloatArray {
        val hsv = FloatArray(3)
        Color.RGBToHSV((c[0] * 255).toInt(), (c[1] * 255).toInt(), (c[2] * 255).toInt(), hsv)
        hsv[1] = max(hsv[1], 0.45f)
        hsv[2] = max(hsv[2], 0.85f)
        val i = Color.HSVToColor(hsv)
        return floatArrayOf(Color.red(i) / 255f, Color.green(i) / 255f, Color.blue(i) / 255f)
    }
}
