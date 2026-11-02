package com.vivenotes.byteink.testing

/**
 * The largest per-channel RGB difference a software (Skia raster) rendering may have from a
 * reference rendering of the same, exactly equal, ByteInk geometry.
 *
 * x86_64 keeps the original bound of 2. Skia's arm64 raster pipeline (NEON) rounds edge coverage
 * under transforms and translucent compositing differently from its x86 one (SSE/AVX): on arm64 a
 * few pixels of otherwise identical images reach 4–6 levels from the x86_64 and Android references,
 * while ByteInk's geometry and encodings stay exact (native/ARM64.md). The bound follows the
 * architecture of the JVM that renders and compares, which is the platform under test.
 * byteink-compose's InkSceneRasterCacheTest applies the same bound.
 */
internal object SoftwareRaster {
    val arm64: Boolean = System.getProperty("os.arch").lowercase() in setOf("aarch64", "arm64")

    val maxChannelDelta: Int = if (arm64) 6 else 2

    /** The bound as reports state it. */
    val description: String = if (arm64) "≤$maxChannelDelta (Skia's arm64 raster pipeline; ≤2 on x86_64)" else "≤$maxChannelDelta"
}
