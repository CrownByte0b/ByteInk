package com.vivenotes.byteink.upstream

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GoogleInkJniTest {

    @Test
    fun readsBothMacroFormsWhereverTheyWrap() {
        val source = """
            #include "ink/jni/internal/jni_defines.h"

            extern "C" {

            JNI_METHOD(brush, BrushNative, jlong, create)
            (JNIEnv* env, jobject object, jlong family_pointer) {
              return 0;
            }

            JNI_METHOD_INNER(brush, BrushFamily, InputModel,
                             jlong, createSlidingWindowModel)(JNIEnv* env, jobject object) {
              return 0;
            }

            JNI_METHOD(brush_behavior, EasingFunctionNative, void, free)(JNIEnv*, jobject, jlong) {}

            }  // extern "C"
        """.trimIndent()

        assertEquals(
            setOf(
                "Java_androidx_ink_brush_BrushNative_create",
                "Java_androidx_ink_brush_BrushFamily_00024InputModel_createSlidingWindowModel",
                "Java_androidx_ink_brush_behavior_EasingFunctionNative_free",
            ),
            GoogleInkJni.symbols(source),
        )
    }

    @Test
    fun ignoresCommentsQuotesAndTheMacroDefinitions() {
        val source = """
            #define JNI_METHOD(module, clazz, return_type, method_name) \
              JNIEXPORT return_type JNICALL Java_androidx_ink_##module##_##clazz##_##method_name
            // JNI_METHOD(brush, BrushNative, void, removed)
            /* JNI_METHOD(brush, BrushNative, void, alsoRemoved) */
            const char* kHint = "use JNI_METHOD(brush, BrushNative, void, quoted)";
            const char kQuote = '"';
            JNI_METHOD(brush, BrushNative, jfloat, getSize)(JNIEnv*, jobject, jlong) { return 0; }
        """.trimIndent()

        assertEquals(setOf("Java_androidx_ink_brush_BrushNative_getSize"), GoogleInkJni.symbols(source))
    }

    @Test
    fun jniSourcesAreTheNonTestCcFilesOfJniDirectories() {
        assertTrue(GoogleInkJni.isJniSource("ink/brush/internal/jni/brush_jni.cc"))
        assertTrue(GoogleInkJni.isJniSource("ink/jni/internal/status_jni.cc"))
        assertTrue(GoogleInkJni.isJniSource("ink/rendering/android/internal/jni/mesh_renderer_jni.cc"))
        assertFalse(GoogleInkJni.isJniSource("ink/brush/internal/jni/brush_jni_test.cc"))
        assertFalse(GoogleInkJni.isJniSource("ink/jni/internal/jni_defines.h"))
        assertFalse(GoogleInkJni.isJniSource("ink/brush/brush.cc"))
        assertFalse(GoogleInkJni.isJniSource("third_party/jni/glue.cc"))
    }
}
