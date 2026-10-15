package com.mdmesh.core.install

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A silent Device-Owner install must name the exact bytes it installs: [InstallRequest.integrityProblem]
 * refuses any request with a part that lacks a well-formed hex SHA-256, before anything is fetched.
 */
class InstallIntegrityTest {

    private val hex = "a".repeat(64)

    @Test
    fun `single apk with sha256 passes`() {
        assertNull(InstallRequest(url = "http://lan/app.apk", packageName = "com.x", sha256 = hex).integrityProblem)
    }

    @Test
    fun `uppercase hex is accepted`() {
        val upper = "AB".repeat(32)
        assertNull(InstallRequest(url = "http://lan/app.apk", packageName = "com.x", sha256 = upper).integrityProblem)
    }

    @Test
    fun `single apk without sha256 is refused with a clear detail`() {
        val problem = InstallRequest(url = "https://f-droid.org/repo/x.apk", packageName = "com.x").integrityProblem
        assertNotNull(problem)
        assertTrue(problem!!, problem.contains("sha256"))
        assertTrue(problem, problem.contains("https://f-droid.org/repo/x.apk"))
    }

    @Test
    fun `blank or malformed sha256 is refused`() {
        assertNotNull(InstallRequest(url = "http://h/a.apk", packageName = "com.x", sha256 = " ").integrityProblem)
        // base64 of a digest is not what the agent verifies against
        assertNotNull(
            InstallRequest(
                url = "http://h/a.apk",
                packageName = "com.x",
                sha256 = "ungWv48Bz-pBQUDeXa4iI7ADYaOWF3qctBD_YfIAFa0",
            ).integrityProblem,
        )
        assertNotNull(InstallRequest(url = "http://h/a.apk", packageName = "com.x", sha256 = "ab").integrityProblem)
    }

    @Test
    fun `local path installs need a sha256 too`() {
        assertNotNull(InstallRequest(localPath = "/data/app.apk", packageName = "com.x").integrityProblem)
    }

    @Test
    fun `every part of a bundle needs a sha256`() {
        val req = InstallRequest(
            packageName = "com.x",
            parts = listOf(
                ApkPart(url = "http://h/base.apk", sha256 = hex),
                ApkPart(url = "http://h/split_en.apk"),
            ),
        )
        val problem = req.integrityProblem
        assertNotNull(problem)
        assertTrue(problem!!, problem.contains("http://h/split_en.apk"))
    }

    @Test
    fun `without the server's guarantee an unhashed install proceeds as before`() {
        val policy = InstallIntegrityPolicy()
        assertNull(policy.problem(InstallRequest(url = "http://lan/app.apk", packageName = "com.x")))
        policy.update(null)
        assertNull(policy.problem(InstallRequest(url = "http://lan/app.apk", packageName = "com.x")))
        policy.update("something-else")
        assertNull(policy.problem(InstallRequest(url = "http://lan/app.apk", packageName = "com.x")))
    }

    @Test
    fun `with the server's guarantee an unhashed install is refused`() {
        val policy = InstallIntegrityPolicy()
        policy.update("required")
        assertNotNull(policy.problem(InstallRequest(url = "http://lan/app.apk", packageName = "com.x")))
        assertNull(policy.problem(InstallRequest(url = "http://lan/app.apk", packageName = "com.x", sha256 = hex)))
    }

    @Test
    fun `bundle ignores the top-level sha256`() {
        val req = InstallRequest(
            url = "http://h/ignored.apk",
            packageName = "com.x",
            sha256 = hex,
            parts = listOf(ApkPart(url = "http://h/base.apk")),
        )
        assertNotNull(req.integrityProblem)
        assertNull(req.copy(parts = listOf(ApkPart(url = "http://h/base.apk", sha256 = hex))).integrityProblem)
    }
}
