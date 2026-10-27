package me.timschneeberger.rootlessjamesdsp.session.rootless

import android.content.Context
import android.content.Intent
import android.media.audiofx.DynamicsProcessing
import android.os.Process.myUid
import android.os.SystemClock
import me.timschneeberger.rootlessjamesdsp.androideq.AndroidEq
import me.timschneeberger.rootlessjamesdsp.model.AudioSessionDumpEntry
import me.timschneeberger.rootlessjamesdsp.model.IEffectSession
import me.timschneeberger.rootlessjamesdsp.model.rootless.MutedEffectSession
import me.timschneeberger.rootlessjamesdsp.session.shared.BaseSessionDatabase
import me.timschneeberger.rootlessjamesdsp.utils.MutedAudioEffectFactory
import timber.log.Timber

class RootlessSessionDatabase(context: Context) : BaseSessionDatabase(context) {

    private val factory by lazy { MutedAudioEffectFactory() }
    private var sessionLossListener: OnSessionLossListener? = null
    private var appProblemListener: OnAppProblemListener? = null

    override val excludedPackages = arrayOf(
        context.packageName,

        // Non-music apps known to cause issues
        "com.google.android.googlequicksearchbox",
        "com.google.android.as",
        "com.kieronquinn.app.pixelambientmusic",
        "com.draftkings.sportsbook",
        "com.samsung.gpuwatchapp",
        "com.kalkiarts.hexabloompro"
    )

    override fun shouldAcceptSessionDump(id: Int, session: AudioSessionDumpEntry): Boolean {
        if (!session.isUsageRecordable()) {
            Timber.d("Skipped session $id due to usage ($session)")
            return false
        }
        return true
    }

    override fun shouldAddSession(id: Int, uid: Int, packageName: String) = true

    override fun createSession(id: Int, uid: Int, packageName: String): MutedEffectSession? {
        if (AndroidEq.isEnabled) {
            // Direct Mode: DynamicsProcessing встраивается в тракт приложения.
            // Capture loop не запускается — эффект живёт в audioMuteEffect.
            val effect = AndroidEq.create(id)
            if (effect == null) {
                appProblemListener?.onAppProblemDetected(uid)
                return null
            }
            return MutedEffectSession(uid, packageName, effect)
        }

        // Standard / Low-latency: mute-эффект как обычно
        val muteEffect = factory.make(id, packageName)
        if (muteEffect == null) {
            appProblemListener?.onAppProblemDetected(uid)
            return null
        }
        return MutedEffectSession(uid, packageName, muteEffect)
    }

    override fun onSessionRemoved(item: IEffectSession) {
        (item as MutedEffectSession).run {
            val effect = audioMuteEffect
            if (effect is DynamicsProcessing) {
                // Direct Mode: освобождаем через AndroidEq
                AndroidEq.release(effect)
            }
            try {
                effect?.enabled = false
                effect?.release()
            } catch (ex: Exception) {
                Timber.e("onSessionRemoved: effect already destroyed")
                Timber.d(ex)
            }
        }
    }

    // Быстрый mute по AudioPlaybackCallback: dumpsys отдаёт пересозданную
    // сессию плеера с задержкой до ~0.5с, а трек уже играет — при агрессивном
    // автопреампе это SPL-удар.
    // Когда плеер исчезает из конфигов, его трек (и сессия, и наш mute-эффект)
    // уже уничтожен — framework не доставляет controlLost, а hasControl() на
    // мёртвом эффекте врёт. Сразу вешаем свежий эффект: на мёртвой сессии он
    // существует в подвешенном состоянии и автоматически применяется к
    // следующему треку плеера (sid сохраняется)
    private val knownConfigBySid = hashMapOf<Int, Pair<Int, Int>>() // sid -> uid to usage

    override val preAttachOnSessionDeath = true

    // entries: (sid, uid, usage) — снимок активных плееров из AudioPlaybackCallback
    fun syncFromPlaybackConfigs(entries: List<Triple<Int, Int, Int>>) {
        if (isDisposing) return

        val currentSids = hashSetOf<Int>()
        for ((sid, uid, usage) in entries) {
            // Свой трек в конфигах — это якорь на sid плеера (или выход DSP):
            // не затираем известные данные плеера своим uid
            if (uid == myUid()) continue
            currentSids.add(sid)
            knownConfigBySid[sid] = uid to usage
            tryAttachFromPlaybackConfig(sid, uid, usage)
        }

        for (sid in sessionList.keys.toList()) {
            if (sid in currentSids || sid in preAttachedSids) continue
            // Якорь держит цепочку сессии живой: сносить и пересоздавать
            // эффект не нужно
            if (isAnchored(sid)) continue
            val (uid, usage) = knownConfigBySid[sid] ?: continue
            removeSession(sid)
            if (tryAttachFromPlaybackConfig(sid, uid, usage))
                Timber.i("[SPL] fast-path: player gone (sid=$sid); pre-attached fresh mute")
        }
    }

    private fun tryAttachFromPlaybackConfig(sid: Int, uid: Int, usage: Int): Boolean {
        if (isDisposing || sid <= 0 || uid <= 0 || uid == myUid()) return false
        if (isUidExcluded(uid)) return false
        if (sessionList.containsKey(sid)) return false

        val name = context.packageManager.getNameForUid(uid)
        if (name != null && excludedPackages.contains(name)) return false

        val lastFail = failedAttachAt[sid]
        if (lastFail != null && SystemClock.elapsedRealtime() - lastFail < FAILED_ATTACH_RETRY_MS) return false

        val data = AudioSessionDumpEntry(
            uid,
            name ?: uid.toString(),
            AudioSessionDumpEntry.usageString(usage),
            "CONTENT_TYPE_UNKNOWN"
        )
        if (!shouldAcceptSessionDump(sid, data)) return false

        Timber.i("[SPL] fast-path: attach sid=$sid; $name")
        addSession(sid, uid, name ?: uid.toString())
        fastAttachedAt[sid] = SystemClock.elapsedRealtime()
        return sessionList.containsKey(sid)
    }

    init {
        factory.sessionLossListener = { sid, packageName ->
            sendFxCloseBroadcast(packageName, sid)
            // Эффект мёртв: убираем запись сразу, чтобы fast-path пересоздал
            // эффект, не дожидаясь dumpsys
            removeSession(sid)
            sessionLossListener?.onSessionLost(sid)
        }
    }

    private fun sendFxCloseBroadcast(pkgName: String, sid: Int) {
        val intent = Intent("android.media.action.CLOSE_AUDIO_EFFECT_CONTROL_SESSION")
        intent.putExtra("android.media.extra.PACKAGE_NAME", pkgName)
        intent.putExtra("android.media.extra.AUDIO_SESSION", sid)
        intent.putExtra(EXTRA_IGNORE, 1)
        context.sendBroadcast(intent)

        Timber.d("Sent control session close request for $pkgName")
    }

    fun setOnSessionLossListener(newSessionLossListener: OnSessionLossListener) {
        sessionLossListener = newSessionLossListener
    }

    fun setOnAppProblemListener(newAppProblemListener: OnAppProblemListener) {
        appProblemListener = newAppProblemListener
    }

    interface OnSessionLossListener {
        fun onSessionLost(sid: Int)
    }

    interface OnAppProblemListener {
        fun onAppProblemDetected(uid: Int)
    }

    companion object {
        const val EXTRA_IGNORE = "rootlessjamesdsp.ignore"
    }
}

typealias OnRootlessSessionChangeListener = BaseSessionDatabase.OnSessionChangeListener
