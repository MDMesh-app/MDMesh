package com.mdmesh.proto

import kotlinx.serialization.Serializable

/**
 * One installed package as reported by the `apps.scan` command. Icons are NOT included here — they
 * are fetched separately and in batches via `apps.icons` (see [AppIconsResult]) so the initial
 * scan stays small.
 *
 * @property pkg the package name (e.g. `com.android.settings`).
 * @property label the user-visible application label.
 * @property system true for a system app (`FLAG_SYSTEM` / `FLAG_UPDATED_SYSTEM_APP`).
 * @property launchable true if the package has a launcher entry point (`getLaunchIntentForPackage`).
 * @property versionName human version string, when available.
 * @property versionCode monotonic version code, when available.
 */
@Serializable
data class AppInfo(
    val pkg: String,
    val label: String,
    val system: Boolean = false,
    val launchable: Boolean = false,
    val versionName: String? = null,
    val versionCode: Long? = null,
)

/** Result payload of `apps.scan` (carried as JSON in the command-result `detail`). */
@Serializable
data class AppScanResult(
    val apps: List<AppInfo> = emptyList(),
)

/** Payload of the `apps.icons` command: the packages whose icons the console wants rendered. */
@Serializable
data class AppIconsRequest(
    val packages: List<String> = emptyList(),
)

/** A single rendered icon: a base64-encoded PNG for [pkg]. */
@Serializable
data class AppIcon(
    val pkg: String,
    val pngBase64: String,
)

/** Result payload of `apps.icons` (carried as JSON in the command-result `detail`). */
@Serializable
data class AppIconsResult(
    val icons: List<AppIcon> = emptyList(),
)
