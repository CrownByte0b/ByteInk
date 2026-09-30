package com.vivenotes.byteink.nativeloader

import com.vivenotes.byteink.nativeloader.LoadedInkLibrary.Origin
import com.vivenotes.byteink.nativeloader.NativeLibraryLoader.CacheLocation
import com.vivenotes.byteink.nativeloader.NativeLibraryLoader.Location
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class NativeLibraryLoaderTest {

    @get:Rule
    val temporary = TemporaryFolder()

    /** What the fake jar bundles for Linux, and the SHA-256 its manifest gives. */
    private val library = "a library's bytes".toByteArray()
    private val sha256 = NativeLibraryLoader.sha256(library.inputStream(), null)

    /** Every path handed to the fake System.load. */
    private val loaded: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Test
    fun extractsIntoTheCacheUnderItsHashAndLoadsItFromThere() {
        val root = folder("cache")

        val result = loader(CacheLocation(root)).load()

        assertEquals(root.resolve(sha256).resolve("libink.so"), result.path)
        assertContentEquals(library, result.path.readBytes())
        assertEquals(sha256, result.sha256)
        assertEquals(Origin.BUNDLED, result.origin)
        assertEquals(listOf(result.path.toString()), loaded)
        assertEquals(listOf("libink.so"), result.path.parent.listDirectoryEntries().map { it.name })
    }

    @Test
    fun reusesAVerifiedLibraryWithoutReadingTheJarAgain() {
        val root = folder("cache")
        val first = loader(CacheLocation(root)).load()
        val manifestOnly = NativeBundle { name -> if (name.endsWith(NativeBundle.MANIFEST)) manifest() else error("read $name again") }

        val second = loader(CacheLocation(root), bundle = manifestOnly).load()

        assertEquals(first.path, second.path)
    }

    @Test
    fun replacesADamagedLibrary() {
        val root = folder("cache")
        val damaged = root.resolve(sha256).resolve("libink.so")
        Files.createDirectories(damaged.parent)
        damaged.writeBytes("half a library".toByteArray())

        loader(CacheLocation(root)).load()

        assertContentEquals(library, damaged.readBytes())
    }

    @Test
    fun fallsBackWhenTheCacheCannotBeCreated() {
        val notADirectory = temporary.newFile("cache").toPath()
        val fallback = folder("fallback")

        val result = loader(CacheLocation(notADirectory), CacheLocation(fallback)).load()

        assertTrue(result.path.startsWith(fallback))
        assertEquals(listOf(result.path.toString()), loaded)
    }

    @Test
    fun fallsBackWhenTheCacheIsReadOnly() {
        val root = folder("cache")
        readOnly(root) {
            val fallback = folder("fallback")

            val result = loader(CacheLocation(root), CacheLocation(fallback)).load()

            assertTrue(result.path.startsWith(fallback))
        }
    }

    @Test
    fun usesAReadOnlyCacheThatAlreadyHoldsTheLibrary() {
        val root = folder("cache")
        val first = loader(CacheLocation(root)).load()
        readOnly(root, first.path.parent) {
            val second = loader(CacheLocation(root)).load()

            assertEquals(first.path, second.path)
        }
    }

    @Test
    fun triesTheNextLocationWhenTheSystemCannotLoadFromOne() {
        val noexec = folder("noexec")
        val fallback = folder("fallback")

        val result = loader(CacheLocation(noexec), CacheLocation(fallback)) { path ->
            if (path.startsWith(noexec.toString())) throw UnsatisfiedLinkError("failed to map segment from shared object")
            loaded += path
        }.load()

        assertTrue(result.path.startsWith(fallback))
    }

    @Test
    fun saysWhatItTriedWhenNoLocationWorks() {
        val notADirectory = temporary.newFile("cache").toPath()
        val noexec = folder("noexec")

        val failure = assertFailsWith<UnsatisfiedLinkError> {
            loader(CacheLocation(notADirectory), CacheLocation(noexec)) { throw UnsatisfiedLinkError("noexec") }.load()
        }

        val message = failure.message.orEmpty()
        assertContains(message, "$notADirectory: ")
        assertContains(message, noexec.toString())
        assertContains(message, "-D${InkNativeLibrary.CACHE_PROPERTY}=")
        assertContains(message, "-D${InkNativeLibrary.LIBRARY_PROPERTY}=")
        assertEquals(2, failure.suppressed.size)
    }

    @Test
    fun aDamagedJarFailsWithoutTryingElsewhere() {
        val first = folder("first")
        val second = folder("second")
        val damaged = bundle(bytes = "not what the manifest says".toByteArray())

        val failure = assertFailsWith<UnsatisfiedLinkError> {
            loader(CacheLocation(first), CacheLocation(second), bundle = damaged).load()
        }

        assertContains(failure.message.orEmpty(), "damaged")
        assertEquals(emptyList(), first.resolve(sha256).listDirectoryEntries(), "no partial file is left behind")
        assertEquals(emptyList(), second.listDirectoryEntries())
        assertEquals(emptyList(), loaded)
    }

    @Test
    fun refusesOtherPlatformsWithAdvice() {
        val failure = assertFailsWith<UnsatisfiedLinkError> { loader(platform = null).load() }

        val message = failure.message.orEmpty()
        assertContains(message, "Linux x86_64 and Windows x86_64")
        assertContains(message, "Mac OS X aarch64")
        assertContains(message, "-D${InkNativeLibrary.LIBRARY_PROPERTY}=")
    }

    @Test
    fun aJarWithoutThisPlatformsLibrarySaysSo() {
        val failure = assertFailsWith<UnsatisfiedLinkError> {
            loader(CacheLocation(folder("cache")), bundle = bundle(manifest = "")).load()
        }

        assertContains(failure.message.orEmpty(), "no Ink native library for linux-x86_64")
    }

    @Test
    fun thePropertyLoadsTheNamedFileAsItIs() {
        val file = temporary.newFile("custom.so").toPath().also { it.writeBytes(library) }
        val root = folder("cache")

        val result = loader(CacheLocation(root), property = file.toString()).load()

        assertEquals(file, result.path)
        assertEquals(Origin.PROPERTY, result.origin)
        assertEquals(sha256, result.sha256)
        assertEquals(listOf(file.toString()), loaded)
        assertEquals(emptyList(), root.listDirectoryEntries(), "nothing is extracted")
    }

    @Test
    fun thePropertyWorksWhereNothingIsBundled() {
        val file = temporary.newFile("libink.dylib").toPath().also { it.writeBytes(library) }

        val result = loader(platform = null, property = file.toString()).load()

        assertEquals(file, result.path)
    }

    @Test
    fun thePropertyMustNameAnExistingFileByAbsolutePath() {
        for (name in listOf("libink.so", temporary.root.resolve("missing.so").path, "")) {
            val failure = assertFailsWith<UnsatisfiedLinkError>(name) { loader(property = name).load() }
            assertContains(failure.message.orEmpty(), "-D${InkNativeLibrary.LIBRARY_PROPERTY} must name an existing file")
        }
    }

    @Test
    fun aNamedFileThatWillNotLoadSaysWhoNamedIt() {
        val file = temporary.newFile("wrong-architecture.so").toPath()
        val refused = UnsatisfiedLinkError("wrong ELF class")

        val failure = assertFailsWith<UnsatisfiedLinkError> { loader(property = file.toString()) { throw refused }.load() }

        assertContains(failure.message.orEmpty(), "named by -D${InkNativeLibrary.LIBRARY_PROPERTY}: wrong ELF class")
        assertEquals(refused, failure.cause)
    }

    @Test
    fun failedWindowsLoadsExplainPathsOutsideTheNativeCodePage() {
        val file = temporary.newFile("日本語.dll").toPath()
        val previous = System.getProperty("sun.jnu.encoding")
        System.setProperty("sun.jnu.encoding", "Cp1252")
        try {
            val failure = assertFailsWith<UnsatisfiedLinkError> {
                loader(platform = Platform.WINDOWS_X86_64, property = file.toString()) {
                    throw UnsatisfiedLinkError("Can't find dependent libraries")
                }.load()
            }
            assertContains(failure.message.orEmpty(), "native path encoding (Cp1252)")
            assertContains(failure.message.orEmpty(), "-D${InkNativeLibrary.CACHE_PROPERTY}")
            assertContains(failure.message.orEmpty(), "such as JBR")
        } finally {
            if (previous == null) System.clearProperty("sun.jnu.encoding") else System.setProperty("sun.jnu.encoding", previous)
        }
    }

    @Test
    fun concurrentFirstLoadsShareOneVerifiedFile() {
        val root = folder("cache")
        val threads = 16
        val start = CyclicBarrier(threads)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            // Separate loaders, as separate processes have.
            val paths = (1..threads)
                .map { pool.submit(Callable { start.await(); loader(CacheLocation(root)).load().path }) }
                .map { it.get() }

            assertEquals(setOf(root.resolve(sha256).resolve("libink.so")), paths.toSet())
            assertContentEquals(library, paths.first().readBytes())
            assertEquals(listOf("libink.so"), paths.first().parent.listDirectoryEntries().map { it.name })
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun theTemporaryFallbackIsNewAndPrivateEachTime() {
        val first = NativeLibraryLoader.TemporaryLocation.directoryFor(sha256)
        val second = NativeLibraryLoader.TemporaryLocation.directoryFor(sha256)

        assertTrue(first != second)
        assertEquals(emptyList(), first.listDirectoryEntries())
        if (posix()) assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(first)))
        Files.delete(first)
        Files.delete(second)
    }

    @Test
    fun theUserCacheFollowsEachPlatformsConventions() {
        val home = temporary.root.path
        fun cache(platform: Platform, vararg environment: Pair<String, String>): Path =
            InkNativeLibrary.userCache(platform, environment.toMap()::get, home)

        val xdg = temporary.root.resolve("xdg").path
        assertEquals(Paths.get(xdg, "byteink", "natives"), cache(Platform.LINUX_X86_64, "XDG_CACHE_HOME" to xdg))
        for (unusable in listOf("", "relative/cache")) {
            assertEquals(Paths.get(home, ".cache", "byteink", "natives"), cache(Platform.LINUX_X86_64, "XDG_CACHE_HOME" to unusable))
        }
        assertEquals(Paths.get(home, ".cache", "byteink", "natives"), cache(Platform.LINUX_X86_64))

        val local = temporary.root.resolve("local").path
        assertEquals(Paths.get(local, "byteink", "natives"), cache(Platform.WINDOWS_X86_64, "LOCALAPPDATA" to local))
        assertEquals(Paths.get(home, "AppData", "Local", "byteink", "natives"), cache(Platform.WINDOWS_X86_64))
    }

    @Test
    fun mapsTheTwoBundledPlatformsAndNothingElse() {
        assertEquals(Platform.LINUX_X86_64, Platform.of("Linux", "amd64"))
        assertEquals(Platform.LINUX_X86_64, Platform.of("Linux", "x86_64"))
        assertEquals(Platform.WINDOWS_X86_64, Platform.of("Windows 11", "amd64"))
        assertEquals(Platform.WINDOWS_X86_64, Platform.of("Windows Server 2025", "AMD64"))
        for ((os, arch) in listOf("Linux" to "aarch64", "Windows 11" to "aarch64", "Mac OS X" to "aarch64", "FreeBSD" to "amd64")) {
            assertEquals(null, Platform.of(os, arch), "$os $arch")
        }
    }

    @Test
    fun theLoadedLibraryDescribesItself() {
        val result = loader(CacheLocation(folder("cache"))).load()

        assertIs<LoadedInkLibrary>(result)
        assertEquals("${result.path} (bundled, SHA-256 $sha256)", result.toString())
    }

    private fun loader(
        vararg locations: Location,
        platform: Platform? = Platform.LINUX_X86_64,
        property: String? = null,
        bundle: NativeBundle = bundle(),
        systemLoad: (String) -> Unit = { loaded += it },
    ) = NativeLibraryLoader(
        platform = platform,
        platformName = if (platform == null) "Mac OS X aarch64" else "Linux amd64",
        libraryProperty = property,
        locations = locations.toList(),
        bundle = bundle,
        systemLoad = systemLoad,
    )

    private fun manifest(text: String = "linux-x86_64.sha256=$sha256\n") = text.byteInputStream()

    private fun bundle(bytes: ByteArray = library, manifest: String = "linux-x86_64.sha256=$sha256\n") = NativeBundle { name ->
        when (name) {
            "${NativeBundle.BASE}/${NativeBundle.MANIFEST}" -> manifest(manifest)
            "${NativeBundle.BASE}/linux-x86_64/libink.so" -> bytes.inputStream()
            else -> null
        }
    }

    private fun folder(name: String): Path = temporary.newFolder(name).toPath()

    private fun posix() = "posix" in FileSystems.getDefault().supportedFileAttributeViews()

    /** Runs [block] with [directories] read-only, where the platform and user make that possible. */
    private fun readOnly(vararg directories: Path, block: () -> Unit) {
        assumeTrue("POSIX permissions", posix())
        val writable = PosixFilePermissions.fromString("rwxr-xr-x")
        directories.forEach { Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("r-xr-xr-x")) }
        try {
            assumeTrue("permissions bind this user", directories.none(Files::isWritable))
            block()
        } finally {
            directories.forEach { Files.setPosixFilePermissions(it, writable) }
        }
    }
}
