package me.timschneeberger.rootlessjamesdsp.flavor

import com.google.firebase.crashlytics.FirebaseCrashlytics

object CrashlyticsImpl {
    // google-services.json does not cover every build variant application id (e.g. local bench
    // builds): FirebaseApp stays uninitialized there and Firebase.crashlytics would throw
    // IllegalStateException on any call, including logging from Application.onCreate. Resolve
    // lazily and degrade to a no-op instead of crashing the app.
    private val crashlytics: FirebaseCrashlytics? by lazy {
        runCatching { FirebaseCrashlytics.getInstance() }.getOrNull()
    }

    fun setCollectionEnabled(on: Boolean) { crashlytics?.setCrashlyticsCollectionEnabled(on) }
    fun setCustomKey(key: String, value: String) { crashlytics?.setCustomKey(key, value) }
    fun setCustomKey(key: String, value: Int) { crashlytics?.setCustomKey(key, value) }
    fun log(msg: String) { crashlytics?.log(msg) }
    fun recordException(t: Throwable) { crashlytics?.recordException(t) }
    fun sendUnsentReports() { crashlytics?.sendUnsentReports() }
}
