package com.mdmesh.core.install

import java.io.File

/**
 * Removes APK downloads a dead process left behind. An install deletes its own download when it ends, but a process
 * death mid-download or mid-commit (an OEM task killer, an agent self-update) leaves a full-size APK in the cache.
 *
 * Deletes a file only when no install in this process is using it AND it hasn't been written for [MIN_AGE_MS], longer
 * than any download or commit takes, so a file in use is never touched even if the bookkeeping were wrong.
 */
internal object DownloadSweep {
    const val PREFIX = "mdm-install-"
    const val SUFFIX = ".apk"
    const val MIN_AGE_MS = 60 * 60_000L

    fun name(id: Long): String = "$PREFIX$id$SUFFIX"

    /** Returns the files it deleted. */
    fun sweep(dir: File, inUse: Set<File>, now: Long): List<File> {
        val stale = dir.listFiles { f ->
            f.isFile && f.name.startsWith(PREFIX) && f.name.endsWith(SUFFIX) && f !in inUse &&
                now - f.lastModified() >= MIN_AGE_MS
        }.orEmpty()
        return stale.filter { it.delete() }
    }
}
