package me.timschneeberger.rootlessjamesdsp.service

import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import me.timschneeberger.rootlessjamesdsp.smoothvolume.SmoothVolumeController
import me.timschneeberger.rootlessjamesdsp.smoothvolume.SmoothVolumeHudConfig
import me.timschneeberger.rootlessjamesdsp.smoothvolume.SmoothVolumeHudManager
import me.timschneeberger.rootlessjamesdsp.utils.preferences.Preferences
import org.koin.android.ext.android.inject
import timber.log.Timber

/**
 * Fallback host for the Smooth Volume Mini-HUD.
 *
 * When the accessibility service is NOT connected but the app holds
 * SYSTEM_ALERT_WINDOW, this (plain, same-process) started service attaches the
 * HUD as a TYPE_APPLICATION_OVERLAY window so the HUD still works without
 * accessibility. Hardware-key capture is not available in this mode — that
 * part genuinely requires the accessibility service.
 *
 * Lifecycle:
 *  - started by the app UI when the HUD is enabled, and by
 *    [VolumeKeyAccessibilityService] when it unbinds while the HUD is enabled;
 *  - collects the controller state; attaches/detaches the HUD window as
 *    availability changes;
 *  - stops itself when the HUD is disabled, the permission is missing, or the
 *    accessibility service took over hosting (no window duplication).
 */
class SmoothVolumeHudService : android.app.Service() {

    private val controller: SmoothVolumeController by inject()
    private val hudManager: SmoothVolumeHudManager by inject()
    private val preferences: Preferences.App by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var collectJob: kotlinx.coroutines.Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.i("SmoothVolumeHudService started")
        if (collectJob == null) {
            collectJob = scope.launch {
                controller.state.collect { evaluate(it) }
            }
        }
        evaluate(controller.state.value)
        // START_NOT_STICKY: with START_STICKY the system restarted this service
        // right after it stopped itself (accessibility host active), creating an
        // endless start/stop loop every few seconds.
        return START_NOT_STICKY
    }

    private fun evaluate(state: SmoothVolumeController.SmoothVolumeState) {
        val hudEnabled = SmoothVolumeHudConfig.fromPreferences(preferences).enabled
        val canOverlay = Settings.canDrawOverlays(this)

        when {
            // Accessibility service owns the HUD: no duplicated window
            state.accessibilityAvailable -> stop("accessibility host active")

            !hudEnabled -> stop("HUD disabled")

            !canOverlay -> stop("no overlay permission")

            else -> {
                controller.setOverlayPermissionAvailable(true)
                if (!hudManager.isAttached) {
                    hudManager.attach(this, useAccessibilityOverlay = false)
                    Timber.i("HUD attached via fallback overlay host")
                }
            }
        }
    }

    private fun stop(reason: String) {
        // Detach only when this service owns the window (fallback application
        // overlay). The accessibility host manages its own window and must not
        // be torn down from here — earlier this detached the accessibility
        // host's HUD whenever this service stopped, silently killing the HUD.
        if (hudManager.isAttachedAsApplicationOverlay) {
            hudManager.detach()
        }
        Timber.i("SmoothVolumeHudService stopping: %s", reason)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        // Detach only if this service owns the window; the accessibility host
        // manages its own window and must not be torn down from here.
        if (!controller.state.value.accessibilityAvailable) {
            hudManager.detach()
        }
        Timber.i("SmoothVolumeHudService destroyed")
        super.onDestroy()
    }

    companion object {
        /** Convenience starter used by the app UI. */
        fun start(context: Context) {
            context.startService(Intent(context, SmoothVolumeHudService::class.java))
        }
    }
}
