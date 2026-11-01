package me.timschneeberger.rootlessjamesdsp.loopback

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import rikka.shizuku.Shizuku
import timber.log.Timber
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/**
 * App-сторона loopback-захвата: биндит UserService через Shizuku, принимает
 * PCM и раздаёт через [pcmSource] — фасад с read()-контрактом существующего
 * capture-цикла RootlessAudioProcessorService (смена источника без изменения
 * цикла).
 */
class LoopbackCaptureClient(private val context: Context) {

    interface Listener {
        /** Захват поднят; дальше pcmSource наполняется до onStopped/onError. */
        fun onStarted(sampleRate: Int, channels: Int)

        fun onError(code: Int, message: String)

        fun onStopped()
    }

    /** Наполняется колбэком UserService; читается capture-циклом. */
    val pcmSource = LoopbackPcmSource()

    var listener: Listener? = null

    private var serviceRef: ILoopbackCapture? = null
    private val startRequested = AtomicBoolean(false)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = ILoopbackCapture.Stub.asInterface(binder)
            synchronized(lock) { serviceRef = service }
            Timber.i("[Loopback] UserService connected")
            if (startRequested.get()) {
                runCatching { service.start(callback) }
                    .onFailure { Timber.e(it, "[Loopback] start after bind failed") }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Timber.w("[Loopback] UserService disconnected")
            synchronized(lock) { serviceRef = null }
            listener?.onError(ERROR_SERVICE_DISCONNECTED, "UserService disconnected")
        }
    }

    /** Binder keeps the Stub alive in native code (GC root) for as long as the
     * remote UserService holds the proxy — even after unbind. It must therefore
     * not strongly reference the client, or the whole client graph (including
     * the service set as [listener]) leaks. */
    private val callback = ClientCallback(this)

    private class ClientCallback(client: LoopbackCaptureClient) : ILoopbackCaptureCallback.Stub() {
        private val clientRef = WeakReference(client)

        override fun onStarted(sampleRate: Int, channels: Int) {
            Timber.i("[Loopback] capture started: ${sampleRate} Hz, $channels ch")
            clientRef.get()?.listener?.onStarted(sampleRate, channels)
        }

        override fun onPcm(pcm: ByteArray) {
            val client = clientRef.get() ?: return
            // byte[] -> ShortArray: binder-поток не должен блокироваться дольше конверсии
            val shorts = ShortArray(pcm.size / 2)
            for (i in shorts.indices) {
                shorts[i] =
                    ((pcm[2 * i].toInt() and 0xFF) or (pcm[2 * i + 1].toInt() shl 8)).toShort()
            }
            client.pcmSource.offer(shorts)
        }

        override fun onError(code: Int, message: String) {
            Timber.e("[Loopback] error $code: $message")
            clientRef.get()?.listener?.onError(code, message)
        }

        override fun onStopped() {
            clientRef.get()?.listener?.onStopped()
        }
    }

    private val lock = Object()

    fun isShizukuReady(): Boolean =
        Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

    /** Привязать UserService и поднять захват. Требует живого Shizuku с грантом. */
    fun start() {
        check(isShizukuReady()) { "Shizuku binder is not alive or permission not granted" }
        startRequested.set(true)
        Shizuku.bindUserService(userServiceArgs(), serviceConnection)
    }

    fun stop() {
        startRequested.set(false)
        synchronized(lock) {
            runCatching { serviceRef?.stop() }
            serviceRef = null
        }
        runCatching { Shizuku.unbindUserService(userServiceArgs(), serviceConnection, true) }
        pcmSource.clear()
    }

    private fun userServiceArgs(): Shizuku.UserServiceArgs =
        Shizuku.UserServiceArgs(ComponentName(context, LoopbackCaptureUserService::class.java))
            .daemon(false)
            .processNameSuffix("loopback")
            .debuggable(true)
            .version(VERSION)

    private companion object {
        const val VERSION = 6
        const val ERROR_SERVICE_DISCONNECTED = 100
    }
}

/**
 * Фасад PCM-источника под контракт capture-цикла (блокирующее чтение в духе
 * AudioRecord.read). Наполняется из UserService; очередь ограничена, чтобы
 * остановленный потребитель не накапливал бесконечный хвост.
 */
class LoopbackPcmSource {
    private val queue = ArrayDeque<ShortArray>()
    private var remainder: ShortArray = ShortArray(0)
    private val lock = Object()

    fun offer(pcm: ShortArray) {
        synchronized(lock) {
            queue.addLast(pcm)
            while (queue.size > MAX_BLOCKS) queue.removeFirst()
            lock.notifyAll()
        }
    }

    /** Блокирующее чтение; возвращает число написанных фреймов-сэмплов. */
    fun read(destination: ShortArray, offset: Int, size: Int): Int {
        synchronized(lock) {
            while (queue.isEmpty() && remainder.isEmpty()) {
                lock.wait()
            }
            var written = 0
            while (written < size) {
                if (remainder.isNotEmpty()) {
                    val take = minOf(remainder.size, size - written)
                    System.arraycopy(remainder, 0, destination, offset + written, take)
                    remainder = remainder.copyOfRange(take, remainder.size)
                    written += take
                    continue
                }
                val block = queue.removeFirstOrNull() ?: break
                val take = minOf(block.size, size - written)
                System.arraycopy(block, 0, destination, offset + written, take)
                if (take < block.size) remainder = block.copyOfRange(take, block.size)
                written += take
            }
            return written
        }
    }

    fun clear() {
        synchronized(lock) {
            queue.clear()
            remainder = ShortArray(0)
        }
    }

    private companion object {
        // 25 блоков x 20 мс = ~0.5 с хвоста
        const val MAX_BLOCKS = 25
    }
}
