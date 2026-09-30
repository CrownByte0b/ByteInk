package com.vivenotes.byteink.oracle

import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.StrokeInputBatch
import java.util.UUID

/** The synthetic inputs and viewports are shared; brush construction remains platform-specific. */
internal object SyntheticFidelityMatrix {
    const val header = "id\tpageId\tfamily\tstabilization\ttool\tscenario\twidth\theight\tscale\tleft\ttop\tthemeArgb"
    val families = listOf("marker", "dashed-line", "highlighter", "pressure-pen") +
        (0..5).map { "calligraphy-v1-p$it" }
    val tools = listOf(InputToolType.UNKNOWN, InputToolType.MOUSE, InputToolType.TOUCH, InputToolType.STYLUS)
    val cases: List<MatrixCase> = families.flatMap { family ->
        (0..5).flatMap { level -> tools.map { tool -> case(family, level, tool, "brush") } } +
            tools.map { tool -> case(family, 3, tool, "operations") }
    }
    val sizes = listOf(2f, 8f, 20f)
    val colors = listOf(0xff173b83.toInt(), 0x80522db5.toInt(), 0xffe04040.toInt())
    fun uuid(key: String): String = UUID.nameUUIDFromBytes("byteink-fidelity-v1:$key".toByteArray(Charsets.UTF_8)).toString()
    fun strokeId(case: MatrixCase, variant: Int): String = uuid("${case.id}:stroke:$variant")
    fun tool(name: String): InputToolType = tools.single { it.toString().substringAfterLast('.').lowercase() == name.lowercase() }

    private fun case(family: String, level: Int, tool: InputToolType, scenario: String): MatrixCase {
        val toolName = tool.toString().substringAfterLast('.').lowercase()
        val id = "$family-s$level-$toolName-$scenario"
        return MatrixCase(id, uuid("page:$id"), family, level, toolName, scenario)
    }

    /** Integer/rational coordinates avoid depending on platform trigonometric implementations. */
    fun inputs(tool: InputToolType, variant: Int): StrokeInputBatch {
        val path = listOf(28 to 0, 52 to -24, 92 to -28, 136 to -12, 176 to 20,
            196 to 0, 172 to -22, 128 to 12, 92 to 28, 52 to 22, 28 to 0,
            52 to -24, 92 to -28, 136 to -12, 176 to 20, 196 to 0)
        return MutableStrokeInputBatch().apply {
            var index = 0
            path.zipWithNext().forEach { (a, b) ->
                repeat(4) { step ->
                    val fraction = step / 4f
                    val pressure = (2 + index % 9) / 12f
                    val x = a.first + (b.first - a.first) * fraction
                    val y = 44f + variant * 100f + a.second + (b.second - a.second) * fraction
                    if (tool == InputToolType.STYLUS) {
                        add(tool, x, y, index * 8L, pressure = pressure,
                            tiltRadians = (index % 7) / 16f, orientationRadians = (index % 11) / 8f)
                    } else add(tool, x, y, index * 8L)
                    index++
                }
            }
            val final = path.last()
            if (tool == InputToolType.STYLUS) {
                add(tool, final.first.toFloat(), 44f + variant * 100f + final.second, index * 8L,
                    pressure = (2 + index % 9) / 12f, tiltRadians = (index % 7) / 16f,
                    orientationRadians = (index % 11) / 8f)
            } else add(tool, final.first.toFloat(), 44f + variant * 100f + final.second, index * 8L)
        }.toImmutable()
    }
}

internal data class MatrixCase(
    val id: String,
    val pageId: String,
    val family: String,
    val stabilization: Int,
    val tool: String,
    val scenario: String,
    val width: Int = 480,
    val height: Int = 640,
    val scale: Float = 2f,
    val left: Float = 0f,
    val top: Float = 0f,
    val themeArgb: Int = 0xff006b61.toInt(),
) {
    fun toTsv(): String = listOf(id, pageId, family, stabilization, tool, scenario,
        width, height, scale, left, top, themeArgb).joinToString("\t")

    companion object {
        fun parse(line: String): MatrixCase = line.split('\t').let { p ->
            require(p.size == 12) { "Malformed matrix case" }
            MatrixCase(p[0], p[1], p[2], p[3].toInt(), p[4], p[5], p[6].toInt(),
                p[7].toInt(), p[8].toFloat(), p[9].toFloat(), p[10].toFloat(), p[11].toInt())
        }
    }
}
