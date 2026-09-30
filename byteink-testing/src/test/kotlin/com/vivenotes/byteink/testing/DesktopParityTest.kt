package com.vivenotes.byteink.testing

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class DesktopParityTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun largeProjectionCountsCannotHideInsideTheGeometryTolerance() {
        fun fixture(name: String, count: Int): File = temporary.newFolder(name).apply {
            resolve("runtime.txt").writeText(name)
            resolve("cases.txt").writeText("notebook-0/page-0/stored\n")
            resolve("bounds.tsv").writeText("")
            resolve("projections.tsv").writeText("notebook-0/page-0/stored\tcount\tprojections\t$count\n")
            val image = resolve("notebook-0/page-0/stored.png")
            image.parentFile.mkdirs()
            ImageIO.write(BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "PNG", image)
        }
        val reference = fixture("reference", 100_000)
        val candidate = fixture("candidate", 100_001)
        val failure = assertFailsWith<IllegalStateException> { DesktopParity.compare(reference, candidate) }
        assertTrue(failure.message!!.contains("geometry differs"))
        assertTrue(candidate.resolve("comparison.md").readText().contains("exact counts: false"))
    }
}
