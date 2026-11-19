package com.personal.dynamicpill

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.util.Log
import kotlin.math.roundToInt

private const val TAG = "DynamicPill"

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

/** Sends a PendingIntent that belongs to another app, allowing it to start an activity from the overlay. */
fun PendingIntent.sendFromOverlay(context: Context) {
    val options = ActivityOptions.makeBasic()
        .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
    try {
        send(context, 0, null, null, null, null, options.toBundle())
    } catch (e: PendingIntent.CanceledException) {
        Log.w(TAG, "PendingIntent was cancelled before it could be sent", e)
    }
}
