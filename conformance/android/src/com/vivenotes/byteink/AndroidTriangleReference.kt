package com.vivenotes.byteink

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import androidx.ink.brush.SelfOverlap
import androidx.ink.geometry.MutableVec
import androidx.ink.geometry.PartitionedMesh
import androidx.ink.geometry.getRawTriangleIndexBuffer
import com.vivenotes.ink.CanvasInkPainter
import com.vivenotes.ink.PageStroke

/**
 * Independent Android software reference for the path port's split-stroke fallback.
 * The pinned Android renderer omits split pieces with no outlines; their actual Ink triangles
 * are filled together here. Original CanvasStrokeRenderer captures remain separate evidence.
 */
internal fun drawAndroidTriangleReference(
    canvas: Canvas,
    painter: CanvasInkPainter,
    strokes: List<PageStroke>,
    scale: Float,
    left: Float,
    top: Float,
) {
    require(!canvas.isHardwareAccelerated) { "Triangle-union reference requires software Canvas" }
    canvas.drawColor(Color.WHITE)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    strokes.forEach { projection ->
        val stroke = painter.paint(projection)
        if (stroke.inputs.size == 0 || stroke.shape.computeBoundingBox() == null) return@forEach
        val transform = Matrix().apply { setValues(floatArrayOf(
            scale * projection.scaleX, 0f, 16f + scale * (projection.offsetX - left),
            0f, scale * projection.scaleY, 16f + scale * (projection.offsetY - top),
            0f, 0f, 1f,
        )) }
        canvas.save()
        try {
            canvas.concat(transform)
            repeat(stroke.shape.getRenderGroupCount()) { group ->
                // The same texture-free paint preference supported by Android's path renderer.
                val brushPaint = requireNotNull(stroke.brush.family.coats[group].paintPreferences.firstOrNull {
                    it.textureLayers.isEmpty() && (it.selfOverlap == SelfOverlap.ANY || it.selfOverlap == SelfOverlap.DISCARD)
                }) { "Unsupported paint in Android triangle reference" }
                // Match CanvasPathRenderer/BrushPaintCache, including highlighter color functions
                // and CanvasInkPainter's theme resolution, without rounding to an ARGB integer.
                paint.setColor(brushPaint.applyColorFunctions(stroke.brush.internalColor).value.toLong())
                canvas.drawPath(androidGroupPath(stroke.shape, group), paint)
            }
        } finally { canvas.restore() }
    }
}

/** One nonzero winding fill per coat prevents alpha seams at shared triangle edges. */
private fun androidGroupPath(shape: PartitionedMesh, group: Int): Path = Path().apply {
    fillType = Path.FillType.WINDING
    val position = MutableVec()
    var hasOutline = false
    repeat(shape.getOutlineCount(group)) { outline ->
        val count = shape.getOutlineVertexCount(group, outline)
        if (count > 0) {
            hasOutline = true
            shape.populateOutlinePosition(group, outline, 0, position)
            moveTo(position.x, position.y)
            for (vertex in 1 until count) {
                shape.populateOutlinePosition(group, outline, vertex, position)
                lineTo(position.x, position.y)
            }
            close()
        }
    }
    if (!hasOutline) shape.renderGroupMeshes(group).forEach { mesh ->
        val positions = FloatArray(mesh.vertexCount * 2)
        repeat(mesh.vertexCount) { vertex ->
            mesh.fillPosition(vertex, position)
            positions[vertex * 2] = position.x
            positions[vertex * 2 + 1] = position.y
        }
        val indices = mesh.getRawTriangleIndexBuffer().duplicate()
        require(indices.remaining() % 3 == 0) { "Malformed Ink triangle index buffer" }
        while (indices.hasRemaining()) {
            val a = (indices.get().toInt() and 0xffff) * 2
            var b = (indices.get().toInt() and 0xffff) * 2
            var c = (indices.get().toInt() and 0xffff) * 2
            val area = (positions[b] - positions[a]) * (positions[c + 1] - positions[a + 1]) -
                (positions[b + 1] - positions[a + 1]) * (positions[c] - positions[a])
            if (area == 0f) continue
            if (area < 0f) { val swap = b; b = c; c = swap }
            moveTo(positions[a], positions[a + 1])
            lineTo(positions[b], positions[b + 1])
            lineTo(positions[c], positions[c + 1])
            close()
        }
    }
}
