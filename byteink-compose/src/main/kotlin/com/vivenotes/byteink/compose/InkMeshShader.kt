/*
 * Shader math adapted from google/ink at 96e50239e1c8955f7e222301661b595847d7de83,
 * ink/rendering/skia/common_internal/sksl_{vertex,fragment,common}_shader_helper_functions.h.
 * Copyright 2024 Google LLC
 * Licensed under the Apache License, Version 2.0.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package com.vivenotes.byteink.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.VertexMode
import androidx.compose.ui.graphics.Vertices
import com.vivenotes.byteink.core.StrokeMesh
import org.jetbrains.skia.Shader
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

internal const val MESH_TRIANGLES_PER_DRAW = 16
internal const val VARYING_STRIDE = 16

internal data class MeshLinearTransform(val a: Float, val b: Float, val d: Float, val e: Float)
internal data class MeshColor(val r: Float, val g: Float, val b: Float, val a: Float)
internal data class StampAnimation(val progress: Float, val frames: Int, val rows: Int, val columns: Int)
internal class MeshChunk(val vertices: Vertices, val varyings: FloatArray) {
    // Only texture-free shaders are retained: texture origins and providers can change per draw.
    var untexturedShader: Shader? = null
    val bytes: Long get() = varyings.size * 4L + vertices.positions.size * 4L +
        vertices.textureCoordinates.size * 4L + vertices.colors.size * 4L + vertices.indices.size * 2L +
        (if (untexturedShader == null) 0L else varyings.size * 4L)
    fun close() { untexturedShader?.close(); untexturedShader = null }
}

/** The prior state is consumed by the next preparation; close only the latest returned state. */
internal class PreparedMesh(
    val mesh: StrokeMesh,
    val transform: MeshLinearTransform,
    val color: MeshColor,
    val stamp: StampAnimation?,
    val positions: FloatArray,
    val varying: FloatArray,
    val changed: BooleanArray,
    val chunks: List<MeshChunk>,
    val preparedVertexCount: Int,
    val preparedChunkCount: Int,
    val reusedChunkCount: Int,
) {
    val scratchBytes: Long get() = positions.size * 4L + varying.size * 4L + changed.size
    fun close() = chunks.forEach { it.close() }
}

private class MeshVertexTemplate(val count: Int) {
    val positions = List(count * 3) { Offset.Zero }
    val coordinates = List(count * 3) { corner ->
        Offset((corner / 3) * 2f + .5f + if (corner % 3 == 1) 1f else 0f, if (corner % 3 == 2) 1f else 0f)
    }
    val colors = List(count * 3) { Color.White }
    val indices = List(count * 3) { it }
    fun vertices() = Vertices(VertexMode.Triangles, positions, coordinates, colors, indices)
}

// Immutable constructor lists avoid allocating/boxing two point lists and an index list per chunk.
private val vertexTemplates = Array(MESH_TRIANGLES_PER_DRAW) { MeshVertexTemplate(it + 1) }

private fun meshChunk(mesh: StrokeMesh, positions: FloatArray, varying: FloatArray, start: Int, count: Int): MeshChunk {
    val vertices = vertexTemplates[count - 1].vertices()
    val data = FloatArray(MESH_TRIANGLES_PER_DRAW * 3 * VARYING_STRIDE)
    repeat(count * 3) { corner ->
        val index = mesh.triangles[start * 3 + corner]
        vertices.positions[corner * 2] = positions[index * 2]
        vertices.positions[corner * 2 + 1] = positions[index * 2 + 1]
        varying.copyInto(data, corner * VARYING_STRIDE, index * VARYING_STRIDE, (index + 1) * VARYING_STRIDE)
    }
    return MeshChunk(vertices, data)
}

/** CPU equivalent of Ink's vertex shader. Skiko exposes fragment RuntimeEffects and drawVertices. */
internal fun prepareMesh(mesh: StrokeMesh, transform: MeshLinearTransform, color: MeshColor, stamp: StampAnimation?): List<MeshChunk> {
    val positions = FloatArray(mesh.vertexCount * 2)
    val varying = FloatArray(mesh.vertexCount * VARYING_STRIDE)
    prepareVertices(mesh, transform, color, stamp, positions, varying, null)
    return (0 until mesh.triangleCount step MESH_TRIANGLES_PER_DRAW).map { start ->
        meshChunk(mesh, positions, varying, start, minOf(MESH_TRIANGLES_PER_DRAW, mesh.triangleCount - start))
    }
}

private fun prepareVertices(mesh: StrokeMesh, transform: MeshLinearTransform, color: MeshColor, stamp: StampAnimation?,
    positions: FloatArray, varying: FloatArray, changed: BooleanArray?) {
    val v = mesh.vertices
    val det = abs(transform.a * transform.e - transform.b * transform.d)
    val hasHsl = mesh.attributeMask and (1 shl 2) != 0
    val baseY = if (hasHsl) color.r * .299f + color.g * .587f + color.b * .114f else 0f
    val baseI = if (hasHsl) color.r * .596f - color.g * .275f - color.b * .321f else 0f
    val baseQ = if (hasHsl) color.r * .212f - color.g * .523f + color.b * .311f else 0f
    val baseHue = if (!hasHsl || color.r == 0f && color.g == 0f && color.b == 0f) 0f else atan2(baseQ, baseI)
    val baseChroma = if (hasHsl) sqrt(baseI * baseI + baseQ * baseQ) else 0f
    fun clamp(x: Float): Float = x.coerceIn(0f, 1f)
    fun distance(dx: Float, dy: Float): Float {
        val rx = -transform.a * dy + transform.b * dx
        val ry = -transform.d * dy + transform.e * dx
        return maxOf(0.000001f, det * (dx * dx + dy * dy) / maxOf(0.000001f, sqrt(rx * rx + ry * ry)))
    }
    repeat(mesh.vertexCount) { vertex ->
        if (changed != null && !changed[vertex]) return@repeat
        val i = vertex * StrokeMesh.VERTEX_STRIDE
        val o = vertex * VARYING_STRIDE
        val sx = v[i + 6]; val sy = v[i + 7]; val sl = v[i + 8]
        val fx = v[i + 9]; val fy = v[i + 10]; val fl = v[i + 11]
        val sidePixels = distance(sx, sy)
        val forwardPixels = distance(fx, fy)
        val target = .5f + (.707107f - .5f) * clamp(2f * (sidePixels - .5f))
        val sideTarget = target / sidePixels
        val forwardTarget = target / forwardPixels
        val sideMargin = (4f / 126f) * maxOf(abs(sl) - 1f, 0f)
        val forwardMargin = (4f / 126f) * maxOf(abs(fl) - 1f, 0f)
        val sideCapped = minOf(sideTarget, sideMargin)
        val sideOutset = sideTarget + (sideCapped - sideTarget) * clamp(4f * sidePixels - 1f)
        val forwardOutset = minOf(forwardTarget, forwardMargin)
        val sox = Math.signum(sl) * sideOutset * sx
        val soy = Math.signum(sl) * sideOutset * sy
        val fox = Math.signum(fl) * forwardOutset * fx
        val foy = Math.signum(fl) * forwardOutset * fy
        val common = clamp((sox * fox + soy * foy) / maxOf(.000001f, fox * fox + foy * foy))
        val px = v[i] + sox + (1f - common) * fox
        val py = v[i + 1] + soy + (1f - common) * foy
        positions[vertex * 2] = px; positions[vertex * 2 + 1] = py

        val alpha = clamp((v[i + 2] + 1f) * color.a)
        if (hasHsl) {
            val hue = baseHue - v[i + 3] * (2f * Math.PI.toFloat())
            val chroma = baseChroma * (v[i + 4] + 1f)
            val y = baseY + v[i + 5]
            val shiftedI = chroma * cos(hue); val shiftedQ = chroma * sin(hue)
            varying[o] = (y + .956f * shiftedI + .621f * shiftedQ) * alpha
            varying[o + 1] = (y - .272f * shiftedI - .647f * shiftedQ) * alpha
            varying[o + 2] = (y - 1.107f * shiftedI + 1.704f * shiftedQ) * alpha
        } else {
            varying[o] = color.r * alpha
            varying[o + 1] = color.g * alpha
            varying[o + 2] = color.b * alpha
        }
        varying[o + 3] = alpha
        varying[o + 4] = sidePixels; varying[o + 5] = forwardPixels
        repeat(4) { edge ->
            val flag = when (edge) {
                0 -> if (sl > -.005f) 1f else 0f
                1 -> if (sl < .005f) 1f else 0f
                2 -> if (fl > -.005f) 1f else 0f
                else -> if (fl < .005f) 1f else 0f
            }
            varying[o + 6 + edge] = flag
            varying[o + 10 + edge] = target * (1f - flag) *
                (if (edge < 2) sideOutset / sideTarget else forwardOutset / forwardTarget)
        }
        if (stamp == null) {
            varying[o + 14] = px; varying[o + 15] = py
        } else {
            val progress = stamp.progress + v[i + 14]
            val frame = floor((progress - floor(progress)) * stamp.frames).toInt()
            varying[o + 14] = (v[i + 12] + frame % stamp.columns) / stamp.columns
            varying[o + 15] = (v[i + 13] + frame / stamp.columns) / stamp.rows
        }
    }
}

/** Exact owned-snapshot comparison, independent of native damage resets or assumed stable prefixes. */
internal fun prepareMeshIncrementally(mesh: StrokeMesh, transform: MeshLinearTransform, color: MeshColor,
    stamp: StampAnimation?, previous: PreparedMesh? = null): PreparedMesh {
    val old = previous?.mesh
    val sameKey = previous != null && previous.transform == transform && previous.color == color && previous.stamp == stamp
    val oldCapacity = previous?.changed?.size ?: 0
    val capacity = when {
        mesh.vertexCount == 0 -> 0
        mesh.vertexCount > oldCapacity -> maxOf(mesh.vertexCount, oldCapacity + oldCapacity / 2)
        mesh.vertexCount < oldCapacity / 2 -> mesh.vertexCount
        else -> oldCapacity
    }
    val positions = if (capacity == oldCapacity && previous != null) previous.positions
        else FloatArray(capacity * 2).also { previous?.positions?.copyInto(it, endIndex = minOf(it.size, previous.positions.size)) }
    val varying = if (capacity == oldCapacity && previous != null) previous.varying
        else FloatArray(capacity * VARYING_STRIDE).also { previous?.varying?.copyInto(it, endIndex = minOf(it.size, previous.varying.size)) }
    val changed = if (capacity == oldCapacity && previous != null) previous.changed else BooleanArray(capacity)
    var preparedVertices = 0
    repeat(mesh.vertexCount) { vertex ->
        var different = !sameKey || old == null || old.attributeMask != mesh.attributeMask || vertex >= old.vertexCount
        if (!different) {
            val start = vertex * StrokeMesh.VERTEX_STRIDE
            for (offset in start until start + StrokeMesh.VERTEX_STRIDE) {
                if (mesh.vertices[offset].toRawBits() != old!!.vertices[offset].toRawBits()) { different = true; break }
            }
        }
        changed[vertex] = different
        if (different) preparedVertices++
    }
    prepareVertices(mesh, transform, color, stamp, positions, varying, changed)
    val chunkCount = (mesh.triangleCount + MESH_TRIANGLES_PER_DRAW - 1) / MESH_TRIANGLES_PER_DRAW
    val chunks = ArrayList<MeshChunk>(chunkCount)
    var preparedChunks = 0
    var reusedChunks = 0
    repeat(chunkCount) { chunk ->
        val start = chunk * MESH_TRIANGLES_PER_DRAW
        val count = minOf(MESH_TRIANGLES_PER_DRAW, mesh.triangleCount - start)
        val cached = previous?.chunks?.getOrNull(chunk)
        var reusable = sameKey && cached != null && cached.vertices.indices.size == count * 3 && old!!.attributeMask == mesh.attributeMask
        if (reusable) {
            for (offset in start * 3 until (start + count) * 3) {
                val index = mesh.triangles[offset]
                if (index != old!!.triangles[offset] || changed[index]) { reusable = false; break }
            }
        }
        if (reusable) { chunks.add(cached!!); reusedChunks++ }
        else { chunks.add(meshChunk(mesh, positions, varying, start, count)); preparedChunks++ }
    }
    previous?.chunks?.forEachIndexed { index, chunk -> if (chunks.getOrNull(index) !== chunk) chunk.close() }
    return PreparedMesh(mesh, transform, color, stamp, positions, varying, changed, chunks, preparedVertices, preparedChunks, reusedChunks)
}

/** All twelve Ink texture blend modes, with the accumulated texture as source. */
internal const val INK_TEXTURE_BLEND_SKSL = """
float4 inkBlend(float4 src, float4 dst, int mode) {
    if (mode == 0) return src * dst;
    if (mode == 1) return dst * src.a;
    if (mode == 2) return dst * (1.0 - src.a);
    if (mode == 3) return src * dst.a + dst * (1.0 - src.a);
    if (mode == 4) return src * dst.a;
    if (mode == 5) return src + dst * (1.0 - src.a);
    if (mode == 6) return dst + src * (1.0 - dst.a);
    if (mode == 7) return src;
    if (mode == 8) return dst;
    if (mode == 9) return src * (1.0 - dst.a);
    if (mode == 10) return dst * src.a + src * (1.0 - dst.a);
    return src * (1.0 - dst.a) + dst * (1.0 - src.a);
}
"""

internal val INK_MESH_SKSL: String = """
uniform float4 vertexData[${MESH_TRIANGLES_PER_DRAW * 3 * VARYING_STRIDE / 4}];
uniform shader coatTexture;
uniform int hasTexture;
uniform int textureBlend;
layout(color) uniform float4 colorSpaceProbe;
float3 inkColorToWorking(float3 linear) {
    // Skia disables the color-space intrinsics on untagged raster canvases. Those canvases
    // conventionally store sRGB. A tagged uniform detects that case without assuming the
    // destination gamut/transfer function on color-managed canvases (including linear ones).
    if (abs(toLinearSrgb(colorSpaceProbe.rgb).r - 0.21404114) > 0.01) {
        float3 x = abs(linear);
        return sign(linear) * mix(12.92 * x, 1.055 * pow(x, float3(1.0 / 2.4)) - 0.055, step(0.0031308, x));
    }
    return fromLinearSrgb(linear);
}
float coverage(float2 pixels, float4 edges, float4 outsets) {
    float target = mix(0.5, 0.707107, saturate(2.0 * (pixels.x - 0.5)));
    float4 outset = min(outsets / max(float4(1.0) - edges, 0.000001), target);
    float2 adjustedPixels = pixels + outset.xz + outset.yw;
    float4 toEdge = saturate((adjustedPixels.xxyy * edges) / (2.0 * target));
    float2 interior = step(1.9999, edges.xz + edges.yw);
    float2 result = mix(max(toEdge.xz + toEdge.yw - float2(1.0), 0.0), float2(1.0), interior);
    return result.x * result.y;
}
""" + INK_TEXTURE_BLEND_SKSL + """
half4 main(float2 coord) {
    int triangle = int(floor(coord.x * 0.5));
    float barycentricX = coord.x - float(triangle) * 2.0 - 0.5;
    float3 weights = float3(1.0 - barycentricX - coord.y, barycentricX, coord.y);
    float4 color, a, b, c;
""" + (0 until MESH_TRIANGLES_PER_DRAW).joinToString("\n") { triangle ->
    // RuntimeEffects target Skia's portable ES2 subset, which requires constant array indices.
    val branch = if (triangle == 0) "if" else "else if"
    "$branch (triangle == $triangle) {\n" + listOf("color", "a", "b", "c").mapIndexed { slot, name ->
        val base = triangle * 12 + slot
        "    $name = vertexData[$base] * weights.x + vertexData[${base + 4}] * weights.y + vertexData[${base + 8}] * weights.z;"
    }.joinToString("\n") + "\n}"
} + """
    // Ink mesh colors interpolate in premultiplied linear sRGB. Convert to the destination
    // working space before combining them with Skia's color-managed image shader.
    color.rgb = color.a > 0.0 ? inkColorToWorking(color.rgb / color.a) * color.a : float3(0.0);
    color *= coverage(a.xy, float4(a.zw, b.xy), float4(b.zw, c.xy));
    return half4(hasTexture == 0 ? color : inkBlend(float4(coatTexture.eval(c.zw)), color, textureBlend));
}
"""

internal val INK_PATH_TEXTURE_SKSL: String = """
uniform shader coatTexture;
layout(color) uniform float4 brushColor;
uniform int textureBlend;
""" + INK_TEXTURE_BLEND_SKSL + """
half4 main(float2 coord) {
    float4 color = brushColor;
    color.rgb *= color.a;
    return half4(inkBlend(float4(coatTexture.eval(coord)), color, textureBlend));
}
"""
