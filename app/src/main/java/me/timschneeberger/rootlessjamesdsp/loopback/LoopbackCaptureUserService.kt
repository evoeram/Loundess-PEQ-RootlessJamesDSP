package me.timschneeberger.rootlessjamesdsp.loopback

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioRecord
import android.os.Process
import android.os.RemoteException
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
    @Volatile
    private var callback: ILoopbackCaptureCallback? = null
    @Volatile
    private var captureThread: Thread? = null

    override fun start(callback: ILoopbackCaptureCallback) {
        // UserService-процесс не исполняет Application.onCreate: дерева Timber
        // нет — сажаем напрямую, иначе логи захвата теряются
        if (Timber.treeCount == 0) Timber.plant(Timber.DebugTree())
        check(running.compareAndSet(false, true)) { "Loopback capture is already running" }
        this.callback = callback
        // Клиент умер (process killed) → захват останавливаем: иначе осиротевший
        // UserService держит policy и loopback-маршрут навсегда
        runCatching {
            callback.asBinder().linkToDeath({ -> runCatching { stop() } }, 0)
        }
        // На binder-потоке getCallingUid() = uid приложения-клиента: именно его
        // надо исключить из правила микширования (иначе наш же выходной трек
        // попадает в loopback → петля и тишина на устройстве)
        appUid = android.os.Binder.getCallingUid()

        // ВАЖНО: вся подготовка policy — ВНЕ binder-потока. На binder-потоке
        // checkCallingOrSelfPermission проверяет права ВЫЗЫВАЮЩЕГО приложения
        // (без MODIFY_AUDIO_ROUTING), а вне binder-контекста — собственный uid
        // UserService (root: checkComponentPermission даёт root всё; shell:
        // platform.xml выдаёт MODIFY_AUDIO_ROUTING) — ровно как у sk2andy
        captureThread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            try {
                context.installShellAttribution()

                val createdPolicy = LoopbackAudioPolicy(context, appUid)
                policy = createdPolicy
                val createdRecord = createdPolicy.openRecord()
                record = createdRecord
                createdRecord.startRecording()

                callback.onStarted(SAMPLE_RATE, CHANNELS)
                Timber.i("[Loopback] capture started (${SAMPLE_RATE} Hz, $CHANNELS ch)")
                pump(createdRecord, callback)
            } catch (error: Throwable) {
                running.set(false)
                Timber.e(error, "[Loopback] start failed")
                runCatching { callback.onError(ERROR_START_FAILED, error.message ?: error.javaClass.simpleName) }
                closeCapture()
            }
        }.apply {
            name = "LoopbackCapture"
            start()
        }
    }

    override fun stop() {
        Timber.i("[Loopback] stop requested")
        running.set(false)
        stopAndClose()
        runCatching { callback?.onStopped() }
    }

    override fun isRunning(): Boolean = running.get()

    private fun pump(record: AudioRecord, callback: ILoopbackCaptureCallback) {
        val blockFrames = SAMPLE_RATE * CHANNELS / 50 // ~20 мс стерео
        val pcm = ShortArray(blockFrames)
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
        } finally {
            running.set(false)
        }
    }

    private fun stopAndClose() {
        captureThread?.interrupt()
        captureThread = null
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
        runCatching { policy?.close() }
        policy = null
    }

    private fun closeCapture() {
        stopAndClose()
        callback = null
    }

    companion object {
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 2

        const val ERROR_START_FAILED = 1
        const val ERROR_PUMP_DIED = 2

        /** Идентификатор UserService для Shizuku.bindUserService. */
        const val SERVICE_CLASS_NAME = "me.timschneeberger.rootlessjamesdsp.loopback.LoopbackCaptureUserService"
    }
}
