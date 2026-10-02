package com.mdmesh.core.install

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import com.mdmesh.core.config.AppRestrictions
import dagger.hilt.android.qualifiers.ApplicationContext
import com.mdmesh.core.di.DownloadHttpClient
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** One APK file of an install — a whole app, or a single split of a bundle. */
data class ApkPart(
    /** Remote APK URL; mutually exclusive-ish with [localPath] (URL preferred). */
    val url: String? = null,
    /** Absolute path to an already-present APK on the device. */
    val localPath: String? = null,
    /** Optional lowercase hex SHA-256 of this part; verified after fetch if present. */
    val sha256: String? = null,
)

/** What the caller wants installed. */
data class InstallRequest(
    /** Remote APK URL; mutually exclusive-ish with [localPath] (URL preferred). */
    val url: String? = null,
    /** Absolute path to an already-present APK on the device. */
    val localPath: String? = null,
    /** Target package name; required for [PackageInstaller.SessionParams.setAppPackageName]. */
    val packageName: String,
    /** Requested version code; `null`/`0` means "any" (see [VersionPolicy]). */
    val versionCode: Long? = null,
    /** Optional lowercase hex SHA-256 of the APK; verified after download if present. */
    val sha256: String? = null,
    /** When true and the install succeeds, launch the app's main activity. */
    val runAfterInstall: Boolean = false,
    /**
     * Split-APK bundle parts (base + config/feature splits). When non-empty this is a multi-APK
     * install and [url]/[localPath]/[sha256] are ignored; every part is written into ONE
     * PackageInstaller session (the only correct way to install splits). Empty = single-APK install.
     */
    val parts: List<ApkPart> = emptyList(),
) {
    /** Normalized part list: the explicit [parts], or the single [url]/[localPath] as one part. */
    val apkParts: List<ApkPart>
        get() = if (parts.isNotEmpty()) parts else listOf(ApkPart(url, localPath, sha256))
}

/** Result of an install/uninstall attempt. */
sealed interface InstallOutcome {
    /** The package is installed at the requested version (or was just installed/removed). */
    data object Success : InstallOutcome

    /** No action needed (e.g. already at the requested version); [reason] explains. */
    data class Skipped(val reason: String) : InstallOutcome

    /** The attempt failed. [status] is a `PackageInstaller.STATUS_*` when one was reported. */
    data class Failure(val status: Int?, val reason: String) : InstallOutcome
}

/**
 * Silent (Device-Owner / privileged) app install + uninstall via [PackageInstaller].
 *
 * Modernized port of Headwind's `InstallUtils` serialized pump:
 *  - OkHttp download to [Context.getCacheDir] instead of `HttpURLConnection`.
 *  - One `suspend` call that commits a session and awaits its result through
 *    [InstallResultBus] (manifest receiver), instead of `AsyncTask` recursion.
 *  - The commit `PendingIntent` is **explicit** (action [InstallResultBus.ACTION],
 *    `setPackage(ctx.packageName)`), `FLAG_IMMUTABLE`, with `requestCode = sessionId`
 *    so each session's broadcast is uniquely addressable.
 *  - `setInstallReason(INSTALL_REASON_POLICY)` and, on API 31+,
 *    `setRequireUserAction(USER_ACTION_NOT_REQUIRED)`.
 *
 * Version gating is delegated to the pure [VersionPolicy]; downgrades are blocked so the
 * download/install loop can't churn forever (the caller must uninstall first).
 *
 * @see InstallResultBus for the action/extra constants the `:app` receiver consumes.
 */
@Singleton
class InstallManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val resultBus: InstallResultBus,
    @DownloadHttpClient private val httpClient: OkHttpClient,
    private val appRestrictions: AppRestrictions,
) {

    private val packageInstaller: PackageInstaller
        get() = context.packageManager.packageInstaller

    /**
     * Downloads (or reads) the APK, applies the version policy, then performs a silent
     * install and awaits the platform's result. Returns the mapped [InstallOutcome].
     */
    suspend fun install(req: InstallRequest): InstallOutcome {
        // 1. Version gate (pure) — skip / block before touching the network.
        when (val decision = VersionPolicy.shouldInstall(installedVersionCode(req.packageName), req.versionCode)) {
            VersionPolicy.Decision.Install -> Unit
            is VersionPolicy.Decision.Skip -> return InstallOutcome.Skipped(decision.reason)
            VersionPolicy.Decision.DowngradeBlocked ->
                return InstallOutcome.Failure(
                    status = null,
                    reason = "downgrade blocked for ${req.packageName}: uninstall the current version first",
                )
        }

        // 2. Obtain every part (single APK, or base + splits of a bundle), verifying each
        // part's optional checksum. All parts install together in one session (step 3).
        val parts = req.apkParts
        val fetched = ArrayList<FetchedApk>(parts.size)
        try {
            for (part in parts) {
                val file = runCatching { obtainPart(part) }
                    .getOrElse { return InstallOutcome.Failure(null, "apk fetch failed: ${it.message}") }
                fetched += FetchedApk(file, downloaded = part.url != null)
                part.sha256?.let { expected ->
                    val actual = sha256Of(file)
                    if (!actual.equals(expected, ignoreCase = true)) {
                        return InstallOutcome.Failure(null, "checksum mismatch: expected $expected got $actual")
                    }
                }
            }

            // 3. Create + write EVERY part into ONE session + commit, awaiting the broadcast result.
            val outcome = runCatching {
                appRestrictions.managedInstall(req.packageName) {
                    withTimeout(INSTALL_TIMEOUT_MS) { commitInstall(req.packageName, fetched.map { it.file }) }
                }
            }
                .getOrElse { return InstallOutcome.Failure(null, "install session error: ${it.message}") }

            // 4. Optionally launch the app on success.
            if (outcome is InstallOutcome.Success && req.runAfterInstall) {
                launchApp(req.packageName)
            }
            return outcome
        } finally {
            // Only clean up files we downloaded; never delete a caller-provided APK.
            fetched.forEach { if (it.downloaded) it.file.delete() }
        }
    }

    /** A fetched part plus whether we downloaded it (and therefore own its cleanup). */
    private data class FetchedApk(val file: File, val downloaded: Boolean)

    /** Silently uninstalls [packageName], awaiting the platform's result. */
    @SuppressLint("MissingPermission") // Device Owner holds DELETE_PACKAGES (declared in the app)
    suspend fun uninstall(packageName: String): InstallOutcome {
        if (installedVersionCode(packageName) == null) {
            return InstallOutcome.Skipped("not installed: $packageName")
        }
        // Reuse a stable session id derived from the package so the PendingIntent is unique.
        val sessionId = -(packageName.hashCode() and 0x7fff_ffff) - 1
        return try {
            appRestrictions.managedRemoval(packageName) {
                withTimeout(INSTALL_TIMEOUT_MS) {
                    packageInstaller.uninstall(packageName, resultSender(sessionId).intentSender)
                    mapResult(resultBus.await(sessionId))
                }
            }
        } catch (e: Exception) {
            InstallOutcome.Failure(null, "uninstall error: ${e.message}")
        }
    }

    // --- internals -----------------------------------------------------------------

    /**
     * Writes ALL [apks] into a single install session and commits once. One apk installs a whole
     * app; several install a split set (base + config/feature splits) atomically — the platform
     * validates on commit that they share package, version, and signing cert.
     */
    private suspend fun commitInstall(packageName: String, apks: List<File>): InstallOutcome =
        withContext(Dispatchers.IO) {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(packageName)
                setSize(apks.sumOf { it.length() })
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setInstallReason(PackageManager.INSTALL_REASON_POLICY) // API 26+ (advisory metadata)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
            }

            val sessionId = packageInstaller.createSession(params)
            packageInstaller.openSession(sessionId).use { session ->
                // Each split needs a distinct name within the session; the platform reads the real
                // split identity from each APK's manifest, so index-based names are fine.
                apks.forEachIndexed { i, apk ->
                    FileInputStream(apk).use { input ->
                        session.openWrite("part_$i.apk", 0, apk.length()).use { output ->
                            input.copyTo(output, bufferSize = 64 * 1024)
                            session.fsync(output)
                        }
                    }
                }
                session.commit(resultSender(sessionId).intentSender)
            }

            mapResult(resultBus.await(sessionId))
        }

    /**
     * Builds the explicit, immutable [PendingIntent] for a session's result broadcast.
     * The intent action is [InstallResultBus.ACTION], scoped to our own package, and
     * carries the session id so the (single) manifest receiver can correlate it.
     */
    private fun resultSender(sessionId: Int): PendingIntent {
        val intent = Intent(InstallResultBus.ACTION)
            .setPackage(context.packageName)
            .putExtra(InstallResultBus.EXTRA_SESSION_ID, sessionId)
        // MUST be MUTABLE: PackageInstaller.commit() fills the result extras (EXTRA_STATUS...)
        // into this PendingIntent. FLAG_IMMUTABLE makes commit() throw
        // "the status receiver should come from a mutable PendingIntent". The intent is
        // explicit (setPackage), so a mutable PendingIntent is not the unsafe-implicit case.
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = flags or PendingIntent.FLAG_MUTABLE
        }
        return PendingIntent.getBroadcast(context, sessionId, intent, flags)
    }

    private fun mapResult(event: InstallResultBus.InstallResultEvent): InstallOutcome =
        when (event.status) {
            PackageInstaller.STATUS_SUCCESS -> InstallOutcome.Success
            PackageInstaller.STATUS_PENDING_USER_ACTION ->
                // A true Device Owner should never get this; surface it clearly rather
                // than launch the confirm dialog (which the explicit/immutable PendingIntent
                // and Intent-Redirection mitigation deliberately avoid).
                InstallOutcome.Failure(
                    event.status,
                    "unexpected STATUS_PENDING_USER_ACTION — silent install requires Device Owner / privilege",
                )
            else -> InstallOutcome.Failure(event.status, event.message ?: statusName(event.status))
        }

    private suspend fun obtainPart(part: ApkPart): File {
        part.localPath?.let { return File(it) }
        val url = requireNotNull(part.url) { "install part needs either url or localPath" }
        return withContext(Dispatchers.IO) { download(url) }
    }

    private fun download(url: String): File {
        val dest = File(context.cacheDir, "mdm-install-${System.nanoTime()}.apk")
        httpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) {
                dest.delete()
                error("HTTP ${response.code} for $url")
            }
            val body = response.body ?: run { dest.delete(); error("empty response body for $url") }
            body.byteStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
        }
        return dest
    }

    private fun installedVersionCode(packageName: String): Long? = runCatching {
        @Suppress("DEPRECATION")
        val info: PackageInfo = context.packageManager.getPackageInfo(packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }
    }.getOrNull()

    private fun launchApp(packageName: String) {
        context.packageManager.getLaunchIntentForPackage(packageName)?.let { intent ->
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { stream: InputStream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object { const val INSTALL_TIMEOUT_MS = 5 * 60 * 1000L }

    private fun statusName(status: Int): String = when (status) {
        PackageInstaller.STATUS_FAILURE -> "FAILURE_UNKNOWN"
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "FAILURE_BLOCKED"
        PackageInstaller.STATUS_FAILURE_ABORTED -> "FAILURE_ABORTED"
        PackageInstaller.STATUS_FAILURE_INVALID -> "FAILURE_INVALID"
        PackageInstaller.STATUS_FAILURE_CONFLICT -> "FAILURE_CONFLICT"
        PackageInstaller.STATUS_FAILURE_STORAGE -> "FAILURE_STORAGE"
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "FAILURE_INCOMPATIBLE"
        else -> "STATUS_$status"
    }
}
