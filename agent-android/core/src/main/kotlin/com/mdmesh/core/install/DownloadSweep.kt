package com.mdmesh.core.install

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Removes APK downloads a dead process left behind. An install deletes its own download when it ends, but a process
 * death mid-download or mid-commit (an OEM task killer, an agent self-update) leaves a full-size APK in the cache.
 *
 * Each download's name carries the id of the process that made it. A file of another process (or from an agent that
 * named files without it) is a dead process's, since the agent runs in one process: it goes at once, however fresh,
 * so the sweep at the next start is enough. A file of this process goes only when no install is using it AND it
 * hasn't been written for [MIN_AGE_MS], longer than any download or commit takes, so a file in use is never touched
 * even if the bookkeeping were wrong. (A reused process id only makes a leftover wait for that age rule.)
 */
internal object DownloadSweep {
    const val PREFIX = "mdm-install-"
    const val SUFFIX = ".apk"
    const val MIN_AGE_MS = 60 * 60_000L

    fun name(owner: Int, id: Long): String = "$PREFIX$owner-${java.lang.Long.toUnsignedString(id)}$SUFFIX"

    /** The process id in a download's name; null for a name from before names carried it. */
    fun ownerOf(name: String): Int? =
        name.removePrefix(PREFIX).removeSuffix(SUFFIX).split('-')
            .takeIf { it.size == 2 }
            ?.first()?.toIntOrNull()

    /** Returns the files it deleted. */
    fun sweep(dir: File, inUse: Set<File>, now: Long, owner: Int): List<File> {
        val stale = dir.listFiles { f ->
            f.isFile && f.name.startsWith(PREFIX) && f.name.endsWith(SUFFIX) && f !in inUse &&
                (ownerOf(f.name) != owner || now - f.lastModified() >= MIN_AGE_MS)
        }.orEmpty()
        return stale.filter { it.delete() }
    }
}

/**
 * The APK downloads of this process: names each one, protects it from the sweep from before its first byte until
 * [release], and sweeps what a dead process left whenever a new download starts.
 */
internal class DownloadCache(
    private val dir: () -> File,
    /** This process's id, which download names carry. */
    private val owner: Int,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val inUse: MutableSet<File> = ConcurrentHashMap.newKeySet()

    /** Blocking (it lists and deletes files): call it off the main thread. */
    fun newFile(): File {
        sweep()
        return File(dir(), DownloadSweep.name(owner, System.nanoTime())).also { inUse.add(it) }
    }

    fun release(file: File) {
        file.delete()
        inUse.remove(file)
    }

    /** Returns the leftovers it deleted. */
    fun sweep(): List<File> = DownloadSweep.sweep(dir(), inUse.toSet(), now(), owner)
}
