package com.vivenotes.byteink.vive

import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/** A page of ink as stored rows rebuild it. */
public class LoadedInkPage(
    /** The page's projections, in draw order. */
    public val strokes: List<PageStroke>,
    /**
     * Rows that decoded but that replay left without geometry: the eraser took their last piece.
     * Android tombstones these; reported here, since a replay is not the place to write.
     */
    public val erasedAway: List<String>,
    /** Live rows this build could not read — an encoder it does not know, or damaged data — and so did not draw. */
    public val unreadable: List<String>,
)

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
    ): LoadedInkPage {
        val rows = strokes.filter { it.deletedAt == null }.sortedWith(DRAW_ORDER)
        val operations = buildList {
            erases.filter { it.deletedAt == null }.forEach { add(Operation.Erase(it)) }
            moves.filter { it.deletedAt == null }.forEach { add(Operation.Move(it)) }
        }.sortedWith(compareBy(Operation::createdAt, Operation::id))
        val streaming = onPartial != null && operations.none { it is Operation.Move }
        val shown = ArrayList<PageStroke>(rows.size)
        val unreadable = ArrayList<String>()
        val decoded = ArrayList<PageStroke>(rows.size)
        decodeInChunks(rows, executor) { chunk, failed ->
            decoded += chunk
            unreadable += failed
            if (streaming) {
                shown += replay(chunk, operations)
                onPartial(ArrayList(shown))
            }
        }
        val live = if (streaming) shown else replay(decoded, operations)
        val drawn = live.mapTo(HashSet(live.size)) { it.id }
        return LoadedInkPage(
            strokes = live,
            erasedAway = decoded.mapNotNull { stroke -> stroke.id.takeIf { it !in drawn } }.distinct(),
            unreadable = unreadable,
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

    private fun replay(strokes: List<PageStroke>, operations: List<Operation>): List<PageStroke> =
        operations.fold(strokes) { current, operation ->
            when (operation) {
                is Operation.Erase -> {
                    val stored = operation.stored
                    val mode = InkEraseMode.of(stored.mode) ?: return@fold current
                    val mask = ViveInkCodec.decodeErase(stored) ?: return@fold current
                    when (mode) {
                        InkEraseMode.Normal -> current.subtract(mask, stored.targetIds)
                        InkEraseMode.Object -> current.eraseObjects(mask, stored.targetIds)
                    }
                }
                is Operation.Move -> {
                    val stored = operation.stored
                    val path = ViveInkCodec.decodeMove(stored) ?: return@fold current
                    current
                        .replayMove(path = path, targetIds = stored.targetIds, dx = stored.dxDp, dy = stored.dyDp)
                        .replayResize(
                            path = path,
                            targetIds = stored.targetIds,
                            anchor = InkPoint(stored.anchorX, stored.anchorY),
                            scaleX = stored.scaleX,
                            scaleY = stored.scaleY,
                        )
                }
            }
        }

    /** Decodes [rows] in draw order, handing each chunk's projections and unreadable ids to [onChunk] in order. */
    private fun decodeInChunks(
        rows: List<StoredInkStroke>,
        executor: Executor?,
        onChunk: (List<PageStroke>, List<String>) -> Unit,
    ) {
        val chunks = rows.chunked(DECODE_CHUNK)
        if (executor == null) {
            chunks.forEach { chunk -> decodeChunk(chunk).let { (decoded, failed) -> onChunk(decoded, failed) } }
            return
        }
        chunks.map { chunk -> CompletableFuture.supplyAsync({ decodeChunk(chunk) }, executor) }
            .forEach { future -> future.join().let { (decoded, failed) -> onChunk(decoded, failed) } }
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
