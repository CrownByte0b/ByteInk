package com.vivenotes.byteink.testing

import java.awt.image.BufferedImage
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AndroidNotebookFidelityTest {
    @Test
    fun geometryIgnoresRowOrderAndAdmitsOnlyTheOracleTolerance() {
        compare("case\ta\t500.0\ncase\tb\t1.0\n", "case\tb\t1.00001\ncase\ta\t500.04\n") { result ->
            assertEquals(2, result.rows)
            assertEquals(2, result.values)
            assertEquals(1, result.mismatches)
            assertEquals(0.04, result.maxGap, 1e-8)
            assertTrue(result.issues.single().contains("case / a"))
        }
    }

    @Test
    fun geometryCatchesChangedIdentitiesAndNullGeometry() {
        compare("case\ta\tnull\ncase\tb\t1.0\n", "case\ta\t0.0\ncase\tc\t1.0\n") { result ->
            assertEquals(3, result.mismatches)
            assertEquals(0, result.values)
            assertEquals(3, result.issues.size)
        }
    }

    @Test
    fun identicalImagesHaveZeroErrorAndPerfectSsim() {
        val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB)
        val result = AndroidNotebookFidelity.pixels(image, image)
        assertEquals(0.0, result.mae)
        assertEquals(0, result.maximum)
        assertEquals(0, result.differingInk)
        assertEquals(1.0, result.ssim)
    }

    @Test
    fun rasterMetricsDetectAVisibleColourDifference() {
        val a = BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB)
        val b = BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB)
        for (y in 0..7) for (x in 0..7) b.setRGB(x, y, 0xff0000)
        val result = AndroidNotebookFidelity.pixels(a, b)
        assertEquals(85.0, result.mae)
        assertEquals(255, result.maximum)
        assertEquals(64, result.ink)
        assertEquals(64, result.differingInk)
        assertTrue(result.ssim < 0.01)
    }

    private fun compare(a: String, b: String, check: (AndroidNotebookFidelity.Geometry) -> Unit) {
        val directory = Files.createTempDirectory("fidelity-gate-").toFile()
        try {
            val left = directory.resolve("left.tsv").apply { writeText(a) }
            val right = directory.resolve("right.tsv").apply { writeText(b) }
            check(AndroidNotebookFidelity.geometry(left, right, keyColumns = 2))
        } finally { directory.deleteRecursively() }
    }
}
