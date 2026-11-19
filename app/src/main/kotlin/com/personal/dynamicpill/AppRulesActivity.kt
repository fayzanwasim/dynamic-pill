package com.personal.dynamicpill

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView

/** Lists launchable apps and lets the user choose how the pill treats each one. */
class AppRulesActivity : Activity() {

    private class AppEntry(val label: String, val packageName: String, val icon: Drawable)

    private class RowViews(val icon: ImageView, val label: TextView, val summary: TextView)

    private lateinit var rules: AppRules
    private lateinit var apps: List<AppEntry>

    private val adapter = object : BaseAdapter() {
        override fun getCount() = apps.size

        override fun getItem(position: Int) = apps[position]

        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView ?: createRow()
            val views = row.tag as RowViews
            val app = apps[position]
            views.icon.setImageDrawable(app.icon)
            views.label.text = app.label
            views.summary.text = summaryFor(rules.get(app.packageName))
            return row
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle(R.string.action_app_rules)
        rules = AppRules(this)
        apps = loadApps()

        val list = ListView(this).apply {
            adapter = this@AppRulesActivity.adapter
            setOnItemClickListener { _, _, position, _ -> showRuleDialog(apps[position]) }
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
            clipToPadding = false
        }
        setContentView(list)
    }

    private fun loadApps(): List<AppEntry> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(launcherIntent, 0)
            .filter { it.activityInfo.packageName != packageName }
            .map { AppEntry(it.loadLabel(packageManager).toString(), it.activityInfo.packageName, it.loadIcon(packageManager)) }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    private fun createRow(): View {
        val padding = dp(ROW_PADDING_DP)
        val icon = ImageView(this)
        val label = TextView(this).apply { textSize = LABEL_SP }
        val summary = TextView(this).apply { textSize = SUMMARY_SP }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label)
            addView(summary)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(padding, padding / 2, padding, padding / 2)
            gravity = Gravity.CENTER_VERTICAL
            addView(icon, LinearLayout.LayoutParams(dp(ICON_DP), dp(ICON_DP)))
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = padding })
            tag = RowViews(icon, label, summary)
        }
    }

    private fun summaryFor(rule: AppRule): String {
        val mode = getString(rule.mode.labelRes)
        return if (rule.holdSeconds > 0) getString(R.string.rule_summary_with_seconds, mode, rule.holdSeconds) else mode
    }

    private fun showRuleDialog(app: AppEntry) {
        val current = rules.get(app.packageName)
        val padding = dp(ROW_PADDING_DP)

        val modes = RadioGroup(this).apply {
            AppMode.entries.forEach { mode ->
                addView(RadioButton(this@AppRulesActivity).apply { id = mode.ordinal + 1; setText(mode.labelRes) })
            }
            check(current.mode.ordinal + 1)
        }
        val secondsLabel = TextView(this)
        val seconds = SeekBar(this).apply {
            max = MAX_HOLD_SECONDS
            progress = current.holdSeconds
            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                        secondsLabel.text = holdLabel(progress)
                    }

                    override fun onStartTrackingTouch(bar: SeekBar) = Unit

                    override fun onStopTrackingTouch(bar: SeekBar) = Unit
                },
            )
        }
        secondsLabel.text = holdLabel(seconds.progress)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, 0)
            addView(modes)
            addView(secondsLabel)
            addView(seconds)
        }
        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setView(content)
            .setPositiveButton(R.string.save) { _, _ ->
                val mode = AppMode.entries[modes.checkedRadioButtonId - 1]
                rules.set(app.packageName, AppRule(mode, seconds.progress))
                adapter.notifyDataSetChanged()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun holdLabel(seconds: Int): String =
        if (seconds == 0) getString(R.string.rule_duration_default) else getString(R.string.rule_duration_seconds, seconds)

    private companion object {
        const val MAX_HOLD_SECONDS = 10
        const val ROW_PADDING_DP = 16
        const val ICON_DP = 40
        const val LABEL_SP = 16f
        const val SUMMARY_SP = 13f
    }
}
