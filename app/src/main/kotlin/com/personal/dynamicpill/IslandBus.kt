package com.personal.dynamicpill

/** Receives events from the data sources; implemented by the overlay service. */
interface IslandSink {
    /** A short-lived event such as a notification. */
    fun onNotification(content: IslandContent)

    /** Adds or updates an ongoing activity, such as a call or timer. */
    fun onLiveActivity(content: IslandContent)

    fun onLiveActivityEnded(id: String)

    fun onMedia(state: MediaState?)

    /** Shows sample content so the layout can be tuned from the settings screen. */
    fun onPreview(content: IslandContent, expanded: Boolean)
}

/**
 * Connects the notification listener (producer) to the accessibility service (overlay host).
 * Everything runs on the main thread, so no synchronization is needed.
 */
object IslandBus {
    /** Connection state, shown on the health screen. */
    var listenerConnected = false

    private var latestMedia: MediaState? = null
    private val liveActivities = LinkedHashMap<String, IslandContent>()

    /** Setting the sink replays the current media and live activities so a late-starting service catches up. */
    var sink: IslandSink? = null
        set(value) {
            field = value
            value?.onMedia(latestMedia)
            value?.let { target -> liveActivities.values.forEach(target::onLiveActivity) }
        }

    fun publishNotification(content: IslandContent) {
        sink?.onNotification(content)
    }

    fun publishLive(content: IslandContent) {
        liveActivities[content.id] = content
        sink?.onLiveActivity(content)
    }

    fun endLive(id: String) {
        if (liveActivities.remove(id) != null) sink?.onLiveActivityEnded(id)
    }

    fun clearLive() {
        liveActivities.keys.toList().forEach(::endLive)
    }

    fun publishPreview(content: IslandContent, expanded: Boolean) {
        sink?.onPreview(content, expanded)
    }

    fun publishMedia(state: MediaState?) {
        latestMedia = state
        sink?.onMedia(state)
    }
}
