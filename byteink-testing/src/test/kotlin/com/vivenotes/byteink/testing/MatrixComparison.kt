package com.vivenotes.byteink.testing

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.max
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** Typed comparison: topology/counts are integers, while native coordinates have a float tolerance. */
internal object MatrixComparison {
    data class Geometry(val integers: Int, val floats: Int, val maximumGap: Double, val issues: List<String>)

    fun geometry(reference: JsonElement, candidate: JsonElement): Geometry {
        var integers = 0
        var floats = 0
        var maximumGap = 0.0
        val issues = mutableListOf<String>()
        fun issue(path: String, message: String) { if (issues.size < 40) issues += "$path: $message" }
        fun compare(a: JsonElement, b: JsonElement, path: String) {
            when {
                a is JsonObject && b is JsonObject -> {
                    (a.keys - b.keys).forEach { issue("$path.$it", "missing field") }
                    (b.keys - a.keys).forEach { issue("$path.$it", "unexpected field") }
                    (a.keys intersect b.keys).forEach { compare(a.getValue(it), b.getValue(it), "$path.$it") }
                }
                a is JsonArray && b is JsonArray -> {
                    if (a.size != b.size) issue(path, "array length ${a.size} != ${b.size}")
                    repeat(minOf(a.size, b.size)) { compare(a[it], b[it], "$path[$it]") }
                }
                a is JsonPrimitive && b is JsonPrimitive && a != JsonNull && b != JsonNull &&
                    !a.isString && !b.isString && a.doubleOrNull != null && b.doubleOrNull != null -> {
                    val integer = a.content.matches(Regex("-?\\d+"))
                    val candidateInteger = b.content.matches(Regex("-?\\d+"))
                    if (integer != candidateInteger) {
                        issue(path, "numeric types differ: $a != $b")
                    } else if (integer) {
                        integers++
                        if (a.longOrNull != b.longOrNull || a.longOrNull == null) issue(path, "exact integer $a != $b")
                    } else {
                        floats++
                        val x = requireNotNull(a.doubleOrNull)
                        val y = requireNotNull(b.doubleOrNull)
                        val gap = abs(x - y)
                        maximumGap = max(maximumGap, gap)
                        if (!x.isFinite() || !y.isFinite() || gap > 1e-4 + 1e-5 * max(abs(x), abs(y))) {
                            issue(path, "float $a != $b (gap $gap)")
                        }
                    }
                }
                a != b -> issue(path, "$a != $b")
            }
        }
        compare(reference, candidate, "geometry")
        return Geometry(integers, floats, maximumGap, issues)
    }

    /** Refuses stale, damaged, missing or unlisted artifacts before building a stroke. */
    fun validateManifest(directory: File): Map<String, String> {
        val manifest = File(directory, "manifest.sha256")
        require(manifest.isFile) { "Missing Android matrix manifest: $manifest" }
        val entries = manifest.readLines().filter { it.isNotBlank() }.map { line ->
            require(line.matches(Regex("[0-9a-f]{64}  .+"))) { "Malformed checksum manifest line: $line" }
            line.substring(66) to line.substring(0, 64)
        }
        require(entries.map { it.first }.distinct().size == entries.size) { "Duplicate Android matrix manifest paths" }
        val root = directory.canonicalFile.toPath()
        entries.forEach { (path, expected) ->
            require(!path.startsWith('/') && '\\' !in path && path.split('/').none { it == ".." || it.isEmpty() }) {
                "Unsafe Android matrix manifest path: $path"
            }
            val file = File(directory, path)
            require(file.canonicalFile.toPath().startsWith(root) && file.isFile) { "Missing Android artifact: $path" }
            require(sha256(file) == expected) { "Android artifact checksum differs: $path" }
        }
        val actual = directory.walkTopDown().filter { it.isFile && it != manifest }
            .map { it.relativeTo(directory).invariantSeparatorsPath }.toSet()
        require(actual == entries.map { it.first }.toSet()) {
            "Android matrix manifest inventory differs: unlisted=${actual - entries.map { it.first }.toSet()}, " +
                "missing=${entries.map { it.first }.toSet() - actual}"
        }
        return entries.toMap()
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val bytes = ByteArray(65536)
            while (true) {
                val count = input.read(bytes)
                if (count < 0) break
                digest.update(bytes, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    data class Pixels(
        val maximum: Int, val mae: Double, val differingPixels: Int, val differingPercent: Double,
        val inkPixels: Int, val differingInkPercent: Double, val ssim: Double,
        val referenceInk: Int, val candidateInk: Int, val unexplainedInterior: Int,
    ) {
        fun json(): JsonObject = JsonObject(mapOf(
            "maxChannelDelta" to JsonPrimitive(maximum), "meanAbsoluteChannelError" to JsonPrimitive(mae),
            "pixelsDiffering" to JsonPrimitive(differingPixels), "pixelsDifferingPercent" to JsonPrimitive(differingPercent),
            "inkPixels" to JsonPrimitive(inkPixels), "inkPixelsDeltaAbove16Percent" to JsonPrimitive(differingInkPercent),
            "ssim" to JsonPrimitive(ssim), "referenceInkPixels" to JsonPrimitive(referenceInk),
            "candidateInkPixels" to JsonPrimitive(candidateInk), "unexplainedInteriorPixels" to JsonPrimitive(unexplainedInterior),
        ))
    }

    /** RGB metrics against white paper; alpha is already composited by both rendering pipelines. */
    fun pixels(reference: BufferedImage, candidate: BufferedImage, allowTranslucentAny: Boolean,
        translucentPathRgb: Int? = null): Pixels {
        require(reference.width == candidate.width && reference.height == candidate.height) { "Raster dimensions differ" }
        val width = reference.width
        val height = reference.height
        val shifts = intArrayOf(16, 8, 0)
        val a = reference.getRGB(0, 0, width, height, null, 0, width)
        val b = candidate.getRGB(0, 0, width, height, null, 0, width)
        val referenceInk = BooleanArray(a.size)
        val candidateInk = BooleanArray(a.size)
        fun ink(color: Int): Boolean = ((color ushr 16) and 255) < 250 || ((color ushr 8) and 255) < 250 || (color and 255) < 250
        for (i in a.indices) { referenceInk[i] = ink(a[i]); candidateInk[i] = ink(b[i]) }
        fun nearEdge(index: Int): Boolean {
            val x = index % width
            val y = index / width
            val valueA = referenceInk[index]
            val valueB = candidateInk[index]
            // Android mesh AA and hardware MSAA may affect a two-pixel band, including internal
            // overlap boundaries whose pixels are all ink. Interior changes
            // must satisfy the same-hue alpha accumulation rule below, rather than this exception.
            for (py in maxOf(0, y - 2)..minOf(height - 1, y + 2)) {
                for (px in maxOf(0, x - 2)..minOf(width - 1, x + 2)) {
                    val neighbor = py * width + px
                    if (referenceInk[neighbor] != valueA || candidateInk[neighbor] != valueB) return true
                    for (shift in shifts) {
                        if (abs(((a[index] ushr shift) and 255) - ((a[neighbor] ushr shift) and 255)) > 16 ||
                            abs(((b[index] ushr shift) and 255) - ((b[neighbor] ushr shift) and 255)) > 16) return true
                    }
                }
            }
            return false
        }
        fun sameHueDarkening(index: Int): Boolean {
            if (!allowTranslucentAny || !referenceInk[index] || !candidateInk[index]) return false
            // A translucent stroke's uniform interior has this known colour after compositing on
            // white paper. Opaque strokes on the same page must never acquire an ANY exception.
            if (translucentPathRgb != null && shifts.any { shift ->
                    abs(((b[index] ushr shift) and 255) - ((translucentPathRgb ushr shift) and 255)) > 2
                }) return false
            var numerator = 0.0
            var denominator = 0.0
            for (shift in shifts) {
                val x = 255 - ((b[index] ushr shift) and 255) // path candidate
                val y = 255 - ((a[index] ushr shift) and 255) // Android hardware
                numerator += x.toDouble() * y
                denominator += x.toDouble() * x
            }
            if (denominator == 0.0) return false
            val ratio = numerator / denominator
            if (ratio < 0.95 || ratio > 2.1) return false
            for (shift in shifts) {
                val pathDistance = 255 - ((b[index] ushr shift) and 255)
                val hardwareDistance = 255 - ((a[index] ushr shift) and 255)
                if (abs(hardwareDistance - ratio * pathDistance) > 5.0) return false
            }
            return true
        }
        var maximum = 0
        var sum = 0L
        var differing = 0
        var inkPixels = 0
        var differingInk = 0
        var unexplained = 0
        for (i in a.indices) {
            var delta = 0
            for (shift in shifts) {
                val difference = abs(((a[i] ushr shift) and 255) - ((b[i] ushr shift) and 255))
                delta = max(delta, difference)
                sum += difference
            }
            maximum = max(maximum, delta)
            if (delta > 0) differing++
            if (referenceInk[i] || candidateInk[i]) {
                inkPixels++
                if (delta > 16) differingInk++
            }
            if (delta > 16 && !nearEdge(i) && !sameHueDarkening(i)) unexplained++
        }
        fun luminance(color: Int): Double = 0.2126 * ((color ushr 16) and 255) +
            0.7152 * ((color ushr 8) and 255) + 0.0722 * (color and 255)
        var ssim = 0.0
        var windows = 0
        for (top in 0 until height step 8) for (left in 0 until width step 8) {
            var sx = 0.0; var sy = 0.0; var sxx = 0.0; var syy = 0.0; var sxy = 0.0; var count = 0
            for (y in top until minOf(top + 8, height)) for (x in left until minOf(left + 8, width)) {
                val first = luminance(a[y * width + x]); val second = luminance(b[y * width + x])
                sx += first; sy += second; sxx += first * first; syy += second * second; sxy += first * second; count++
            }
            val mx = sx / count; val my = sy / count
            val vx = max(0.0, sxx / count - mx * mx); val vy = max(0.0, syy / count - my * my)
            val covariance = sxy / count - mx * my
            ssim += ((2 * mx * my + 6.5025) * (2 * covariance + 58.5225)) /
                ((mx * mx + my * my + 6.5025) * (vx + vy + 58.5225))
            windows++
        }
        return Pixels(maximum, sum.toDouble() / (a.size * 3L), differing, differing * 100.0 / a.size,
            inkPixels, differingInk * 100.0 / max(1, inkPixels), ssim / windows,
            referenceInk.count { it }, candidateInk.count { it }, unexplained)
    }

    fun readImage(file: File): BufferedImage = requireNotNull(ImageIO.read(file)) { "Unreadable PNG: $file" }

    /** The pinned oracle already treats Google's non-public animation-phase field 10 as informational. */
    fun publicInputProto(message: ByteArray): ByteArray {
        var at = 0
        fun varint(): Long {
            var value = 0L
            var shift = 0
            while (true) {
                require(at < message.size && shift < 64) { "Malformed input protobuf varint" }
                val byte = message[at++].toInt() and 255
                value = value or ((byte and 127).toLong() shl shift)
                if (byte and 128 == 0) return value
                shift += 7
            }
        }
        val output = ByteArrayOutputStream(message.size)
        while (at < message.size) {
            val start = at
            val key = varint()
            require(key ushr 3 > 0) { "Invalid input protobuf field" }
            when ((key and 7).toInt()) {
                0 -> varint()
                1 -> at += 8
                2 -> { val length = Math.toIntExact(varint()); require(length >= 0); at = Math.addExact(at, length) }
                5 -> at += 4
                else -> error("Unsupported input protobuf wire type")
            }
            require(at <= message.size) { "Truncated input protobuf" }
            if (key ushr 3 != 10L) output.write(message, start, at - start)
        }
        return output.toByteArray()
    }

    /** Existing accepted differences are constrained to edges or same-hue ANY self-overlap. */
    fun hardwareIssues(metrics: Pixels): List<String> = buildList {
        if (metrics.ssim < 0.95) add("hardware SSIM ${metrics.ssim} < 0.95")
        if (metrics.mae > 5.0) add("hardware channel MAE ${metrics.mae} > 5.0")
        val areaRatio = metrics.candidateInk.toDouble() / max(1, metrics.referenceInk)
        if (metrics.referenceInk == 0 && metrics.candidateInk != 0 ||
            metrics.referenceInk > 0 && areaRatio !in 0.85..1.15) add("hardware ink area ratio $areaRatio outside 0.85..1.15")
        if (metrics.unexplainedInterior > 0) add("${metrics.unexplainedInterior} unexplained interior pixels above delta 16")
    }
}
