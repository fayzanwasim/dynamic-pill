package com.personal.dynamicpill

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max

private const val SAMPLE_SIZE = 16
private const val MIN_WEIGHT = 0.01f
private const val MIN_SATURATION = 0.45f
private const val MIN_BRIGHTNESS = 0.8f

/**
 * A lively accent color for [bitmap] (album art): pixels are averaged with more weight on saturated,
 * bright ones, then the result is lifted so it stays visible on the black pill.
 */
fun accentFrom(bitmap: Bitmap): Int {
    // Hardware bitmaps cannot be read pixel by pixel, so copy them into a readable one first.
    val readable = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
    val sample = Bitmap.createScaledBitmap(readable, SAMPLE_SIZE, SAMPLE_SIZE, true)
    val hsv = FloatArray(3)
    var red = 0f
    var green = 0f
    var blue = 0f
    var totalWeight = 0f
    for (x in 0 until SAMPLE_SIZE) {
        for (y in 0 until SAMPLE_SIZE) {
            val pixel = sample.getPixel(x, y)
            Color.colorToHSV(pixel, hsv)
            val weight = hsv[1] * hsv[2] + MIN_WEIGHT
            red += Color.red(pixel) * weight
            green += Color.green(pixel) * weight
            blue += Color.blue(pixel) * weight
            totalWeight += weight
        }
    }
    Color.colorToHSV(Color.rgb((red / totalWeight).toInt(), (green / totalWeight).toInt(), (blue / totalWeight).toInt()), hsv)
    hsv[1] = max(hsv[1], MIN_SATURATION)
    hsv[2] = max(hsv[2], MIN_BRIGHTNESS)
    return Color.HSVToColor(hsv)
}
