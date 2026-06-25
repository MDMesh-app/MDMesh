package com.mdmesh.core.install

import com.mdmesh.core.install.VersionPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionPolicyTest {

    @Test
    fun `any-version request installs when not present`() {
        assertEquals(Decision.Install, VersionPolicy.shouldInstall(installedVersionCode = null, requestedVersionCode = null))
        assertEquals(Decision.Install, VersionPolicy.shouldInstall(installedVersionCode = null, requestedVersionCode = 0L))
    }

    @Test
    fun `any-version request skips when already present`() {
        val d = VersionPolicy.shouldInstall(installedVersionCode = 5L, requestedVersionCode = null)
        assertTrue(d is Decision.Skip)
        val d0 = VersionPolicy.shouldInstall(installedVersionCode = 5L, requestedVersionCode = 0L)
        assertTrue(d0 is Decision.Skip)
    }

    @Test
    fun `installs requested version when not present`() {
        assertEquals(Decision.Install, VersionPolicy.shouldInstall(installedVersionCode = null, requestedVersionCode = 10L))
    }

    @Test
    fun `installs when requested is greater than installed`() {
        assertEquals(Decision.Install, VersionPolicy.shouldInstall(installedVersionCode = 9L, requestedVersionCode = 10L))
    }

    @Test
    fun `skips when requested equals installed`() {
        val d = VersionPolicy.shouldInstall(installedVersionCode = 10L, requestedVersionCode = 10L)
        assertTrue(d is Decision.Skip)
    }

    @Test
    fun `blocks downgrade when requested is less than installed`() {
        assertEquals(
            Decision.DowngradeBlocked,
            VersionPolicy.shouldInstall(installedVersionCode = 11L, requestedVersionCode = 10L),
        )
    }
}
