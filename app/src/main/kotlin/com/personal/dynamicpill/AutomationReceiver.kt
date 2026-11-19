package com.personal.dynamicpill

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * Lets automation apps (Tasker, Automate, `adb shell am broadcast`) put their own content on the pill.
 * Every request must carry the token shown in the app, so other apps cannot write to the pill.
 *
 * Extras for [ACTION_SHOW]: token, title (required), text, id, seconds, expand, timer_seconds, live.
 * [ACTION_END] ends a live activity started with the same id.
 */
class AutomationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getStringExtra(EXTRA_TOKEN) != IslandSettings(context).apiToken()) {
            EventLog.record(SOURCE, "rejected", "missing or wrong token")
            return
        }
        when (intent.action) {
            ACTION_SHOW -> show(context, intent)
            ACTION_END -> IslandBus.endLive(idFor(intent))
        }
    }

    private fun show(context: Context, intent: Intent) {
        val title = intent.getStringExtra(EXTRA_TITLE)
        if (title.isNullOrBlank()) {
            EventLog.record(SOURCE, "rejected", "no title")
            return
        }
        val timerSeconds = intent.getIntExtra(EXTRA_TIMER_SECONDS, 0)
        val content = IslandContent(
            id = idFor(intent),
            icon = context.getDrawable(R.drawable.ic_launcher),
            title = title,
            subtitle = intent.getStringExtra(EXTRA_TEXT).orEmpty(),
            glance = if (timerSeconds > 0) {
                Glance.Timer(SystemClock.elapsedRealtime() + timerSeconds * MS_PER_SECOND, countDown = true)
            } else {
                Glance.None
            },
            priority = if (timerSeconds > 0) IslandPriority.TIMER else IslandPriority.OTHER,
            expandOnArrival = intent.getBooleanExtra(EXTRA_EXPAND, false),
            holdSeconds = intent.getIntExtra(EXTRA_SECONDS, 0).takeIf { it > 0 },
        )
        if (intent.getBooleanExtra(EXTRA_LIVE, false)) IslandBus.publishLive(content) else IslandBus.publishNotification(content)
        EventLog.record(SOURCE, "shown", if (intent.getBooleanExtra(EXTRA_LIVE, false)) "live activity" else "event")
    }

    private fun idFor(intent: Intent) = "$ID_PREFIX${intent.getStringExtra(EXTRA_ID) ?: DEFAULT_ID}"

    companion object {
        const val ACTION_SHOW = "com.personal.dynamicpill.action.SHOW"
        const val ACTION_END = "com.personal.dynamicpill.action.END"
        private const val EXTRA_TOKEN = "token"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_ID = "id"
        private const val EXTRA_SECONDS = "seconds"
        private const val EXTRA_EXPAND = "expand"
        private const val EXTRA_TIMER_SECONDS = "timer_seconds"
        private const val EXTRA_LIVE = "live"
        private const val ID_PREFIX = "automation:"
        private const val DEFAULT_ID = "default"
        private const val SOURCE = "Automation"
        private const val MS_PER_SECOND = 1_000L
    }
}
