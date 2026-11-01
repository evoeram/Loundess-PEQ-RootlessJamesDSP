package me.timschneeberger.rootlessjamesdsp.voiceisolation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin

/**
 * Runs the streaming DeepFilterNet3 backend over synthetic material.
 *
 * [DeepFilterNet3GraphTest] proves the three graphs still compute what upstream did; what is left to
 * prove here is the driver around them -- that the features, the two-stage gating and the deep-filter
 * window turn a noisy signal into a quieter one, and that the enhanced audio arrives in the hop the
 * reference promises. The lag is the load-bearing part: [VoiceIsolationProcessor] holds the dry path
 * back by [EnhancerBackend.dryDelayHops] on the strength of it, and a wrong number there makes the
 * mixer subtract a misaligned copy of the voice from itself.
 */
class DeepFilterNet3ProcessorTest {
    private val sampleRate = 48_000
    private val hopSize = 480
    private val hops = 120
    private val noise = 0.02

    private fun assets(): List<ByteArray> = GRAPH_ASSETS.map { name ->
        val file = File("src/main/assets/$name")
        assumeTrue("bundled DeepFilterNet3 graphs are missing", file.isFile)
        file.readBytes()
    }

    /**
     * Noise at both ends with an aperiodically gated harmonic voice in the middle. The gate pattern is
     * what makes the delay measurable: a periodic voice would correlate just as well one period away.
     */
    private fun material(gap: Boolean): Pair<FloatArray, BooleanArray> {
        val total = hops * hopSize
        val samples = FloatArray(total)
        val voiced = BooleanArray(hops)
        var seed = 987654321L
        for (hop in 0 until hops) {
            voiced[hop] = hop in VOICED_FIRST..VOICED_LAST && when {
                gap && hop in GAP_FIRST..GAP_LAST -> false
                else -> {
                    seed = seed * 6364136223846793005L + 1442695040888963407L
                    (seed ushr 33) % 5 != 0L
                }
            }
        }

        var noiseSeed = 246353421L
        for (index in 0 until total) {
            noiseSeed = noiseSeed * 6364136223846793005L + 1442695040888963407L
            val background = (noiseSeed ushr 40).toDouble() / (1L shl 24) - 0.5
            val hop = index / hopSize
            var voice = 0.0
            if (voiced[hop]) {
                val time = index.toDouble() / sampleRate
                for (harmonic in 1..14) {
                    voice += sin(2.0 * PI * 110.0 * harmonic * time) / harmonic
                }
            }
            samples[index] = (voice * VOICE_LEVEL + background * noise).toFloat()
        }
        return samples to voiced
    }

    private fun process(backend: EnhancerBackend, input: FloatArray): FloatArray {
        val output = FloatArray(input.size)
        val frame = FloatArray(hopSize)
        val result = FloatArray(hopSize)
        var hop = 0
        while (hop < hops) {
            input.copyInto(frame, 0, hop * hopSize, (hop + 1) * hopSize)
            backend.process(frame, result)
            result.copyInto(output, hop * hopSize)
            hop++
        }
        return output
    }

    /** Per-hop energy of the enhanced signal, which is what the gate pattern shows up in. */
    private fun envelope(samples: FloatArray): DoubleArray = DoubleArray(hops) { hop ->
        var sum = 0.0
        for (index in hop * hopSize until (hop + 1) * hopSize) {
            sum += samples[index].toDouble() * samples[index]
        }
        sum / hopSize
    }

    private fun correlation(reference: BooleanArray, energy: DoubleArray, lag: Int): Double {
        var sum = 0.0
        for (hop in VOICED_FIRST..VOICED_LAST) {
            val value = energy[(hop + lag).coerceAtMost(hops - 1)]
            sum += if (reference[hop]) value else -value
        }
        return sum
    }

    private fun bestLag(reference: BooleanArray, energy: DoubleArray): Map<Int, Double> =
        (0..6).associateWith { lag -> correlation(reference, energy, lag) }

    @Test
    fun `enhanced audio trails the input by three hops`() {
        val backend = DeepFilterNet3Processor(assets())
        try {
            val (input, voiced) = material(gap = false)
            val lags = bestLag(voiced, envelope(process(backend, input)))
            val worstNeighbour = max(lags[2]!!, lags[4]!!)
            assertTrue(
                "hop 3 is not the clear winner: " + lags.entries.joinToString { "${it.key}=%.2e".format(it.value) },
                lags[3]!! > 1.5 * worstNeighbour
            )
        } finally {
            backend.close()
        }
    }

    /**
     * Skipped silent hops must not compress the timeline. The reference answers a long silence with
     * zeros without analysing anything at all, which shifts every later frame closer to the front; the
     * backend keeps the STFT running instead, so the gate pattern has to reappear three hops late
     * across a two-and-a-half second gap too.
     */
    @Test
    fun `a silence gap does not slide the voice forward`() {
        val backend = DeepFilterNet3Processor(assets())
        try {
            val (input, voiced) = material(gap = true)
            val lags = bestLag(voiced, envelope(process(backend, input)))
            val worstNeighbour = max(lags[2]!!, lags[4]!!)
            assertTrue(
                "gap shifted the alignment: " + lags.entries.joinToString { "${it.key}=%.2e".format(it.value) },
                lags[3]!! > 1.5 * worstNeighbour
            )
        } finally {
            backend.close()
        }
    }

    @Test
    fun `keeps the voice and throws away the background`() {
        val backend = DeepFilterNet3Processor(assets())
        try {
            val (input, _) = material(gap = false)
            val output = process(backend, input)
            assertTrue("backend produced NaN or infinity", output.all { it.isFinite() })

            // Output hop t holds input hop t-3, so the windows shift by that much.
            val shift = 3 * hopSize
            val speechFrom = VOICED_FIRST * hopSize + shift
            val speechTo = (VOICED_LAST + 1) * hopSize + shift
            val voiceIn = peak(input, speechFrom, speechTo)
            val voiceOut = peak(output, speechFrom, speechTo)
            assertTrue("voice peak fell from $voiceIn to $voiceOut", voiceOut > 0.45f * voiceIn)

            // Both ends are pure background; the tail keeps three hops out because the last output
            // hops would otherwise read past the input.
            val backgroundFrom = (VOICED_LAST + 1) * hopSize
            val backgroundTo = (hops - 3) * hopSize
            val inPower = power(input, backgroundFrom, backgroundTo)
            val outPower = power(output, backgroundFrom + shift, backgroundTo + shift)
            assertTrue(
                "background only got %.1f dB quieter".format(10 * log10(outPower / inPower)),
                outPower < 0.02 * inPower
            )
        } finally {
            backend.close()
        }
    }

    @Test
    fun `reset rewinds every ring and state`() {
        val backend = DeepFilterNet3Processor(assets())
        try {
            val (input, _) = material(gap = false)
            val first = process(backend, input)
            backend.reset()
            val second = process(backend, input)

            var worst = 0f
            for (index in first.indices) worst = max(worst, abs(first[index] - second[index]))
            assertEquals(0f, worst, 1e-6f)
            assertTrue("the backend never produced anything", first.any { abs(it) > 0.01f })
        } finally {
            backend.close()
        }
    }

    private fun peak(samples: FloatArray, from: Int, to: Int): Float {
        var highest = 0f
        for (index in from until to) highest = max(highest, abs(samples[index]))
        return highest
    }

    private fun power(samples: FloatArray, from: Int, to: Int): Double {
        var sum = 0.0
        for (index in from until to) sum += samples[index].toDouble() * samples[index]
        return sum / (to - from)
    }

    companion object {
        private val GRAPH_ASSETS = listOf(
            "deepfilternet3_enc.onnx",
            "deepfilternet3_erb_dec.onnx",
            "deepfilternet3_df_dec.onnx",
        )

        private const val VOICE_LEVEL = 0.12
        private val VOICED_FIRST = 20
        private val VOICED_LAST = 95
        private val GAP_FIRST = 45
        private val GAP_LAST = 70
    }
}
