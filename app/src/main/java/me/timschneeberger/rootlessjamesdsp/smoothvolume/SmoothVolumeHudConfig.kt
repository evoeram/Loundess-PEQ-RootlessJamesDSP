package me.timschneeberger.rootlessjamesdsp.smoothvolume

import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.utils.preferences.Preferences

enum class HudOrientation { VERTICAL, HORIZONTAL;
    companion object { fun from(v: String) = if (v == "horizontal") HORIZONTAL else VERTICAL } }

enum class HudStyle { COMPACT, EXPANDED;
    companion object { fun from(v: String) = if (v == "expanded") EXPANDED else COMPACT } }

enum class HudPosition { LEFT, RIGHT, TOP, BOTTOM, CUSTOM;
    companion object { fun from(v: String) = entries.firstOrNull { it.name.lowercase() == v } ?: RIGHT } }

enum class HudSize { SMALL, MEDIUM, LARGE;
    companion object { fun from(v: String) = entries.firstOrNull { it.name.lowercase() == v } ?: MEDIUM } }

enum class HudTheme { SYSTEM, DARK, LIGHT;
    companion object { fun from(v: String) = entries.firstOrNull { it.name.lowercase() == v } ?: SYSTEM } }

enum class HudCornerRadius { SMALL, MEDIUM, LARGE;
    companion object { fun from(v: String) = entries.firstOrNull { it.name.lowercase() == v } ?: MEDIUM } }

enum class HudAutoHide(val seconds: Int) { NEVER(0), S1(1), S2(2), S3(3), S5(5), S10(10);
    companion object { fun from(v: String) = entries.firstOrNull { v == it.seconds.toString() || (v == "never" && it == NEVER) } ?: S2 } }

enum class HudHaptics { OFF, LIGHT, STANDARD;
    companion object { fun from(v: String) = entries.firstOrNull { it.name.lowercase() == v } ?: STANDARD } }

enum class HudTapAction { NONE, EXPAND, MUTE, RESET, OPEN_APP;
    companion object { fun from(v: String) = entries.firstOrNull { it.name.lowercase() == v } ?: EXPAND } }

/** Scale factor applied to all HUD dimensions. */
fun HudSize.scale(): Float = when (this) {
    HudSize.SMALL -> 0.8f
    HudSize.MEDIUM -> 1.0f
    HudSize.LARGE -> 1.25f
}

fun HudCornerRadius.radiusDp(): Float = when (this) {
    HudCornerRadius.SMALL -> 8f
    HudCornerRadius.MEDIUM -> 16f
    HudCornerRadius.LARGE -> 28f
}

/**
 * HUD appearance/behavior configuration. Loaded from the App preferences
 * (separate keys from the persisted gain, per spec) and passed to the HUD
 * view; the settings page writes these keys and the live preview rebuilds
 * the config from the same loader.
 */
data class SmoothVolumeHudConfig(
    val enabled: Boolean,
    val orientation: HudOrientation,
    val style: HudStyle,
    val position: HudPosition,
    val size: HudSize,
    val opacityPercent: Int,
    val theme: HudTheme,
    val cornerRadius: HudCornerRadius,
    val autoHide: HudAutoHide,
    val showOnHardwareKeys: Boolean,
    val showOnAppVolumeChanges: Boolean,
    val showOnHudInteraction: Boolean,
    val haptics: HudHaptics,
    val animations: Boolean,
    val tapAction: HudTapAction,
    val doubleTapAction: HudTapAction,
    val longPressAction: HudTapAction,
    val swipeStepDb: Double,
    val swipeAcceleration: Boolean,
    val reverseSwipe: Boolean,
) {
    companion object {
        fun fromPreferences(prefs: Preferences.App): SmoothVolumeHudConfig = SmoothVolumeHudConfig(
            enabled = prefs.get(R.string.key_smooth_volume_hud_enabled),
            orientation = HudOrientation.from(prefs.get(R.string.key_smooth_volume_hud_orientation)),
            style = HudStyle.from(prefs.get(R.string.key_smooth_volume_hud_style)),
            position = HudPosition.from(prefs.get(R.string.key_smooth_volume_hud_position)),
            size = HudSize.from(prefs.get(R.string.key_smooth_volume_hud_size)),
            opacityPercent = prefs.get(R.string.key_smooth_volume_hud_opacity),
            theme = HudTheme.from(prefs.get(R.string.key_smooth_volume_hud_theme)),
            cornerRadius = HudCornerRadius.from(prefs.get(R.string.key_smooth_volume_hud_corner_radius)),
            autoHide = HudAutoHide.from(prefs.get(R.string.key_smooth_volume_hud_auto_hide)),
            showOnHardwareKeys = prefs.get(R.string.key_smooth_volume_hud_show_on_keys),
            showOnAppVolumeChanges = prefs.get(R.string.key_smooth_volume_hud_show_on_app),
            showOnHudInteraction = prefs.get(R.string.key_smooth_volume_hud_show_on_hud),
            haptics = HudHaptics.from(prefs.get(R.string.key_smooth_volume_hud_haptics)),
            animations = prefs.get(R.string.key_smooth_volume_hud_animations),
            tapAction = HudTapAction.from(prefs.get(R.string.key_smooth_volume_hud_action_tap)),
            doubleTapAction = HudTapAction.from(prefs.get(R.string.key_smooth_volume_hud_action_double_tap)),
            longPressAction = HudTapAction.from(prefs.get(R.string.key_smooth_volume_hud_action_long_press)),
            swipeStepDb = prefs.get<String>(R.string.key_smooth_volume_hud_swipe_step).toDoubleOrNull() ?: 0.5,
            swipeAcceleration = prefs.get(R.string.key_smooth_volume_hud_swipe_accel),
            reverseSwipe = prefs.get(R.string.key_smooth_volume_hud_reverse),
        )
    }
}
