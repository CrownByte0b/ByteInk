package com.vivenotes.byteink.testing

import com.vivenotes.byteink.kit.StoredInkErase
import com.vivenotes.byteink.kit.StoredInkMove
import com.vivenotes.byteink.kit.StoredInkStroke
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * A ViveNotes `.vive` notebook, opened for tests: the zip around the app's SQLite database, read as
 * the rows byteink replays. Every row is returned, tombstoned ones included, so replay's own
 * filtering is what decides what is live. The database is copied to a temporary file, deleted on
 * [close].
 */
public class ViveNotebook private constructor(
    private val database: File,
    private val source: File,
    private val connection: Connection,
) : AutoCloseable {

    /** Every page in the notebook, including empty pages, sorted by id. */
    public val pageIds: List<String> = query(
        "SELECT id FROM pages ORDER BY id",
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

    /**
     * Test support for a save/load proof: copies this archive and appends [strokes] to its database.
     * Existing rows, operations and other archive entries are preserved. Refreshes the manifest's
     * database descriptor, stroke count and archive checksums. The source is never written, and an
     * existing destination is refused. This is not the application's export/sync implementation.
     */
    public fun writeCopyWithStrokes(destination: File, strokes: List<StoredInkStroke>) {
        writeCopyWithInk(destination, strokes, emptyList())
    }

    /** Appends new strokes and erases, including target links, in one transaction to a fresh copy. */
    public fun writeCopyWithInk(
        destination: File,
        strokes: List<StoredInkStroke>,
        erases: List<StoredInkErase>,
    ) {
        require(destination.canonicalFile != source.canonicalFile) { "The copy must differ from the source" }
        require(!destination.exists()) { "The destination already exists: $destination" }
        val copy = Files.createTempFile("vive-copy-", ".sqlite").toFile()
        var output: File? = null
        try {
            Files.copy(database.toPath(), copy.toPath(), StandardCopyOption.REPLACE_EXISTING)
            val count = DriverManager.getConnection("jdbc:sqlite:${copy.path}").use { db ->
                db.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
                db.autoCommit = false
                db.prepareStatement("INSERT INTO ink_strokes (id,pageId,seq,brushFamily,brushVersion,sizeDp,colorArgb,colorFollowsTheme,epsilon,stabilization,minX,minY,maxX,maxY,points,enc,createdAt,groupId,deletedAt) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)").use { statement ->
                    strokes.forEach { row ->
                        val values = listOf(row.id, row.pageId, row.seq, row.brushFamily, row.brushVersion, row.sizeDp,
                            row.colorArgb, row.colorFollowsTheme?.let { if (it) 1 else 0 }, row.epsilon, row.stabilization,
                            row.minX, row.minY, row.maxX, row.maxY, row.points, row.enc, row.createdAt, row.groupId, row.deletedAt)
                        values.forEachIndexed { index, value ->
                            if (value is ByteArray) statement.setBytes(index + 1, value) else statement.setObject(index + 1, value)
                        }
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
                db.prepareStatement("INSERT INTO ink_erases (id,pageId,mode,sizeDp,points,enc,createdAt,deletedAt) VALUES (?,?,?,?,?,?,?,?)").use { statement ->
                    erases.forEach { row ->
                        val values = listOf(row.id, row.pageId, row.mode, row.sizeDp, row.points, row.enc, row.createdAt, row.deletedAt)
                        values.forEachIndexed { index, value ->
                            if (value is ByteArray) statement.setBytes(index + 1, value) else statement.setObject(index + 1, value)
                        }
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
                // Android's target table intentionally has no stroke FK (targets can outlive rows).
                // New operations still need real same-page targets; do not create broken gestures.
                db.prepareStatement("SELECT pageId FROM ink_strokes WHERE id = ?").use { lookup ->
                    db.prepareStatement("INSERT INTO ink_erase_targets (eraseId,strokeId) VALUES (?,?)").use { statement ->
                        erases.forEach { row ->
                            require(row.targetIds.distinct().size == row.targetIds.size) { "Duplicate erase targets: ${row.id}" }
                            row.targetIds.forEach { target ->
                                lookup.setString(1, target)
                                lookup.executeQuery().use { result ->
                                    require(result.next() && result.getString(1) == row.pageId) {
                                        "Erase target must exist on its page: ${row.id}/$target"
                                    }
                                }
                                statement.setString(1, row.id)
                                statement.setString(2, target)
                                statement.addBatch()
                            }
                        }
                        statement.executeBatch()
                    }
                }
                db.commit()
                db.createStatement().use { statement ->
                    statement.executeQuery("SELECT count(*) FROM ink_strokes").use { rows -> rows.next(); rows.getLong(1) }
                }
            }
            verifiedArchive(source) { zip, manifest ->
                val hash = copy.inputStream().use { it.sha256() }
                val updated = JsonObject(manifest + mapOf(
                    "database" to JsonObject(manifest.getValue("database").jsonObject + mapOf(
                        "byteCount" to JsonPrimitive(copy.length()), "sha256" to JsonPrimitive(hash),
                    )),
                    "counts" to JsonObject(manifest.getValue("counts").jsonObject + ("strokes" to JsonPrimitive(count))),
                )).toString().encodeToByteArray()
                destination.absoluteFile.parentFile.mkdirs()
                val staging = Files.createTempFile(destination.absoluteFile.parentFile.toPath(), ".vive-", ".tmp").toFile()
                output = staging
                val checksums = linkedMapOf<String, String>()
                ZipOutputStream(staging.outputStream().buffered()).use { result ->
                    zip.entries().asSequence().filterNot { it.isDirectory || it.name == "checksums.sha256" }.forEach { entry ->
                        result.putNextEntry(ZipEntry(entry.name))
                        when (entry.name) {
                            "notebook.sqlite" -> copy.inputStream().use { it.copyTo(result) }
                            "manifest.json" -> result.write(updated)
                            else -> zip.getInputStream(entry).use { it.copyTo(result) }
                        }
                        result.closeEntry()
                        checksums[entry.name] = when (entry.name) {
                            "notebook.sqlite" -> hash
                            "manifest.json" -> updated.inputStream().use { it.sha256() }
                            else -> zip.getInputStream(entry).use { it.sha256() }
                        }
                    }
                    result.putNextEntry(ZipEntry("checksums.sha256"))
                    result.write(checksums.entries.joinToString("\n", postfix = "\n") { (name, sha) -> "$sha  $name" }.encodeToByteArray())
                    result.closeEntry()
                }
                Files.move(staging.toPath(), destination.toPath())
            }
        } finally {
            copy.delete()
            output?.delete()
        }
    }

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
        try { connection.close() } finally { database.delete() }
    }

    public companion object {
        /** Verifies every archive checksum and the database manifest, then opens the private database copy. */
        public fun open(file: File): ViveNotebook {
            val database = Files.createTempFile("vive-", ".sqlite").toFile()
            var connection: Connection? = null
            try {
                verifiedArchive(file) { zip, _ ->
                    zip.getInputStream(zip.getEntry("notebook.sqlite")).use { input -> database.outputStream().use(input::copyTo) }
                }
                connection = DriverManager.getConnection("jdbc:sqlite:${database.path}")
                return ViveNotebook(database, file, connection)
            } catch (failure: Throwable) {
                connection?.close()
                database.delete()
                throw failure
            }
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
