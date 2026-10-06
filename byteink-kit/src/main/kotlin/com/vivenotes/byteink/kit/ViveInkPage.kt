package com.vivenotes.byteink.kit

import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.Collections
import java.util.TreeSet

/** A page of ink as stored rows rebuild it. */
public class LoadedInkPage internal constructor(
    /** The page's projections, in draw order. */
    public val strokes: List<PageStroke>,
    /**
     * Rows that decoded but that replay left without geometry: the eraser took their last piece.
     * Android tombstones these; reported here, since a replay is not the place to write.
     */
    public val erasedAway: List<String>,
    /** Live rows this build could not read — an encoder it does not know, or damaged data — and so did not draw. */
    public val unreadable: List<String>,
    sourceStrokes: List<PageStroke>,
    operations: List<DecodedInkOperation>,
) {
    /** Decoded rows before replay, in draw order; their immutable inputs can supply portable data. */
    public val sourceStrokes: List<PageStroke> = Collections.unmodifiableList(ArrayList(sourceStrokes))
    /** Validated operation geometry, decoded once for this load and ordered by time/id. */
    public val operations: List<DecodedInkOperation> = Collections.unmodifiableList(ArrayList(operations))

    public constructor(strokes: List<PageStroke>, erasedAway: List<String>, unreadable: List<String>) :
        this(strokes, erasedAway, unreadable, strokes, emptyList())
}

/**
 * Rebuilds a page from its stored rows exactly as the Android app does (its `InkPageLoader`): every
 * live stroke decoded, in draw order, then every live erase and move replayed in the order they were
 * made.
 */
public object ViveInkPage {

    /**
     * The page [strokes], [erases] and [moves] make. Tombstoned rows are left out; so is an erase
     * whose mode this build does not know, and any operation whose data cannot be read.
     *
     * With an [executor], rows are decoded in chunks on it. With [onPartial], and when there are no
     * moves, each chunk is published as soon as it is replayed, as Android does for large pages: an
     * erase applies only to its own targets, so chunks replay independently, whereas a move's clamp
     * depends on everything it moves.
     */
    public fun load(
        strokes: List<StoredInkStroke>,
        erases: List<StoredInkErase>,
        moves: List<StoredInkMove>,
        executor: Executor? = null,
        onPartial: ((List<PageStroke>) -> Unit)? = null,
    ): LoadedInkPage = load(strokes, erases, moves, executor, onPartial, null)

    internal fun load(
        strokes: List<StoredInkStroke>, erases: List<StoredInkErase>, moves: List<StoredInkMove>,
        executor: Executor?, onPartial: ((List<PageStroke>) -> Unit)?, work: InkReplayWork?,
    ): LoadedInkPage {
        val rows = strokes.filter { it.deletedAt == null }.sortedWith(DRAW_ORDER)
        val storedOperations = buildList {
            erases.filter { it.deletedAt == null }.forEach { add(Operation.Erase(it)) }
            moves.filter { it.deletedAt == null }.forEach { add(Operation.Move(it)) }
        }.sortedWith(compareBy(Operation::createdAt, Operation::id))
        val streaming = onPartial != null && storedOperations.none { it is Operation.Move }
        val operations = prepare(storedOperations, work)
        val operationsByRow = if (streaming) buildMap<String, MutableList<Int>> {
            operations.forEachIndexed { index, operation ->
                operation.targetIds.forEach { id -> getOrPut(id) { ArrayList() }.add(index) }
            }
        } else emptyMap()
        val shown = ArrayList<PageStroke>(rows.size)
        val unreadable = ArrayList<String>()
        val decoded = ArrayList<PageStroke>(rows.size)
        decodeInChunks(rows, executor, work) { chunk, failed ->
            decoded += chunk
            unreadable += failed
            if (streaming) {
                val relevant = TreeSet<Int>()
                chunk.forEach { stroke -> operationsByRow[stroke.id]?.let(relevant::addAll) }
                shown += replay(chunk, relevant.map(operations::get), work)
                onPartial(ArrayList(shown))
            }
        }
        val live = if (streaming) shown else replay(decoded, operations, work)
        val drawn = live.mapTo(HashSet(live.size)) { it.id }
        return LoadedInkPage(
            strokes = live,
            erasedAway = decoded.mapNotNull { stroke -> stroke.id.takeIf { it !in drawn } }.distinct(),
            unreadable = unreadable,
            sourceStrokes = decoded,
            operations = operations,
        )
    }

    /** One stored row as a projection, or null if it cannot be read. */
    public fun decode(row: StoredInkStroke): PageStroke? = ViveInkCodec.decode(row)?.let { stroke ->
        PageStroke(
            id = row.id,
            stroke = stroke,
            brushFamily = row.brushFamily,
            brushVersion = row.brushVersion,
            stabilization = row.stabilization,
            colorFollowsTheme = row.colorFollowsTheme,
            groupId = row.groupId,
        )
    }

    /**
     * Replays already decoded source strokes and operations without decoding their inputs again.
     * Supply the original projections before operations, such as [LoadedInkPage.sourceStrokes].
     * Operations are ordered by their persisted time and id; the supplied lists are not modified.
     * Like [load], geometric replay belongs on a worker thread.
     */
    public fun replay(strokes: List<PageStroke>, operations: List<DecodedInkOperation>): List<PageStroke> =
        replay(strokes, operations.sortedWith(compareBy(DecodedInkOperation::createdAt, DecodedInkOperation::id)), null)

    private fun prepare(operations: List<Operation>, work: InkReplayWork?): List<DecodedInkOperation> =
        operations.mapNotNull { operation ->
            when (operation) {
                is Operation.Erase -> {
                    val stored = operation.stored
                    val mode = InkEraseMode.of(stored.mode) ?: return@mapNotNull null
                    work?.let { it.maskDecodes++ }
                    val mask = ViveInkCodec.decodeErase(stored) ?: return@mapNotNull null
                    DecodedInkOperation.Erase(stored.id, stored.createdAt, stored.targetIds, mode, mask)
                }
                is Operation.Move -> {
                    val stored = operation.stored
                    work?.let { it.pathDecodes++ }
                    val path = ViveInkCodec.decodeMove(stored) ?: return@mapNotNull null
                    DecodedInkOperation.Move(stored.id, stored.createdAt, stored.targetIds, path,
                        stored.dxDp, stored.dyDp, stored.scaleX, stored.scaleY, InkPoint(stored.anchorX, stored.anchorY))
                }
            }
        }

    private fun replay(
        strokes: List<PageStroke>, operations: List<DecodedInkOperation>, work: InkReplayWork?,
    ): List<PageStroke> {
        if (operations.isEmpty() || strokes.isEmpty()) return strokes
        return InkReplay(strokes, work).apply { operations.forEach(::apply) }.flatten()
    }

    /** Decodes [rows] in draw order, handing each chunk's projections and unreadable ids to [onChunk] in order. */
    private fun decodeInChunks(
        rows: List<StoredInkStroke>,
        executor: Executor?,
        work: InkReplayWork?,
        onChunk: (List<PageStroke>, List<String>) -> Unit,
    ) {
        if (executor == null) {
            for (start in rows.indices step DECODE_CHUNK) {
                val (decoded, failed) = decodeChunk(rows.subList(start, minOf(start + DECODE_CHUNK, rows.size)))
                onChunk(decoded, failed)
            }
            return
        }
        val pending = ArrayDeque<CompletableFuture<Pair<List<PageStroke>, List<String>>>>()
        var next = 0
        try {
            while (next < rows.size || pending.isNotEmpty()) {
                while (next < rows.size && pending.size < MAX_DECODE_JOBS) {
                    val chunk = rows.subList(next, minOf(next + DECODE_CHUNK, rows.size))
                    pending.addLast(CompletableFuture.supplyAsync({ decodeChunk(chunk) }, executor))
                    next += chunk.size
                    work?.let { it.peakDecodeJobs = maxOf(it.peakDecodeJobs, pending.size) }
                }
                val (decoded, failed) = pending.removeFirst().join()
                onChunk(decoded, failed)
            }
        } finally {
            // Cancel queued suppliers on failure; active native work may finish on the caller's executor.
            pending.forEach { it.cancel(false) }
        }
    }

    private fun decodeChunk(rows: List<StoredInkStroke>): Pair<List<PageStroke>, List<String>> {
        val decoded = ArrayList<PageStroke>(rows.size)
        val failed = ArrayList<String>()
        rows.forEach { row -> decode(row)?.let(decoded::add) ?: failed.add(row.id) }
        return decoded to failed
    }

    /**
     * Draw order as the Android app's query returns it: `ORDER BY seq, id`, which compares ids byte
     * by byte in UTF-8 — Unicode code point order, not Kotlin's UTF-16 string order.
     */
    private val DRAW_ORDER: Comparator<StoredInkStroke> =
        compareBy<StoredInkStroke> { it.seq }.then { a, b -> compareCodePoints(a.id, b.id) }

    private fun compareCodePoints(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val x = a.codePointAt(i)
            val y = b.codePointAt(j)
            if (x != y) return x.compareTo(y)
            i += Character.charCount(x)
            j += Character.charCount(y)
        }
        return (a.length - i).compareTo(b.length - j)
    }

    private const val DECODE_CHUNK = 512
    internal const val MAX_DECODE_JOBS = 4

    /** A stored operation, ordered as Android orders them: by `createdAt`, then id. */
    private sealed interface Operation {
        val createdAt: Long
        val id: String

        class Erase(val stored: StoredInkErase) : Operation {
            override val createdAt: Long get() = stored.createdAt
            override val id: String get() = stored.id
        }

        class Move(val stored: StoredInkMove) : Operation {
            override val createdAt: Long get() = stored.createdAt
            override val id: String get() = stored.id
        }
    }
}
