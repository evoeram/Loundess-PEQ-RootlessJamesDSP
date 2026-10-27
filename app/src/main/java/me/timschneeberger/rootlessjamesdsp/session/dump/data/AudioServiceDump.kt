package me.timschneeberger.rootlessjamesdsp.session.dump.data

import me.timschneeberger.rootlessjamesdsp.model.AudioSessionDumpEntry
import me.timschneeberger.rootlessjamesdsp.session.dump.utils.AudioFlingerServiceDumpUtils

data class AudioServiceDump(
    override val sessions: HashMap<Int /* sid */, AudioSessionDumpEntry>,
    override var audioFlingerPlayerSids: List<AudioFlingerServiceDumpUtils.Dataset>? = null,
) :
    ISessionInfoDump
{
    override fun toString(): String {
        val sb = StringBuilder("\n--> Session stack\n")
        sessions.forEach { (key, value) ->
            sb.append("sid=$key\t-> $value\n")
        }
        return sb.toString()
    }
}