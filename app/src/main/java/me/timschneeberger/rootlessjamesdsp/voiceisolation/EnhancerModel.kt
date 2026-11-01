package me.timschneeberger.rootlessjamesdsp.voiceisolation

/**
 * Enhancer models shipped with the app. The preference stores [key]; everything else here only
 * drives the framing, the settings summary and the log line, while the networks themselves
 * describe their tensor contract through ONNX metadata.
 */
enum class EnhancerModel(
    val key: String,
    val assets: List<String>,
    /** Rate and hop of the running pipeline, used to describe the model before it is loaded. */
    val modelSampleRate: Int,
    val hopSize: Int,
) {
    GTCRN("gtcrn", listOf("gtcrn.onnx"), 16_000, 256),
    DEEPFILTERNET3(
        "deepfilternet3",
        listOf("deepfilternet3_enc.onnx", "deepfilternet3_erb_dec.onnx", "deepfilternet3_df_dec.onnx"),
        48_000,
        480,
    ),
    DPDFNET2("dpdfnet2_48khz_hr", listOf("dpdfnet2_48khz_hr.onnx"), 48_000, 480),
    DPDFNET8("dpdfnet8_48khz_hr", listOf("dpdfnet8_48khz_hr.onnx"), 48_000, 480),
    ;

    internal fun create(modelBytes: List<ByteArray>): EnhancerBackend = when (this) {
        GTCRN -> GtcrnProcessor(modelBytes.first())
        DEEPFILTERNET3 -> DeepFilterNet3Processor(modelBytes)
        DPDFNET2, DPDFNET8 -> DpdfNetProcessor(modelBytes.first())
    }

    companion object {
        val Default = GTCRN

        fun fromKey(key: String?): EnhancerModel = entries.firstOrNull { it.key == key } ?: Default
    }
}
