@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.vivenotes.byteink.wasm

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.await
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun engineUrl(): String = js("process.env.BYTEINK_WASM_ENGINE")
private fun androidFamily(): Promise<NativeNumbers> =
    js("import('node:fs/promises').then(fs => fs.readFile(process.env.BYTEINK_WASM_FAMILY))")

class InkWasmTest {
    private val brush = InkBrush(colorArgb = 0xff123456.toInt(), size = 4f)
    private val samples = listOf(InkSample(10f, 20f, 0.0, InkToolType.MOUSE), InkSample(40f, 20f, 16.0, InkToolType.MOUSE))

    @Test fun authorReadHitTestAndReload() = runTest {
        val engine = InkWasm.load(engineUrl())
        assertEquals("96e50239e1c8955f7e222301661b595847d7de83", engine.engineCommit)
        engine.createStroke(brush, samples).use { stroke ->
            assertEquals(samples, stroke.inputs())
            assertTrue(stroke.contains(25f, 20f))
            assertFalse(stroke.contains(25f, 50f))
            assertTrue(stroke.geometry().flatMap { it.meshes }.sumOf { it.triangleCount } > 0)
            val encoded = stroke.encodeInputs()
            engine.decodeStroke(brush, encoded).use { rebuilt ->
                assertContentEquals(encoded, rebuilt.encodeInputs())
                assertTrue(rebuilt.contains(25f, 20f))
            }
            val android = stroke.encodeAndroidInputs()
            engine.decodeAndroidStroke(brush, android).use { rebuilt ->
                assertContentEquals(android, rebuilt.encodeAndroidInputs())
                assertTrue(rebuilt.contains(25f, 20f))
            }
        }
    }

    @Test fun everyStockBrushAndOptionalPenAttributes() = runTest {
        val engine = InkWasm.load(engineUrl())
        val pen = samples.map { it.copy(toolType = InkToolType.STYLUS, pressure = .6f, tiltRadians = .2f,
            orientationRadians = .5f, strokeUnitLengthCm = .01f) }
        InkStockBrush.entries.forEach { family ->
            val config = InkBrush(family, 0x80334455.toInt(), size = 5f)
            engine.createStroke(config, pen).use { stroke ->
                assertEquals(pen, stroke.inputs())
                engine.decodeStroke(config, stroke.encodeInputs()).use { decoded ->
                    assertEquals(2, decoded.inputs().size)
                    assertEquals(InkToolType.STYLUS, decoded.inputs().first().toolType)
                    assertTrue(decoded.geometry().flatMap { it.meshes }.sumOf { it.triangleCount } > 0)
                }
            }
        }
    }

    @Test fun serializedAndroidFamiliesAreCopiedAndWorkForAuthoringAndReload() = runTest {
        val engine = InkWasm.load(engineUrl())
        val bytes = androidFamily().await()
        val encoded = ByteArray(bytes.length) { numberAt(bytes, it).toInt().toByte() }
        val configured = InkBrush.fromFamilyProto(encoded, size = 4f)
        encoded.fill(0) // Mutating the caller's buffer must not change the configured family.
        engine.createStroke(configured, samples).use { direct ->
            engine.decodeAndroidStroke(configured, direct.encodeAndroidInputs()).use { decoded ->
                assertTrue(decoded.contains(25f, 20f))
                assertEquals(2, decoded.inputs().size)
            }
            engine.startStroke(configured).use { live ->
                live.enqueue(samples)
                live.update(16.0)
                live.finish(16.0).use { finished ->
                    assertContentEquals(direct.geometry().first().meshes.first().positions,
                        finished.geometry().first().meshes.first().positions)
                }
            }
        }
    }

    @Test fun predictionsAreReplacedAndNeverStored() = runTest {
        val engine = InkWasm.load(engineUrl())
        engine.startStroke(brush).use { live ->
            live.enqueue(samples, listOf(InkSample(60f, 25f, 32.0, InkToolType.MOUSE)))
            live.update(32.0)
            assertEquals(2, live.realInputCount)
            assertEquals(1, live.predictedInputCount)
            live.enqueue(emptyList(), listOf(InkSample(70f, 30f, 48.0, InkToolType.MOUSE)))
            live.update(48.0)
            assertEquals(1, live.predictedInputCount)
            live.finish(48.0).use { finished ->
                assertEquals(0, live.predictedInputCount)
                assertEquals(samples, finished.inputs())
                assertTrue(finished.contains(25f, 20f))
            }
            assertFailsWith<IllegalStateException> { live.enqueue(samples) }
            assertFailsWith<IllegalStateException> { live.finish(48.0) }
        }
    }

    @Test fun snapshotsSurviveCloseAndRepeatedCloseIsSafe() = runTest {
        val engine = InkWasm.load(engineUrl())
        val stroke = engine.createStroke(brush, samples)
        val positions = stroke.geometry().first().meshes.first().positions
        val saved = positions.copyOf()
        stroke.close()
        stroke.close()
        assertContentEquals(saved, positions)
        assertFailsWith<IllegalStateException> { stroke.geometry() }
        assertFailsWith<IllegalStateException> { stroke.inputs() }
        assertFailsWith<IllegalStateException> { stroke.encodeInputs() }
        assertFailsWith<IllegalStateException> { stroke.contains(0f, 0f) }
        val live = engine.startStroke(brush)
        live.close()
        live.close()
        assertFailsWith<IllegalStateException> { live.update(0.0) }
        assertFailsWith<IllegalStateException> { live.geometry() }
        assertFailsWith<IllegalStateException> { live.enqueue(samples) }
        assertFailsWith<IllegalStateException> { live.realInputCount }
    }

    @Test fun invalidDataDoesNotCorruptTheEngine() = runTest {
        val engine = InkWasm.load(engineUrl())
        assertFailsWith<IllegalArgumentException> { InkSample(Float.NaN, 0f, 0.0) }
        assertFailsWith<IllegalArgumentException> { InkSample(0f, 0f, -1.0) }
        assertFailsWith<IllegalArgumentException> { InkSample(0f, 0f, 0.0, pressure = 1.1f) }
        assertFailsWith<IllegalArgumentException> { InkBrush(size = .1f) }
        assertFails { engine.decodeStroke(brush, byteArrayOf(-1)) }
        assertFails { engine.createStroke(brush, samples + samples.first()) }
        engine.startStroke(brush).use { live ->
            live.enqueue(samples)
            live.update(16.0)
            assertFails { live.finish(8.0) }
            live.finish(16.0).close()
        }
        engine.createStroke(brush, samples).use { assertTrue(it.contains(25f, 20f)) }
    }

    @Test fun emptyStrokesAreSupported() = runTest {
        val engine = InkWasm.load(engineUrl())
        engine.createStroke(brush, emptyList()).use { empty ->
            assertTrue(empty.inputs().isEmpty())
            assertFalse(empty.contains(0f, 0f))
            assertEquals(0, empty.geometry().flatMap { it.meshes }.sumOf { it.triangleCount })
        }
    }

    @Test fun loadFailureIsReported() = runTest {
        assertFailsWith<IllegalArgumentException> { InkWasm.load("") }
        assertFails { InkWasm.load("file:///byteink-missing-module.mjs") }
    }
}
