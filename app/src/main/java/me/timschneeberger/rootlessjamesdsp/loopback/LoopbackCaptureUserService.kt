package me.timschneeberger.rootlessjamesdsp.loopback

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioRecord
import android.os.IBinder
import android.os.Process
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shizuku UserService: живёт в процессе shell/root UID и владеет
 * loopback-захватом (AudioPolicy LOOP_BACK + AudioRecord sink).
 * PCM уходит в приложение через oneway-колбэк.
 *
 * Поток PCM: собственный поток захвата с THREAD_PRIORITY_AUDIO; блоки ~20 мс
 * (1920 фреймов PCM16 stereo = 7680 байт). При смерти binder'а приложения
 * (процесс убит) захват останавливается сам — binder-link-to-death через
 * Shizuku-инфраструктуру колбэка.
 *
 * Контекст UserService имеет атрибуцию приложения — подменяем её на
 * com.android.shell (installShellAttribution) ДО создания policy.
 */
class LoopbackCaptureUserService(private val context: Context) : ILoopbackCapture.Stub() {

    private var policy: LoopbackAudioPolicy? = null
    private var record: AudioRecord? = null
    private var appUid = 0
    private val running = AtomicBoolean(false)
    private val stateLock = Object()
    @Volatile
    private var callback: ILoopbackCaptureCallback? = null
    @Volatile
    private var captureThread: Thread? = null
    private var deathRecipient: IBinder.DeathRecipient? = null

    override fun start(callback: ILoopbackCaptureCallback) {
        // UserService-процесс не исполняет Application.onCreate: дерева Timber
        // нет — сажаем напрямую, иначе логи захвата теряются
        if (Timber.treeCount == 0) Timber.plant(Timber.DebugTree())
        check(running.compareAndSet(false, true)) { "Loopback capture is already running" }
        this.callback = callback
        // Клиент умер (process killed) → захват останавливаем: иначе осиротевший
        // UserService держит policy и loopback-маршрут навсегда
        val recipient = IBinder.DeathRecipient { runCatching { stop() } }
        deathRecipient = recipient
        runCatching { callback.asBinder().linkToDeath(recipient, 0) }
        // На binder-потоке getCallingUid() = uid приложения-клиента: именно его
        // надо исключить из правила микширования (иначе наш же выходной трек
        // попадает в loopback → петля и тишина на устройстве)
        appUid = android.os.Binder.getCallingUid()

        // ВАЖНО: вся подготовка policy — ВНЕ binder-потока. На binder-потоке
        // checkCallingOrSelfPermission проверяет права ВЫЗЫВАЮЩЕГО приложения
        // (без MODIFY_AUDIO_ROUTING), а вне binder-контекста — собственный uid
        // UserService (root: checkComponentPermission даёт root всё; shell:
        // platform.xml выдаёт MODIFY_AUDIO_ROUTING) — ровно как у sk2andy
        val thread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            try {
                context.installShellAttribution()

                val createdPolicy = LoopbackAudioPolicy(context, appUid)
                policy = createdPolicy
                val createdRecord = createdPolicy.openRecord()
                record = createdRecord
                if (!running.get()) throw InterruptedException("Capture stopped during startup")
                createdRecord.startRecording()
                if (!running.get()) throw InterruptedException("Capture stopped during startup")

                callback.onStarted(SAMPLE_RATE, CHANNELS)
                Timber.i("[Loopback] capture started (${SAMPLE_RATE} Hz, $CHANNELS ch)")
                pump(createdRecord, callback)
            } catch (error: Throwable) {
                if (running.get()) {
                    Timber.e(error, "[Loopback] capture failed")
                    runCatching {
                        callback.onError(
                            ERROR_START_FAILED,
                            error.message ?: error.javaClass.simpleName,
                        )
                    }
                }
            } finally {
                running.set(false)
                // Only the capture thread releases AudioRecord/policy. stop() waits
                // for this cleanup instead of racing a blocking read or PCM pump.
                closeCapture()
                synchronized(stateLock) {
                    if (captureThread === Thread.currentThread()) captureThread = null
                }
            }
        }.apply { name = "LoopbackCapture" }
        synchronized(stateLock) { captureThread = thread }
        thread.start()
    }

    override fun stop() {
        Timber.i("[Loopback] stop requested")
        running.set(false)
        val stoppedCallback = callback
        val thread = synchronized(stateLock) { captureThread }

        // AudioRecord.stop() wakes a blocking read. The capture thread remains
        // the sole owner that releases the recorder and unregisters the policy.
        runCatching { record?.stop() }
        thread?.interrupt()
        if (thread != null && thread !== Thread.currentThread()) {
            var interrupted = false
            while (thread.isAlive) {
                try {
                    thread.join()
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
        }
        runCatching { stoppedCallback?.onStopped() }
    }

    override fun isRunning(): Boolean = running.get()

    private fun pump(record: AudioRecord, callback: ILoopbackCaptureCallback) {
        val pcm = ShortArray(PCM_BLOCK_FRAMES * CHANNELS)
        try {
            while (running.get() && !Thread.currentThread().isInterrupted) {
                var offset = 0
                while (offset < pcm.size && running.get()) {
                    val read = record.read(pcm, offset, pcm.size - offset, AudioRecord.READ_BLOCKING)
                    if (read <= 0) throw IllegalStateException("Loopback sink read failed: $read")
                    offset += read
                }
                if (!running.get()) break
                val bytes = ByteArray(pcm.size * 2)
                for (i in pcm.indices) {
                    bytes[2 * i] = (pcm[i].toInt() and 0xFF).toByte()
                    bytes[2 * i + 1] = (pcm[i].toInt() shr 8 and 0xFF).toByte()
                }
                callback.onPcm(bytes)
            }
        } catch (error: Throwable) {
            if (running.get()) {
                Timber.e(error, "[Loopback] capture pump died")
                runCatching { callback.onError(ERROR_PUMP_DIED, error.message ?: error.javaClass.simpleName) }
            }
        }
    }

    private fun closeCapture() {
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
        runCatching { policy?.close() }
        policy = null
        deathRecipient?.let { recipient ->
            runCatching { callback?.asBinder()?.unlinkToDeath(recipient, 0) }
        }
        deathRecipient = null
        callback = null
    }

    companion object {
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 2
        const val PCM_BLOCK_FRAMES = SAMPLE_RATE / 50 // 20 ms

        const val ERROR_START_FAILED = 1
        const val ERROR_PUMP_DIED = 2

        /** Идентификатор UserService для Shizuku.bindUserService. */
        const val SERVICE_CLASS_NAME = "me.timschneeberger.rootlessjamesdsp.loopback.LoopbackCaptureUserService"
    }
}
