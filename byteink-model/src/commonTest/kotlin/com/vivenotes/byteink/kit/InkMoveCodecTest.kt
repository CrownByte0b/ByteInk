package com.vivenotes.byteink.kit

import kotlin.test.*

class InkMoveCodecTest {
    @Test fun wireIsLittleEndianAndKeepsFloatBits() {
        val path = listOf(InkPoint(0f, -0f), InkPoint(1.5f, -2f), InkPoint(100f, 20f))
        val expected = byteArrayOf(3,0,0,0, 0,0,0,0, 0,0,0,-128, 0,0,-64,63, 0,0,0,-64, 0,0,-56,66, 0,0,-96,65)
        assertContentEquals(expected, InkMoveCodec.encodePath(path))
        assertEquals(path, InkMoveCodec.decodePath(expected))
    }

    @Test fun invalidCountsTruncationAndNonFiniteCoordinatesAreRefused() {
        val valid = InkMoveCodec.encodePath(listOf(InkPoint(0f,0f), InkPoint(1f,0f), InkPoint(1f,1f)))
        for (size in 0 until valid.size) assertNull(InkMoveCodec.decodePath(valid.copyOf(size)))
        assertNull(InkMoveCodec.decodePath(valid.copyOf(valid.size + 1)))
        assertNull(InkMoveCodec.decodePath(valid.copyOf().apply { for (i in 0..3) this[i] = -1 }))
        assertNull(InkMoveCodec.decodePath(valid.copyOf().apply { this[6] = -64; this[7] = 127 }))
        assertFailsWith<IllegalArgumentException> { InkMoveCodec.encodePath(emptyList()) }
        assertFailsWith<IllegalArgumentException> {
            InkMoveCodec.encodePath(listOf(InkPoint(Float.NaN,0f), InkPoint(1f,0f), InkPoint(1f,1f)))
        }
    }
}
