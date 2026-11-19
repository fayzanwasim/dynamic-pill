package com.personal.dynamicpill

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.drawable.BitmapDrawable
import android.hardware.display.DisplayManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import kotlin.math.min

/**
 * Hosts the pill as an accessibility overlay window positioned over the camera cutout and decides
 * what it shows. Ongoing activities ("live" ones: calls, timers, media...) stay on the pill in priority
 * order, short-lived events (notifications, charging) take over temporarily.
 */
class IslandAccessibilityService : AccessibilityService(), IslandSink {

    private val handler = Handler(Looper.getMainLooper())
    private val settleRunnable = Runnable { settle() }
    private val dropPausedMediaRunnable = Runnable { dropPausedMedia() }
    private val chargingRunnable = Runnable { showSystemEvent(systemEvents.chargingEvent()) }
    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_POWER_CONNECTED) {
                // Give the charging current a moment to ramp up so the wattage reading is meaningful.
                handler.removeCallbacks(chargingRunnable)
                handler.postDelayed(chargingRunnable, CHARGING_MEASURE_DELAY_MS)
                return
            }
            val event = systemEvents.eventFor(intent)
            if (event != null) showSystemEvent(event) else render()
        }
    }
    private val idleTickRunnable = Runnable { render() }
    private val bubbleTimeoutRunnable = Runnable { clearBubble() }
    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> onSettingsChanged(key) }

    // Registering reports every output already connected; only additions after that are real connections.
    private var audioDevicesReported = false
    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            if (!audioDevicesReported) {
                audioDevicesReported = true
                return
            }
            addedDevices.firstNotNullOfOrNull(systemEvents::outputConnectedEvent)?.let(::showSystemEvent)
        }
    }

    private lateinit var windowManager: WindowManager
    private lateinit var settings: IslandSettings
    private lateinit var rules: AppRules
    private lateinit var keyguardManager: KeyguardManager
    private lateinit var powerManager: PowerManager
    private lateinit var systemEvents: SystemEvents
    private lateinit var idleDecorations: IdleDecorations
    private lateinit var ignoredForegroundPackages: Set<String>

    private var connected = false
    private var island: IslandView? = null
    private var bubbleHost: BubbleHost? = null
    private var bubbleContent: IslandContent? = null
    private var bubbleCount = 0
    private val pendingTransients = ArrayDeque<IslandContent>()
    private var media: MediaState? = null
    private var lastPreview: Pair<IslandContent, Boolean>? = null

    private val liveActivities = LinkedHashMap<String, IslandContent>()
    private var transient: IslandContent? = null
    private var transientExpanded = false
    private var liveExpanded = false
    private var focusedId: String? = null
    private var userDismissed = false
    private var foregroundPackage: String? = null

    override fun onCreate() {
        super.onCreate()
        keyguardManager = getSystemService(KeyguardManager::class.java)
        powerManager = getSystemService(PowerManager::class.java)
        settings = IslandSettings(this)
        systemEvents = SystemEvents(this, settings)
        idleDecorations = IdleDecorations(this)
        rules = AppRules(this)
        ignoredForegroundPackages = IGNORED_FOREGROUND_PACKAGES + packageName + inputMethodPackages()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // The overlay-capable WindowManager is only available once the system has connected the service.
        windowManager = getSystemService(WindowManager::class.java)
        settings.registerListener(settingsListener)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        systemEvents.addActionsTo(filter)
        registerReceiver(systemReceiver, filter, RECEIVER_NOT_EXPORTED)
        audioDevicesReported = false
        getSystemService(AudioManager::class.java).registerAudioDeviceCallback(audioDeviceCallback, handler)
        connected = true
        attachIsland()
        IslandBus.sink = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        // The system can unbind a service that never finished connecting, e.g. while the app is reinstalled.
        if (connected) {
            connected = false
            IslandBus.sink = null
            settings.unregisterListener(settingsListener)
            unregisterReceiver(systemReceiver)
            getSystemService(AudioManager::class.java).unregisterAudioDeviceCallback(audioDeviceCallback)
            detachIsland()
        }
        return super.onUnbind(intent)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        attachIsland()
        render()
    }

    /** Tracks the foreground app so apps marked "hide while open" (games, video) can suppress the pill. */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val eventPackage = event.packageName?.toString() ?: return
        if (eventPackage in ignoredForegroundPackages || eventPackage == foregroundPackage) return
        foregroundPackage = eventPackage
        render()
    }

    // The service has nothing to interrupt: it only hosts the overlay window.
    override fun onInterrupt() = Unit

    override fun onNotification(content: IslandContent) {
        if (settings.isOn(IslandSetting.IGNORE_FOREGROUND_APP) && content.sourcePackage == foregroundPackage) {
            EventLog.record(appLabel(content.sourcePackage), "skipped", "you are using this app right now")
            return
        }
        when {
            canShowAsBubble(content) -> showBubble(content)
            transient != null && transient?.id != content.id -> enqueue(content)
            else -> showTransient(content, content.expandOnArrival, holdMs(content))
        }
    }

    /** A notification that arrives while another is on the pill waits its turn instead of replacing it. */
    private fun enqueue(content: IslandContent) {
        pendingTransients.removeAll { it.id == content.id }
        pendingTransients.addLast(content)
        while (pendingTransients.size > MAX_PENDING) pendingTransients.removeFirst()
    }

    private fun appLabel(packageName: String?): String {
        val name = packageName ?: return ""
        return runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(name, 0)).toString() }
            .getOrDefault(name)
    }

    /** Like Apple's island: a new event arriving while an activity owns the compact pill pops out as a bubble. */
    private fun canShowAsBubble(content: IslandContent): Boolean =
        bubbleHost != null &&
            !content.expandOnArrival &&
            transient == null &&
            !liveExpanded &&
            !userDismissed &&
            !isSuppressed() &&
            currentLive() != null

    private fun showBubble(content: IslandContent) {
        val pill = island ?: return
        bubbleCount = if (bubbleContent == null) 1 else bubbleCount + 1
        bubbleContent = content
        bubbleHost?.show(forDisplay(content).icon, pill.compactWidthPx, bubbleCount)
        handler.removeCallbacks(bubbleTimeoutRunnable)
        handler.postDelayed(bubbleTimeoutRunnable, holdMs(content))
    }

    private fun clearBubble() {
        handler.removeCallbacks(bubbleTimeoutRunnable)
        if (bubbleContent == null) return
        bubbleContent = null
        bubbleCount = 0
        bubbleHost?.hide()
    }

    /** Tapping the bubble promotes its event to the pill, expanded. */
    private fun onBubbleClicked() {
        val content = bubbleContent ?: return
        clearBubble()
        showTransient(content, expanded = true, holdMs = TAP_HOLD_MS)
    }

    /** Keeps the bubble in step with the pill: it goes away when the pill is busy and follows its width. */
    private fun syncBubble() {
        if (bubbleContent == null) return
        val pill = island
        if (pill == null || transient != null || liveExpanded || userDismissed || currentLive() == null || isSuppressed()) {
            clearBubble()
        } else {
            bubbleHost?.reposition(pill.compactWidthPx)
        }
    }

    override fun onPreview(content: IslandContent, expanded: Boolean) {
        lastPreview = content to expanded
        showTransient(content, expanded, PREVIEW_HOLD_MS)
    }

    override fun onLiveActivity(content: IslandContent) {
        val isNew = !liveActivities.containsKey(content.id)
        liveActivities[content.id] = content
        userDismissed = false
        if (isNew && content.expandOnArrival) {
            focusedId = content.id
            liveExpanded = true
        }
        render()
    }

    override fun onLiveActivityEnded(id: String) {
        removeLive(id)
        render()
    }

    override fun onMedia(state: MediaState?) {
        val previous = media
        media = state
        handler.removeCallbacks(dropPausedMediaRunnable)
        when {
            state == null -> removeLive(MEDIA_ID)
            state.isPlaying || previous?.isPlaying == true -> {
                liveActivities[MEDIA_ID] = mediaContent(state)
                if (previous?.key != state.key || previous.isPlaying != state.isPlaying) userDismissed = false
                if (!state.isPlaying) handler.postDelayed(dropPausedMediaRunnable, PAUSED_MEDIA_KEEP_MS)
            }
            else -> removeLive(MEDIA_ID)
        }
        render()
    }

    private fun removeLive(id: String) {
        liveActivities.remove(id)
        if (focusedId == id) focusedId = null
        if (liveActivities.isEmpty()) liveExpanded = false
    }

    private fun dropPausedMedia() {
        if (media?.isPlaying == true) return
        removeLive(MEDIA_ID)
        render()
    }

    private fun showTransient(content: IslandContent, expanded: Boolean, holdMs: Long) {
        transient = content
        transientExpanded = expanded
        userDismissed = false
        scheduleSettle(holdMs)
        render()
    }

    /**
     * System HUD events (volume, ringer, charging...) take over the pill immediately; they are brief.
     * A notification they interrupt goes back to the front of the queue instead of being lost.
     */
    private fun showSystemEvent(event: SystemEvents.Event) {
        val interrupted = transient
        if (interrupted?.sourcePackage != null && interrupted.id != event.content.id) {
            pendingTransients.addFirst(interrupted)
        }
        showTransient(event.content, expanded = false, holdMs = event.holdMs)
    }

    private fun mediaContent(state: MediaState): IslandContent {
        val controls = state.controller.transportControls
        val playPause = if (state.isPlaying) {
            IslandAction(android.R.drawable.ic_media_pause) { controls.pause() }
        } else {
            IslandAction(android.R.drawable.ic_media_play) { controls.play() }
        }
        val device = outputDeviceName()
        return IslandContent(
            id = MEDIA_ID,
            icon = state.art?.let { BitmapDrawable(resources, it) } ?: getDrawable(android.R.drawable.ic_media_play),
            title = state.title,
            subtitle = listOf(state.artist, device.orEmpty()).filter { it.isNotEmpty() }.joinToString(SUBTITLE_SEPARATOR),
            glance = if (state.isPlaying) Glance.Bars else Glance.None,
            priority = IslandPriority.MEDIA,
            accent = state.art?.let(::accentFrom),
            actions = listOf(
                IslandAction(android.R.drawable.ic_media_previous) { controls.skipToPrevious() },
                playPause,
                IslandAction(android.R.drawable.ic_media_next) { controls.skipToNext() },
            ),
            secondaryActions = state.customActions.take(MAX_CUSTOM_ACTIONS).map { action ->
                IslandAction(label = action.name) { controls.sendCustomAction(action, null) }
            },
            media = state.durationMs.takeIf { it > 0 }?.let { duration ->
                MediaProgress(
                    durationMs = duration,
                    positionMs = state.positionMs,
                    speed = if (state.isPlaying) state.speed else 0f,
                    elapsedRealtimeAtPosition = state.positionUpdatedAt,
                    onSeek = controls::seekTo,
                )
            },
            onTap = { state.controller.sessionActivity?.sendFromOverlay(this) },
        )
    }

    /** Name of the headphones or speaker audio is going to, or null for the phone's own speaker. */
    private fun outputDeviceName(): String? =
        getSystemService(AudioManager::class.java)
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type in SystemEvents.EXTERNAL_OUTPUT_TYPES }
            ?.productName
            ?.toString()

    private fun render() {
        drawPill()
        syncBubble()
    }

    /** Draws whatever should currently be on the pill from the transient event and live activities. */
    private fun drawPill() {
        val view = island ?: return
        val ordered = liveActivities.values.sortedBy { it.priority }
        val primaryLive = ordered.firstOrNull { it.id == focusedId } ?: ordered.firstOrNull()
        val target = transient ?: primaryLive.takeUnless { userDismissed }
        val suppressed = isSuppressed()
        if (target == null || (suppressed && target.priority != IslandPriority.CALL)) {
            if (target == null && !suppressed && settings.isOn(IslandSetting.ALWAYS_ON)) showIdle(view) else view.hide()
            return
        }
        handler.removeCallbacks(idleTickRunnable)
        if (transient != null) {
            view.show(forDisplay(target), transientExpanded)
        } else {
            val secondary = ordered.firstOrNull { it.id != target.id }
            view.show(forDisplay(target), liveExpanded, secondary?.let(::forDisplay).takeUnless { liveExpanded })
        }
    }

    /** Shows the always-on resting pill with the chosen decoration, refreshing it while it shows changing info. */
    private fun showIdle(view: IslandView) {
        val decoration = idleDecorations.forWidget(IdleWidget.entries[settings.get(IslandSetting.IDLE_WIDGET)])
        view.showIdle(decoration.left, decoration.right, decoration.effect)
        handler.removeCallbacks(idleTickRunnable)
        if (decoration.ticking) handler.postDelayed(idleTickRunnable, IDLE_TICK_MS)
    }

    /** While the phone is locked, private notifications show only their app; everything else is unchanged. */
    private fun forDisplay(content: IslandContent): IslandContent =
        if (keyguardManager.isKeyguardLocked) content.lockedVersion ?: content else content

    /** The pill steps aside while the screen is off (saves battery), on the lock screen if chosen, and for chosen apps. */
    private fun isSuppressed(): Boolean =
        !powerManager.isInteractive ||
            rules.get(foregroundPackage).mode == AppMode.HIDE_WHILE_OPEN ||
            (!settings.isOn(IslandSetting.SHOW_ON_LOCKSCREEN) && keyguardManager.isKeyguardLocked)

    private fun holdMs(content: IslandContent): Long =
        if (content.persistent) 0L else (content.holdSeconds ?: settings.get(IslandSetting.HOLD_SECONDS)) * MS_PER_SECOND

    private fun scheduleSettle(delayMs: Long) {
        handler.removeCallbacks(settleRunnable)
        if (delayMs > 0) handler.postDelayed(settleRunnable, delayMs)
    }

    /** Ends the temporary state: drops the transient event, collapses any expanded pill and shows the next queued one. */
    private fun settle() {
        // A finished preview should not be replayed by a later, unrelated settings change.
        if (transient != null && transient?.id == lastPreview?.first?.id) lastPreview = null
        transient = null
        transientExpanded = false
        liveExpanded = false
        val next = pendingTransients.removeFirstOrNull()
        if (next != null) showTransient(next, next.expandOnArrival, holdMs(next)) else render()
    }

    /** Long-press opens whatever the pill is showing in its own app. */
    private fun onPillLongPressed() {
        val content = transient ?: currentLive() ?: return
        content.onTap?.invoke()
        collapseOrDismiss()
        render()
    }

    private fun onPillClicked(state: IslandView.State) {
        if (state == IslandView.State.IDLE) return
        val content = transient ?: currentLive() ?: return
        if (state == IslandView.State.COMPACT) {
            if (transient != null) transientExpanded = true else liveExpanded = true
            scheduleSettle(if (content.persistent) 0L else TAP_HOLD_MS)
        } else {
            content.onTap?.invoke()
            collapseOrDismiss()
        }
        render()
    }

    /** Returns an expanded pill to its compact form, e.g. after the user touches elsewhere on screen. */
    private fun collapseExpanded() {
        if (island?.isExpanded != true) return
        val shown = transient
        if (shown != null) {
            transientExpanded = false
            scheduleSettle(holdMs(shown))
        } else {
            liveExpanded = false
        }
        render()
    }

    private fun collapseOrDismiss() {
        if (transient != null) {
            handler.removeCallbacks(settleRunnable)
            transient = null
            transientExpanded = false
        } else {
            liveExpanded = false
        }
    }

    private fun onSwipe(swipe: IslandView.Swipe) {
        when (swipe) {
            IslandView.Swipe.UP -> dismiss()
            IslandView.Swipe.LEFT -> swipeSideways(forward = true)
            IslandView.Swipe.RIGHT -> swipeSideways(forward = false)
        }
        render()
    }

    private fun dismiss() {
        when {
            transient != null -> collapseOrDismiss()
            liveExpanded -> liveExpanded = false
            else -> userDismissed = true
        }
    }

    /** Skips tracks while the music card is open, otherwise cycles through the live activities. */
    private fun swipeSideways(forward: Boolean) {
        if (transient != null) return
        val current = currentLive() ?: return
        if (liveExpanded && current.id == MEDIA_ID) {
            val controls = media?.controller?.transportControls ?: return
            if (forward) controls.skipToNext() else controls.skipToPrevious()
            return
        }
        val ids = liveActivities.values.sortedBy { it.priority }.map { it.id }
        if (ids.size < 2) return
        val step = if (forward) 1 else ids.size - 1
        focusedId = ids[(ids.indexOf(current.id) + step) % ids.size]
    }

    private fun currentLive(): IslandContent? {
        val ordered = liveActivities.values.sortedBy { it.priority }
        return ordered.firstOrNull { it.id == focusedId } ?: ordered.firstOrNull()
    }

    /**
     * Layout and look settings need the overlay rebuilt (and the last preview replayed so the change is
     * visible live); behavior settings only need a redraw. A null key means everything changed.
     */
    private fun onSettingsChanged(key: String?) {
        if (key != null && key !in LAYOUT_SETTING_KEYS) {
            render()
            return
        }
        attachIsland()
        val preview = lastPreview
        if (preview != null) onPreview(preview.first, preview.second) else render()
    }

    private fun attachIsland() {
        detachIsland()
        val metrics = buildMetrics()
        if (metrics == null) {
            Log.w(TAG, "No top camera cutout in this orientation; the pill is disabled")
            return
        }
        val view = IslandView(this, metrics).apply {
            visibility = View.INVISIBLE
            onPillClick = ::onPillClicked
            onOutsideTouch = ::collapseExpanded
            onSwipe = ::onSwipe
            onLongPress = ::onPillLongPressed
        }
        windowManager.addView(view, overlayParams(metrics))
        island = view
        bubbleHost = BubbleHost(this, windowManager, metrics, ::onBubbleClicked)
    }

    private fun detachIsland() {
        handler.removeCallbacks(settleRunnable)
        handler.removeCallbacks(idleTickRunnable)
        handler.removeCallbacks(bubbleTimeoutRunnable)
        bubbleHost?.release()
        bubbleHost = null
        bubbleContent = null
        island?.let { windowManager.removeView(it) }
        island = null
    }

    private fun inputMethodPackages(): Set<String> =
        getSystemService(InputMethodManager::class.java).enabledInputMethodList.map { it.packageName }.toSet()

    private fun buildMetrics(): IslandMetrics? {
        val bounds = windowManager.currentWindowMetrics.bounds
        if (bounds.width() > bounds.height()) return null
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val cutout = display.cutout?.boundingRectTop?.takeUnless { it.isEmpty } ?: return null
        return IslandMetrics(
            cutoutWidth = cutout.width(),
            cutoutBottom = cutout.bottom,
            offsetX = cutout.centerX() - bounds.width() / 2 + dp(settings.get(IslandSetting.OFFSET_X)),
            offsetY = dp(settings.get(IslandSetting.OFFSET_Y)),
            gap = dp(settings.get(IslandSetting.PADDING)),
            compactSide = dp(settings.get(IslandSetting.COMPACT_SIDE)),
            idleSide = dp(settings.get(IslandSetting.IDLE_SIDE)),
            compactRadius = dp(settings.get(IslandSetting.COMPACT_RADIUS)),
            edgePadding = dp(settings.get(IslandSetting.EDGE_PADDING)),
            expandedWidth = min(bounds.width() - dp(EXPANDED_MARGIN_DP), dp(settings.get(IslandSetting.EXPANDED_WIDTH))),
            expandedRadius = dp(settings.get(IslandSetting.CORNER_RADIUS)),
            accentColor = IslandPalette.color(settings.get(IslandSetting.ACCENT_COLOR)),
            bounciness = settings.get(IslandSetting.BOUNCINESS),
            haptics = settings.isOn(IslandSetting.HAPTICS),
            animationSpeed = settings.get(IslandSetting.ANIMATION_SPEED),
        )
    }

    private fun overlayParams(metrics: IslandMetrics) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        x = metrics.offsetX
        y = metrics.offsetY
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        title = TAG
    }

    private companion object {
        const val TAG = "DynamicPill"
        const val MEDIA_ID = "media"
        const val CHARGING_MEASURE_DELAY_MS = 1_200L
        const val MAX_PENDING = 5
        const val MAX_CUSTOM_ACTIONS = 2
        const val SUBTITLE_SEPARATOR = " · "
        const val MS_PER_SECOND = 1_000L
        const val PREVIEW_HOLD_MS = 6_000L
        const val PAUSED_MEDIA_KEEP_MS = 5_000L
        const val TAP_HOLD_MS = 5_000L
        const val IDLE_TICK_MS = 20_000L

        // Settings that change the overlay's size, position or look and therefore need it rebuilt.
        val LAYOUT_SETTING_KEYS = setOf(
            IslandSetting.OFFSET_X,
            IslandSetting.OFFSET_Y,
            IslandSetting.COMPACT_SIDE,
            IslandSetting.PADDING,
            IslandSetting.IDLE_SIDE,
            IslandSetting.COMPACT_RADIUS,
            IslandSetting.EDGE_PADDING,
            IslandSetting.EXPANDED_WIDTH,
            IslandSetting.CORNER_RADIUS,
            IslandSetting.BOUNCINESS,
            IslandSetting.ANIMATION_SPEED,
            IslandSetting.HAPTICS,
            IslandSetting.ACCENT_COLOR,
        ).map { it.key }.toSet()
        const val EXPANDED_MARGIN_DP = 24
        val IGNORED_FOREGROUND_PACKAGES = setOf("com.android.systemui", "android")
    }
}
