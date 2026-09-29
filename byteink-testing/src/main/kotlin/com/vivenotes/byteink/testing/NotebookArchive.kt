package com.vivenotes.byteink.testing

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** The archive checks the Android transfer format supplies, before any database is opened. */
internal fun <T> verifiedArchive(file: File, read: (ZipFile, JsonObject) -> T): T = ZipFile(file).use { zip ->
    val entries = zip.entries().asSequence().filterNot { it.isDirectory }.toList()
    val names = entries.map { it.name }
    require(names.size == names.toSet().size) { "Duplicate notebook archive entry" }
    val checksumEntry = requireNotNull(zip.getEntry("checksums.sha256")) { "Missing checksums.sha256" }
    val checksums = linkedMapOf<String, String>()
    zip.getInputStream(checksumEntry).bufferedReader().useLines { lines ->
        lines.filter { it.isNotBlank() }.forEach { line ->
            val match = requireNotNull(Regex("([0-9a-fA-F]{64})  (.+)").matchEntire(line)) { "Invalid notebook checksum line" }
            val (hash, name) = match.destructured
            require(checksums.put(name, hash.lowercase()) == null) { "Duplicate checksum for $name" }
        }
    }
    require(checksums.keys == names.toSet() - "checksums.sha256") { "Notebook checksums do not match archive entries" }
    checksums.forEach { (name, expected) ->
        val actual = zip.getInputStream(zip.getEntry(name)).use { it.sha256() }
        require(actual == expected) { "Notebook checksum mismatch for $name" }
    }
    val manifest = zip.getInputStream(requireNotNull(zip.getEntry("manifest.json"))).use {
        Json.parseToJsonElement(it.readBytes().decodeToString()).jsonObject
    }
    require(manifest.getValue("format").jsonPrimitive.content == "com.vivenotes.notebook") { "Unknown notebook format" }
    require(manifest.getValue("formatVersion").jsonPrimitive.content == "1") { "Unsupported notebook format version" }
    val descriptor = manifest.getValue("database").jsonObject
    require(descriptor.getValue("path").jsonPrimitive.content == "notebook.sqlite") { "Unsupported database entry" }
    require(descriptor.getValue("sha256").jsonPrimitive.content == checksums["notebook.sqlite"]) { "Database manifest checksum mismatch" }
    require(descriptor.getValue("byteCount").jsonPrimitive.long == zip.getEntry("notebook.sqlite").size) { "Database manifest size mismatch" }
    read(zip, manifest)
}

internal fun InputStream.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
