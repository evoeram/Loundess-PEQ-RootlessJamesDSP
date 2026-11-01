package me.timschneeberger.rootlessjamesdsp.voiceisolation

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * DeepFilterNet3 ships as three chunk graphs that cannot be called frame by frame: their five GRUs
 * keep the recurrent state inside the graph, and the two time-mixing convolutions read their
 * neighbours from a zero `Pad`. [analysis/onnx_probe/df3_patch.py] re-exports them for streaming -
 * every hidden state becomes a graph input and output, and every `Pad` becomes a `Concat` that is
 * fed with the real past frames from the pipeline's ring buffers.
 *
 * The untouched upstream graphs are kept next to this test as the reference: the streamed calls
 * have to reproduce a single chunked call of the original, frame for frame. Anything else - a
 * reversed concatenation, a wrong history length - shows up as drift.
 */
class DeepFilterNet3GraphTest {
    private val bundled = File("src/main/assets")
    private val pristine = File("src/test/assets")
    private val environment = OrtEnvironment.getEnvironment()
    private val hiddenSize = 256
    private val erbBins = 32
    private val specBins = 96
    private val specChannels = 2
    private val channels = 64
    private val encoderHistory = 2
    private val dfHistory = 4

    private class Output(val data: FloatArray, val shape: LongArray) {
        val frames: Int get() = shape[1].toInt()
    }

    private fun open(directory: File, name: String): OrtSession = OrtSession.SessionOptions().use { options ->
        options.setIntraOpNumThreads(1)
        OrtEnvironment.getEnvironment().createSession(File(directory, name).readBytes(), options)
    }

    private fun run(
        session: OrtSession,
        inputs: Map<String, Pair<FloatArray, LongArray>>,
    ): Map<String, Output> {
        val tensors = inputs.mapValues { (_, payload) ->
            OnnxTensor.createTensor(environment, FloatBuffer.wrap(payload.first), payload.second)
        }
        return session.run(tensors).use { result ->
            result.mapNotNull { (name, value) ->
                val tensor = value as? OnnxTensor ?: return@mapNotNull null
                val buffer = tensor.floatBuffer
                name to Output(FloatArray(buffer.remaining()).also { buffer.get(it) }, tensor.info.shape)
            }.toMap()
        }.also { tensors.values.forEach(OnnxTensor::close) }
    }

    private fun erbFeatures(frames: Int) = FloatArray(frames * erbBins) { index ->
        val time = index / erbBins
        val band = index % erbBins
        (sin(0.1 * time + 0.05 * band) * 0.4 - 0.2).toFloat()
    }

    private fun specFeatures(frames: Int) = FloatArray(specChannels * frames * specBins) { index ->
        val channel = index / (frames * specBins)
        val rest = index % (frames * specBins)
        (sin(0.2 * (rest / specBins) + 0.01 * (rest % specBins) + channel * 1.5f) * 0.3).toFloat()
    }

    /**
     * Extracts [count] consecutive frames from a channel-major `[1, channels, frames, width]`
     * tensor. Frames outside the source stay zero, which is what the graph padded before the patch.
     */
    private fun window(
        data: FloatArray,
        channels: Int,
        frames: Int,
        start: Int,
        width: Int,
        count: Int,
    ): FloatArray {
        val out = FloatArray(channels * count * width)
        for (channel in 0 until channels) {
            for (frame in 0 until count) {
                val source = start + frame
                if (source in 0 until frames) {
                    System.arraycopy(
                        data,
                        (channel * frames + source) * width,
                        out,
                        (channel * count + frame) * width,
                        width,
                    )
                }
            }
        }
        return out
    }

    private fun tensor(frames: Int, width: Int, channels: Int = 1) = longArrayOf(
        1,
        channels.toLong(),
        frames.toLong(),
        width.toLong(),
    )

    private fun stateShape() = longArrayOf(1, 1, hiddenSize.toLong())

    /** One streamed encoder call: this hop plus the frames the patched `Pad` used to zero-fill. */
    private fun encoderInputs(
        erb: FloatArray,
        spec: FloatArray,
        frames: Int,
        step: Int,
        state: FloatArray,
    ) = buildMap<String, Pair<FloatArray, LongArray>> {
        for (offset in 0 until encoderHistory) {
            put(
                "stream_erb_conv0_in${offset + 1}",
                window(erb, 1, frames, step - encoderHistory + offset, erbBins, 1) to tensor(1, erbBins),
            )
            put(
                "stream_df_conv0_in${offset + 1}",
                window(spec, specChannels, frames, step - encoderHistory + offset, specBins, 1) to
                    tensor(1, specBins, specChannels),
            )
        }
        put("feat_erb", window(erb, 1, frames, step, erbBins, 1) to tensor(1, erbBins))
        put("feat_spec", window(spec, specChannels, frames, step, specBins, 1) to tensor(1, specBins, specChannels))
        put("stream_h1_in", state to stateShape())
    }

    private fun chunkedEncoderInputs(erb: FloatArray, spec: FloatArray, frames: Int) = mapOf(
        "feat_erb" to (erb to tensor(frames, erbBins)),
        "feat_spec" to (spec to tensor(frames, specBins, specChannels)),
    )

    private fun erbDecoderInputs(encoder: Map<String, Output>, frames: Int, states: List<FloatArray>?) =
        buildMap<String, Pair<FloatArray, LongArray>> {
            put("emb", encoder.getValue("emb").data to longArrayOf(1, frames.toLong(), 512))
            for (name in listOf("e3", "e2", "e1", "e0")) {
                val skip = encoder.getValue(name)
                put(name, skip.data to tensor(frames, skip.shape[3].toInt(), skip.shape[1].toInt()))
            }
            if (states != null) {
                put("stream_h1_in", states[0] to stateShape())
                put("stream_h2_in", states[1] to stateShape())
            }
        }

    /** One streamed deep-filter call: the hop's `c0` plus the four frames before it from the ring. */
    private fun dfDecoderInputs(
        emb: FloatArray,
        history: List<FloatArray>,
        states: List<FloatArray>,
    ): Map<String, Pair<FloatArray, LongArray>> {
        require(history.size == dfHistory + 1)
        return buildMap {
            for (offset in 0 until dfHistory) {
                put("stream_df_convp_in${offset + 1}", history[offset] to tensor(1, specBins, channels))
            }
            put("c0", history[dfHistory] to tensor(1, specBins, channels))
            put("emb", emb to longArrayOf(1, 1, 512))
            put("stream_h1_in", states[0] to stateShape())
            put("stream_h2_in", states[1] to stateShape())
        }
    }

    private fun chunkedDfDecoderInputs(encoder: Map<String, Output>, frames: Int) = mapOf(
        "emb" to (encoder.getValue("emb").data to longArrayOf(1, frames.toLong(), 512)),
        "c0" to (
            encoder.getValue("c0").data to tensor(frames, specBins, channels)
            ),
    )

    /** The time axis of each graph output: `[1,S,*]` for embeddings, `[1,C,S,*]` for feature maps. */
    private val framesAxes = mapOf(
        "emb" to 1,
        "lsnr" to 1,
        "coefs" to 1,
        "e0" to 2,
        "e1" to 2,
        "e2" to 2,
        "e3" to 2,
        "c0" to 2,
        "m" to 2,
    )

    /**
     * Largest distance between one frame of `left` and one frame of `right`. Each tensor is indexed
     * with its own frame count, so a streamed one-frame output can face a multi-frame reference.
     */
    private fun hopDiff(name: String, left: Output, leftFrame: Int, right: Output, rightFrame: Int): Float {
        val axis = framesAxes.getValue(name)
        fun Output.spanned(): Triple<Int, Int, Int> {
            val groups = shape.take(axis).fold(1L) { product, size -> product * size }.toInt()
            val frames = shape[axis].toInt()
            return Triple(groups, frames, data.size / (groups * frames))
        }
        val (leftGroups, leftFrames, width) = left.spanned()
        val (rightGroups, rightFrames, rightWidth) = right.spanned()
        require(width == rightWidth) { "$name has different widths: $width and $rightWidth" }
        require(leftGroups == rightGroups) { "$name has different leading dimensions" }
        var worst = 0f
        for (group in 0 until leftGroups) {
            for (index in 0 until width) {
                val leftOffset = (group * leftFrames + leftFrame) * width + index
                val rightOffset = (group * rightFrames + rightFrame) * width + index
                worst = max(worst, abs(left.data[leftOffset] - right.data[rightOffset]))
            }
        }
        return worst
    }

    @Test
    fun upstreamGraphsAreCausalOverTime() {
        assumeTrue(File(pristine, "deepfilternet3_enc_chunk.onnx").exists())
        val encoder = open(pristine, "deepfilternet3_enc_chunk.onnx")
        val frames = 8
        val erb = erbFeatures(frames)
        val spec = specFeatures(frames)
        val perturbedFrame = 3

        val base = run(encoder, chunkedEncoderInputs(erb, spec, frames))
        fun bump(data: FloatArray, frame: Int, width: Int) = data.copyOf().also { copy ->
            for (bin in 0 until width) copy[frame * width + bin] += 0.5f
        }

        for ((branch, moved) in listOf(
            "erb" to run(encoder, chunkedEncoderInputs(bump(erb, perturbedFrame, erbBins), spec, frames)),
            "spec" to run(encoder, chunkedEncoderInputs(erb, bump(spec, perturbedFrame, specBins), frames)),
        )) {
            // e0 is only written by the erb branch, c0 only by the spec branch, emb by both.
            for (name in listOf("e0", "c0", "emb")) {
                for (hop in 0 until perturbedFrame) {
                    assertEquals(
                        "$branch output $name at hop $hop may not read frame $perturbedFrame",
                        0f,
                        hopDiff(name, moved.getValue(name), hop, base.getValue(name), hop),
                        1e-5f,
                    )
                }
            }
            assertTrue(
                "$branch output has to read frame $perturbedFrame",
                hopDiff("c0", moved.getValue("c0"), perturbedFrame, base.getValue("c0"), perturbedFrame) > 0f ||
                    hopDiff("e0", moved.getValue("e0"), perturbedFrame, base.getValue("e0"), perturbedFrame) > 0f,
            )
        }
        encoder.close()
    }

    /** Worst hop distance between two chunked outputs of the same shape. */
    private fun chunkDifference(name: String, left: Output, right: Output, frames: Int): Float {
        var worst = 0f
        for (hop in 0 until frames) {
            worst = max(worst, hopDiff(name, left, hop, right, hop))
        }
        return worst
    }

    /**
     * The re-export must be identity when the history frames are zero, which is exactly what the
     * original `Pad` fed. Failing here means the concatenation itself is wrong, not the driver.
     */
    @Test
    fun patchedGraphsReproduceTheChunkCallWithEmptyHistory() {
        assumeTrue(File(bundled, "deepfilternet3_enc.onnx").exists())
        assumeTrue(File(pristine, "deepfilternet3_enc_chunk.onnx").exists())
        val frames = 8
        val erb = erbFeatures(frames)
        val spec = specFeatures(frames)
        val states = List(2) { FloatArray(hiddenSize) }

        val encoder = open(bundled, "deepfilternet3_enc.onnx")
        val referenceEncoder = open(pristine, "deepfilternet3_enc_chunk.onnx")
        val streamed = run(
            encoder,
            chunkedEncoderInputs(erb, spec, frames) + mapOf(
                "stream_erb_conv0_in1" to (FloatArray(erbBins) to tensor(1, erbBins)),
                "stream_erb_conv0_in2" to (FloatArray(erbBins) to tensor(1, erbBins)),
                "stream_df_conv0_in1" to (FloatArray(specChannels * specBins) to tensor(1, specBins, specChannels)),
                "stream_df_conv0_in2" to (FloatArray(specChannels * specBins) to tensor(1, specBins, specChannels)),
                "stream_h1_in" to (states[0] to stateShape()),
            ),
        )
        val reference = run(referenceEncoder, chunkedEncoderInputs(erb, spec, frames))
        for (name in streamed.keys.filterNot { it.startsWith("stream_") }) {
            assertEquals(
                "patched encoder output $name differs in chunk mode",
                0f,
                chunkDifference(name, streamed.getValue(name), reference.getValue(name), frames),
                1e-4f,
            )
        }

        val erbDecoder = open(bundled, "deepfilternet3_erb_dec.onnx")
        val referenceErbDecoder = open(pristine, "deepfilternet3_erb_dec_chunk.onnx")
        val streamedMask = run(erbDecoder, erbDecoderInputs(streamed, frames, states))
        val referenceMask = run(referenceErbDecoder, erbDecoderInputs(reference, frames, null))
        assertEquals(
            "patched ERB decoder differs in chunk mode",
            0f,
            chunkDifference("m", streamedMask.getValue("m"), referenceMask.getValue("m"), frames),
            1e-4f,
        )

        val dfDecoder = open(bundled, "deepfilternet3_df_dec.onnx")
        val referenceDfDecoder = open(pristine, "deepfilternet3_df_dec_chunk.onnx")
        val streamedCoefs = run(
            dfDecoder,
            chunkedDfDecoderInputs(streamed, frames) + buildMap {
                for (offset in 0 until dfHistory) {
                    put(
                        "stream_df_convp_in${offset + 1}",
                        FloatArray(channels * specBins) to tensor(1, specBins, channels),
                    )
                }
                put("stream_h1_in", states[0] to stateShape())
                put("stream_h2_in", states[1] to stateShape())
            },
        )
        val referenceCoefs = run(referenceDfDecoder, chunkedDfDecoderInputs(reference, frames))
        assertEquals(
            "patched deep filter decoder differs in chunk mode",
            0f,
            chunkDifference("coefs", streamedCoefs.getValue("coefs"), referenceCoefs.getValue("coefs"), frames),
            1e-4f,
        )

        encoder.close()
        referenceEncoder.close()
        erbDecoder.close()
        referenceErbDecoder.close()
        dfDecoder.close()
        referenceDfDecoder.close()
    }

    @Test
    fun perFrameStreamingReproducesTheChunkedRun() {
        assumeTrue(File(bundled, "deepfilternet3_enc.onnx").exists())
        assumeTrue(File(pristine, "deepfilternet3_enc_chunk.onnx").exists())
        val streamedEncoder = open(bundled, "deepfilternet3_enc.onnx")
        val streamedErbDecoder = open(bundled, "deepfilternet3_erb_dec.onnx")
        val streamedDfDecoder = open(bundled, "deepfilternet3_df_dec.onnx")
        val chunkedEncoder = open(pristine, "deepfilternet3_enc_chunk.onnx")
        val chunkedErbDecoder = open(pristine, "deepfilternet3_erb_dec_chunk.onnx")
        val chunkedDfDecoder = open(pristine, "deepfilternet3_df_dec_chunk.onnx")

        val frames = 8
        val erb = erbFeatures(frames)
        val spec = specFeatures(frames)

        val referenceEncoder = run(chunkedEncoder, chunkedEncoderInputs(erb, spec, frames))
        val referenceMask = run(chunkedErbDecoder, erbDecoderInputs(referenceEncoder, frames, null))
        val referenceCoefs = run(chunkedDfDecoder, chunkedDfDecoderInputs(referenceEncoder, frames))
        assertEquals(frames, referenceEncoder.getValue("emb").frames)
        assertEquals(frames, referenceMask.getValue("m").shape[2].toInt())
        assertEquals(frames, referenceCoefs.getValue("coefs").frames)

        val encoderState = FloatArray(hiddenSize)
        val maskStates = listOf(FloatArray(hiddenSize), FloatArray(hiddenSize))
        val dfStates = listOf(FloatArray(hiddenSize), FloatArray(hiddenSize))
        val c0Frames = ArrayList<FloatArray>()
        val drift = LinkedHashMap<String, Float>()
        var worstEncoder = 0f
        var worstMask = 0f
        var worstCoefs = 0f

        for (step in 0 until frames) {
            val encoded = run(streamedEncoder, encoderInputs(erb, spec, frames, step, encoderState))
            c0Frames.add(encoded.getValue("c0").data)
            for (name in listOf("emb", "lsnr", "e0", "e1", "e2", "e3", "c0")) {
                val difference = hopDiff(name, encoded.getValue(name), 0, referenceEncoder.getValue(name), step)
                drift[name] = max(drift.getOrDefault(name, 0f), difference)
                worstEncoder = max(worstEncoder, difference)
            }

            val mask = run(streamedErbDecoder, erbDecoderInputs(encoded, 1, maskStates))
            worstMask = max(worstMask, hopDiff("m", mask.getValue("m"), 0, referenceMask.getValue("m"), step))

            val coefs = run(
                streamedDfDecoder,
                dfDecoderInputs(
                    encoded.getValue("emb").data,
                    List(dfHistory + 1) { index ->
                        val source = step - dfHistory + index
                        if (source < 0) FloatArray(channels * specBins) else c0Frames[source]
                    },
                    dfStates,
                ),
            )
            worstCoefs = max(
                worstCoefs,
                hopDiff("coefs", coefs.getValue("coefs"), 0, referenceCoefs.getValue("coefs"), step),
            )

            encoded.getValue("stream_h1_out").data.copyInto(encoderState)
            mask.getValue("stream_h1_out").data.copyInto(maskStates[0])
            mask.getValue("stream_h2_out").data.copyInto(maskStates[1])
            coefs.getValue("stream_h1_out").data.copyInto(dfStates[0])
            coefs.getValue("stream_h2_out").data.copyInto(dfStates[1])
        }

        assertEquals("streamed encoder output drifts from the chunked upstream graph: $drift", 0f, worstEncoder, 1e-3f)
        assertEquals("streamed ERB gains drift from the chunked upstream graph", 0f, worstMask, 1e-3f)
        assertEquals("streamed deep filter coefficients drift", 0f, worstCoefs, 1e-3f)

        streamedEncoder.close()
        streamedErbDecoder.close()
        streamedDfDecoder.close()
        chunkedEncoder.close()
        chunkedErbDecoder.close()
        chunkedDfDecoder.close()
    }
}
