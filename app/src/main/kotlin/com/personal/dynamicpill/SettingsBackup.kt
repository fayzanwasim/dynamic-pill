package com.personal.dynamicpill

import android.content.Context
import org.json.JSONObject

/**
 * Exports and imports every setting and per-app rule as one JSON document.
 * The automation token is a local secret: it is never exported and survives an import.
 */
object SettingsBackup {
    private val PREF_FILES = listOf(IslandSettings.PREFS_NAME, AppRules.PREFS_NAME)
    private val PRIVATE_KEYS = setOf(IslandSettings.KEY_API_TOKEN)
    private const val JSON_INDENT = 2

    fun export(context: Context): String {
        val root = JSONObject()
        PREF_FILES.forEach { name ->
            val values = context.getSharedPreferences(name, Context.MODE_PRIVATE).all
                .filter { (key, value) -> key !in PRIVATE_KEYS && (value is Int || value is String) }
            root.put(name, JSONObject(values))
        }
        return root.toString(JSON_INDENT)
    }

    /** Replaces the stored values with those in [json]; throws [org.json.JSONException] on malformed input. */
    fun import(context: Context, json: String) {
        val root = JSONObject(json)
        PREF_FILES.forEach { name ->
            val values = root.optJSONObject(name) ?: return@forEach
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val kept = PRIVATE_KEYS.mapNotNull { key -> prefs.getString(key, null)?.let { key to it } }
            val editor = prefs.edit().clear()
            values.keys().forEach { key ->
                if (key in PRIVATE_KEYS) return@forEach
                when (val value = values.get(key)) {
                    is Int -> editor.putInt(key, value)
                    is String -> editor.putString(key, value)
                }
            }
            kept.forEach { (key, value) -> editor.putString(key, value) }
            editor.apply()
        }
    }
}
