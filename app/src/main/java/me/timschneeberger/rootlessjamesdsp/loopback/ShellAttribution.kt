package me.timschneeberger.rootlessjamesdsp.loopback

import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import timber.log.Timber

/**
 * Подмена атрибуции Context на com.android.shell. Выполняется ТОЛЬКО внутри
 * Shizuku UserService.
 *
 * ВАЖНО: uid в атрибуции — ВСЕГДА 2000 (shell), а не Process.myUid():
 * - когда Shizuku запущен через ADB, UserService и так shell;
 * - когда Shizuku запущен через root, UserService работает как uid 0, но
 *   root НЕ имеет MODIFY_AUDIO_ROUTING в platform.xml — а shell имеет.
 *   Атрибуция shell проходит проверку AudioPolicy при обоих способах запуска.
 *
 * Паттерн из sk2andy/android-realtime-voice-isolation (ShellAttributionContext),
 * с падением вместо тихого пропуска: неприменённый патч даёт неочевидный отказ
 * AudioPolicy («uid 10298» в логе).
 */
internal fun Context.installShellAttribution() {
    val shellAttribution = AttributionSource.Builder(SHELL_UID)
        .setPackageName(SHELL_PACKAGE)
        .build()

    val targets = sequenceOf(this)
        .plus(runCatching {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as? Context
        }.getOrNull()?.let { sequenceOf(it) } ?: emptySequence())
        .map(::unwrapContext)
        .distinctBy { System.identityHashCode(it) }

    var patched = 0
    targets.forEach { context ->
        var type: Class<*>? = context.javaClass
        var field: java.lang.reflect.Field? = null
        while (type != null && field == null) {
            field = runCatching { type.getDeclaredField("mAttributionSource") }.getOrNull()
            type = type.superclass
        }
        check(field != null) {
            "[Loopback] Context ${context.javaClass.name} has no mAttributionSource field"
        }
        field!!.isAccessible = true
        field.set(context, shellAttribution)
        patched++
        Timber.i(
            "[Loopback] shell attribution installed on ${context.javaClass.name}: " +
                "uid=${shellAttribution.uid} pkg=${shellAttribution.packageName}",
        )
    }
    check(patched > 0) { "[Loopback] no context patched for shell attribution" }
}

private fun unwrapContext(context: Context): Context {
    var current = context
    while (current is ContextWrapper && current.baseContext !== current) {
        current = current.baseContext
    }
    return current
}

private const val SHELL_PACKAGE = "com.android.shell"
private const val SHELL_UID = 2000
