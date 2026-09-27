package com.vivenotes.byteink.upstream

import kotlin.test.Test
import kotlin.test.assertEquals

class JniNamesTest {

    @Test
    fun manglesAsTheJniSpecificationSays() {
        assertEquals("androidx_ink_geometry_MeshNative", JniNames.mangle("androidx/ink/geometry/MeshNative"))
        assertEquals("BrushFamily_00024InputModel", JniNames.mangle("BrushFamily\$InputModel"))
        assertEquals("get_1value", JniNames.mangle("get_value"))
        assertEquals("_3Ljava_lang_String_2", JniNames.mangle("[Ljava/lang/String;"))
        assertEquals("caf_000e9", JniNames.mangle("café"))
    }

    @Test
    fun namesEachNativeByItsShortName() {
        val create = NativeMethod("androidx/ink/brush/BrushNative", "create", "(JIFF)J")
        val free = NativeMethod("androidx/ink/brush/BrushNative", "free", "(J)V")
        val inner = NativeMethod("androidx/ink/brush/BrushFamily\$InputModel", "create", "()J")

        assertEquals(
            sortedSetOf(
                "Java_androidx_ink_brush_BrushFamily_00024InputModel_create",
                "Java_androidx_ink_brush_BrushNative_create",
                "Java_androidx_ink_brush_BrushNative_free",
            ),
            JniNames.of(listOf(create, free, inner)),
        )
    }

    @Test
    fun overloadedNativesCanOnlyBindByTheirLongNames() {
        val byInt = NativeMethod("a/B", "f", "(I)V")
        val byStringAndLongs = NativeMethod("a/B", "f", "(Ljava/lang/String;[J)J")
        val elsewhere = NativeMethod("a/C", "f", "(I)V")

        assertEquals(
            sortedSetOf("Java_a_B_f__I", "Java_a_B_f__Ljava_lang_String_2_3J", "Java_a_C_f"),
            JniNames.of(listOf(byInt, byStringAndLongs, elsewhere)),
        )
    }
}
