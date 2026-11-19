package com.personal.dynamicpill

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.os.PowerManager
import android.provider.Settings

/** Everything the pill needs to work, checked live, with a hint for the first thing to fix. */
object HealthReport {

    fun build(context: Context): String {
        val accessibilityEnabled = isAccessibilityEnabled(context)
        val overlayConnected = IslandBus.sink != null
        val listenerGranted = context.getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(ComponentName(context, IslandNotificationListener::class.java))
        val batteryUnrestricted = context.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName)

        val lines = listOf(
            line(context, accessibilityEnabled, R.string.health_accessibility),
            line(context, overlayConnected, R.string.health_overlay),
            line(context, listenerGranted, R.string.health_listener),
            line(context, IslandBus.listenerConnected, R.string.health_listener_connected),
            line(context, batteryUnrestricted, R.string.health_battery),
        )
        val hint = when {
            !accessibilityEnabled -> R.string.health_hint_enable_accessibility
            !overlayConnected -> R.string.health_hint_reconnect
            !listenerGranted -> R.string.health_hint_enable_listener
            !batteryUnrestricted -> R.string.health_hint_battery
            else -> R.string.health_hint_ok
        }
        return (lines + "" + context.getString(hint)).joinToString("\n")
    }

    private fun line(context: Context, ok: Boolean, labelRes: Int): String =
        context.getString(if (ok) R.string.health_ok else R.string.health_missing, context.getString(labelRes))

    /** The system stores enabled services in either the long or the short component form. */
    private fun isAccessibilityEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val component = ComponentName(context, IslandAccessibilityService::class.java)
        return enabled.split(':').any { it == component.flattenToString() || it == component.flattenToShortString() }
    }
}
