package com.personal.dynamicpill

import android.app.Notification
import android.app.NotificationManager

enum class NotificationKind { SKIP, TRANSIENT, CALL, ALARM, TIMER, NAVIGATION, PROGRESS }

/** Everything the classifier needs to know about one notification, free of Android objects so it is unit-testable. */
data class NotificationFacts(
    val isOwnPackage: Boolean,
    val isGroupSummary: Boolean,
    val category: String?,
    val isOngoing: Boolean,
    val isNoClear: Boolean,
    val showsChronometer: Boolean,
    /** The notification's importance, or null when the system has not ranked it. */
    val importance: Int?,
    /** The notification matched one of the user's VIP keywords. */
    val isVip: Boolean = false,
)

data class ClassifierConfig(
    val ignorePersistent: Boolean,
    val onlyImportant: Boolean,
    val respectDoNotDisturb: Boolean,
    val doNotDisturbActive: Boolean,
    /** The app is set to "show ongoing activity", so its ongoing notifications become live activities. */
    val appShowsLive: Boolean,
)

/** The decision, plus a human-readable [reason] for the "why didn't it show" log. */
data class Classification(val kind: NotificationKind, val reason: String)

/** Decides what a notification becomes on the pill. */
object NotificationClassifier {

    fun classify(facts: NotificationFacts, config: ClassifierConfig): Classification = when {
        facts.isOwnPackage -> skip("this app's own notification")
        facts.isGroupSummary -> skip("group summary")
        facts.category == Notification.CATEGORY_TRANSPORT -> skip("media controls are shown from the media session")
        facts.category == Notification.CATEGORY_CALL -> Classification(NotificationKind.CALL, "call")
        facts.category == Notification.CATEGORY_ALARM -> Classification(NotificationKind.ALARM, "alarm")
        (facts.isOngoing || facts.isNoClear) && config.ignorePersistent -> skip("persistent notification (ignored by your setting)")
        facts.isOngoing -> classifyOngoing(facts, config)
        else -> classifyEvent(facts, config)
    }

    private fun classifyOngoing(facts: NotificationFacts, config: ClassifierConfig): Classification = when {
        facts.category == Notification.CATEGORY_NAVIGATION -> Classification(NotificationKind.NAVIGATION, "navigation")
        facts.showsChronometer -> Classification(NotificationKind.TIMER, "timer or stopwatch")
        config.appShowsLive -> Classification(NotificationKind.PROGRESS, "ongoing activity from an app set to live")
        else -> skip("ongoing notification without a timer (set the app to 'live' in per-app rules to show it)")
    }

    private fun classifyEvent(facts: NotificationFacts, config: ClassifierConfig): Classification {
        if (facts.isVip) return Classification(NotificationKind.TRANSIENT, "matched a VIP keyword")
        if (config.respectDoNotDisturb && config.doNotDisturbActive) return skip("Do Not Disturb is on")
        val minimum = if (config.onlyImportant) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_DEFAULT
        val importance = facts.importance
        if (importance == null || importance < minimum) {
            val needed = if (config.onlyImportant) "high (only-important is on)" else "default"
            return skip("importance too low, needs $needed")
        }
        return Classification(NotificationKind.TRANSIENT, "important enough")
    }

    private fun skip(reason: String) = Classification(NotificationKind.SKIP, reason)
}
