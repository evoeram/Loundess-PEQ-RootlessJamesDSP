package me.timschneeberger.rootlessjamesdsp.loopback

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import rikka.shizuku.Shizuku
import timber.log.Timber
import java.lang.ref.WeakReference

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

    private val lock = Object()
    private var serviceRef: ILoopbackCapture? = null
    private var connectionRef: ServiceConnection? = null
    private var generation = 0L
    private var startRequested = false

    private fun isActive(expectedGeneration: Long): Boolean = synchronized(lock) {
        startRequested && generation == expectedGeneration
    }

    private fun createServiceConnection(
        expectedGeneration: Long,
        callback: ILoopbackCaptureCallback,
    ): ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = ILoopbackCapture.Stub.asInterface(binder)
            val active = synchronized(lock) {
                if (startRequested && generation == expectedGeneration && connectionRef === this) {
                    serviceRef = service
                    true
                } else {
                    false
                }
            }
            if (!active) {
                // Shizuku may deliver a queued connection after unbind. It must not
                // start another capture or attach another callback to the new service.
                Timber.w("[Loopback] ignoring stale UserService connection")
                runCatching { Shizuku.unbindUserService(userServiceArgs(), this, true) }
                return
            }

            Timber.i("[Loopback] UserService connected (generation $expectedGeneration)")
            runCatching { service.start(callback) }
                .onFailure { error ->
                    Timber.e(error, "[Loopback] start after bind failed")
                    if (isActive(expectedGeneration)) {
                        listener?.onError(ERROR_START_FAILED, error.message ?: "start failed")
                    }
                }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            val active = synchronized(lock) {
                if (startRequested && generation == expectedGeneration && connectionRef === this) {
                    serviceRef = null
                    true
                } else {
                    false
                }
            }
            if (!active) return
            Timber.w("[Loopback] UserService disconnected")
            listener?.onError(ERROR_SERVICE_DISCONNECTED, "UserService disconnected")
        }
    }

    /** Binder keeps the Stub alive in native code (GC root) for as long as the
     * remote UserService holds the proxy — even after unbind. It must therefore
     * not strongly reference the client, or the whole client graph (including
     * the service set as [listener]) leaks. */
    private class ClientCallback(
        client: LoopbackCaptureClient,
        private val expectedGeneration: Long,
    ) : ILoopbackCaptureCallback.Stub() {
        private val clientRef = WeakReference(client)

        override fun onStarted(sampleRate: Int, channels: Int) {
            val client = clientRef.get() ?: return
            if (!client.isActive(expectedGeneration)) return
            Timber.i("[Loopback] capture started: ${sampleRate} Hz, $channels ch")
            client.listener?.onStarted(sampleRate, channels)
        }

        override fun onPcm(pcm: ByteArray) {
            val client = clientRef.get() ?: return
            client.offerPcm(expectedGeneration, pcm)
        }

        override fun onError(code: Int, message: String) {
            val client = clientRef.get() ?: return
            if (!client.isActive(expectedGeneration)) return
            Timber.e("[Loopback] error $code: $message")
            client.listener?.onError(code, message)
        }

        override fun onStopped() {
            val client = clientRef.get() ?: return
            if (!client.isActive(expectedGeneration)) return
            client.listener?.onStopped()
        }
    }

    private fun offerPcm(expectedGeneration: Long, pcm: ByteArray) {
        synchronized(lock) {
            // Serialize with stop/start: an old binder callback can never enqueue
            // data after the new generation has cleared its source.
            if (startRequested && generation == expectedGeneration) {
                pcmSource.offer(pcm)
            }
        }
    }

    fun isShizukuReady(): Boolean =
        Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

    /** Привязать UserService и поднять захват. Требует живого Shizuku с грантом. */
    fun start() {
        check(isShizukuReady()) { "Shizuku binder is not alive or permission not granted" }
        synchronized(lock) {
            if (startRequested) {
                Timber.d("[Loopback] start ignored: capture already requested")
                return
            }

            generation++
            startRequested = true
            pcmSource.clear()
            val callback = ClientCallback(this, generation)
            val connection = createServiceConnection(generation, callback)
            connectionRef = connection
            try {
                Shizuku.bindUserService(userServiceArgs(), connection)
            } catch (error: Throwable) {
                startRequested = false
                connectionRef = null
                pcmSource.close()
                throw error
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            startRequested = false
            generation++
            val service = serviceRef
            val connection = connectionRef
            serviceRef = null
            connectionRef = null

            // close(), а не clear(): заблокированный в read() capture-поток должен
            // проснуться и завершиться, а не висеть на lock.wait() (BUG-02)
            pcmSource.close()
            if (service != null) {
                runCatching { service.stop() }
                    .onFailure { Timber.w(it, "[Loopback] remote stop failed") }
            }
            if (connection != null) {
                runCatching { Shizuku.unbindUserService(userServiceArgs(), connection, true) }
                    .onFailure { Timber.w(it, "[Loopback] unbind failed") }
            }
        }
    }

    private fun userServiceArgs(): Shizuku.UserServiceArgs =
        Shizuku.UserServiceArgs(ComponentName(context, LoopbackCaptureUserService::class.java))
            .daemon(false)
            .processNameSuffix("loopback")
            .debuggable(true)
            .version(VERSION)

    private companion object {
        const val VERSION = 7
        const val ERROR_START_FAILED = 1
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
    private val pool = ArrayDeque<ShortArray>()
    private var remainder: ShortArray? = null
    private var remainderOffset = 0
    private val lock = Object()

    /** Закрытый источник будит заблокированных читателей и отдаёт -1 (BUG-02). */
    private var closed = false
    private var droppedBlocks = 0L
    private var lastDropLogNanos = 0L

    val droppedBlockCount: Long
        get() = synchronized(lock) { droppedBlocks }

    /** Binder path: reuse consumed blocks instead of allocating a ShortArray every 20 ms. */
    fun offer(pcm: ByteArray) {
        require(pcm.size % 2 == 0) { "PCM16 block must contain a whole number of samples" }
        val block = synchronized(lock) {
            if (closed) return
            pool.removeFirstOrNull()?.takeIf { it.size == pcm.size / 2 } ?: ShortArray(pcm.size / 2)
        }
        for (i in block.indices) {
            block[i] = ((pcm[2 * i].toInt() and 0xFF) or
                (pcm[2 * i + 1].toInt() shl 8)).toShort()
        }
        offer(block)
    }

    fun offer(pcm: ShortArray) {
        synchronized(lock) {
            if (closed) {
                recycle(pcm)
                return
            }
            queue.addLast(pcm)
            var overflow = 0
            while (queue.size > MAX_BLOCKS) {
                recycle(queue.removeFirst())
                overflow++
            }
            if (overflow > 0) {
                droppedBlocks += overflow
                val now = System.nanoTime()
                if (lastDropLogNanos == 0L || now - lastDropLogNanos >= DROP_LOG_INTERVAL_NANOS) {
                    lastDropLogNanos = now
                    Timber.w(
                        "[Loopback] PCM queue overflow: dropped $overflow blocks " +
                            "($droppedBlocks total, queue=${queue.size})"
                    )
                }
            }
            lock.notifyAll()
        }
    }

    /**
     * AudioRecord-compatible blocking read: normally returns exactly [size] samples.
     * Returning whatever happened to be queued made the native engine repeatedly
     * reinitialize convolvers and other block-size-dependent state. A short read
     * is now allowed only when close() interrupts an already partially filled read.
     */
    fun read(destination: ShortArray, offset: Int, size: Int): Int {
        synchronized(lock) {
            var written = 0
            while (written < size) {
                while (!closed && queue.isEmpty() && remainder == null) {
                    lock.wait()
                }
                if (closed && queue.isEmpty() && remainder == null) {
                    return if (written > 0) written else -1
                }

                remainder?.let { block ->
                    val take = minOf(block.size - remainderOffset, size - written)
                    System.arraycopy(
                        block,
                        remainderOffset,
                        destination,
                        offset + written,
                        take,
                    )
                    remainderOffset += take
                    written += take
                    if (remainderOffset == block.size) {
                        remainder = null
                        remainderOffset = 0
                        recycle(block)
                    }
                    if (written == size) return written
                }

                // The queue can be empty after consuming a partial remainder.
                // Wait for the rest of this read instead of returning a random
                // block size to JamesDSP (20/40/60 ms depending on scheduling).
                if (queue.isEmpty()) continue
                val block = queue.removeFirst()
                val take = minOf(block.size, size - written)
                System.arraycopy(block, 0, destination, offset + written, take)
                if (take < block.size) {
                    remainder = block
                    remainderOffset = take
                } else {
                    recycle(block)
                }
                written += take
            }
            return written
        }
    }

    /** Сброс буфера без закрытия: очередь пустеет, читатели будятся, источник снова пригоден к чтению. */
    fun clear() {
        synchronized(lock) {
            queue.forEach(::recycle)
            queue.clear()
            remainder?.let(::recycle)
            remainder = null
            remainderOffset = 0
            closed = false
            lock.notifyAll()
        }
    }

    /** Остановка источника: будит заблокированный read(), который вернёт -1. */
    fun close() {
        synchronized(lock) {
            closed = true
            lock.notifyAll()
        }
    }

    private fun recycle(block: ShortArray) {
        if (pool.size < MAX_POOLED_BLOCKS) pool.addLast(block)
    }

    private companion object {
        // 25 блоков x 20 мс = ~0.5 с хвоста
        const val MAX_BLOCKS = 25
        const val MAX_POOLED_BLOCKS = 8
        const val DROP_LOG_INTERVAL_NANOS = 5_000_000_000L
    }
}
