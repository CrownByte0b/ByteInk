package com.vivenotes.byteink.testing

import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import com.vivenotes.byteink.vive.StoredInkStroke
import com.vivenotes.byteink.vive.ViveBrushes
import com.vivenotes.byteink.vive.ViveInkCodec
import java.io.File
import java.sql.DriverManager
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotebookArchiveTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun appendCopyPreservesEveryExistingRowOperationAndAttachment() {
        val source = syntheticNotebook(temporary.root)
        val before = source.readBytes()
        val row = newStroke("appended", "page", 5)
        val copy = File(temporary.root, "copy.vive")
        ViveNotebook.open(source).use { original ->
            val page = original.page("page")
            assertEquals(listOf("empty", "page"), original.pageIds)
            original.writeCopyWithStrokes(copy, listOf(row))
            ViveNotebook.open(copy).use { reread ->
                val loaded = reread.page("page")
                assertEquals(page.strokes + row, loaded.strokes)
                assertEquals(page.erases, loaded.erases)
                assertEquals(page.moves, loaded.moves)
            }
        }
        assertTrue(before.contentEquals(source.readBytes()))
        val originalEntries = entries(source)
        val copyEntries = entries(copy)
        (originalEntries.keys - setOf("notebook.sqlite", "manifest.json", "checksums.sha256")).forEach { name ->
            assertTrue(originalEntries.getValue(name).contentEquals(copyEntries.getValue(name)), name)
        }
        val db = File(temporary.root, "copy.sqlite").apply { writeBytes(copyEntries.getValue("notebook.sqlite")) }
        DriverManager.getConnection("jdbc:sqlite:$db").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT payload FROM future_table").use { rows -> rows.next(); assertEquals("unknown data", rows.getString(1)) }
            }
        }
    }

    @Test
    fun damagedEntryIsRefusedBeforeOpeningTheDatabase() {
        val source = syntheticNotebook(temporary.root)
        val data = entries(source).toMutableMap()
        data["attachments/test"] = byteArrayOf(0)
        val damaged = File(temporary.root, "damaged.vive")
        zip(damaged, data)
        val error = assertFailsWith<IllegalArgumentException> { ViveNotebook.open(damaged).close() }
        assertTrue(error.message!!.contains("checksum mismatch"))
    }

    @Test
    fun checksumListMustCoverEveryEntry() {
        val source = syntheticNotebook(temporary.root)
        val data = entries(source).toMutableMap()
        data["unlisted"] = byteArrayOf(1)
        val damaged = File(temporary.root, "unlisted.vive")
        zip(damaged, data)
        assertFailsWith<IllegalArgumentException> { ViveNotebook.open(damaged).close() }
    }

    @Test
    fun theDatabaseDescriptorMustAgreeWithItsVerifiedBytes() {
        val source = syntheticNotebook(temporary.root)
        val data = entries(source).toMutableMap()
        val manifest = Json.parseToJsonElement(data.getValue("manifest.json").decodeToString()).jsonObject
        data["manifest.json"] = JsonObject(manifest + ("database" to JsonObject(
            manifest.getValue("database").jsonObject + ("byteCount" to JsonPrimitive(1)),
        ))).toString().encodeToByteArray()
        val damaged = File(temporary.root, "manifest-size.vive")
        zipWithChecksums(damaged, data - "checksums.sha256")
        assertFailsWith<IllegalArgumentException> { ViveNotebook.open(damaged).close() }
    }

    @Test
    fun aCopyNeverOverwritesTheSourceOrAnExistingDestination() {
        val source = syntheticNotebook(temporary.root)
        val existing = File(temporary.root, "existing.vive").apply { writeText("keep") }
        ViveNotebook.open(source).use { notebook ->
            assertFailsWith<IllegalArgumentException> { notebook.writeCopyWithStrokes(source, emptyList()) }
            assertFailsWith<IllegalArgumentException> { notebook.writeCopyWithStrokes(existing, emptyList()) }
        }
        assertEquals("keep", existing.readText())
    }

    @Test
    fun aFailedBatchLeavesNoDestinationAndTheSourceCanStillBeRead() {
        val source = syntheticNotebook(temporary.root)
        val destination = File(temporary.root, "failed.vive")
        ViveNotebook.open(source).use { notebook ->
            val duplicate = newStroke("same", "page", 5)
            assertFailsWith<java.sql.SQLException> { notebook.writeCopyWithStrokes(destination, listOf(duplicate, duplicate)) }
            assertFalse(destination.exists())
            assertEquals(2, notebook.page("page").strokes.size)
        }
    }
}

/** Synthetic transfer schema and opaque rows: no data derived from personal notebooks. */
internal fun syntheticNotebook(directory: File): File {
    val database = File(directory, "synthetic.sqlite")
    DriverManager.getConnection("jdbc:sqlite:$database").use { db ->
        db.createStatement().use { sql ->
            sql.execute("CREATE TABLE pages (id TEXT PRIMARY KEY)")
            sql.execute("INSERT INTO pages VALUES ('page'),('empty')")
            sql.execute("CREATE TABLE ink_strokes (id TEXT PRIMARY KEY,pageId TEXT NOT NULL REFERENCES pages(id),seq INTEGER,brushFamily TEXT,brushVersion INTEGER,sizeDp REAL,colorArgb INTEGER,colorFollowsTheme INTEGER,epsilon REAL,stabilization INTEGER,minX REAL,minY REAL,maxX REAL,maxY REAL,points BLOB,enc TEXT,createdAt INTEGER,groupId TEXT,deletedAt INTEGER)")
            sql.execute("INSERT INTO ink_strokes VALUES ('opaque','page',1,'unknown',99,1,-1,NULL,0.25,0,0,0,1,1,x'00ff','future',1,'group',NULL)")
            sql.execute("INSERT INTO ink_strokes VALUES ('tombstone','page',2,'marker',1,1,-1,0,0.25,0,0,0,1,1,x'00ff','future',1,NULL,2)")
            sql.execute("CREATE TABLE ink_erases (id TEXT PRIMARY KEY,pageId TEXT,mode TEXT,sizeDp REAL,points BLOB,enc TEXT,createdAt INTEGER,deletedAt INTEGER)")
            sql.execute("INSERT INTO ink_erases VALUES ('erase','page','future',12,x'ffaa','future',2,NULL)")
            sql.execute("CREATE TABLE ink_erase_targets (eraseId TEXT,strokeId TEXT)")
            sql.execute("INSERT INTO ink_erase_targets VALUES ('erase','opaque')")
            sql.execute("CREATE TABLE ink_moves (id TEXT PRIMARY KEY,pageId TEXT,dxDp REAL,dyDp REAL,scaleX REAL,scaleY REAL,anchorX REAL,anchorY REAL,points BLOB,enc TEXT,createdAt INTEGER,deletedAt INTEGER)")
            sql.execute("INSERT INTO ink_moves VALUES ('move','page',1,2,1,1,0,0,x'ffaa','future',3,NULL)")
            sql.execute("CREATE TABLE ink_move_targets (moveId TEXT,strokeId TEXT)")
            sql.execute("INSERT INTO ink_move_targets VALUES ('move','opaque')")
            sql.execute("CREATE TABLE future_table (payload TEXT)")
            sql.execute("INSERT INTO future_table VALUES ('unknown data')")
        }
    }
    val hash = database.inputStream().use { it.sha256() }
    val manifest = """{"format":"com.vivenotes.notebook","formatVersion":1,"future":"preserve","database":{"path":"notebook.sqlite","sha256":"$hash","byteCount":${database.length()}},"counts":{"strokes":2}}"""
    val file = File(directory, "synthetic.vive")
    zipWithChecksums(file, mapOf("manifest.json" to manifest.encodeToByteArray(), "notebook.sqlite" to database.readBytes(), "attachments/test" to byteArrayOf(1, 2, 3)))
    database.delete()
    return file
}

internal fun newStroke(id: String, page: String, seq: Int, family: String = ViveBrushes.MARKER): StoredInkStroke {
    val inputs = MutableStrokeInputBatch().apply {
        repeat(10) { i -> add(InputToolType.MOUSE, 10f + i * 8, 40f + seq * 16, i * 16L) }
    }
    val stroke = Stroke(ViveBrushes.brush(family, 0, 0x800020ff.toInt(), 8f), inputs.toImmutable())
    return ViveInkCodec.encodeStroke(stroke, id, page, seq, family, 0, false, 100L)
}

private fun entries(file: File): Map<String, ByteArray> = ZipFile(file).use { zip ->
    zip.entries().asSequence().associate { entry -> entry.name to zip.getInputStream(entry).use { it.readBytes() } }
}

private fun zip(file: File, entries: Map<String, ByteArray>) {
    ZipOutputStream(file.outputStream()).use { output -> entries.forEach { (name, bytes) ->
        output.putNextEntry(ZipEntry(name)); output.write(bytes); output.closeEntry()
    } }
}

private fun zipWithChecksums(file: File, entries: Map<String, ByteArray>) {
    val checksums = entries.entries.joinToString("\n", postfix = "\n") { (name, bytes) -> "${bytes.inputStream().use { it.sha256() }}  $name" }
    zip(file, entries + ("checksums.sha256" to checksums.encodeToByteArray()))
}
