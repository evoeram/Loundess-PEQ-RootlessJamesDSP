package me.timschneeberger.rootlessjamesdsp.loopback;

/**
 * Oneway-колбэк из UserService (shell UID) в процесс приложения.
 * onPcm вызывается на каждые ~20 мс PCM16 stereo (собственный поток UserService).
 */
oneway interface ILoopbackCaptureCallback {
    /** Захват поднят: sampleRate=48000, channels=2, encoding=PCM16. */
    void onStarted(int sampleRate, int channels);

    /** Блок PCM16 interleaved stereo, размер кратен фрейму. */
    void onPcm(in byte[] pcm);

    /** Фатальная ошибка захвата (policy отклонён, sink умер и т.п.). */
    void onError(int code, String message);

    /** Захват остановлен штатно (stop() или смерть сервиса). */
    void onStopped();
}
