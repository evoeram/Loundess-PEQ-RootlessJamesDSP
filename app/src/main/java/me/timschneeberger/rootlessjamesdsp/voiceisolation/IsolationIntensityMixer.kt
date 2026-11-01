package me.timschneeberger.rootlessjamesdsp.voiceisolation

import kotlin.math.abs
import kotlin.math.tanh

/**
 * Blends the enhancer output with the latency-aligned dry frame. The estimated background is
 * synthesised in the time domain as `dry - isolated`, so the voice estimate is left untouched
 * while the background is attenuated. Ported from Voice Isolation for Android (MIT).
 */
internal class IsolationIntensityMixer(
    frameSize: Int,
    private val modelSampleRate: Int,
    private val initialDelayHops: Int = 1,
) {
    private val dryFrames = Array(MAX_DELAY_HOPS + 1) { FloatArray(frameSize) }
    private val delayEstimator = VoiceDelayEstimator(MAX_DELAY_HOPS)
    private val speechDetector = SpeechActivityDetector()
    private var mixInitialized = false
    private var currentReduction = 0f
    private var currentVoiceGain = 1f
    private var newestDryIndex = 0
    private var dryFramesWritten = 0L
    private var delayLocked = false
    private var candidateDelay = -1
    private var candidateCount = 0

    var detectedDelayHops = initialDelayHops
        private set

    val speechDetected: Boolean
        get() = speechDetector.speechDetected

    val effectiveReduction: Float
        get() = currentReduction

    init {
        require(initialDelayHops in 1..MAX_DELAY_HOPS)
    }

    fun mix(
        input: FloatArray,
        isolated: FloatArray,
        output: FloatArray,
        settings: VoiceIsolationSettings,
    ) {
        require(input.size == dryFrames[0].size && input.isNotEmpty())
        require(isolated.size == dryFrames[0].size)
        require(output.size == dryFrames[0].size)

        input.copyInto(dryFrames[newestDryIndex])
        dryFramesWritten++
        if (!delayLocked && dryFramesWritten >= CALIBRATION_START_HOPS &&
            dryFramesWritten % CALIBRATION_INTERVAL_HOPS == 0L
        ) {
            updateDelay(isolated)
        }
        val dryIndex = (newestDryIndex - detectedDelayHops + dryFrames.size) % dryFrames.size
        val alignedDry = dryFrames[dryIndex]
        newestDryIndex = (newestDryIndex + 1) % dryFrames.size

        val normalized = settings.normalized()
        val hasSpeech = speechDetector.update(alignedDry, isolated)
        val softenBackground = normalized.autoMode && !hasSpeech
        val targetReduction = if (softenBackground) {
            minOf(normalized.backgroundReduction, AUTO_BACKGROUND_REDUCTION)
        } else {
            normalized.backgroundReduction
        }
        val targetVoiceGain = (if (softenBackground) 1f else normalized.voiceGain) *
            (1f - normalized.voiceReduction)
        if (!mixInitialized) {
            currentReduction = targetReduction
            currentVoiceGain = targetVoiceGain
            mixInitialized = true
        }

        val rampFraction = if (normalized.autoMode) {
            val rampMs = if (hasSpeech) AUTO_ATTACK_MS else AUTO_RELEASE_MS
            (output.size * 1000f / modelSampleRate / rampMs).coerceAtMost(1f)
        } else {
            1f
        }
        val nextReduction = currentReduction + (targetReduction - currentReduction) * rampFraction
        val nextVoiceGain = currentVoiceGain + (targetVoiceGain - currentVoiceGain) * rampFraction
        val reductionStep = (nextReduction - currentReduction) / output.size
        val gainStep = (nextVoiceGain - currentVoiceGain) / output.size

        for (index in output.indices) {
            val reduction = currentReduction + reductionStep * (index + 1)
            val voiceGain = currentVoiceGain + gainStep * (index + 1)
            val background = alignedDry[index] - isolated[index]
            output[index] = softLimit(background * (1f - reduction) + isolated[index] * voiceGain)
        }
        currentReduction = nextReduction
        currentVoiceGain = nextVoiceGain
    }

    private fun updateDelay(isolated: FloatArray) {
        val estimate = delayEstimator.estimate(
            dryFrames, newestDryIndex, isolated, dryFramesWritten.toInt(),
        ) ?: return
        if (estimate.delayHops == candidateDelay) {
            candidateCount++
        } else {
            candidateDelay = estimate.delayHops
            candidateCount = 1
        }
        if (candidateCount >= CALIBRATION_CONFIRMATIONS) {
            detectedDelayHops = candidateDelay
            delayLocked = true
        }
    }

    fun reset() {
        dryFrames.forEach { it.fill(0f) }
        speechDetector.reset()
        mixInitialized = false
        currentReduction = 0f
        currentVoiceGain = 1f
        newestDryIndex = 0
        dryFramesWritten = 0
        detectedDelayHops = initialDelayHops
        delayLocked = false
        candidateDelay = -1
        candidateCount = 0
    }

    private fun softLimit(sample: Float): Float {
        val magnitude = abs(sample)
        if (magnitude <= SOFT_LIMIT_START) return sample

        val limitedMagnitude = SOFT_LIMIT_START + SOFT_LIMIT_RANGE * tanh(
            ((magnitude - SOFT_LIMIT_START) / SOFT_LIMIT_RANGE).toDouble(),
        ).toFloat()
        return if (sample < 0f) -limitedMagnitude else limitedMagnitude
    }

    companion object {
        private const val MAX_DELAY_HOPS = 6
        private const val CALIBRATION_START_HOPS = 8L
        private const val CALIBRATION_INTERVAL_HOPS = 4L
        private const val CALIBRATION_CONFIRMATIONS = 3
        private const val MAX_PEAK = 0.9999695f
        private const val SOFT_LIMIT_START = 0.9f
        private const val SOFT_LIMIT_RANGE = MAX_PEAK - SOFT_LIMIT_START
        private const val AUTO_BACKGROUND_REDUCTION = 0.2f
        private const val AUTO_ATTACK_MS = 40f
        private const val AUTO_RELEASE_MS = 250f
    }
}
