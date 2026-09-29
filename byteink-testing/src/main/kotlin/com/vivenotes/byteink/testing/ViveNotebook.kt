package com.vivenotes.byteink.testing

import com.vivenotes.byteink.vive.StoredInkErase
import com.vivenotes.byteink.vive.StoredInkMove
import com.vivenotes.byteink.vive.StoredInkStroke
import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.zip.ZipFile

/**
 * A ViveNotes `.vive` notebook, opened for tests: the zip around the app's SQLite database, read as
 * the rows byteink replays. Every row is returned, tombstoned ones included, so replay's own
 * filtering is what decides what is live. The database is copied to a temporary file, deleted on
 * [close].
 */
public class ViveNotebook private constructor(private val database: File) : AutoCloseable {

    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite:${database.path}")

    /** The pages that hold any ink row, sorted. */
    public val pageIds: List<String> = query(
        "SELECT pageId FROM ink_strokes UNION SELECT pageId FROM ink_erases UNION SELECT pageId FROM ink_moves ORDER BY 1",
    ) { it.getString(1) }

    /** Every ink row of [pageId]. */
    public fun page(pageId: String): NotebookPage = NotebookPage(
        pageId = pageId,
        strokes = query("SELECT * FROM ink_strokes WHERE pageId = ?", pageId) { row ->
            StoredInkStroke(
                id = row.getString("id"),
                pageId = row.getString("pageId"),
                seq = row.getInt("seq"),
                brushFamily = row.getString("brushFamily"),
                brushVersion = row.getInt("brushVersion"),
                sizeDp = row.getFloat("sizeDp"),
                colorArgb = row.getInt("colorArgb"),
                colorFollowsTheme = row.nullableInt("colorFollowsTheme")?.let { it != 0 },
                epsilon = row.getFloat("epsilon"),
                stabilization = row.getInt("stabilization"),
                minX = row.getFloat("minX"),
                minY = row.getFloat("minY"),
                maxX = row.getFloat("maxX"),
                maxY = row.getFloat("maxY"),
                points = row.getBytes("points"),
                enc = row.getString("enc"),
                createdAt = row.getLong("createdAt"),
                groupId = row.getString("groupId"),
                deletedAt = row.nullableLong("deletedAt"),
            )
        },
        erases = eraseTargets.let { targets ->
            query("SELECT * FROM ink_erases WHERE pageId = ?", pageId) { row ->
                StoredInkErase(
                    id = row.getString("id"),
                    pageId = row.getString("pageId"),
                    mode = row.getString("mode"),
                    sizeDp = row.getFloat("sizeDp"),
                    points = row.getBytes("points"),
                    enc = row.getString("enc"),
                    createdAt = row.getLong("createdAt"),
                    deletedAt = row.nullableLong("deletedAt"),
                    targetIds = targets[row.getString("id")].orEmpty(),
                )
            }
        },
        moves = moveTargets.let { targets ->
            query("SELECT * FROM ink_moves WHERE pageId = ?", pageId) { row ->
                StoredInkMove(
                    id = row.getString("id"),
                    pageId = row.getString("pageId"),
                    dxDp = row.getFloat("dxDp"),
                    dyDp = row.getFloat("dyDp"),
                    scaleX = row.getFloat("scaleX"),
                    scaleY = row.getFloat("scaleY"),
                    anchorX = row.getFloat("anchorX"),
                    anchorY = row.getFloat("anchorY"),
                    points = row.getBytes("points"),
                    enc = row.getString("enc"),
                    createdAt = row.getLong("createdAt"),
                    deletedAt = row.nullableLong("deletedAt"),
                    targetIds = targets[row.getString("id")].orEmpty(),
                )
            }
        },
    )

    private val eraseTargets by lazy { targets("ink_erase_targets", "eraseId") }
    private val moveTargets by lazy { targets("ink_move_targets", "moveId") }

    /** An operation table's targets, by operation id. */
    private fun targets(table: String, operation: String): Map<String, List<String>> =
        query("SELECT $operation, strokeId FROM $table ORDER BY rowid") { it.getString(1) to it.getString(2) }
            .groupBy({ it.first }, { it.second })

    private fun <T> query(sql: String, vararg arguments: String, read: (ResultSet) -> T): List<T> =
        connection.prepareStatement(sql).use { statement ->
            arguments.forEachIndexed { index, argument -> statement.setString(index + 1, argument) }
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(read(rows)) } }
        }

    private fun ResultSet.nullableInt(column: String): Int? = getInt(column).takeUnless { wasNull() }

    private fun ResultSet.nullableLong(column: String): Long? = getLong(column).takeUnless { wasNull() }

    override fun close() {
        connection.close()
        database.delete()
    }

    public companion object {
        /** Opens a `.vive` file: a zip holding `notebook.sqlite`. */
        public fun open(file: File): ViveNotebook {
            val database = Files.createTempFile("vive-", ".sqlite").toFile()
            ZipFile(file).use { zip ->
                val entry = requireNotNull(zip.getEntry("notebook.sqlite")) { "$file holds no notebook.sqlite" }
                zip.getInputStream(entry).use { input -> database.outputStream().use(input::copyTo) }
            }
            return ViveNotebook(database)
        }
    }
}

/** One page's ink rows, as stored. */
public class NotebookPage(
    public val pageId: String,
    public val strokes: List<StoredInkStroke>,
    public val erases: List<StoredInkErase>,
    public val moves: List<StoredInkMove>,
)
