package me.timschneeberger.rootlessjamesdsp.session.dump.data

import me.timschneeberger.rootlessjamesdsp.model.AudioSessionDumpEntry
import me.timschneeberger.rootlessjamesdsp.session.dump.utils.AudioFlingerServiceDumpUtils

interface ISessionInfoDump : IDump {
    val sessions: HashMap<Int /* sid */, AudioSessionDumpEntry>

    /**
     * Global session refs из media.audio_flinger (полные строки: sid, pid,
     * uid; включая наши якоря — они признак живой цепочки). null = данных
     * audioflinger нет
     */
    var audioFlingerPlayerSids: List<AudioFlingerServiceDumpUtils.Dataset>?
}