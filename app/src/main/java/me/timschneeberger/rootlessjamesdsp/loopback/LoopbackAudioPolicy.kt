package me.timschneeberger.rootlessjamesdsp.loopback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import timber.log.Timber

/**
 * Динамическая AudioPolicy с pure LOOP_BACK миксом. Выполняется ТОЛЬКО в
 * Shizuku UserService (shell UID): создание policy требует
 * MODIFY_AUDIO_ROUTING, который есть у shell, но не у приложений.
 * Hidden API через рефлексию — для shell non-SDK ограничения не действуют.
 *
 * Pure LOOP_BACK удаляет совпадающие плееры из обычного рендера: медиа-микс
 * целиком уходит в наш AudioRecord sink, на устройство не играет — mute-эффекты
 * и гонка attach не нужны.
 */
internal class LoopbackAudioPolicy(
    private val context: Context,
    /** uid приложения-потребителя: его треки исключаются из loopback (иначе петля). */
    private val appUid: Int,
) : AutoCloseable {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var policy: Any? = null
    private var record: AudioRecord? = null

    /** Поднять policy и вернуть AudioRecord-sink (48 кГц, стерео, PCM16). */
    fun openRecord(): AudioRecord {
        check(policy == null) { "Loopback policy is already open" }

        // Правило микширования: медиа-usage'ы, исключая наш собственный uid
        // (иначе наш выходной трек попадает в захват — петля)
        val ruleClass = Class.forName("android.media.audiopolicy.AudioMixingRule")
        val ruleBuilderClass = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder")
        val usageRule = ruleClass.getField("RULE_MATCH_ATTRIBUTE_USAGE").getInt(null)
        val uidRule = ruleClass.getField("RULE_MATCH_UID").getInt(null)
        val ruleBuilder = ruleBuilderClass.getConstructor().newInstance()
        val addRule = ruleBuilderClass.getMethod(
            "addRule",
            AudioAttributes::class.java,
            Int::class.javaPrimitiveType,
        )
        listOf(
            AudioAttributes.USAGE_MEDIA,
            AudioAttributes.USAGE_GAME,
            AudioAttributes.USAGE_UNKNOWN,
        ).forEach { usage ->
            addRule.invoke(
                ruleBuilder,
                AudioAttributes.Builder().setUsage(usage).build(),
                usageRule,
            )
        }
        ruleBuilderClass.getMethod("excludeMixRule", Int::class.javaPrimitiveType, Any::class.java)
            .invoke(ruleBuilder, uidRule, appUid)
        val rule = ruleBuilderClass.getMethod("build").invoke(ruleBuilder)

        // Микс: LOOP_BACK, 48 кГц стерео PCM16 (формат тракта DSP)
        val mixClass = Class.forName("android.media.audiopolicy.AudioMix")
        val mixBuilderClass = Class.forName("android.media.audiopolicy.AudioMix\$Builder")
        val loopBack = mixClass.getField("ROUTE_FLAG_LOOP_BACK").getInt(null)
        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val mixBuilder = mixBuilderClass.getConstructor(ruleClass).newInstance(rule)
        mixBuilderClass.getMethod("setFormat", AudioFormat::class.java).invoke(mixBuilder, format)
        mixBuilderClass.getMethod("setRouteFlags", Int::class.javaPrimitiveType)
            .invoke(mixBuilder, loopBack)
        val mix = requireNotNull(mixBuilderClass.getMethod("build").invoke(mixBuilder))

        val policyClass = Class.forName("android.media.audiopolicy.AudioPolicy")
        val policyBuilderClass = Class.forName("android.media.audiopolicy.AudioPolicy\$Builder")
        val policyBuilder = policyBuilderClass.getConstructor(Context::class.java).newInstance(context)
        policyBuilderClass.getMethod("addMix", mixClass).invoke(policyBuilder, mix)
        val createdPolicy = policyBuilderClass.getMethod("build").invoke(policyBuilder)

        val registrationResult = audioManager.javaClass
            .getMethod("registerAudioPolicy", policyClass)
            .invoke(audioManager, createdPolicy) as Int
        check(registrationResult == AUDIO_POLICY_SUCCESS) {
            "AudioPolicy registration failed: $registrationResult"
        }

        return try {
            val createdRecord = createRecordSink(mix, mixClass, createdPolicy, policyClass)
            check(createdRecord.state == AudioRecord.STATE_INITIALIZED) {
                "Loopback sink initialization failed"
            }
            policy = createdPolicy
            record = createdRecord
            createdRecord
        } catch (error: Throwable) {
            runCatching {
                audioManager.javaClass.getMethod("unregisterAudioPolicy", policyClass)
                    .invoke(audioManager, createdPolicy)
            }
            throw error
        }
    }

    /**
     * Sink микса. Основной путь — официальный hidden-API
     * AudioPolicy.createAudioRecordSink(mix): framework сам собирает
     * AudioRecord с правильной атрибуцией владельца policy (shell).
     * Fallback — ручной AudioRecord.Builder с REMOTE_SUBMIX + addr-тегом и
     * ЯВНОЙ атрибуцией shell (в UserService-процессе контекстной атрибуции
     * может не быть, и без неё нативная инициализация падает с
     * "Cannot create AudioRecord").
     */
    private fun createRecordSink(
        mix: Any,
        mixClass: Class<*>,
        createdPolicy: Any,
        policyClass: Class<*>,
    ): AudioRecord {
        val registration = mixClass.getMethod("getRegistration").invoke(mix) as String
        Timber.i("[Loopback] mix registered as '$registration'")

        // Основной путь: AudioPolicy.createAudioRecordSink(AudioMix)
        runCatching {
            val record = policyClass.getMethod("createAudioRecordSink", mixClass)
                .invoke(createdPolicy, mix) as AudioRecord
            Timber.i("[Loopback] sink created via createAudioRecordSink")
            return record
        }.onFailure { Timber.w("[Loopback] createAudioRecordSink failed: ${it.message}") }

        // Fallback: ручной AudioRecord с REMOTE_SUBMIX + явной shell-атрибуцией
        val attributesBuilder = AudioAttributes.Builder()
        attributesBuilder.javaClass
            .getMethod("setInternalCapturePreset", Int::class.javaPrimitiveType)
            .invoke(attributesBuilder, REMOTE_SUBMIX_SOURCE)
        attributesBuilder.javaClass.getMethod("addTag", String::class.java)
            .invoke(attributesBuilder, "addr=$registration")
        val attributes = attributesBuilder.build()

        val inputFormat = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        // Стерео в minBufferSize — консервативно: некоторые платформы занижают
        // размер под нестандартные входные маски (паттерн AudioPolicy.createAudioRecordSink)
        val minimumBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minimumBuffer > 0) { "Unsupported AudioRecord format: $minimumBuffer" }

        // setAudioAttributes — hidden-метод Builder (паттерн sk2andy): через
        // него в AudioRecord попадает addr-тег, привязывающий sink к миксу
        val recordBuilder = AudioRecord.Builder()
            .setAudioFormat(inputFormat)
            .setBufferSizeInBytes(maxOf(minimumBuffer, DEFAULT_SINK_BUFFER_BYTES))
        recordBuilder.javaClass
            .getMethod("setAudioAttributes", AudioAttributes::class.java)
            .invoke(recordBuilder, attributes)
        return recordBuilder.build()
    }

    override fun close() {
        runCatching { record?.release() }
        record = null
        policy?.let { currentPolicy ->
            runCatching {
                val policyClass = Class.forName("android.media.audiopolicy.AudioPolicy")
                audioManager.javaClass
                    .getMethod("unregisterAudioPolicy", policyClass)
                    .invoke(audioManager, currentPolicy)
            }
        }
        policy = null
    }

    companion object {
        const val SAMPLE_RATE = 48_000
        private const val AUDIO_POLICY_SUCCESS = 0
        private const val REMOTE_SUBMIX_SOURCE = 8
        private const val SHELL_PACKAGE = "com.android.shell"
        private const val DEFAULT_SINK_BUFFER_BYTES = 16_384
    }
}
