package com.personal.dynamicpill

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile that switches the always-on idle pill on and off. */
class AlwaysOnTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        val settings = IslandSettings(this)
        settings.set(IslandSetting.ALWAYS_ON, if (settings.isOn(IslandSetting.ALWAYS_ON)) 0 else 1)
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        tile.state = if (IslandSettings(this).isOn(IslandSetting.ALWAYS_ON)) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }
}
