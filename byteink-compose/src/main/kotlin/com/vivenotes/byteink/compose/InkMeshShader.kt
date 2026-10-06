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
internal class MeshChunk(val vertices: Vertices, val varyings: FloatArray)

/** CPU equivalent of Ink's vertex shader. Skiko exposes fragment RuntimeEffects and drawVertices. */
internal fun prepareMesh(mesh: StrokeMesh, transform: MeshLinearTransform, color: MeshColor, stamp: StampAnimation?): List<MeshChunk> {
    val positions = FloatArray(mesh.vertexCount * 2)
    val varying = FloatArray(mesh.vertexCount * VARYING_STRIDE)
    val v = mesh.vertices
    val det = abs(transform.a * transform.e - transform.b * transform.d)
    fun clamp(x: Float): Float = x.coerceIn(0f, 1f)
    fun distance(dx: Float, dy: Float): Float {
        val rx = -transform.a * dy + transform.b * dx
        val ry = -transform.d * dy + transform.e * dx
        return maxOf(0.000001f, det * (dx * dx + dy * dy) / maxOf(0.000001f, sqrt(rx * rx + ry * ry)))
    }
    repeat(mesh.vertexCount) { vertex ->
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

        var y = color.r * .299f + color.g * .587f + color.b * .114f
        val yi = color.r * .596f - color.g * .275f - color.b * .321f
        val yq = color.r * .212f - color.g * .523f + color.b * .311f
        val hue = (if (color.r == 0f && color.g == 0f && color.b == 0f) 0f else atan2(yq, yi)) - v[i + 3] * (2f * Math.PI.toFloat())
        val chroma = sqrt(yi * yi + yq * yq) * (v[i + 4] + 1f)
        y += v[i + 5]
        val shiftedI = chroma * cos(hue); val shiftedQ = chroma * sin(hue)
        val alpha = clamp((v[i + 2] + 1f) * color.a)
        val hasHsl = mesh.attributeMask and (1 shl 2) != 0
        varying[o] = (if (hasHsl) y + .956f * shiftedI + .621f * shiftedQ else color.r) * alpha
        varying[o + 1] = (if (hasHsl) y - .272f * shiftedI - .647f * shiftedQ else color.g) * alpha
        varying[o + 2] = (if (hasHsl) y - 1.107f * shiftedI + 1.704f * shiftedQ else color.b) * alpha
        varying[o + 3] = alpha
        varying[o + 4] = sidePixels; varying[o + 5] = forwardPixels
        val edges = floatArrayOf(if (sl > -.005f) 1f else 0f, if (sl < .005f) 1f else 0f,
            if (fl > -.005f) 1f else 0f, if (fl < .005f) 1f else 0f)
        repeat(4) { edge ->
            varying[o + 6 + edge] = edges[edge]
            varying[o + 10 + edge] = target * (1f - edges[edge]) *
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
    return (0 until mesh.triangleCount step MESH_TRIANGLES_PER_DRAW).map { start ->
        val count = minOf(MESH_TRIANGLES_PER_DRAW, mesh.triangleCount - start)
        val points = ArrayList<Offset>(count * 3)
        val coords = ArrayList<Offset>(count * 3)
        val data = FloatArray(MESH_TRIANGLES_PER_DRAW * 3 * VARYING_STRIDE)
        repeat(count) { triangle ->
            repeat(3) { corner ->
                val index = mesh.triangles[(start + triangle) * 3 + corner]
                points.add(Offset(positions[index * 2], positions[index * 2 + 1]))
                // Keep each interval away from integer ID boundaries: interpolation rounding
                // at an edge must not select its neighboring triangle's uniform attributes.
                coords.add(Offset(triangle * 2f + .5f + if (corner == 1) 1f else 0f, if (corner == 2) 1f else 0f))
                varying.copyInto(data, (triangle * 3 + corner) * VARYING_STRIDE, index * VARYING_STRIDE, (index + 1) * VARYING_STRIDE)
            }
        }
        MeshChunk(Vertices(VertexMode.Triangles, points, coords, List(points.size) { Color.White }, points.indices.toList()), data)
    }
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
