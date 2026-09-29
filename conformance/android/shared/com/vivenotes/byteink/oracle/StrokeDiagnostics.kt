package com.vivenotes.byteink.oracle

import androidx.ink.geometry.MutableVec
import androidx.ink.geometry.getRawTriangleIndexBuffer
import androidx.ink.geometry.getRawVertexBuffer
import androidx.ink.storage.encode
import androidx.ink.strokes.InProgressStroke
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.getRawTriangleIndexBuffer
import androidx.ink.strokes.getRawVertexBuffer
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream

/** Shared, test-only dump: the same code examines Android and desktop native stroke construction. */
internal fun writeStrokeDiagnostics(stroke: Stroke, directory: File) {
    directory.mkdirs()
    File(directory, "inputs.tsv").bufferedWriter().use { output ->
        repeat(stroke.inputs.size) { i ->
            val input = stroke.inputs[i]
            output.append(listOf(i, input.toolType, input.x, input.y, input.elapsedTimeMillis,
                input.strokeUnitLengthCm, input.pressure, input.tiltRadians, input.orientationRadians).joinToString("\t")).append('\n')
        }
    }
    val encoded = ByteArrayOutputStream().also { stroke.brush.family.encode(it) }.toByteArray()
    File(directory, "family.pb").writeBytes(GZIPInputStream(encoded.inputStream()).use { it.readBytes() })
    File(directory, "prefixes.tsv").bufferedWriter().use { output ->
        val prefix = MutableStrokeInputBatch()
        repeat(stroke.inputs.size) { i ->
            prefix.add(stroke.inputs[i])
            val shape = Stroke(stroke.brush, prefix).shape
            val vertices = (0 until shape.getRenderGroupCount()).sumOf { group ->
                shape.renderGroupMeshes(group).sumOf { it.vertexCount }
            }
            output.append("$i\t$vertices\t${shape.computeBoundingBox()}\n")
        }
    }
    val position = MutableVec()
    File(directory, "mesh.tsv").bufferedWriter().use { output ->
        val shape = stroke.shape
        output.append("bounds\t${shape.computeBoundingBox()}\n")
        repeat(shape.getRenderGroupCount()) { group ->
            repeat(shape.getOutlineCount(group)) { outline ->
                repeat(shape.getOutlineVertexCount(group, outline)) { vertex ->
                    shape.populateOutlinePosition(group, outline, vertex, position)
                    output.append("outline\t$group\t$outline\t$vertex\t${position.x}\t${position.y}\n")
                }
            }
            shape.renderGroupMeshes(group).forEachIndexed { meshIndex, mesh ->
                output.append("count\t$group\t$meshIndex\t${mesh.vertexCount}\t${mesh.triangleCount}\n")
                mesh.vertexAttributeUnpackingParams.forEachIndexed { attribute, params ->
                    params.components.forEachIndexed { component, coding ->
                        output.append("coding\t$group\t$meshIndex\t$attribute\t$component\t${coding.offset}\t${coding.scale}\n")
                    }
                }
                repeat(mesh.vertexCount) { vertex ->
                    mesh.fillPosition(vertex, position)
                    output.append("position\t$group\t$meshIndex\t$vertex\t${position.x}\t${position.y}\n")
                }
                File(directory, "mesh-$group-$meshIndex-vertices.bin").writeBytes(mesh.getRawVertexBuffer().bytes())
                val indices = mesh.getRawTriangleIndexBuffer().duplicate()
                val bytes = ByteBuffer.allocate(indices.remaining() * 2).order(ByteOrder.LITTLE_ENDIAN)
                while (indices.hasRemaining()) bytes.putShort(indices.get())
                File(directory, "mesh-$group-$meshIndex-indices.bin").writeBytes(bytes.array())
            }
        }
    }
    // A live stroke exposes unpacked floats, which separates modelling from immutable mesh packing.
    val live = InProgressStroke()
    live.start(stroke.brush)
    live.enqueueInputs(stroke.inputs, MutableStrokeInputBatch())
    live.finishInput()
    live.updateShape()
    File(directory, "live.tsv").bufferedWriter().use { output ->
        repeat(live.getBrushCoatCount()) { coat ->
            repeat(live.getMeshPartitionCount(coat)) { partition ->
                output.append("count\t$coat\t$partition\t${live.getVertexCount(coat, partition)}\n")
                repeat(live.getVertexCount(coat, partition)) { vertex ->
                    live.populatePosition(coat, partition, vertex, position)
                    output.append("position\t$coat\t$partition\t$vertex\t${position.x}\t${position.y}\n")
                }
                File(directory, "live-$coat-$partition-vertices.bin").writeBytes(live.getRawVertexBuffer(coat, partition).bytes())
                val indices = live.getRawTriangleIndexBuffer(coat, partition).duplicate()
                val bytes = ByteBuffer.allocate(indices.remaining() * 2).order(ByteOrder.LITTLE_ENDIAN)
                while (indices.hasRemaining()) bytes.putShort(indices.get())
                File(directory, "live-$coat-$partition-indices.bin").writeBytes(bytes.array())
            }
        }
    }
}

private fun ByteBuffer.bytes(): ByteArray = duplicate().let { copy -> ByteArray(copy.remaining()).also(copy::get) }
