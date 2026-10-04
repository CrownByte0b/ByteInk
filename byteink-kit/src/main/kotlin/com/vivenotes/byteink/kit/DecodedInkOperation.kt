package com.vivenotes.byteink.kit

import androidx.ink.strokes.Stroke
import java.util.Collections

/** Validated operation geometry shared by replay and optional portable display adapters. */
public sealed class DecodedInkOperation {
    public abstract val id: String
    public abstract val createdAt: Long
    public abstract val targetIds: Set<String>

    public class Erase internal constructor(
        override val id: String,
        override val createdAt: Long,
        targets: Collection<String>,
        public val mode: InkEraseMode,
        public val mask: Stroke,
    ) : DecodedInkOperation() {
        override val targetIds: Set<String> = Collections.unmodifiableSet(LinkedHashSet(targets))
    }

    public class Move internal constructor(
        override val id: String,
        override val createdAt: Long,
        targets: Collection<String>,
        path: List<InkPoint>,
        public val dx: Float,
        public val dy: Float,
        public val scaleX: Float,
        public val scaleY: Float,
        public val anchor: InkPoint,
    ) : DecodedInkOperation() {
        override val targetIds: Set<String> = Collections.unmodifiableSet(LinkedHashSet(targets))
        public val path: List<InkPoint> = Collections.unmodifiableList(ArrayList(path))
        internal val lasso: LassoShape = LassoShape(this.path)
    }
}

internal class InkReplayWork {
    var maskDecodes: Int = 0
    var pathDecodes: Int = 0
    var targetBuckets: Long = 0
    var targetProjections: Long = 0
    var peakDecodeJobs: Int = 0
}

/** Buckets retain original row positions; only targeted buckets change until the final flatten. */
internal class InkReplay(strokes: List<PageStroke>, private val work: InkReplayWork?) {
    private class Bucket(val order: Int, var strokes: List<PageStroke>)
    private val buckets = strokes.mapIndexed { index, stroke -> Bucket(index, listOf(stroke)) }
    private val byRow = HashMap<String, MutableList<Bucket>>(strokes.size).apply {
        buckets.forEach { bucket -> getOrPut(bucket.strokes.single().id) { ArrayList(1) }.add(bucket) }
    }

    fun apply(operation: DecodedInkOperation) {
        when (operation) {
            is DecodedInkOperation.Erase -> {
                for (id in operation.targetIds) for (bucket in byRow[id].orEmpty()) {
                    if (bucket.strokes.isEmpty()) continue
                    record(bucket)
                    bucket.strokes = when (operation.mode) {
                        InkEraseMode.Normal -> bucket.strokes.subtractTargeted(operation.mask)
                        InkEraseMode.Object -> bucket.strokes.eraseObjectsTargeted(operation.mask)
                    }
                }
            }
            is DecodedInkOperation.Move -> {
                val targets = operation.targetIds.flatMap { byRow[it].orEmpty() }
                    .filter { it.strokes.isNotEmpty() }.sortedBy(Bucket::order)
                if (targets.isEmpty()) return
                targets.forEach(::record)
                // A single clamp covers all selected rows, and resize selects again after translation.
                val moved = targets.flatMap { it.strokes }
                    .replayMove(operation.lasso, operation.dx, operation.dy)
                    .replayResize(operation.lasso, operation.anchor, operation.scaleX, operation.scaleY)
                var offset = 0
                for (bucket in targets) {
                    val size = bucket.strokes.size
                    bucket.strokes = ArrayList(moved.subList(offset, offset + size))
                    offset += size
                }
            }
        }
    }

    fun flatten(): List<PageStroke> = buckets.flatMap { it.strokes }

    private fun record(bucket: Bucket) {
        work?.let { it.targetBuckets++; it.targetProjections += bucket.strokes.size }
    }
}
