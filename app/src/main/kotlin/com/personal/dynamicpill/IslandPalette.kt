package com.personal.dynamicpill

/** Accent colors for the equalizer and progress bar. The pill itself stays black to blend with the camera. */
object IslandPalette {
    val COLORS = intArrayOf(
        0xFFFFFFFF.toInt(),
        0xFF30D158.toInt(),
        0xFF0A84FF.toInt(),
        0xFFFF375F.toInt(),
        0xFFFF9F0A.toInt(),
        0xFFBF5AF2.toInt(),
    )

    fun color(index: Int): Int = COLORS[index.coerceIn(0, COLORS.lastIndex)]
}
