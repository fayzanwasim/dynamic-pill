package com.personal.dynamicpill

import android.content.Context
import android.content.SharedPreferences

/** How the pill treats one app. */
enum class AppMode(val labelRes: Int) {
    DEFAULT(R.string.mode_default),
    MUTED(R.string.mode_muted),
    AUTO_EXPAND(R.string.mode_auto_expand),
    LIVE(R.string.mode_live),
    HIDE_WHILE_OPEN(R.string.mode_hide_while_open),
}

/** [holdSeconds] of 0 means "use the global duration". */
data class AppRule(val mode: AppMode = AppMode.DEFAULT, val holdSeconds: Int = 0)

/** Per-app overrides, stored as "MODE|seconds" strings keyed by package name. */
class AppRules(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(packageName: String?): AppRule {
        val stored = packageName?.let { prefs.getString(it, null) } ?: return AppRule()
        val (modeName, seconds) = stored.split(SEPARATOR).let { it.first() to it.getOrNull(1) }
        val mode = AppMode.entries.firstOrNull { it.name == modeName } ?: AppMode.DEFAULT
        return AppRule(mode, seconds?.toIntOrNull() ?: 0)
    }

    fun set(packageName: String, rule: AppRule) {
        val editor = prefs.edit()
        if (rule == AppRule()) {
            editor.remove(packageName)
        } else {
            editor.putString(packageName, "${rule.mode.name}$SEPARATOR${rule.holdSeconds}")
        }
        editor.apply()
    }

    companion object {
        const val PREFS_NAME = "app_rules"
        private const val SEPARATOR = "|"
    }
}
