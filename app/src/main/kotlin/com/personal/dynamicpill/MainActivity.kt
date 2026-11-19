package com.personal.dynamicpill

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.PersistableBundle
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONException
import java.io.IOException

/** Setup shortcuts, live previews, layout and behavior settings, per-app rules and backup. */
class MainActivity : Activity() {

    private lateinit var settings: IslandSettings
    private val sliders = mutableMapOf<IslandSetting, SeekBar>()
    private val toggles = mutableMapOf<IslandSetting, Switch>()
    private lateinit var healthText: TextView
    private lateinit var vipField: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = IslandSettings(this)

        val padding = dp(PADDING_DP)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(padding + bars.left, padding + bars.top, padding + bars.right, padding + bars.bottom)
                insets
            }
        }

        column.addView(TextView(this).apply { setText(R.string.setup_intro) })
        healthText = TextView(this).apply { setPadding(0, dp(BUTTON_GAP_DP), 0, 0) }
        column.addView(healthText)
        column.addView(settingsButton(R.string.action_accessibility, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
        column.addView(
            settingsButton(R.string.action_notification_access, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)),
        )
        column.addView(
            settingsButton(
                R.string.action_app_info,
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
            ),
        )

        column.addView(sectionTitle(R.string.section_preview))
        column.addView(actionButton(R.string.action_preview_expanded) { preview(PreviewKind.EXPANDED) })
        column.addView(actionButton(R.string.action_preview_compact) { preview(PreviewKind.COMPACT) })
        column.addView(actionButton(R.string.action_preview_music) { preview(PreviewKind.MUSIC) })
        column.addView(actionButton(R.string.action_preview_timer) { preview(PreviewKind.TIMER) })
        column.addView(actionButton(R.string.action_preview_message_over_music) { previewMessageOverMusic() })

        column.addView(sectionTitle(R.string.section_layout))
        IslandSetting.entries.filterNot { it.toggle }.forEach { column.addView(sliderRow(it)) }

        column.addView(sectionTitle(R.string.section_behavior))
        IslandSetting.entries.filter { it.toggle }.forEach { column.addView(toggleRow(it)) }
        column.addView(TextView(this).apply {
            setText(R.string.vip_label)
            setPadding(0, dp(BUTTON_GAP_DP), 0, 0)
        })
        vipField = EditText(this).apply {
            setHint(R.string.vip_hint)
            setText(settings.getText(IslandSettings.KEY_VIP_KEYWORDS))
            addTextChangedListener(
                object : TextWatcher {
                    override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit

                    override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit

                    override fun afterTextChanged(text: Editable?) {
                        settings.setText(IslandSettings.KEY_VIP_KEYWORDS, text?.toString().orEmpty())
                    }
                },
            )
        }
        column.addView(vipField)
        column.addView(actionButton(R.string.action_app_rules) { startActivity(Intent(this, AppRulesActivity::class.java)) })
        column.addView(actionButton(R.string.action_event_log) { startActivity(Intent(this, EventLogActivity::class.java)) })

        column.addView(sectionTitle(R.string.section_profiles))
        IslandProfile.entries.forEach { profile ->
            column.addView(
                actionButton(profile.labelRes) {
                    settings.apply(profile)
                    refreshControls()
                },
            )
        }

        column.addView(sectionTitle(R.string.section_automation))
        val automationHelp = TextView(this).apply {
            text = getString(R.string.automation_help, settings.apiToken())
            setTextIsSelectable(true)
        }
        column.addView(automationHelp)
        column.addView(actionButton(R.string.action_copy_token) { copyToken(settings.apiToken()) })
        column.addView(
            actionButton(R.string.action_new_token) {
                automationHelp.text = getString(R.string.automation_help, settings.rotateApiToken())
                Toast.makeText(this, R.string.token_rotated, Toast.LENGTH_LONG).show()
            },
        )

        column.addView(sectionTitle(R.string.section_backup))
        column.addView(actionButton(R.string.action_export) { startBackupFilePicker(exporting = true) })
        column.addView(actionButton(R.string.action_import) { startBackupFilePicker(exporting = false) })
        column.addView(
            actionButton(R.string.action_reset) {
                settings.reset()
                refreshControls()
            },
        )

        setContentView(ScrollView(this).apply { addView(column) })
    }

    override fun onResume() {
        super.onResume()
        healthText.text = HealthReport.build(this)
    }

    @Deprecated("Uses the platform result callback; the app has no AndroidX activity dependency.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        try {
            when (requestCode) {
                REQUEST_EXPORT -> contentResolver.openOutputStream(uri)?.use {
                    it.write(SettingsBackup.export(this).toByteArray())
                }
                REQUEST_IMPORT -> contentResolver.openInputStream(uri)?.use {
                    SettingsBackup.import(this, it.readBytes().decodeToString())
                }
            }
            Toast.makeText(this, R.string.backup_done, Toast.LENGTH_SHORT).show()
            refreshControls()
        } catch (e: IOException) {
            Toast.makeText(this, R.string.backup_failed, Toast.LENGTH_LONG).show()
        } catch (e: JSONException) {
            Toast.makeText(this, R.string.backup_failed, Toast.LENGTH_LONG).show()
        }
    }

    /** Copies the token marked as sensitive, so Android keeps it out of the clipboard preview. */
    private fun copyToken(token: String) {
        val clip = ClipData.newPlainText(TOKEN_CLIP_LABEL, token).apply {
            description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        Toast.makeText(this, R.string.token_copied, Toast.LENGTH_SHORT).show()
    }

    private enum class PreviewKind { EXPANDED, COMPACT, MUSIC, TIMER }

    private fun preview(kind: PreviewKind) {
        val base = IslandContent(
            id = PREVIEW_ID,
            icon = packageManager.getApplicationIcon(packageName),
            title = getString(R.string.preview_title),
            subtitle = getString(R.string.preview_text),
        )
        when (kind) {
            PreviewKind.EXPANDED -> IslandBus.publishPreview(base, expanded = true)
            PreviewKind.COMPACT -> IslandBus.publishPreview(base.copy(glance = Glance.Text(getString(R.string.preview_compact_label))), false)
            PreviewKind.MUSIC -> IslandBus.publishPreview(
                base.copy(
                    title = getString(R.string.preview_music_title),
                    subtitle = getString(R.string.preview_music_artist),
                    glance = Glance.Bars,
                    actions = listOf(
                        IslandAction(android.R.drawable.ic_media_previous) {},
                        IslandAction(android.R.drawable.ic_media_pause) {},
                        IslandAction(android.R.drawable.ic_media_next) {},
                    ),
                ),
                expanded = false,
            )
            PreviewKind.TIMER -> IslandBus.publishPreview(
                base.copy(
                    title = getString(R.string.preview_timer_title),
                    subtitle = "",
                    glance = Glance.Timer(SystemClock.elapsedRealtime() - PREVIEW_TIMER_OFFSET_MS, countDown = false),
                ),
                expanded = false,
            )
        }
    }

    /** Starts a fake music activity, then a message, so the detached bubble can be tuned without real music. */
    private fun previewMessageOverMusic() {
        val icon = packageManager.getApplicationIcon(packageName)
        IslandBus.publishLive(
            IslandContent(
                id = PREVIEW_LIVE_ID,
                icon = icon,
                title = getString(R.string.preview_music_title),
                subtitle = getString(R.string.preview_music_artist),
                glance = Glance.Bars,
                priority = IslandPriority.MEDIA,
            ),
        )
        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed(
            {
                IslandBus.publishNotification(
                    IslandContent(
                        id = PREVIEW_ID,
                        icon = icon,
                        title = getString(R.string.preview_message_title),
                        subtitle = getString(R.string.preview_message_text),
                    ),
                )
            },
            PREVIEW_MESSAGE_DELAY_MS,
        )
        handler.postDelayed({ IslandBus.endLive(PREVIEW_LIVE_ID) }, PREVIEW_LIVE_DURATION_MS)
    }

    private fun startBackupFilePicker(exporting: Boolean) {
        val intent = if (exporting) {
            Intent(Intent.ACTION_CREATE_DOCUMENT).putExtra(Intent.EXTRA_TITLE, BACKUP_FILE_NAME)
        } else {
            Intent(Intent.ACTION_OPEN_DOCUMENT)
        }
        intent.addCategory(Intent.CATEGORY_OPENABLE).setType(BACKUP_MIME_TYPE)
        startActivityForResult(intent, if (exporting) REQUEST_EXPORT else REQUEST_IMPORT)
    }

    private fun sliderRow(setting: IslandSetting): LinearLayout {
        val label = TextView(this)
        val updateLabel = { value: Int ->
            label.text = getString(R.string.setting_value, getString(setting.labelRes), valueText(setting, value))
        }
        val seekBar = SeekBar(this).apply {
            min = setting.min
            max = setting.max
            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                        updateLabel(progress)
                        if (fromUser) settings.set(setting, progress)
                    }

                    override fun onStartTrackingTouch(bar: SeekBar) = Unit

                    override fun onStopTrackingTouch(bar: SeekBar) = Unit
                },
            )
        }
        sliders[setting] = seekBar
        seekBar.progress = settings.get(setting)
        // A slider already at its initial position fires no change callback, so set the label explicitly.
        updateLabel(seekBar.progress)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(BUTTON_GAP_DP), 0, 0)
            addView(label)
            addView(seekBar)
        }
    }

    private fun toggleRow(setting: IslandSetting): Switch {
        val toggle = Switch(this).apply {
            setText(setting.labelRes)
            isChecked = settings.isOn(setting)
            setOnCheckedChangeListener { _, checked -> settings.set(setting, if (checked) 1 else 0) }
            setPadding(0, dp(BUTTON_GAP_DP), 0, 0)
        }
        toggles[setting] = toggle
        return toggle
    }

    private fun valueText(setting: IslandSetting, value: Int): String = when (setting) {
        IslandSetting.ACCENT_COLOR -> resources.getStringArray(R.array.accent_names)[value]
        IslandSetting.IDLE_WIDGET -> resources.getStringArray(R.array.idle_widget_names)[value]
        else -> value.toString()
    }

    private fun refreshControls() {
        sliders.forEach { (setting, bar) -> bar.progress = settings.get(setting) }
        toggles.forEach { (setting, toggle) -> toggle.isChecked = settings.isOn(setting) }
        val vip = settings.getText(IslandSettings.KEY_VIP_KEYWORDS)
        if (vipField.text.toString() != vip) vipField.setText(vip)
    }

    private fun sectionTitle(textRes: Int) = TextView(this).apply {
        setText(textRes)
        textSize = SECTION_TITLE_SP
        setPadding(0, dp(SECTION_GAP_DP), 0, 0)
    }

    private fun settingsButton(labelRes: Int, intent: Intent) = actionButton(labelRes) { startActivity(intent) }

    private fun actionButton(labelRes: Int, onClick: () -> Unit) = Button(this).apply {
        setText(labelRes)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(BUTTON_GAP_DP) }
    }

    private companion object {
        const val PREVIEW_ID = "preview"
        const val PREVIEW_LIVE_ID = "preview_live"
        const val PREVIEW_MESSAGE_DELAY_MS = 1_200L
        const val PREVIEW_LIVE_DURATION_MS = 9_000L
        const val TOKEN_CLIP_LABEL = "Dynamic Pill token"
        const val REQUEST_EXPORT = 2
        const val REQUEST_IMPORT = 3
        const val BACKUP_FILE_NAME = "dynamic-pill-settings.json"
        const val BACKUP_MIME_TYPE = "application/json"
        const val PREVIEW_TIMER_OFFSET_MS = 125_000L
        const val PADDING_DP = 24
        const val BUTTON_GAP_DP = 12
        const val SECTION_GAP_DP = 28
        const val SECTION_TITLE_SP = 18f
    }
}
