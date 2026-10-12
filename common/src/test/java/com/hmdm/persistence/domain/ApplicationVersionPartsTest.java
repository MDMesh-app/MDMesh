package com.hmdm.persistence.domain;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class ApplicationVersionPartsTest {
    @Test
    public void copiesPartsFromApplication() {
        Application a = new Application();
        a.setParts("[{\"url\":\"http://x/base.apk\",\"sha256\":\"aa\"}]");
        assertEquals(a.getParts(), new ApplicationVersion(a).getParts());
    }
    @Test
    public void nullPartsStaysNull() {
        assertNull(new ApplicationVersion(new Application()).getParts());
    }
    /** An admin-supplied hex SHA-256 for an external URL is stored in the apkHash form QR provisioning uses. */
    @Test
    public void storesASuppliedSha256AsApkHash() {
        Application a = new Application();
        a.setSha256("BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD");
        assertEquals("ungWv48Bz-pBQUDeXa4iI7ADYaOWF3qctBD_YfIAFa0=", new ApplicationVersion(a).getApkHash());
        a.setSha256("not-a-hash");
        assertNull(new ApplicationVersion(a).getApkHash());
        assertNull(new ApplicationVersion(new Application()).getApkHash());
    }
}
