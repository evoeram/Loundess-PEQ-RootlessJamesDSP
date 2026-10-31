package me.timschneeberger.rootlessjamesdsp.session.rootless

import android.content.Context
import android.content.Intent
import android.media.audiofx.DynamicsProcessing
import android.os.Process
import android.os.Process.myUid
import android.os.SystemClock
import me.timschneeberger.rootlessjamesdsp.androideq.AndroidEq
import me.timschneeberger.rootlessjamesdsp.model.AudioSessionDumpEntry
import me.timschneeberger.rootlessjamesdsp.model.IEffectSession
import me.timschneeberger.rootlessjamesdsp.model.rootless.MutedEffectSession
import me.timschneeberger.rootlessjamesdsp.session.dump.utils.AudioFlingerServiceDumpUtils
import me.timschneeberger.rootlessjamesdsp.session.shared.BaseSessionDatabase
import me.timschneeberger.rootlessjamesdsp.session.shared.SessionAnchor
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

    // ── Пул упреждающих mute на будущие sid (прогноз по счётчику) ────────────
    // У лент Shorts/Reels трек создаётся за 8-22 мс до старта (createTrack →
    // start, замер на MT2111): ни колбэк конфигов, ни dumpsys физически не
    // успевают вешать mute до первого сэмпла. Спасает прогноз: счётчик sid
    // глобальный монотонный с шагом, который выводится из дельт соседних sid
    // (на этой прошивке шаг 8), следующий трек получает maxKnownSid + k*шаг.
    // На такие sid заранее вешается ЯКОРЬ (тихий трек — он создаёт сессию и
    // цепочку; эффект на никогда не существовавшей сессии не инициализируется,
    // Error -3), затем mute. Когда трек появляется — цепочка уже с mute,
    // покрытие с нулевого сэмпла. Промах (чужой трек занял прогнозный sid)
    // деградирует в обычный реактивный путь
    private val poolSids = hashSetOf<Int>()
    private var poolUid = 0
    private var poolHighWater = 0
    private var poolStep = 0

    // ── Динамическая глубина пула (адаптация WMD-EWMA / AIMD) ────────────────
    // Глубина K эволюционирует по сигналам недопокрытия: промах = реактивный
    // attach (refs pre-attach) sid ВЫШЕ потолка прогнозного окна — счётчик
    // убежал. EWMA промахов > порога → K+1 (additive increase), гистерезис
    // срабатываний и окно заморозки против флаттера; после длинного периода
    // без промахов — мультипликативное сжатие. Потолок K_max задан ценой
    // коллатерального мутинга (шире окно — больше чужих треков под нашим
    // mute); память при наших размерах (static 4 КБ якорь + эффект) не лимит.
    private var poolDepth = POOL_DEPTH_MIN
    private var missEwma = 0.0
    private var missSincePoll = false
    private var kCooldown = 0
    private var stableTicks = 0

    override fun isManagedSession(sid: Int): Boolean = poolSids.contains(sid)

    /** Занят ли sid прогнозом пула: наши треки (выход DSP) обязаны его миновать,
     *  иначе висящий mute пула заглушит собственный выход до перезапуска DSP. */
    fun isPoolSid(sid: Int): Boolean = poolSids.contains(sid)

    override fun preCreateAnchor(sid: Int): SessionAnchor? {
        if (!poolSids.contains(sid)) return null
        return SessionAnchor.create(sid)
    }

    override fun onReactivePreAttach(sid: Int) {
        // Сигнал промаха прогноза: трек появился выше потолка окна.
        // Предсозданные (created-stopped) треки ниже потолка — не промах:
        // они покрыты окном или придут реактивно без burst'а
        if (sid > poolHighWater) missSincePoll = true
    }

    private fun adaptPoolDepth() {
        // EWMA промахов: сэмпл за этот опрос — был ли хоть один недопокрытый attach
        val sample = if (missSincePoll) 1.0 else 0.0
        missSincePoll = false
        missEwma = MISS_EWMA_ALPHA * sample + (1.0 - MISS_EWMA_ALPHA) * missEwma

        if (kCooldown > 0) {
            // Окно заморозки после смены K: даём пулу перестроиться без флаттера
            kCooldown--
            stableTicks++
            return
        }

        if (missEwma > MISS_WIDEN_THRESHOLD && poolDepth < POOL_DEPTH_MAX) {
            poolDepth++
            kCooldown = K_CHANGE_COOLDOWN_POLLS
            stableTicks = 0
            Timber.i("[SPL] pool depth -> $poolDepth (missEwma=%.2f)".format(missEwma))
        } else {
            stableTicks++
            if (missEwma < 0.01 && stableTicks >= SHRINK_STABLE_POLLS && poolDepth > POOL_DEPTH_MIN) {
                // Мультипликативное сжатие (AIMD) после длинного периода без промахов
                poolDepth = maxOf((poolDepth * 0.8).toInt(), POOL_DEPTH_MIN)
                kCooldown = K_CHANGE_COOLDOWN_POLLS
                stableTicks = 0
                Timber.i("[SPL] pool depth -> $poolDepth (no misses)")
            }
        }
    }

    override fun onRefsPolled(rows: List<AudioFlingerServiceDumpUtils.Dataset>, now: Long) {
        if (isDisposing) return
        poolSids.retainAll { sessionList.containsKey(it) }

        val foreignSorted = rows.filter { it.pid != Process.myPid() && it.sid > 0 }
            .map { it.sid }.distinct().sorted()
        val maxOwn = sessionList.keys.filter { !poolSids.contains(it) && it > 0 }.maxOrNull() ?: 0
        val maxSid = maxOf(foreignSorted.lastOrNull() ?: 0, maxOwn)
        if (maxSid <= 0) {
            releasePool("nothing playing")
            return
        }
        poolHighWater = maxOf(poolHighWater, maxSid)
        adaptPoolDepth()

        // Шаг аллокатора: НОД дельт соседних живых sid
        if (foreignSorted.size >= 2) {
            var g = 0
            for (i in 1 until foreignSorted.size) g = gcd(g, foreignSorted[i] - foreignSorted[i - 1])
            if (g > 0) poolStep = g
        }
        val step = if (poolStep > 0) poolStep else 1

        // Прогнозируемый uid: владелец любой живой строки, чью сессию мы держим
        val predicted = rows.asSequence()
            .filter { it.pid != Process.myPid() }
            .mapNotNull { it.uid }
            .distinct()
            .firstOrNull { uid -> uid > 0 && sessionList.values.any { it.uid == uid } }
        if (predicted == null || isUidExcluded(predicted)) {
            releasePool("no predicted uid")
            return
        }
        if (predicted != poolUid) {
            // Mute uid-агностичен, но прогнозная сессия несёт uid для
            // фильтров/уведомлений — при смене играющего приложения пул проще
            // пересобрать под нового владельца
            if (poolUid != 0) releasePool("uid switch $poolUid -> $predicted")
            poolUid = predicted
        }

        val name = context.packageManager.getNameForUid(predicted) ?: predicted.toString()
        // Один attach за опрос: создание эффекта ~50-100 мс на Looper-потоке.
        // Окно до следующего ролика — секунды, нескольких опросов хватает
        var attached = 0
        for (k in 1..poolDepth) {
            if (attached >= 1) break
            val sid = poolHighWater + step * k
            if (sid <= poolHighWater || sid <= 0 || sessionList.containsKey(sid)) continue
            if (now - (recentlyReleasedAt[sid] ?: 0L) < 1_000L) continue
            val lastFail = failedAttachAt[sid]
            if (lastFail != null && now - lastFail < FAILED_ATTACH_RETRY_MS) continue

            Timber.i("[SPL] pool pre-attach: hanging anchor+mute on future sid=$sid (uid=$predicted, step=$step)")
            poolSids.add(sid) // до addSession: preCreateAnchor ключуется на членстве
            addSession(sid, predicted, name)
            if (!sessionList.containsKey(sid)) poolSids.remove(sid) else attached++
        }

        // Счётчик ушёл дальше: прогнозные sid ниже high water уже распределены —
        // сессия остаётся в общем списке и живёт на обычной уборке
        poolSids.removeAll { it <= maxSid }
    }

    private fun releasePool(reason: String) {
        if (poolUid == 0 && poolSids.isEmpty()) return
        if (poolSids.isNotEmpty())
            Timber.i("[SPL] pool release (${poolSids.size}): $reason")
        poolSids.toList().forEach(::removeSession)
        poolSids.clear()
        poolUid = 0
    }

    private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

    override fun createSession(id: Int, uid: Int, packageName: String): MutedEffectSession? {
        // Спекулятивная сессия пула: трек ещё не существует. Неудача
        // инициализации эффекта здесь — не проблема приложения: Terminating-
        // путь вести не должен (иначе сервис убивает сам себя на каждом
        // неудачном прогнозе)
        val speculative = poolSids.contains(id)

        if (AndroidEq.isEnabled) {
            // Direct Mode: DynamicsProcessing встраивается в тракт приложения.
            // Capture loop не запускается — эффект живёт в audioMuteEffect.
            val effect = AndroidEq.create(id)
            if (effect == null) {
                if (!speculative) appProblemListener?.onAppProblemDetected(uid)
                return null
            }
            return MutedEffectSession(uid, packageName, effect)
        }

        // Standard / Low-latency: mute-эффект как обычно
        val muteEffect = factory.make(id, packageName)
        if (muteEffect == null) {
            if (!speculative) appProblemListener?.onAppProblemDetected(uid)
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
        if (isDisposing || sid <= 0 || uid <= 0 || uid == myUid()) {
            Timber.d("[SPL] fast-path skip sid=$sid uid=$uid: disposing/basic guard")
            return false
        }
        if (isUidExcluded(uid)) {
            Timber.d("[SPL] fast-path skip sid=$sid uid=$uid: uid excluded")
            return false
        }
        if (sessionList.containsKey(sid)) return false

        val name = context.packageManager.getNameForUid(uid)
        if (name != null && excludedPackages.contains(name)) {
            Timber.d("[SPL] fast-path skip sid=$sid: package excluded ($name)")
            return false
        }

        val lastFail = failedAttachAt[sid]
        if (lastFail != null && SystemClock.elapsedRealtime() - lastFail < FAILED_ATTACH_RETRY_MS) {
            Timber.d("[SPL] fast-path skip sid=$sid: attach failed recently")
            return false
        }

        val data = AudioSessionDumpEntry(
            uid,
            name ?: uid.toString(),
            AudioSessionDumpEntry.usageString(usage),
            "CONTENT_TYPE_UNKNOWN"
        )
        if (!shouldAcceptSessionDump(sid, data)) {
            Timber.d("[SPL] fast-path skip sid=$sid: usage not recordable ($data)")
            return false
        }

        Timber.i("[SPL] fast-path: attach sid=$sid; $name")
        addSession(sid, uid, name ?: uid.toString())
        fastAttachedAt[sid] = SystemClock.elapsedRealtime()
        return sessionList.containsKey(sid)
    }

    // ── Покрытие предзагруженных sid лент ────────────────────────────────────
    // Ленты Shorts/Reels создают треки будущих роликов заранее (created-
    // stopped): они видны в refs audioflinger до старта, и refs pre-attach
    // вешает на них mute до первого сэмпла. Никакой реактивный путь (колбэк
    // конфигов ~30 мс после createTrack, dumpsys ещё позже) не успевает за
    // мгновенный старт — покрывать надо именно предзаготовленные сессии.
    // Эффект на НИКОГДА не существовавшей сессии не инициализируется
    // (Error -3), поэтому «пул sid впереди аллокатора» на этой прошивке
    // не работает — убран, ставку делаем на refs pre-attach предзагрузки

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

        // Динамическая глубина пула: минимальный/максимальный размер окна и
        // параметры адаптации (EWMA промахов, гистерезис, заморозка, сжатие)
        private const val POOL_DEPTH_MIN = 4
        private const val POOL_DEPTH_MAX = 8          // потолок по коллатеральному мутингу
        private const val MISS_EWMA_ALPHA = 0.2       // окно EWMA ~5 опросов
        private const val MISS_WIDEN_THRESHOLD = 0.05 // β > 5% → расширение
        private const val K_CHANGE_COOLDOWN_POLLS = 10  // окно заморозки W
        private const val SHRINK_STABLE_POLLS = 60      // стабильность перед сжатием
    }
}

typealias OnRootlessSessionChangeListener = BaseSessionDatabase.OnSessionChangeListener
