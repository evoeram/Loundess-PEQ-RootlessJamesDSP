package me.timschneeberger.rootlessjamesdsp.measurement

/**
 * Target type presets for Auto-EQ.
 *
 * Each preset defines a match range and a default target curve shape.
 * Inspired by REW's target type selector.
 *
 * @param displayName human-readable name
 * @param matchRangeStart start of the correction range (Hz)
 * @param matchRangeEnd end of the correction range (Hz)
 * @param defaultCurve default target curve for this target type
 */
enum class TargetType(
    val displayName: String,
    val matchRangeStart: Double,
    val matchRangeEnd: Double,
    val defaultCurve: TargetCurve
) {
    FULL_RANGE(
        "Full Range",
        20.0, 20000.0,
        TargetCurve.flat()
    ),
    BOOKSHELF(
        "Bookshelf",
        60.0, 20000.0,
        TargetCurve.flat()
    ),
    SUBWOOFER(
        "Subwoofer",
        10.0, 200.0,
        TargetCurve.flat()
    ),
    HARMAN_OVER_EAR(
        "Harman (Over-Ear)",
        20.0, 10000.0,
        TargetCurve.harman()
    ),
    ROOM_CURVE(
        "Room Curve",
        20.0, 20000.0,
        TargetCurve.roomCurve()
    );
}
