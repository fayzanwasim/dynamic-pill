package com.personal.dynamicpill

import android.app.Notification
import android.app.Person
import android.content.ComponentName
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Turns notifications into pill content: short-lived events for ordinary notifications and live
 * activities for calls, alarms, timers, navigation and (for apps set to "live") ongoing progress.
 * Every decision is recorded in the [EventLog]. Also hosts the media session monitor.
 */
class IslandNotificationListener : NotificationListenerService() {

    private lateinit var settings: IslandSettings
    private lateinit var rules: AppRules
    private var mediaMonitor: MediaMonitor? = null
    private val lastShown = mutableMapOf<String, String>()

    override fun onCreate() {
        super.onCreate()
        settings = IslandSettings(this)
        rules = AppRules(this)
    }

    override fun onListenerConnected() {
        IslandBus.listenerConnected = true
        mediaMonitor = MediaMonitor(this, IslandBus::publishMedia).also { it.start() }
        // Pick up ongoing activities (a running timer, an active call) that started before we connected.
        activeNotifications?.filter { it.isOngoing || it.notification.category == Notification.CATEGORY_CALL }
            ?.forEach(::handle)
    }

    override fun onListenerDisconnected() {
        IslandBus.listenerConnected = false
        mediaMonitor?.stop()
        mediaMonitor = null
        IslandBus.publishMedia(null)
        IslandBus.clearLive()
        // Ask the system to bind us again if we were unbound because the process was killed.
        requestRebind(ComponentName(this, IslandNotificationListener::class.java))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = handle(sbn)

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        lastShown.remove(sbn.key)
        IslandBus.endLive(sbn.key)
    }

    private fun handle(sbn: StatusBarNotification) {
        val app = appLabel(sbn.packageName).toString()
        val rule = rules.get(sbn.packageName)
        if (rule.mode == AppMode.MUTED) {
            EventLog.record(app, "skipped", "app is set to never show")
            return
        }
        val notification = sbn.notification
        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE) ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT) ?: ""

        val facts = factsFor(sbn, "$title $text")
        val result = NotificationClassifier.classify(facts, configFor(rule))
        if (result.kind == NotificationKind.SKIP) {
            EventLog.record(app, "skipped", result.reason)
            return
        }
        if (title.isEmpty() && text.isEmpty()) {
            EventLog.record(app, "skipped", "no title or text")
            return
        }
        val fingerprint = "$title|$text|${extras.getInt(Notification.EXTRA_PROGRESS)}"
        if (lastShown.put(sbn.key, fingerprint) == fingerprint) return

        val photo = senderPhoto(notification)
        val base = IslandContent(
            id = sbn.key,
            icon = photo ?: packageManager.getApplicationIcon(sbn.packageName),
            title = title.ifEmpty { app },
            subtitle = text,
            holdSeconds = rule.holdSeconds.takeIf { it > 0 },
            sourcePackage = sbn.packageName,
            onTap = notification.contentIntent?.let { intent -> { intent.sendFromOverlay(this) } },
        )
        publish(result.kind, sbn, base, expand = facts.isVip || shouldExpand(rule))
        EventLog.record(app, result.kind.name.lowercase(), result.reason)
    }

    private fun publish(kind: NotificationKind, sbn: StatusBarNotification, base: IslandContent, expand: Boolean) {
        when (kind) {
            NotificationKind.TRANSIENT -> IslandBus.publishNotification(base.copy(expandOnArrival = expand))
            NotificationKind.CALL -> IslandBus.publishLive(callContent(sbn, base))
            NotificationKind.ALARM -> IslandBus.publishLive(alarmContent(sbn, base))
            NotificationKind.TIMER -> IslandBus.publishLive(
                base.copy(priority = IslandPriority.TIMER, glance = chronometerGlance(sbn.notification)),
            )
            NotificationKind.NAVIGATION -> IslandBus.publishLive(base.copy(priority = IslandPriority.NAVIGATION))
            NotificationKind.PROGRESS -> IslandBus.publishLive(progressContent(sbn, base))
            NotificationKind.SKIP -> Unit
        }
    }

    private fun shouldExpand(rule: AppRule): Boolean =
        rule.mode == AppMode.AUTO_EXPAND || settings.isOn(IslandSetting.AUTO_EXPAND_NOTIFICATIONS)

    private fun factsFor(sbn: StatusBarNotification, searchableText: String): NotificationFacts {
        val notification = sbn.notification
        val ranking = Ranking()
        return NotificationFacts(
            isOwnPackage = sbn.packageName == packageName,
            isGroupSummary = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            category = notification.category,
            isOngoing = sbn.isOngoing,
            isNoClear = notification.flags and Notification.FLAG_NO_CLEAR != 0,
            showsChronometer = notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER),
            importance = if (currentRanking.getRanking(sbn.key, ranking)) ranking.importance else null,
            isVip = matchesVip(searchableText),
        )
    }

    private fun configFor(rule: AppRule) = ClassifierConfig(
        ignorePersistent = settings.isOn(IslandSetting.IGNORE_PERSISTENT),
        onlyImportant = settings.isOn(IslandSetting.ONLY_IMPORTANT),
        respectDoNotDisturb = settings.isOn(IslandSetting.RESPECT_DND),
        doNotDisturbActive = currentInterruptionFilter != INTERRUPTION_FILTER_ALL,
        appShowsLive = rule.mode == AppMode.LIVE,
    )

    /** Keywords are comma or line separated and matched case-insensitively against the title and text. */
    private fun matchesVip(searchableText: String): Boolean {
        val keywords = settings.getText(IslandSettings.KEY_VIP_KEYWORDS)
            .split(',', '\n')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
        val haystack = searchableText.lowercase()
        return keywords.any { it in haystack }
    }

    /** The sender's photo from the notification (conversation avatar or large icon), if it has one. */
    private fun senderPhoto(notification: Notification): Drawable? {
        val person = notification.extras.getParcelable(Notification.EXTRA_MESSAGING_PERSON, Person::class.java)
        return person?.icon?.loadDrawable(this) ?: notification.getLargeIcon()?.loadDrawable(this)
    }

    private fun callContent(sbn: StatusBarNotification, base: IslandContent): IslandContent {
        val notification = sbn.notification
        val callType = notification.extras.getInt(Notification.EXTRA_CALL_TYPE, 0)
        val isIncoming = callType == Notification.CallStyle.CALL_TYPE_INCOMING || (callType == 0 && !sbn.isOngoing)
        val count = notification.actions.orEmpty().size
        val actions = notification.actions.orEmpty().mapIndexed { index, action ->
            IslandAction(
                label = action.title,
                style = callActionStyle(isIncoming, index, count),
                onClick = { action.actionIntent?.sendFromOverlay(this) },
            )
        }
        val showsTimer = notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)
        return base.copy(
            priority = IslandPriority.CALL,
            glance = if (showsTimer) chronometerGlance(notification) else Glance.None,
            expandOnArrival = isIncoming && settings.isOn(IslandSetting.AUTO_EXPAND_CALLS),
            persistent = isIncoming,
            actions = actions,
        )
    }

    /** A ringing alarm behaves like an incoming call: it stays expanded with the alarm's own buttons. */
    private fun alarmContent(sbn: StatusBarNotification, base: IslandContent): IslandContent {
        val actions = sbn.notification.actions.orEmpty()
        return base.copy(
            priority = IslandPriority.CALL,
            expandOnArrival = true,
            persistent = true,
            actions = actions.mapIndexed { index, action ->
                IslandAction(
                    label = action.title,
                    style = if (index == actions.lastIndex) ActionStyle.DESTRUCTIVE else ActionStyle.NEUTRAL,
                    onClick = { action.actionIntent?.sendFromOverlay(this) },
                )
            },
        )
    }

    /** Call notifications list the decline action first and the answer action last. */
    private fun callActionStyle(isIncoming: Boolean, index: Int, count: Int): ActionStyle = when {
        !isIncoming -> ActionStyle.DESTRUCTIVE
        index == 0 -> ActionStyle.DESTRUCTIVE
        index == count - 1 -> ActionStyle.POSITIVE
        else -> ActionStyle.NEUTRAL
    }

    private fun progressContent(sbn: StatusBarNotification, base: IslandContent): IslandContent {
        val extras = sbn.notification.extras
        val max = extras.getInt(Notification.EXTRA_PROGRESS_MAX)
        val determinate = max > 0 && !extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)
        val percent = if (determinate) extras.getInt(Notification.EXTRA_PROGRESS) * PERCENT_MAX / max else null
        return base.copy(
            priority = IslandPriority.OTHER,
            progress = percent,
            glance = percent?.let { Glance.Text("$it%") } ?: Glance.None,
        )
    }

    private fun chronometerGlance(notification: Notification): Glance.Timer {
        val countDown = notification.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN)
        // `when` is the start time (count up) or end time (count down) in wall-clock time; a Chronometer
        // wants the same instant on the elapsedRealtime clock.
        val base = SystemClock.elapsedRealtime() + (notification.`when` - System.currentTimeMillis())
        return Glance.Timer(base, countDown)
    }

    private fun appLabel(packageName: String): CharSequence =
        runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)) }
            .getOrDefault(packageName)

    private companion object {
        const val PERCENT_MAX = 100
    }
}
