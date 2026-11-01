package me.timschneeberger.rootlessjamesdsp.voiceisolation

/**
 * PCM16 <-> float conversion conventions of the ported pipeline (1/32768 scale, truncating
 * quantization without dither). Ported from Voice Isolation for Android (MIT).
 */
internal object PcmConverter {
    private const val SHORT_SCALE = 32768f

    fun toFloat(input: ShortArray, output: FloatArray, count: Int = input.size) {
        require(count <= input.size && count <= output.size)
        for (index in 0 until count) {
            output[index] = input[index] / SHORT_SCALE
        }
    }

    fun toShort(input: FloatArray, output: ShortArray, gain: Float = 1f) {
        require(input.size <= output.size)
        for (index in input.indices) {
            val sample = (input[index] * gain).coerceIn(-1f, 0.9999695f)
            output[index] = (sample * SHORT_SCALE).toInt().toShort()
        }
    }

    /** Same conversions restricted to the [offset, offset + count) region of the PCM16 buffer. */
    fun toFloatRange(input: ShortArray, output: FloatArray, offset: Int, count: Int) {
        require(offset >= 0 && count in 0..(input.size - offset) && count <= output.size)
        for (index in 0 until count) {
            output[index] = input[offset + index] / SHORT_SCALE
        }
    }

    fun toShortRange(input: FloatArray, output: ShortArray, offset: Int, count: Int) {
        require(offset >= 0 && count in 0..(output.size - offset) && count <= input.size)
        for (index in 0 until count) {
            val sample = input[index].coerceIn(-1f, 0.9999695f)
            output[offset + index] = (sample * SHORT_SCALE).toInt().toShort()
        }
    }
}
