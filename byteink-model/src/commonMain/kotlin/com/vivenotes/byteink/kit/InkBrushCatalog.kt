package com.vivenotes.byteink.kit

/** Shared, pinned storage/brush rules. Backends construct the real Ink family from this definition. */
public object InkBrushCatalog {
    public const val MARKER: String = "marker"
    public const val PRESSURE_PEN: String = "pressure-pen"
    public const val DASHED_LINE: String = "dashed-line"
    public const val HIGHLIGHTER: String = "highlighter"
    public const val BRUSH_VERSION: Int = 1
    public const val EPSILON: Float = 0.25f
    public const val MAX_CALLIGRAPHY_PRESSURE: Int = 5
    public const val MAX_STABILIZATION: Int = 5
    private const val CALLIGRAPHY_PREFIX = "calligraphy-v1-p"

    public fun calligraphy(pressure: Int): String = CALLIGRAPHY_PREFIX + pressure.coerceIn(0, 5)

    public fun penFamilyId(solidLine: Boolean, fountain: Boolean, pressure: Int): String = when {
        !solidLine -> DASHED_LINE
        fountain -> MARKER
        else -> calligraphy(pressure)
    }

    /** Zero means passthrough; positive values are sliding windows at 180 Hz. */
    public fun windowMillis(stabilization: Int): Int = when (stabilization.coerceIn(0, 5)) {
        0 -> 0; 1 -> 20; 2 -> 40; 3 -> 60; 4 -> 90; else -> 120
    }

    public fun describe(familyId: String, stabilization: Int): InkFamilyDefinition {
        val pressure = if (familyId.startsWith(CALLIGRAPHY_PREFIX))
            familyId.removePrefix(CALLIGRAPHY_PREFIX).toIntOrNull()?.coerceIn(0, 5) ?: 3 else 0
        val kind = when {
            familyId == MARKER -> 0
            familyId == HIGHLIGHTER -> 2
            familyId == DASHED_LINE -> 3
            familyId.startsWith(CALLIGRAPHY_PREFIX) -> 4
            else -> 1 // The Android fallback for unknown IDs.
        }
        val response = pressure.toFloat() / 5
        return InkFamilyDefinition(kind, pressure, if (kind == 2) -1 else windowMillis(stabilization),
            1f + (0.45f - 1f) * response, 1f + (1.6f - 1f) * response)
    }
}

/** A highlighter's window is -1: keep its stock input model regardless of stored stabilization. */
public data class InkFamilyDefinition(
    public val kind: Int,
    public val pressureLevel: Int,
    public val windowMillis: Int,
    public val minSize: Float,
    public val maxSize: Float,
)
