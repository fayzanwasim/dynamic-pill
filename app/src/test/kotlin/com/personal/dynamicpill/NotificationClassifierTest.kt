package com.personal.dynamicpill

import android.app.Notification
import android.app.NotificationManager
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationClassifierTest {

    private val defaultConfig = ClassifierConfig(
        ignorePersistent = false,
        onlyImportant = false,
        respectDoNotDisturb = true,
        doNotDisturbActive = false,
        appShowsLive = false,
    )

    private fun facts(
        category: String? = null,
        ongoing: Boolean = false,
        noClear: Boolean = false,
        chronometer: Boolean = false,
        importance: Int? = NotificationManager.IMPORTANCE_DEFAULT,
        vip: Boolean = false,
        own: Boolean = false,
        summary: Boolean = false,
    ) = NotificationFacts(own, summary, category, ongoing, noClear, chronometer, importance, vip)

    private fun kind(facts: NotificationFacts, config: ClassifierConfig = defaultConfig) =
        NotificationClassifier.classify(facts, config).kind

    @Test
    fun `ordinary notification is shown as an event`() {
        assertEquals(NotificationKind.TRANSIENT, kind(facts()))
    }

    @Test
    fun `own notifications and group summaries are skipped`() {
        assertEquals(NotificationKind.SKIP, kind(facts(own = true)))
        assertEquals(NotificationKind.SKIP, kind(facts(summary = true)))
    }

    @Test
    fun `media controls are left to the media session`() {
        assertEquals(NotificationKind.SKIP, kind(facts(category = Notification.CATEGORY_TRANSPORT, ongoing = true)))
    }

    @Test
    fun `calls and alarms become live activities even when persistent notifications are ignored`() {
        val config = defaultConfig.copy(ignorePersistent = true)
        assertEquals(NotificationKind.CALL, kind(facts(category = Notification.CATEGORY_CALL, ongoing = true), config))
        assertEquals(NotificationKind.ALARM, kind(facts(category = Notification.CATEGORY_ALARM, ongoing = true), config))
    }

    @Test
    fun `ongoing chronometer is a timer and ongoing navigation is navigation`() {
        assertEquals(NotificationKind.TIMER, kind(facts(ongoing = true, chronometer = true)))
        assertEquals(NotificationKind.NAVIGATION, kind(facts(category = Notification.CATEGORY_NAVIGATION, ongoing = true)))
    }

    @Test
    fun `plain ongoing notification is skipped unless the app is set to live`() {
        assertEquals(NotificationKind.SKIP, kind(facts(ongoing = true)))
        assertEquals(NotificationKind.PROGRESS, kind(facts(ongoing = true), defaultConfig.copy(appShowsLive = true)))
    }

    @Test
    fun `ignoring persistent notifications also drops timers and non-dismissible ones`() {
        val config = defaultConfig.copy(ignorePersistent = true)
        assertEquals(NotificationKind.SKIP, kind(facts(ongoing = true, chronometer = true), config))
        assertEquals(NotificationKind.SKIP, kind(facts(noClear = true), config))
    }

    @Test
    fun `only-important requires high importance`() {
        val config = defaultConfig.copy(onlyImportant = true)
        assertEquals(NotificationKind.SKIP, kind(facts(importance = NotificationManager.IMPORTANCE_DEFAULT), config))
        assertEquals(NotificationKind.TRANSIENT, kind(facts(importance = NotificationManager.IMPORTANCE_HIGH), config))
    }

    @Test
    fun `low importance and unranked notifications are skipped`() {
        assertEquals(NotificationKind.SKIP, kind(facts(importance = NotificationManager.IMPORTANCE_LOW)))
        assertEquals(NotificationKind.SKIP, kind(facts(importance = null)))
    }

    @Test
    fun `do not disturb silences events only when respected`() {
        val dnd = defaultConfig.copy(doNotDisturbActive = true)
        assertEquals(NotificationKind.SKIP, kind(facts(), dnd))
        assertEquals(NotificationKind.TRANSIENT, kind(facts(), dnd.copy(respectDoNotDisturb = false)))
    }

    @Test
    fun `vip keywords bypass importance and do not disturb`() {
        val config = defaultConfig.copy(onlyImportant = true, doNotDisturbActive = true)
        assertEquals(NotificationKind.TRANSIENT, kind(facts(importance = NotificationManager.IMPORTANCE_LOW, vip = true), config))
    }

    @Test
    fun `every decision carries a reason`() {
        val reason = NotificationClassifier.classify(facts(importance = NotificationManager.IMPORTANCE_LOW), defaultConfig).reason
        assertEquals("importance too low, needs default", reason)
    }
}
