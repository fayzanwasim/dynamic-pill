package com.personal.dynamicpill

/**
 * The most recent decisions the pill made about notifications, so "why didn't it show?" has an answer.
 * Only app names and reasons are kept (never notification text) and nothing leaves memory.
 * Used from the main thread only.
 */
object EventLog {
    data class Entry(val timeMillis: Long, val app: String, val decision: String, val reason: String)

    private const val MAX_ENTRIES = 50
    private val entries = ArrayDeque<Entry>()

    fun record(app: String, decision: String, reason: String) {
        entries.addFirst(Entry(System.currentTimeMillis(), app, decision, reason))
        while (entries.size > MAX_ENTRIES) entries.removeLast()
    }

    fun snapshot(): List<Entry> = entries.toList()

    fun clear() = entries.clear()
}
