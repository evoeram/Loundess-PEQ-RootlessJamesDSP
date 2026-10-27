package me.timschneeberger.rootlessjamesdsp.session.shared

import android.content.Context
import android.os.Process
import android.os.Process.myUid
import android.os.SystemClock
import me.timschneeberger.rootlessjamesdsp.model.AudioSessionDumpEntry
import me.timschneeberger.rootlessjamesdsp.model.IEffectSession
import me.timschneeberger.rootlessjamesdsp.session.dump.data.ISessionInfoDump
import me.timschneeberger.rootlessjamesdsp.session.dump.utils.AudioFlingerServiceDumpUtils
import timber.log.Timber

abstract class BaseSessionDatabase(protected val context: Context) {

    val sessionList = hashMapOf<Int, IEffectSession>()
    protected var isDisposing = false
    private val changeCallbacks = mutableListOf<OnSessionChangeListener>()
    private var excludedUids = arrayOf<Int>()

    // Сессии с провалившимся attach (systemui и т.п.): без кэша ретраятся на
    // каждом опросе (~100мс на попытку) и задерживают attach реальных сессий
    protected val failedAttachAt = hashMapOf<Int, Long>()

    // Сессии, заатаченные по быстрому пути (AudioPlaybackCallback, до того как
    // их увидит dumpsys): устаревший дамп не должен их сносить сразу
    protected val fastAttachedAt = hashMapOf<Int, Long>()

    // Сессии, на которые заранее повешен эффект, пока трек плеера не существует
    // («подвешенный» эффект): снимается, когда трек появляется в audioflinger
    protected val preAttachedSids = hashSetOf<Int>()

    // Заранее вешать эффект на мёртвую сессию, чтобы он накрыл следующий трек
    // плеера до первого сэмпла (RootlessSessionDatabase)
    protected open val preAttachOnSessionDeath = false

    // Тихий трек-якорь на sid: удерживает EffectChain сессии живым, пока на
    // сессии нет треков плеера — mute-эффект не уничтожается при release трека
    private val anchors = hashMapOf<Int, SessionAnchor>()

    protected fun isAnchored(sid: Int) = anchors.containsKey(sid)

    // Когда в последний раз в refs audioflinger была ссылка плеера (не наша):
    // основа для освобождения якоря на брошенной сессии
    private val lastPlayerSeenAt = hashMapOf<Int, Long>()

    protected fun isUidExcluded(uid: Int) = excludedUids.contains(uid)
    protected open val excludedPackages = arrayOf(
        context.packageName
    )
    protected abstract fun shouldAcceptSessionDump(id: Int, session: AudioSessionDumpEntry): Boolean
    protected abstract fun shouldAddSession(id: Int, uid: Int, packageName: String): Boolean
    protected abstract fun createSession(id: Int, uid: Int, packageName: String): IEffectSession?
    protected abstract fun onSessionRemoved(item: IEffectSession)

    fun destroy()
    {
        isDisposing = true
        clearSessions()
    }

    fun clearSessions(){
        sessionList.forEach { (_, session) -> onSessionRemoved(session) }
        sessionList.clear()
        anchors.values.forEach { it.release() }
        anchors.clear()
        lastPlayerSeenAt.clear()
    }

    fun update(dump: ISessionInfoDump)
    {
        if(isDisposing) {
            Timber.d("update: SessionDatabase is disposing; ignoring dump")
            return
        }

        val removedSessions = sessionList.filter {
            !dump.sessions.contains(it.key) &&
                // Сессию могли заатачить из конфига плеера раньше, чем dumpsys
                // её увидит: не сносим свежий fast-attach по устаревшему дампу
                SystemClock.elapsedRealtime() - (fastAttachedAt[it.key] ?: 0L) >= FAST_ATTACH_REMOVAL_GRACE_MS
        }
        val addedSessions = dump.sessions.filter {
            !sessionList.contains(it.key) && !excludedUids.contains(it.value.uid)
        }

        Timber.i("[SPL] update: +[${addedSessions.entries.joinToString { "${it.key}:${it.value.packageName}" }}] -[${removedSessions.keys.joinToString()}]")

        addedSessions.forEach next@ {
            val sid = it.key
            val data = it.value
            val name = context.packageManager.getNameForUid(it.value.uid)
            if (data.uid == myUid() || excludedPackages.contains(name)) {
                Timber.d("Skipped session $sid due to package name $name ($data)")
                return@next
            }
            if (sid == 0) {
                Timber.w("Session 0 skipped ($data)")
                return@next
            }

            val lastFail = failedAttachAt[sid]
            if (lastFail != null && SystemClock.elapsedRealtime() - lastFail < FAILED_ATTACH_RETRY_MS) {
                Timber.d("Skipped session $sid: attach failed recently")
                return@next
            }

            if(shouldAcceptSessionDump(sid, data)) {
                addSession(sid, data.uid, data.packageName)
            }
        }

        // dumpsys audio может продолжать числить плеера, чей трек уже выпущен:
        // сессия и наш mute-эффект в этот момент уничтожены, а dumpsys этого
        // не видит — до следующего дампа новый трек играет без mute (SPL-удар).
        // Авторитетный источник — Global session refs из media.audio_flinger:
        // ссылка плеера исчезает вместе с треком (наши якоря тоже числятся —
        // для них это признак живой цепочки). Пока сессия мертва, вешаем
        // свежий эффект: он «подвешен» и накрывает следующий трек до первого
        // сэмпла
        dump.audioFlingerPlayerSids?.let { preAttachDeadSessions(it) }

        val refsSids = dump.audioFlingerPlayerSids?.map { it.sid }?.toSet()
        removedSessions.forEach { (sid, session) ->
            // audioflinger держит ссылку плеера, а dumpsys сессию потерял:
            // сессия жива, mute сносить нельзя — иначе получим разрыв уже на
            // работающем треке
            if (refsSids?.contains(sid) == true) {
                return@forEach
            }
            if (preAttachedSids.contains(sid)) {
                return@forEach
            }

            removeSession(sid)
            if (!preAttachOnSessionDeath) return@forEach

            // Дамп выкинул и саму сессию (а не только ссылку плеера): детект
            // смерти по audioflinger сюда не доходит, т.к. требует запись в
            // dump.sessions. Pre-attach по данным уже проверенной сессии
            val lastFail = failedAttachAt[sid]
            if (lastFail != null && SystemClock.elapsedRealtime() - lastFail < FAILED_ATTACH_RETRY_MS)
                return@forEach

            Timber.i("[SPL] dump dropped sid=$sid; pre-attach fresh mute")
            addSession(sid, session.uid, session.packageName)
            if (sessionList.containsKey(sid)) {
                preAttachedSids.add(sid)
                fastAttachedAt[sid] = SystemClock.elapsedRealtime()
            }
        }
    }

    /**
     * Сессии без ссылки плеера в audioflinger мертвы (сессия и mute-эффект
     * уничтожены framework'ом, controlLost не приходит). На мёртвой сессии
     * создаём свежий mute-эффект: он «подвешен» и автоматически применяется
     * к следующему треку плеера на том же sid до первого сэмпла.
     * Сессии с якорем пропускаются: якорь сам держит цепочку (и ссылку refs),
     * гонка за детекцией смерти им не нужна; брошенные сессии освобождаем
     * по простою
     */
    fun preAttachDeadSessions(rows: List<AudioFlingerServiceDumpUtils.Dataset>) {
        if (isDisposing || !preAttachOnSessionDeath) return
        val now = SystemClock.elapsedRealtime()
        sessionList.keys.toList().forEach { sid ->
            // Ссылка чужого pid = трек плеера существует (наши строки — якоря
            // и собственный выход — дают pid нашего процесса)
            val playerAlive = rows.any { it.sid == sid && it.pid != Process.myPid() }
            if (playerAlive) {
                lastPlayerSeenAt[sid] = now
                preAttachedSids.remove(sid)
                return@forEach
            }
            if (anchors.containsKey(sid)) {
                val idleFor = now - (lastPlayerSeenAt[sid] ?: now)
                if (idleFor > ANCHOR_IDLE_RELEASE_MS) {
                    Timber.i("[SPL] sid=$sid idle ${idleFor}ms; releasing anchored session")
                    removeSession(sid)
                }
                return@forEach
            }
            if (preAttachedSids.contains(sid)) return@forEach
            val lastFail = failedAttachAt[sid]
            if (lastFail != null && now - lastFail < FAILED_ATTACH_RETRY_MS)
                return@forEach
            val session = sessionList[sid] ?: return@forEach

            Timber.i("[SPL] dead session sid=$sid; pre-attach fresh mute")
            removeSession(sid)
            addSession(sid, session.uid, session.packageName)
            if (sessionList.containsKey(sid)) {
                preAttachedSids.add(sid)
                fastAttachedAt[sid] = SystemClock.elapsedRealtime()
            }
        }
    }

    fun addSession(sid: Int, uid: Int, packageName: String, replace: Boolean = false){
        if(!shouldAddSession(sid, uid, packageName)) {
            return
        }

        if(excludedUids.contains(uid)) {
            Timber.d("Rejected session $sid from excluded uid $uid ($packageName)")
            return
        }

        if(replace) {
            // Remove old sessions from package
            sessionList
                .filter { it.value.packageName == packageName }
                .keys
                .forEach(::removeSession)
            changeCallbacks.forEach { it.onSessionChanged(sessionList) }
        }

        Timber.d("Found new session: sid=$sid; $packageName")
        val t0 = SystemClock.elapsedRealtime()
        val session = createSession(sid, uid, packageName)
        if (session == null) {
            failedAttachAt[sid] = SystemClock.elapsedRealtime()
            return
        }
        failedAttachAt.remove(sid)
        sessionList[sid] = session
        lastPlayerSeenAt[sid] = SystemClock.elapsedRealtime()
        if (preAttachOnSessionDeath) {
            // Якорь после эффекта: attach эффекта может инвалидировать
            // offload-трек плеера, якорь должен попасть в уже спокойную цепочку
            SessionAnchor.create(sid)?.let { anchors[sid] = it }
        }
        Timber.i("[SPL] attach sid=$sid done in ${SystemClock.elapsedRealtime() - t0}ms")
        Timber.d("Successfully added session $sid")

        changeCallbacks.forEach { it.onSessionChanged(sessionList) }
    }

    fun removeSession(sid: Int) {
        preAttachedSids.remove(sid)
        anchors.remove(sid)?.release()
        lastPlayerSeenAt.remove(sid)
        sessionList[sid]?.let { it ->
            Timber.d("Removed session: session ${sid}; data: $it")
            onSessionRemoved(it)
            sessionList.remove(sid)
            changeCallbacks.forEach { it.onSessionChanged(sessionList) }
        }
    }

    fun setExcludedUids(uids: Array<Int>) {
        excludedUids = uids

        val excludedSessions = sessionList.filter {
            excludedUids.contains(it.value.uid)
        }
        val notify = excludedSessions.isNotEmpty()
        excludedSessions.forEach { (_, session) -> onSessionRemoved(session) }
        excludedSessions.map { it.key }.forEach { sid ->
            anchors.remove(sid)?.release()
            lastPlayerSeenAt.remove(sid)
            sessionList.remove(sid)
        }
        if(notify)
            changeCallbacks.forEach { it.onSessionChanged(sessionList) }
    }

    fun registerOnSessionChangeListener(changeListener: OnSessionChangeListener) {
        changeCallbacks.add(changeListener)
        changeListener.onSessionChanged(sessionList)
    }

    fun unregisterOnSessionChangeListener(changeListener: OnSessionChangeListener) {
        changeCallbacks.remove(changeListener)
    }

    interface OnSessionChangeListener {
        fun onSessionChanged(sessionList: HashMap<Int, IEffectSession>)
    }

    companion object {
        protected const val FAILED_ATTACH_RETRY_MS = 30_000L
        private const val FAST_ATTACH_REMOVAL_GRACE_MS = 3_000L
        private const val ANCHOR_IDLE_RELEASE_MS = 5 * 60_000L
    }
}