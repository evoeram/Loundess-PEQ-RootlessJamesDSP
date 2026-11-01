package me.timschneeberger.rootlessjamesdsp.loopback;

import me.timschneeberger.rootlessjamesdsp.loopback.ILoopbackCaptureCallback;

/**
 * Управление loopback-захватом из Shizuku UserService (процесс shell UID).
 * UserService создаёт динамическую AudioPolicy (LOOP_BACK) и качает PCM
 * в колбэк. Вызовы из процесса приложения.
 */
interface ILoopbackCapture {
    /**
     * Запустить захват. Формат фиксируется на стороне UserService
     * (48 кГц стерео PCM16); фактические параметры приходят в onStarted().
     * Повторный вызов без stop() вернёт ошибку через onError().
     */
    oneway void start(in ILoopbackCaptureCallback callback);

    /** Остановить захват и освободить policy/sink. */
    oneway void stop();

    /** Жив ли захват (для переподключения binder'а после рестарта UserService). */
    boolean isRunning();
}
