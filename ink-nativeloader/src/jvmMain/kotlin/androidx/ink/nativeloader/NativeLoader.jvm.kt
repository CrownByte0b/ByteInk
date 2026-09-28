/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Modified for byteink: loads byteink's own native library (see PATCHES.md).
 */

package androidx.ink.nativeloader

import androidx.annotation.RestrictTo
import com.vivenotes.byteink.nativeloader.InkNativeLibrary

/**
 * Native code loader for Android and JVM.
 *
 * Supporting both in a single loader is helpful because Android code is run on JVM for Android host
 * tests.
 */
@InkInternalOnlyApi
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
actual public object NativeLoader {
    actual public fun load() {
        // byteink: the library bundled for Linux or Windows x86_64, from a per-user cache, or the one
        // -Dbyteink.ink.library names. InkNativeLibrary loads it once.
        InkNativeLibrary.load()
    }
}
