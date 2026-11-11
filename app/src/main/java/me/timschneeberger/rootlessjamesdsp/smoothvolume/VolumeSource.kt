package me.timschneeberger.rootlessjamesdsp.smoothvolume

/**
 * Where a Smooth Volume change originated. Used for HUD show-on-* filters
 * and for debugging; every input path funnels into [SmoothVolumeController].
 */
enum class VolumeSource {
    HARDWARE_KEY,
    HUD,
    APP,
    QUICK_SETTINGS,
    SYSTEM,
}
