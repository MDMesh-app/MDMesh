package com.hmdm.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Every app.install the server queues names its bytes with a hex SHA-256 (the agent refuses one without). */
public class InstallIntegrityTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final String HEX = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
    /** The same digest as {@link #HEX}, in the base64url form applicationVersions.apkHash holds. */
    private static final String B64URL = "ungWv48Bz-pBQUDeXa4iI7ADYaOWF3qctBD_YfIAFa0=";

    /** Records which URLs were hashed; answers HEX, or fails for a URL containing "down". */
    private static final class FakeSource implements InstallIntegrity.Sha256Source {
        final List<String> asked = new ArrayList<>();
        @Override public String sha256Hex(String url) throws Exception {
            asked.add(url);
            if (url.contains("down")) throw new java.io.IOException("connection refused");
            return HEX;
        }
    }

    @Test
    public void normalizesHexAndBase64() {
        assertEquals(HEX, InstallIntegrity.normalizeHex(HEX.toUpperCase()));
        assertEquals(HEX, InstallIntegrity.normalizeHex(" " + HEX + " "));
        assertEquals(HEX, InstallIntegrity.normalizeHex(B64URL));
        assertEquals(HEX, InstallIntegrity.normalizeHex("ungWv48Bz-pBQUDeXa4iI7ADYaOWF3qctBD_YfIAFa0"));
        assertEquals(HEX, InstallIntegrity.normalizeHex("ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0="));
        assertNull(InstallIntegrity.normalizeHex(null));
        assertNull(InstallIntegrity.normalizeHex(""));
        assertNull(InstallIntegrity.normalizeHex("ab"));
        assertNull(InstallIntegrity.normalizeHex("not a hash at all!"));
    }

    @Test
    public void adminEnteredHashesMustBeExactly64Hex() {
        assertEquals(HEX, InstallIntegrity.hexOnly(HEX.toUpperCase()));
        assertEquals(HEX, InstallIntegrity.hexOnly(" " + HEX + " "));
        // 43 hex characters also read as unpadded base64 of 32 bytes: a truncated hex hash must not become another hash.
        String truncated = HEX.substring(0, 43);
        assertEquals(64, InstallIntegrity.normalizeHex(truncated).length());
        assertNull(InstallIntegrity.hexOnly(truncated));
        assertNull(InstallIntegrity.hexOnly(B64URL));
        assertNull(InstallIntegrity.hexOnly(HEX + "0"));
        assertNull(InstallIntegrity.hexOnly(null));
        assertNull(InstallIntegrity.hexOnly(""));
    }

    @Test
    public void apkHashFormRoundTrips() throws Exception {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest("abc".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        // Same form QR provisioning stores, so a hash computed here keeps working for the QR checksum.
        assertEquals(com.hmdm.util.CryptoUtil.getBase64String(digest), InstallIntegrity.toApkHash(HEX));
        assertEquals(B64URL, InstallIntegrity.toApkHash(HEX));
        assertNull(InstallIntegrity.toApkHash("nope"));
    }

    @Test
    public void keepsAGivenHashWithoutFetching() throws Exception {
        FakeSource src = new FakeSource();
        String out = InstallIntegrity.ensureSha256(
                "{\"url\":\"https://f-droid.org/repo/x.apk\",\"packageName\":\"x\",\"sha256\":\"" + HEX.toUpperCase() + "\"}", src);
        assertEquals(HEX, M.readTree(out).get("sha256").asText());
        assertTrue(src.asked.isEmpty());
    }

    @Test
    public void convertsStoredBase64ToHex() throws Exception {
        String out = InstallIntegrity.ensureSha256(
                "{\"url\":\"http://lan/files/a.apk\",\"packageName\":\"x\",\"sha256\":\"" + B64URL + "\"}", new FakeSource());
        assertEquals(HEX, M.readTree(out).get("sha256").asText());
    }

    @Test
    public void fillsAMissingHashFromTheSource() throws Exception {
        FakeSource src = new FakeSource();
        String out = InstallIntegrity.ensureSha256(
                "{\"url\":\"http://lan/files/a.apk\",\"packageName\":\"x\",\"versionCode\":3,\"runAfterInstall\":true}", src);
        JsonNode n = M.readTree(out);
        assertEquals(HEX, n.get("sha256").asText());
        assertEquals(3, n.get("versionCode").asInt());
        assertTrue(n.get("runAfterInstall").asBoolean());
        assertEquals(1, src.asked.size());
        assertEquals("http://lan/files/a.apk", src.asked.get(0));
    }

    @Test
    public void fillsEachBundlePart() throws Exception {
        FakeSource src = new FakeSource();
        String out = InstallIntegrity.ensureSha256("{\"packageName\":\"x\",\"parts\":["
                + "{\"url\":\"http://lan/files/base.apk\",\"sha256\":\"" + HEX + "\"},"
                + "{\"url\":\"http://lan/files/split.apk\"}]}", src);
        JsonNode parts = M.readTree(out).get("parts");
        assertEquals(HEX, parts.get(0).get("sha256").asText());
        assertEquals(HEX, parts.get(1).get("sha256").asText());
        assertEquals(1, src.asked.size());
        assertEquals("http://lan/files/split.apk", src.asked.get(0));
    }

    @Test
    public void rejectsAMalformedHash() {
        expectRejected("{\"url\":\"http://lan/a.apk\",\"packageName\":\"x\",\"sha256\":\"abc\"}", "sha256");
    }

    @Test
    public void rejectsWhenTheHashCannotBeComputed() {
        expectRejected("{\"url\":\"http://down.example/a.apk\",\"packageName\":\"x\"}", "http://down.example/a.apk");
    }

    @Test
    public void rejectsAPartWithNoUrlAndNoHash() {
        expectRejected("{\"packageName\":\"x\",\"parts\":[{\"localPath\":\"/sdcard/a.apk\"}]}", "sha256");
        expectRejected("{\"packageName\":\"x\"}", "sha256");
        expectRejected("not json", "payload");
    }

    @Test
    public void aHostedFileIsHashedLiveAndAStoredHashServesOtherUrls() throws Exception {
        // FakeSource stands in for the hosted-file hasher: it answers HEX, and fails for a URL containing "down".
        FakeSource hosted = new FakeSource();
        String stale = "00".repeat(32);
        InstallIntegrity.Sha256Source src = InstallIntegrity.hostedThenStored(hosted, url -> stale);
        String out = InstallIntegrity.ensureSha256("{\"url\":\"http://lan/files/b.apk\",\"packageName\":\"x\"}", src);
        assertEquals("a stored hash must not shadow the hosted file's live hash", HEX, M.readTree(out).get("sha256").asText());

        src = InstallIntegrity.hostedThenStored(hosted, url -> url.equals("https://down.example/a.apk") ? B64URL : null);
        out = InstallIntegrity.ensureSha256("{\"url\":\"https://down.example/a.apk\",\"packageName\":\"x\"}", src);
        assertEquals(HEX, M.readTree(out).get("sha256").asText());

        try {
            InstallIntegrity.ensureSha256("{\"url\":\"https://down.example/z.apk\",\"packageName\":\"x\"}", src);
            fail("no hosted file and no stored hash");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("connection refused"));
        }
    }

    @Test
    public void aFailedStoredLookupIsNotReportedAsNoHash() {
        InstallIntegrity.Sha256Source src = InstallIntegrity.hostedThenStored(new FakeSource(), url -> {
            throw new IllegalStateException("database unavailable");
        });
        try {
            InstallIntegrity.ensureSha256("{\"url\":\"https://down.example/a.apk\",\"packageName\":\"x\"}", src);
            fail("the lookup failed");
        } catch (IllegalArgumentException e) {
            fail("a lookup failure must not read as a definite \"no sha256\": " + e.getMessage());
        } catch (RuntimeException e) {
            assertTrue(e.getClass().getName(), e instanceof InstallIntegrity.LookupFailedException);
        }
    }

    @Test
    public void onlyAppInstallIsTouched() throws Exception {
        String p = "{\"policy\":\"wifi\",\"value\":false}";
        assertSame(p, InstallIntegrity.forCommand("policy.apply", p, new FakeSource()));
        String out = InstallIntegrity.forCommand(" app.install ", "{\"url\":\"http://lan/a.apk\",\"packageName\":\"x\"}", new FakeSource());
        assertEquals(HEX, M.readTree(out).get("sha256").asText());
    }

    private static void expectRejected(String payload, String detailFragment) {
        try {
            InstallIntegrity.ensureSha256(payload, new FakeSource());
            fail("expected rejection of " + payload);
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(detailFragment));
        }
    }
}
