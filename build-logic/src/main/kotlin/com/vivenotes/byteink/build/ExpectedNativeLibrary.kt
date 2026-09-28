package com.vivenotes.byteink.build

import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.process.CommandLineArgumentProvider

/** The system property through which a test JVM learns which Ink library it should be running. */
const val EXPECTED_LIBRARY_PROPERTY = "byteink.test.expectedInkLibrarySha256"

/** byteink's loader property: load this library instead of the one the loader jar bundles. */
const val INK_LIBRARY_PROPERTY = "byteink.ink.library"

/** Tells a test JVM to expect exactly [library], which must exist. */
class ExpectedLibraryFile(
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    val library: Provider<RegularFile>,
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> {
        val file = library.get().asFile
        check(file.isFile) { "$file does not exist" }
        return listOf("-D$EXPECTED_LIBRARY_PROPERTY=${sha256(file.inputStream())}")
    }
}

/** Has byteink's loader load [library] rather than the one its jar bundles. */
class InkLibraryOverride(
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    val library: Provider<RegularFile>,
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> = listOf("-D$INK_LIBRARY_PROPERTY=${library.get().asFile.absolutePath}")
}
