package me.timschneeberger.rootlessjamesdsp.service

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import java.lang.ref.WeakReference

abstract class BaseAudioProcessorService : Service() {
    private val binder: IBinder = LocalBinder(this)

    // NOTE: This must be a nested (non-inner) class. An inner class would
    // implicitly hold a strong reference to the Service via this$0. The
    // Android binder subsystem retains binder objects in native code after
    // onDestroy(); a strong reference would keep the destroyed Service alive
    // and leak it. The WeakReference below breaks that chain.
    class LocalBinder(service: BaseAudioProcessorService) : Binder() {
        private val weakService = WeakReference(service)

        val service: BaseAudioProcessorService?
            get() = weakService.get()

        internal fun clear() {
            weakService.clear()
        }
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onCreate() {
        activeServices++
        super.onCreate()
    }

    override fun onDestroy() {
        activeServices--
        // Release the service reference held by the binder so that a binder
        // retained by native code cannot keep the destroyed service alive.
        (binder as? LocalBinder)?.clear()
        super.onDestroy()
    }

    companion object {
        var activeServices: Int = 0
            private set
    }
}
