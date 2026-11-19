package com.personal.dynamicpill

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.BatteryManager
import kotlin.math.abs
import kotlin.math.roundToInt

/** Turns system broadcasts (volume, ringer, Bluetooth, battery) into short pill events, like iPhone's HUDs. */
class SystemEvents(private val context: Context, private val settings: IslandSettings) {

    /** A ready-to-show event and how long it should stay. */
    data class Event(val content: IslandContent, val holdMs: Long)

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val batteryManager = context.getSystemService(BatteryManager::class.java)

    fun addActionsTo(filter: IntentFilter) {
        filter.addAction(VOLUME_CHANGED_ACTION)
        filter.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
        filter.addAction(Intent.ACTION_BATTERY_LOW)
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
    }

    /** The event for [intent], or null when it is not one of ours, is switched off or carries no change. */
    fun eventFor(intent: Intent): Event? = when (intent.action) {
        VOLUME_CHANGED_ACTION -> volumeEvent(intent).takeIf { settings.isOn(IslandSetting.EVENT_VOLUME) }
        AudioManager.RINGER_MODE_CHANGED_ACTION -> ringerEvent(intent).takeIf { settings.isOn(IslandSetting.EVENT_RINGER) }
        Intent.ACTION_BATTERY_LOW -> lowBatteryEvent().takeIf { settings.isOn(IslandSetting.EVENT_LOW_BATTERY) }
        BluetoothDevice.ACTION_ACL_CONNECTED -> bluetoothEvent(intent).takeIf { settings.isOn(IslandSetting.EVENT_BLUETOOTH) }
        else -> null
    }

    /** Charging with the current power draw, measured shortly after plugging in once the current has ramped up. */
    fun chargingEvent(): Event {
        val percent = batteryPercent()
        val watts = chargingWatts()
        val title = context.getString(if (watts >= FAST_CHARGING_WATTS) R.string.charging_fast_title else R.string.charging_title)
        val subtitle = if (watts > 0) context.getString(R.string.charging_watts, watts) else ""
        val label = context.getString(R.string.charging_percent, percent)
        return Event(
            IslandContent(
                id = CHARGING_ID,
                icon = context.getDrawable(android.R.drawable.ic_lock_idle_charging),
                title = title,
                subtitle = subtitle,
                glance = Glance.Text(label),
                progress = percent,
            ),
            CHARGING_HOLD_MS,
        )
    }

    private fun volumeEvent(intent: Intent): Event? {
        val stream = intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1)
        if (stream !in SHOWN_STREAMS) return null
        val value = intent.getIntExtra(EXTRA_VOLUME_STREAM_VALUE, -1)
        if (value < 0 || value == intent.getIntExtra(EXTRA_PREV_VOLUME_STREAM_VALUE, -1)) return null
        val max = audioManager.getStreamMaxVolume(stream).coerceAtLeast(1)
        val percent = (value * PERCENT_MAX.toFloat() / max).roundToInt()
        val label = context.getString(R.string.charging_percent, percent)
        val icon = if (value == 0) android.R.drawable.ic_lock_silent_mode else android.R.drawable.ic_lock_silent_mode_off
        return Event(
            IslandContent(
                id = VOLUME_ID,
                icon = context.getDrawable(icon),
                title = context.getString(if (stream == AudioManager.STREAM_RING) R.string.event_ring_volume else R.string.event_volume),
                subtitle = label,
                glance = Glance.Text(label),
                progress = percent,
            ),
            VOLUME_HOLD_MS,
        )
    }

    private fun ringerEvent(intent: Intent): Event {
        val (titleRes, iconRes) = when (intent.getIntExtra(AudioManager.EXTRA_RINGER_MODE, AudioManager.RINGER_MODE_NORMAL)) {
            AudioManager.RINGER_MODE_SILENT -> R.string.event_silent to android.R.drawable.ic_lock_silent_mode
            AudioManager.RINGER_MODE_VIBRATE -> R.string.event_vibrate to android.R.drawable.ic_lock_silent_mode
            else -> R.string.event_ring to android.R.drawable.ic_lock_silent_mode_off
        }
        val title = context.getString(titleRes)
        return Event(
            IslandContent(id = RINGER_ID, icon = context.getDrawable(iconRes), title = title, subtitle = "", glance = Glance.Text(title)),
            RINGER_HOLD_MS,
        )
    }

    private fun lowBatteryEvent(): Event {
        val label = context.getString(R.string.charging_percent, batteryPercent())
        return Event(
            IslandContent(
                id = LOW_BATTERY_ID,
                icon = context.getDrawable(android.R.drawable.ic_lock_idle_low_battery),
                title = context.getString(R.string.event_low_battery),
                subtitle = label,
                glance = Glance.Text(label),
            ),
            LOW_BATTERY_HOLD_MS,
        )
    }

    private fun bluetoothEvent(intent: Intent): Event {
        val name = bluetoothName(intent) ?: context.getString(R.string.event_bluetooth_device)
        return Event(
            IslandContent(
                id = BLUETOOTH_ID,
                icon = context.getDrawable(android.R.drawable.stat_sys_data_bluetooth),
                title = name,
                subtitle = context.getString(R.string.event_bluetooth_connected),
                glance = Glance.Text(context.getString(R.string.event_bluetooth_short)),
            ),
            BLUETOOTH_HOLD_MS,
        )
    }

    /** Device names need the Bluetooth "nearby devices" permission; without it the event stays generic. */
    private fun bluetoothName(intent: Intent): String? {
        val granted = context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        if (!granted) return null
        val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        return device?.name
    }

    private fun batteryPercent(): Int = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    /** Power going into the battery: current (µA) times voltage (mV) is nanowatts. */
    private fun chargingWatts(): Int {
        val microAmps = abs(batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW).toLong())
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val milliVolts = sticky?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)?.toLong() ?: 0L
        return (microAmps * milliVolts / NANO_PER_UNIT).toInt()
    }

    companion object {
        const val CHARGING_ID = "charging"
        private const val VOLUME_ID = "system_volume"
        private const val RINGER_ID = "system_ringer"
        private const val LOW_BATTERY_ID = "system_low_battery"
        private const val BLUETOOTH_ID = "system_bluetooth"

        // Not part of the public SDK constants, but broadcast by the platform on every volume change.
        private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
        private const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        private const val EXTRA_VOLUME_STREAM_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE"
        private const val EXTRA_PREV_VOLUME_STREAM_VALUE = "android.media.EXTRA_PREV_VOLUME_STREAM_VALUE"
        private val SHOWN_STREAMS = setOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_RING)

        private const val PERCENT_MAX = 100
        private const val FAST_CHARGING_WATTS = 15
        private const val NANO_PER_UNIT = 1_000_000_000L
        private const val CHARGING_HOLD_MS = 3_500L
        private const val VOLUME_HOLD_MS = 1_500L
        private const val RINGER_HOLD_MS = 2_000L
        private const val LOW_BATTERY_HOLD_MS = 5_000L
        private const val BLUETOOTH_HOLD_MS = 3_000L
    }
}
