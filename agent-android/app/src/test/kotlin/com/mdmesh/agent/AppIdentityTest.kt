package com.mdmesh.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The app label is display text only. An installed agent updates in place only while the package, the signing key
 * and the Device Owner component stay the same, and the QR code names that component; none of them may follow the
 * label.
 */
class AppIdentityTest {

    private val root: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
    }

    private fun read(path: String): String = File(root, path).also { assertTrue("missing $it", it.isFile) }.readText()

    @Test fun `the label is MDMesh and comes from app_name`() {
        assertEquals("MDMesh", StringsXml["app_name"])
        assertTrue(read("app/src/main/AndroidManifest.xml").contains("android:label=\"@string/app_name\""))
    }

    @Test fun `applicationId and namespace are unchanged`() {
        val gradle = read("app/build.gradle.kts")
        assertTrue(gradle.contains("applicationId = \"com.mdmesh.agent\""))
        assertTrue(gradle.contains("namespace = \"com.mdmesh.agent\""))
    }

    @Test fun `the Device Owner component keeps its class name`() {
        assertTrue(read("app/src/main/AndroidManifest.xml").contains("android:name=\".admin.AdminReceiver\""))
        val receiver = read("app/src/main/kotlin/com/mdmesh/agent/admin/AdminReceiver.kt")
        assertTrue(receiver.contains("package com.mdmesh.agent.admin"))
        assertTrue(receiver.contains("class AdminReceiver"))
    }

    @Test fun `the enrollment QR names the same component`() {
        val qr = File(root.parentFile, "web/src/enroll/provisioning.ts")
        assumeTrue("console sources not checked out", qr.isFile)
        assertTrue(qr.readText().contains("/com.mdmesh.agent.admin.AdminReceiver`"))
    }

    @Test fun `notification channel ids are unchanged`() {
        val service = read("app/src/main/kotlin/com/mdmesh/agent/service/CheckInService.kt")
        assertTrue(service.contains("CHANNEL_ID = \"mdm_checkin\""))
        val alerts = read("core/src/main/kotlin/com/mdmesh/core/action/AlertNotifier.kt")
        assertTrue(alerts.contains("CHANNEL = \"mdm_alert\""))
    }
}
