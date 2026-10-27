package me.timschneeberger.rootlessjamesdsp.model

import android.media.AudioAttributes

data class AudioSessionDumpEntry(val uid: Int, val packageName: String, val usage: String, val content: String)
{
    override fun toString(): String {
        return "uid=$uid; package=$packageName; usage=$usage; content=$content; isUsageRecordable=${isUsageRecordable(usage)}"
    }

    fun isUsageRecordable(): Boolean
    {
        return isUsageRecordable(usage)
    }

    companion object
    {
        private const val USAGE_UNKNOWN = "USAGE_UNKNOWN"
        private const val USAGE_MEDIA = "USAGE_MEDIA"
        private const val USAGE_GAME = "USAGE_GAME"

        fun isUsageRecordable(usage: String): Boolean
        {
            val u = usage.uppercase().trim()
            return u.contains(USAGE_UNKNOWN) || u.contains(USAGE_MEDIA) || u.contains(USAGE_GAME)
        }

        // Формат как в dumpsys audio, чтобы переиспользовать isUsageRecordable
        fun usageString(usage: Int): String = when (usage) {
            AudioAttributes.USAGE_MEDIA -> USAGE_MEDIA
            AudioAttributes.USAGE_GAME -> USAGE_GAME
            AudioAttributes.USAGE_UNKNOWN -> USAGE_UNKNOWN
            else -> "USAGE_UNSUPPORTED"
        }
    }
}