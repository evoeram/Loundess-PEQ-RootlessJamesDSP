package me.timschneeberger.rootlessjamesdsp.model.rootless

import android.media.audiofx.AudioEffect
import me.timschneeberger.rootlessjamesdsp.model.IEffectSession

/**
 * @param audioMuteEffect mute-эффект или DynamicsProcessing (в Movie Mode)
 */
data class MutedEffectSession(
    override var uid: Int,
    override var packageName: String,
    var audioMuteEffect: AudioEffect?,
) : IEffectSession {
    override fun toString(): String {
        return "package=$packageName; uid=$uid"
    }
}
