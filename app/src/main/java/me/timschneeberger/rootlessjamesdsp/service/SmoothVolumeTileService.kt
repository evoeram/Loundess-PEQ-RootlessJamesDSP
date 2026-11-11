package me.timschneeberger.rootlessjamesdsp.service

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.smoothvolume.SmoothVolumeController
import me.timschneeberger.rootlessjamesdsp.smoothvolume.SmoothVolumeMath
import me.timschneeberger.rootlessjamesdsp.smoothvolume.VolumeSource
import org.koin.core.context.GlobalContext
import timber.log.Timber

/**
 * Quick Settings tile: toggles Smooth Volume mute. Integrated with the shared
 * [SmoothVolumeController]; its absence never blocks the rest of the feature.
 * Tapping the tile while Smooth Volume is disabled enables the feature first
 * and then mutes.
 */
class SmoothVolumeTileService : TileService() {

    private val controller: SmoothVolumeController? by lazy {
        try {
            GlobalContext.get().get<SmoothVolumeController>()
        } catch (e: Exception) {
            Timber.w(e, "Tile: controller unavailable")
            null
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val controller = controller ?: return
        if (!controller.state.value.enabled) {
            controller.setEnabled(true)
        }
        controller.toggleMute(VolumeSource.QUICK_SETTINGS)
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val state = controller?.state
        if (state == null) {
            tile.state = Tile.STATE_UNAVAILABLE
            tile.updateTile()
            return
        }
        tile.state = when {
            !state.value.enabled -> Tile.STATE_INACTIVE
            state.value.muted -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.subtitle = if (state.value.enabled)
            SmoothVolumeMath.formatDb(state.value.gainDb)
        else
            getString(R.string.smooth_volume_status_inactive)
        tile.updateTile()
    }
}
