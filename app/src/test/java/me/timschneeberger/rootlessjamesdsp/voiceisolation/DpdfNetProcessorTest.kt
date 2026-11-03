package me.timschneeberger.rootlessjamesdsp.voiceisolation

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class DpdfNetProcessorTest {
    @Test
    fun `48 kHz backend returns finite non-silent audio`() {
        val asset = File("src/main/assets/dpdfnet2_48khz_hr.onnx")
        assertTrue("bundled DPDFNet model is missing", asset.isFile)
        val backend = DpdfNetProcessor(asset.readBytes())
        try {
            val input = FloatArray(backend.hopSize)
            val output = FloatArray(backend.hopSize)
            var peak = 0f
            var noiseSeed = 246353421L
            repeat(120) { hop ->
                val voiced = hop in 20..95 && hop % 5 != 0
                for (index in input.indices) {
                    val sampleIndex = hop * backend.hopSize + index
                    noiseSeed = noiseSeed * 6364136223846793005L + 1442695040888963407L
                    val noise = ((noiseSeed ushr 40).toDouble() / (1L shl 24) - 0.5) * 0.04
                    var voice = 0.0
                    if (voiced) {
                        val time = sampleIndex.toDouble() / backend.modelSampleRate
                        for (harmonic in 1..14) {
                            voice += sin(2.0 * PI * 110.0 * harmonic * time) / harmonic
                        }
                    }
                    input[index] = (voice * 0.12 + noise).toFloat()
                }
                backend.process(input, output)
                assertTrue("backend produced NaN or infinity", output.all { it.isFinite() })
                for (sample in output) peak = maxOf(peak, abs(sample))
            }
            assertTrue("backend returned only silence, peak=$peak", peak > 1e-3f)
        } finally {
            backend.close()
        }
    }

    @Test
    fun `full 48 kHz channel survives capture-sized PCM16 blocks`() {
        val asset = File("src/main/assets/dpdfnet2_48khz_hr.onnx")
        val backend = DpdfNetProcessor(asset.readBytes())
        try {
            val isolated = FloatArray(backend.hopSize)
            val mixer = IsolationIntensityMixer(
                backend.hopSize,
                backend.modelSampleRate,
                initialDelayHops = 1 + backend.dryDelayHops,
            )
            var isolatedPeak = 0f
            var mixedPeak = 0f
            val pipeline = HopPipeline(48_000, backend.modelSampleRate, backend.hopSize) { dry, mixed, settings ->
                backend.process(dry, isolated)
                isolatedPeak = maxOf(isolatedPeak, isolated.maxOf { abs(it) })
                mixer.mix(dry, isolated, mixed, settings)
                mixedPeak = maxOf(mixedPeak, mixed.maxOf { abs(it) })
            }
            val settings = VoiceIsolationSettings(backgroundReduction = 0.65f)
            val pcm = ShortArray(5632 * 2)
            val float = FloatArray(pcm.size)
            var peak = 0f
            repeat(30) { block ->
                for (index in pcm.indices step 2) {
                    val sampleIndex = block * 5632 + index / 2
                    val time = sampleIndex.toDouble() / 48_000.0
                    var voice = 0.0
                    for (harmonic in 1..14) {
                        voice += sin(2.0 * PI * 110.0 * harmonic * time) / harmonic
                    }
                    val sample = (voice * 0.12 * 32768).toInt().coerceIn(-32768, 32767)
                    pcm[index] = sample.toShort()
                    pcm[index + 1] = sample.toShort()
                }
                PcmConverter.toFloatRange(pcm, float, 0, pcm.size)
                pipeline.process(float, 0, 5632, 0, settings)
                pipeline.process(float, 1, 5632, 0, settings)
                PcmConverter.toShortRange(float, pcm, 0, pcm.size)
                if (block >= 10) for (sample in pcm) peak = maxOf(peak, abs(sample.toInt()).toFloat())
            }
            assertTrue(
                "full channel returned silence, peak=$peak isolated=$isolatedPeak mixed=$mixedPeak underruns=${pipeline.underruns}",
                peak > 10f,
            )
        } finally {
            backend.close()
        }
    }
}
