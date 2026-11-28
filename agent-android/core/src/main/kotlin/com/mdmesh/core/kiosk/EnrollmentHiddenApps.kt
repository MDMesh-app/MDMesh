package com.mdmesh.core.kiosk

/**
 * System apps an enrollment QR asks the agent to hide: admin extra [EXTRA], package names separated by commas.
 *
 * Hidden in `ADMIN_POLICY_COMPLIANCE`, before the setup wizard goes on to the pages they add. An OEM's setup
 * extension (on a Lenovo tablet, `com.tblenovo.setup`: navigation mode and a "setup complete" page) or a post-setup
 * app promoter then never shows, on a device nobody should have to tap through. The wizard finishes on its own and
 * records setup as complete, so nothing comes back after a reboot ([UserSetup]).
 *
 * Hidden, not uninstalled: `DevicePolicyManager.setApplicationHidden(admin, pkg, false)` brings one back. Which apps
 * are safe to hide is the QR author's call; only the agent itself is never hidden.
 */
object EnrollmentHiddenApps {
    /** The admin-extras key: comma-separated package names, since a QR's extras bundle carries no arrays. */
    const val EXTRA = "com.mdmesh.HIDE_PACKAGES"

    private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

    /** The package names in [extra], in order, without blanks, duplicates, malformed names or [ownPackage]. */
    fun parse(extra: String?, ownPackage: String): List<String> =
        extra.orEmpty().split(',').map(String::trim)
            .filter { it.matches(PACKAGE_NAME) && it != ownPackage }
            .distinct()

    /**
     * Hides each of [packages] with [hide], which returns whether the package is hidden afterwards.
     * @return those not hidden: not installed, refused, or [hide] threw (e.g. not yet Device Owner).
     */
    fun hideAll(packages: List<String>, hide: (String) -> Boolean): List<String> =
        packages.filterNot { runCatching { hide(it) }.getOrDefault(false) }
}
