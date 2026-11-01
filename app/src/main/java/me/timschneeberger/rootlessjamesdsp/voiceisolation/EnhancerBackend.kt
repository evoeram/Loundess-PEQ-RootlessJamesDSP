package me.timschneeberger.rootlessjamesdsp.voiceisolation

/**
 * A streaming speech enhancer that consumes and returns one frame of mono audio at a time.
 *
 * Implementations own their resampling-free internals: sample rate, frame size and recurrent state
 * are theirs, which is what lets [VoiceIsolationProcessor] wrap any of them in the same framing and
 * mixing chain.
 */
internal interface EnhancerBackend : AutoCloseable {
    /** Sample rate the model expects and produces. */
    val modelSampleRate: Int

    /** Frame size at [modelSampleRate] consumed by one [process] call. */
    val hopSize: Int

    /** Processes one hop; [output] receives [hopSize] samples, possibly from an older hop. */
    fun process(input: FloatArray, output: FloatArray)

    /**
     * Extra hops by which [process] answers later than the hop it was given.
     *
     * Every STFT-based backend already returns the previous hop, because the spectrum analysed from
     * hop `t` is synthesised as audio `t-1`, and [IsolationIntensityMixer] keeps one dry frame back
     * to match that. A model with lookahead answers even later: DeepFilterNet3 estimates the gains of
     * hop `t` for hop `t-2`, so it reports two here. The stage then holds the dry path back by the
     * same number of hops, without which `dry - isolated` subtracts two versions of the voice that no
     * longer line up and comb-filters exactly what the model was asked to keep.
     */
    val dryDelayHops: Int
        get() = 0

    /** Exponentially smoothed wall-clock duration of the last inference calls, in milliseconds. */
    fun inferenceMs(): Float

    /** Drops recurrent state and filter-bank history; equivalent to a freshly constructed backend. */
    fun reset()

    override fun close()
}
