package com.mdmesh.core.telemetry

import android.app.admin.SystemUpdateInfo
import com.mdmesh.proto.ProtocolJson
import com.mdmesh.proto.SecurityPosture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityCollectorTest {
    // 2024-01-01 is epoch day 19723.
    @Test fun computesPatchAgeInDays() {
        assertEquals(31, SecurityCollector.patchAgeDays("2023-12-01", nowEpochDay = 19723))
    }

    @Test fun nullPatchYieldsNull() {
        assertNull(SecurityCollector.patchAgeDays(null, nowEpochDay = 19723))
    }

    @Test fun unparseablePatchYieldsNull() {
        assertNull(SecurityCollector.patchAgeDays("not-a-date", nowEpochDay = 19723))
    }

    @Test fun pendingUpdateSecurityPatchState() {
        assertEquals(true, SecurityCollector.securityPatch(SystemUpdateInfo.SECURITY_PATCH_STATE_TRUE))
        assertEquals(false, SecurityCollector.securityPatch(SystemUpdateInfo.SECURITY_PATCH_STATE_FALSE))
        assertNull(SecurityCollector.securityPatch(SystemUpdateInfo.SECURITY_PATCH_STATE_UNKNOWN))
    }

    @Test fun pendingUpdateFieldsAreOmittedWhenNullAndOptionalOnDecode() {
        val none = SecurityPosture(true, true, false, false, false, 10, true)
        val noneJson = ProtocolJson.json.encodeToString(SecurityPosture.serializer(), none)
        assertFalse(noneJson.contains("pendingSystemUpdate"))
        val decoded = ProtocolJson.json.decodeFromString(SecurityPosture.serializer(), noneJson)
        assertEquals("JSON without them (as from before 1.7) decodes", none, decoded)

        val pending = none.copy(pendingSystemUpdateAt = 1_700_000_000_000L, pendingSystemUpdateSecurityPatch = true)
        val json = ProtocolJson.json.encodeToString(SecurityPosture.serializer(), pending)
        assertTrue(json.contains("\"pendingSystemUpdateAt\":1700000000000"))
        assertTrue(json.contains("\"pendingSystemUpdateSecurityPatch\":true"))
    }
}
