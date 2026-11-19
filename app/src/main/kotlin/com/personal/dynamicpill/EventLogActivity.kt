package com.personal.dynamicpill

import android.app.Activity
import android.os.Bundle
import android.text.format.DateFormat
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import java.util.Date

/** "Why didn't it show?": the latest notification decisions with their reasons. */
class EventLogActivity : Activity() {

    private lateinit var adapter: ArrayAdapter<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle(R.string.action_event_log)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())

        val clear = Button(this).apply {
            setText(R.string.action_clear_log)
            setOnClickListener {
                EventLog.clear()
                refresh()
            }
        }
        val list = ListView(this).apply { adapter = this@EventLogActivity.adapter }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(clear, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val timeFormat = DateFormat.getTimeFormat(this)
        val lines = EventLog.snapshot().map { entry ->
            getString(R.string.log_line, timeFormat.format(Date(entry.timeMillis)), entry.app, entry.decision, entry.reason)
        }
        adapter.clear()
        adapter.addAll(lines.ifEmpty { listOf(getString(R.string.log_empty)) })
    }
}
