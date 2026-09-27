package com.vivenotes.byteink.upstream

import java.io.File

/** A commit on google/ink's first-parent history, with the JNI functions its glue defines. */
internal data class ScannedCommit(val id: String, val date: String, val symbols: Set<String>) {
    val label: String get() = "`${id.take(12)}` ${date.take(10)}"
}

/** A reference JNI surface, such as the exports of Google's binary for one AndroidX release. */
internal data class Reference(val label: String, val symbols: Set<String>)

/** How a commit's functions compare with a reference: [missing] of its functions, [extra] others. */
internal data class Match(val missing: Int, val extra: Int) {
    val exact: Boolean get() = missing == 0 && extra == 0

    override fun toString(): String = if (exact) "exact" else "-$missing +$extra"

    companion object {
        fun of(commit: ScannedCommit, reference: Reference): Match = Match(
            missing = reference.symbols.count { it !in commit.symbols },
            extra = commit.symbols.count { it !in reference.symbols },
        )
    }
}

/** Reads the JNI functions defined along google/ink's history, without touching a work tree. */
internal class HistoryScan(private val git: Git) {

    /** Every commit on the first-parent history of [revision], oldest first. */
    fun scan(revision: String): List<ScannedCommit> {
        val symbolsByBlob = HashMap<String, Set<String>>()
        return git.blobReader().use { blobs ->
            firstParentHistory(revision).map { (id, date) ->
                val symbols = jniSources(id).flatMapTo(sortedSetOf()) { blob ->
                    symbolsByBlob.getOrPut(blob) { GoogleInkJni.symbols(blobs.read(blob).decodeToString()) }
                }
                ScannedCommit(id, date, symbols)
            }
        }
    }

    /** The JNI functions defined at [revision]. */
    fun symbolsAt(revision: String): Set<String> {
        val commit = git.run("rev-parse", "--verify", "$revision^{commit}").trim()
        return git.blobReader().use { blobs ->
            jniSources(commit).flatMapTo(sortedSetOf()) { GoogleInkJni.symbols(blobs.read(it).decodeToString()) }
        }
    }

    private fun firstParentHistory(revision: String): List<Pair<String, String>> =
        git.run("log", "--no-show-signature", "--first-parent", "--reverse", "--format=%H %cI", revision)
            .lineSequence()
            .filter(String::isNotBlank)
            .map { line -> line.substringBefore(' ') to line.substringAfter(' ') }
            .toList()

    private fun jniSources(commit: String): List<String> =
        git.run("ls-tree", "-r", "-z", commit, "--", "ink").split('\u0000')
            .filter(String::isNotEmpty)
            .mapNotNull { entry ->
                // "<mode> <type> <object>\t<path>"
                val (_, type, objectId) = entry.substringBefore('\t').split(' ')
                objectId.takeIf { type == "blob" && GoogleInkJni.isJniSource(entry.substringAfter('\t')) }
            }
}

/** A history scan laid out for people: exact-match windows, the pins, and every change on the way. */
internal class ScanReport(
    private val commits: List<ScannedCommit>,
    private val references: List<Reference>,
) {
    private class Run(val first: ScannedCommit, val last: ScannedCommit, val size: Int)

    /** Maximal runs of consecutive commits that define exactly [reference]'s functions. */
    private fun exactRuns(reference: Reference): List<Run> =
        runs(commits) { if (Match.of(it, reference).exact) true else null }

    /** Maximal runs of consecutive commits with the same function count and matches. */
    private fun changes(): List<Run> =
        runs(commits) { commit -> commit.symbols.size to references.map { Match.of(commit, it) } }

    private fun <K : Any> runs(commits: List<ScannedCommit>, key: (ScannedCommit) -> K?): List<Run> {
        val runs = mutableListOf<Run>()
        var start = 0
        while (start < commits.size) {
            val runKey = key(commits[start])
            var end = start
            while (end + 1 < commits.size && key(commits[end + 1]) == runKey) end++
            if (runKey != null) runs += Run(commits[start], commits[end], end - start + 1)
            start = end + 1
        }
        return runs
    }

    fun exactMatches(): String = buildString {
        references.forEach { reference ->
            append("- **${reference.label}** (${reference.symbols.size} functions): ")
            val runs = exactRuns(reference)
            if (runs.isEmpty()) {
                append("no commit matches exactly\n")
            } else {
                append(runs.joinToString("; ") { "${it.first.label} … ${it.last.label} (${it.size} commits)" })
                append('\n')
            }
        }
    }

    fun markdown(revision: String, pins: Pins, pinned: Reference): String = buildString {
        append("# google/ink JNI surface scan\n\n")
        if (commits.isNotEmpty()) {
            append("First-parent history of `$revision`: ${commits.size} commits, ")
            append("${commits.first().date.take(10)} … ${commits.last().date.take(10)}.\n")
        }
        append(
            """
            |Each reference lists the `Java_*` functions a Google-built `libink.so` exports. Each commit's
            |`JNI_METHOD` definitions are compared with it: `exact`, or `-m +e` when the commit lacks m of the
            |reference's functions and defines e that it does not export.
            |
            |## Exact matches
            |
            |
            """.trimMargin(),
        )
        append(exactMatches())
        append("\n## Pins (`upstream/pins.properties`) against ${pinned.label}\n\n")
        append("| Commit | Role | ${pinned.label} |\n|---|---|---|\n")
        val byId = commits.associateBy(ScannedCommit::id)
        pins.googleInkCandidates.forEach { id ->
            val role = if (id == pins.googleInkCommit) "google.ink.commit" else "candidate"
            val commit = byId[id]
            val match = commit?.let { Match.of(it, pinned).toString() } ?: "not on this history"
            append("| ${commit?.label ?: "`${id.take(12)}`"} | $role | $match |\n")
        }
        append("\n## History\n\n")
        append("| From | To | Commits | Functions | ${references.joinToString(" | ") { it.label }} |\n")
        append("|---|---|---:|---:|${references.joinToString("|") { "---" }}|\n")
        changes().forEach { run ->
            append("| ${run.first.label} | ${run.last.label} | ${run.size} | ${run.first.symbols.size} | ")
            append(references.joinToString(" | ") { Match.of(run.first, it).toString() })
            append(" |\n")
        }
    }
}

/**
 * `scan`: reports which google/ink commits have exactly the JNI surface of each reference, and fails
 * unless the pinned commit and every candidate in the pins file match the pinned reference.
 */
internal fun scanCommand(arguments: Arguments): Int {
    val repository = File(arguments.required("--repo")).absoluteFile
    val pins = Pins.read(File(arguments.required("--pins")))
    val references = arguments.all("--reference").map { spec ->
        val label = spec.substringBefore('=', missingDelimiterValue = "")
        if (label.isEmpty()) throw UsageError("--reference expects LABEL=FILE, got $spec")
        Reference(label, SymbolList.read(File(spec.substringAfter('='))))
    }
    if (references.isEmpty()) throw UsageError("--reference is required")
    val pinnedLabel = arguments.required("--pinned")
    val pinned = references.singleOrNull { it.label == pinnedLabel }
        ?: throw UsageError("--pinned $pinnedLabel names no --reference")
    val revision = arguments.optional("--rev") ?: "origin/main"

    if (!repository.exists()) {
        println("Cloning ${pins.googleInkRepository} into $repository")
        repository.parentFile.mkdirs()
        Git(repository.parentFile).run("clone", "--quiet", "--no-checkout", pins.googleInkRepository, repository.path)
    } else if (!arguments.flag("--offline")) {
        Git(repository).run("fetch", "--quiet", "origin")
    }

    val commits = HistoryScan(Git(repository)).scan(revision)
    val report = ScanReport(commits, references)
    arguments.optional("--report")?.let { path ->
        File(path).apply { parentFile?.mkdirs() }.writeText(report.markdown(revision, pins, pinned))
        println("Report: $path")
    }
    print(report.exactMatches())

    val byId = commits.associateBy(ScannedCommit::id)
    val problems = pins.googleInkCandidates.mapNotNull { id ->
        val commit = byId[id] ?: return@mapNotNull "${id.take(12)} is not on the first-parent history of $revision"
        val match = Match.of(commit, pinned)
        if (match.exact) null else "${commit.label} does not match $pinnedLabel: $match"
    }
    if (problems.isNotEmpty()) {
        throw CheckFailed("The native pin does not hold:\n" + problems.joinToString("\n") { "  $it" })
    }
    println(
        "google.ink.commit ${pins.googleInkCommit.take(12)} and all ${pins.googleInkCandidates.size} " +
            "candidates define exactly the JNI surface of $pinnedLabel.",
    )
    return 0
}
