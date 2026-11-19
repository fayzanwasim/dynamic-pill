package com.personal.dynamicpill

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.media.session.MediaController
import android.media.session.PlaybackState

/** A small piece of glanceable information shown beside the camera when the pill is compact. */
sealed interface Glance {
    data object None : Glance

    data class Icon(val drawable: Drawable?) : Glance

    data class Text(val text: CharSequence) : Glance

    /** A live timer; [baseElapsedRealtime] follows the semantics of [android.widget.Chronometer.setBase]. */
    data class Timer(val baseElapsedRealtime: Long, val countDown: Boolean) : Glance

    /** Equalizer bars. */
    data object Bars : Glance

    /** A cartoon eye that looks around and blinks, for the idle pill. */
    data object Eye : Glance
}

/** Decorations the idle pill can show when nothing else is going on. Order matches the settings slider. */
enum class IdleWidget { NONE, EYES, CLOCK, DATE, BATTERY, GLOW, NEXT_ALARM, ORBIT, CHOMP, HEARTBEAT, MOON, WEEKEND }

/** Lower numbers are shown first when several live activities compete for the pill. */
object IslandPriority {
    const val CALL = 0
    const val TIMER = 20
    const val NAVIGATION = 30
    const val MEDIA = 40
    const val OTHER = 50
}

/** Something the pill can show, in both its compact and expanded form. */
data class IslandContent(
    val id: String,
    val icon: Drawable?,
    val title: CharSequence,
    val subtitle: CharSequence,
    val glance: Glance = Glance.None,
    val progress: Int? = null,
    val priority: Int = IslandPriority.OTHER,
    val expandOnArrival: Boolean = false,
    /** Stays expanded until the user dismisses it, instead of collapsing after a delay. */
    val persistent: Boolean = false,
    val holdSeconds: Int? = null,
    val actions: List<IslandAction> = emptyList(),
    /** Optional second row of labelled buttons, e.g. a player's own "Like" or "Shuffle" actions. */
    val secondaryActions: List<IslandAction> = emptyList(),
    /** Playback position for a seek bar with elapsed and total time (music). */
    val media: MediaProgress? = null,
    /** Overrides the accent color for this content, e.g. a color taken from album art. */
    val accent: Int? = null,
    /** Package that produced this content, used to ignore notifications from the app in the foreground. */
    val sourcePackage: String? = null,
    /**
     * What to show instead while the phone is locked (just the app, no sender or message), or null when the
     * content is safe to show as is. Follows the notification's own lock-screen visibility.
     */
    val lockedVersion: IslandContent? = null,
    val onTap: (() -> Unit)? = null,
)

/**
 * A playback position that advances by itself: the position at [elapsedRealtimeAtPosition] was
 * [positionMs], and it moves at [speed] (0 while paused).
 */
data class MediaProgress(
    val durationMs: Long,
    val positionMs: Long,
    val speed: Float,
    val elapsedRealtimeAtPosition: Long,
    val onSeek: (Long) -> Unit,
)

enum class ActionStyle { NEUTRAL, POSITIVE, DESTRUCTIVE }

/** A button in the expanded pill: an icon button when [iconRes] is set, otherwise a labelled one. */
data class IslandAction(
    val iconRes: Int? = null,
    val label: CharSequence? = null,
    val style: ActionStyle = ActionStyle.NEUTRAL,
    val onClick: () -> Unit,
)

data class MediaState(
    val key: String,
    val title: String,
    val artist: String,
    val art: Bitmap?,
    val isPlaying: Boolean,
    val controller: MediaController,
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val speed: Float = 0f,
    val positionUpdatedAt: Long = 0L,
    val customActions: List<PlaybackState.CustomAction> = emptyList(),
)

/** Pixel measurements and look derived from the device's camera cutout, screen and user settings. */
class IslandMetrics(
    val cutoutWidth: Int,
    val cutoutBottom: Int,
    val offsetX: Int,
    val offsetY: Int,
    val gap: Int,
    val compactSide: Int,
    /** Fixed side width of the idle pill, or 0 to fit its decoration. */
    val idleSide: Int,
    val compactRadius: Int,
    /** Space between the pill's outer edge and the icon (left) or visualizer (right). */
    val edgePadding: Int,
    val expandedWidth: Int,
    val expandedRadius: Int,
    val accentColor: Int,
    val bounciness: Int,
    /** Animation speed as a percentage; 100 is the baseline, lower is slower. */
    val animationSpeed: Int,
    val haptics: Boolean,
) {
    val compactHeight: Int = cutoutBottom + gap
    val restingWidth: Int = cutoutWidth + 2 * gap
}
