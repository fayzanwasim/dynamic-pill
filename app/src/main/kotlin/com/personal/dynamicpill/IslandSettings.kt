package com.personal.dynamicpill

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom

/**
 * A user-adjustable value. Ranges and defaults are in dp unless the setting says otherwise;
 * a [toggle] is stored as 0 or 1.
 */
enum class IslandSetting(
    val key: String,
    val labelRes: Int,
    val min: Int,
    val max: Int,
    val default: Int,
    val toggle: Boolean = false,
) {
    OFFSET_X("offset_x", R.string.setting_offset_x, -60, 60, 0),
    OFFSET_Y("offset_y", R.string.setting_offset_y, -30, 60, 6),
    COMPACT_SIDE("compact_side", R.string.setting_compact_side, 4, 80, 67),
    PADDING("padding", R.string.setting_padding, 0, 20, 0),
    IDLE_SIDE("idle_side", R.string.setting_idle_side, 0, 80, 39),
    COMPACT_RADIUS("compact_radius", R.string.setting_compact_radius, 0, 40, 40),
    EDGE_PADDING("edge_padding", R.string.setting_edge_padding, 0, 24, 8),
    EXPANDED_WIDTH("expanded_width", R.string.setting_expanded_width, 160, 400, 400),
    CORNER_RADIUS("corner_radius", R.string.setting_corner_radius, 8, 40, 21),
    HOLD_SECONDS("hold_seconds", R.string.setting_hold_seconds, 1, 10, 4),
    BOUNCINESS("bounciness", R.string.setting_bounciness, 0, 100, 50),
    ANIMATION_SPEED("animation_speed", R.string.setting_animation_speed, 20, 200, 60),
    ACCENT_COLOR("accent_color", R.string.setting_accent_color, 0, IslandPalette.COLORS.size - 1, 0),
    IDLE_WIDGET("idle_widget", R.string.setting_idle_widget, 0, IdleWidget.entries.size - 1, IdleWidget.EYES.ordinal),
    ALWAYS_ON("always_on", R.string.toggle_always_on, 0, 1, 1, toggle = true),
    IGNORE_PERSISTENT("ignore_persistent", R.string.toggle_ignore_persistent, 0, 1, 1, toggle = true),
    ONLY_IMPORTANT("only_important", R.string.toggle_only_important, 0, 1, 0, toggle = true),
    AUTO_EXPAND_NOTIFICATIONS("auto_expand_notifications", R.string.toggle_auto_expand_notifications, 0, 1, 0, toggle = true),
    AUTO_EXPAND_CALLS("auto_expand_calls", R.string.toggle_auto_expand_calls, 0, 1, 1, toggle = true),
    SHOW_ON_LOCKSCREEN("show_on_lockscreen", R.string.toggle_show_on_lockscreen, 0, 1, 1, toggle = true),
    RESPECT_DND("respect_dnd", R.string.toggle_respect_dnd, 0, 1, 1, toggle = true),
    IGNORE_FOREGROUND_APP("ignore_foreground_app", R.string.toggle_ignore_foreground_app, 0, 1, 1, toggle = true),
    HAPTICS("haptics", R.string.toggle_haptics, 0, 1, 1, toggle = true),
    EVENT_VOLUME("event_volume", R.string.toggle_event_volume, 0, 1, 1, toggle = true),
    EVENT_RINGER("event_ringer", R.string.toggle_event_ringer, 0, 1, 1, toggle = true),
    EVENT_BLUETOOTH("event_bluetooth", R.string.toggle_event_bluetooth, 0, 1, 1, toggle = true),
    EVENT_LOW_BATTERY("event_low_battery", R.string.toggle_event_low_battery, 0, 1, 1, toggle = true),
}

/** A bundle of settings that can be applied in one tap. Settings it does not mention are left alone. */
enum class IslandProfile(val labelRes: Int, val values: Map<IslandSetting, Int>) {
    HOME(
        R.string.profile_home,
        mapOf(
            IslandSetting.ALWAYS_ON to 1,
            IslandSetting.IDLE_WIDGET to IdleWidget.EYES.ordinal,
            IslandSetting.ONLY_IMPORTANT to 0,
            IslandSetting.IGNORE_PERSISTENT to 1,
            IslandSetting.HAPTICS to 1,
            IslandSetting.HOLD_SECONDS to 4,
        ),
    ),
    WORK(
        R.string.profile_work,
        mapOf(
            IslandSetting.ALWAYS_ON to 0,
            IslandSetting.ONLY_IMPORTANT to 1,
            IslandSetting.IGNORE_PERSISTENT to 1,
            IslandSetting.HAPTICS to 0,
            IslandSetting.HOLD_SECONDS to 3,
        ),
    ),
    SLEEP(
        R.string.profile_sleep,
        mapOf(
            IslandSetting.ALWAYS_ON to 0,
            IslandSetting.ONLY_IMPORTANT to 1,
            IslandSetting.SHOW_ON_LOCKSCREEN to 0,
            IslandSetting.RESPECT_DND to 1,
            IslandSetting.HAPTICS to 0,
        ),
    ),
}

/** Persists the pill's adjustable values on the device. */
class IslandSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(setting: IslandSetting): Int =
        prefs.getInt(setting.key, setting.default).coerceIn(setting.min, setting.max)

    fun isOn(setting: IslandSetting): Boolean = get(setting) == 1

    fun set(setting: IslandSetting, value: Int) {
        prefs.edit().putInt(setting.key, value).apply()
    }

    /** Restores every default but keeps the automation token, so existing Tasker setups keep working. */
    fun reset() {
        val token = prefs.getString(KEY_API_TOKEN, null)
        val editor = prefs.edit().clear()
        if (token != null) editor.putString(KEY_API_TOKEN, token)
        editor.apply()
    }

    fun apply(profile: IslandProfile) {
        val editor = prefs.edit()
        profile.values.forEach { (setting, value) -> editor.putInt(setting.key, value) }
        editor.apply()
    }

    /** Free-text settings (VIP keywords) live beside the numeric ones. */
    fun getText(key: String): String = prefs.getString(key, "").orEmpty()

    fun setText(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    /** The secret that other apps must send with an automation intent; created on first use. */
    fun apiToken(): String {
        val existing = prefs.getString(KEY_API_TOKEN, null)
        if (existing != null) return existing
        val bytes = ByteArray(TOKEN_BYTES).also { SecureRandom().nextBytes(it) }
        val token = bytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString(KEY_API_TOKEN, token).apply()
        return token
    }

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    companion object {
        const val PREFS_NAME = "island_settings"
        const val KEY_VIP_KEYWORDS = "vip_keywords"
        const val KEY_API_TOKEN = "api_token"
        private const val TOKEN_BYTES = 12
    }
}
