@file:OptIn(InkInternalOnlyApi::class)

package com.vivenotes.byteink.core

import androidx.ink.geometry.PartitionedMesh
import androidx.ink.nativeloader.InkInternalOnlyApi
import androidx.ink.nativeloader.NativeLoader
import androidx.ink.nativeloader.UsedByNative
import androidx.ink.strokes.InProgressStroke

/** One triangle mesh in its shape's own coordinates: an x, y pair per vertex, three vertex indices per triangle. */
@UsedByNative
public class TriangleMesh @UsedByNative constructor(public val positions: FloatArray, public val triangles: IntArray) {
    public val vertexCount: Int get() = positions.size / 2
    public val triangleCount: Int get() = triangles.size / 3
}

/**
 * The geometry Ink builds a stroke from, for renderers.
 *
 * Each call copies a complete group/coat through one ByteInk JNI entry point. Returned arrays
 * belong to the caller, remain valid after a live update or clear, and share no native memory.
 * A live stroke must be read on its authoring thread without concurrent mutation; immutable
 * shapes may be read concurrently. The JNI call retains its owner throughout the copy.
 *
 * The bridge depends on alpha06's internal native pointer and mesh layout. It is maintained in
 * native patch 0006, independently of the upstream engine, with exact getter/partition tests.
 */
public object InkMeshes {

    /** The outlines of [shape]'s render group [group], each as x, y pairs in the shape's coordinates. */
    public fun outlines(shape: PartitionedMesh, group: Int): List<FloatArray> =
        InkGeometryNative.finishedOutlines(shape, group).asList()

    /** The outlines of a stroke still being drawn, for brush coat [coat]. */
    public fun outlines(stroke: InProgressStroke, coat: Int): List<FloatArray> =
        InkGeometryNative.liveOutlines(stroke, coat).asList()

    /** The triangle meshes of [shape]'s render group [group]. */
    public fun triangles(shape: PartitionedMesh, group: Int): List<TriangleMesh> =
        InkGeometryNative.finishedTriangles(shape, group).asList()

    /** The triangle meshes of a stroke still being drawn, for brush coat [coat]: one per partition. */
    public fun triangles(stroke: InProgressStroke, coat: Int): List<TriangleMesh> =
        InkGeometryNative.liveTriangles(stroke, coat).asList()
}

@UsedByNative
private object InkGeometryNative {
    init { NativeLoader.load() }

    @UsedByNative external fun finishedOutlines(owner: PartitionedMesh, group: Int): Array<FloatArray>
    @UsedByNative external fun liveOutlines(owner: InProgressStroke, coat: Int): Array<FloatArray>
    @UsedByNative external fun finishedTriangles(owner: PartitionedMesh, group: Int): Array<TriangleMesh>
    @UsedByNative external fun liveTriangles(owner: InProgressStroke, coat: Int): Array<TriangleMesh>
}
