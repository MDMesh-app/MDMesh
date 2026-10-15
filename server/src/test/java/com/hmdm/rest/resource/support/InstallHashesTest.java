package com.hmdm.rest.resource.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.AgentCommand;
import com.hmdm.util.InstallIntegrity;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** A pending app.install gets its sha256 at delivery: hosted file (live) first, then the customer's Library hash. */
public class InstallHashesTest {

    private static final ObjectMapper M = new ObjectMapper();
    /** sha256("abc") */
    private static final String ABC = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
    private static final String ABC_APK_HASH = "ungWv48Bz-pBQUDeXa4iI7ADYaOWF3qctBD_YfIAFa0=";

    private static final class FakeUnsecureDAO extends UnsecureDAO {
        final Map<String, String> hashByUrl = new HashMap<>();
        Integer askedCustomer;
        FakeUnsecureDAO() {
            super(null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                    "/nonexistent", 0, "com.mdmesh.agent");
        }
        @Override public String findApkHashByUrl(int customerId, String url) {
            askedCustomer = customerId;
            return hashByUrl.get(url);
        }
    }

    private FakeUnsecureDAO dao;
    private InstallHashes hashes;

    @Before
    public void setUp() throws Exception {
        File dir = Files.createTempDirectory("files").toFile();
        Files.write(new File(dir, "a.apk").toPath(), "abc".getBytes(StandardCharsets.US_ASCII));
        dao = new FakeUnsecureDAO();
        hashes = new InstallHashes(new ApkHashResolver(dir.getAbsolutePath(), "http://mdm.lan:8080"), dao);
    }

    private static AgentCommand install(String payload) {
        AgentCommand c = new AgentCommand();
        c.setType("app.install");
        c.setPayload(payload);
        return c;
    }

    @Test
    public void legacyCommandForAHostedApkIsFilledFromTheFile() throws Exception {
        // A Library hash for the same URL is stale (the file was replaced): the live file wins.
        dao.hashByUrl.put("http://mdm.lan:8080/files/a.apk", "00".repeat(32));
        String out = hashes.forDelivery(install("{\"url\":\"http://mdm.lan:8080/files/a.apk\",\"packageName\":\"x\"}"), 7);
        assertEquals(ABC, M.readTree(out).get("sha256").asText());
    }

    @Test
    public void legacyCommandForAnExternalApkIsFilledFromTheLibrary() throws Exception {
        dao.hashByUrl.put("https://apks.example/a.apk", ABC_APK_HASH);
        String out = hashes.forDelivery(install("{\"url\":\"https://apks.example/a.apk\",\"packageName\":\"x\"}"), 7);
        assertEquals(ABC, M.readTree(out).get("sha256").asText());
        assertEquals(Integer.valueOf(7), dao.askedCustomer);
    }

    @Test
    public void legacyCommandWithNoKnownHashIsRefusedWithTheFix() {
        try {
            hashes.forDelivery(install("{\"url\":\"https://apks.example/b.apk\",\"packageName\":\"x\"}"), 7);
            fail("no hash is known");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("no sha256 for https://apks.example/b.apk"));
            assertTrue(e.getMessage(), e.getMessage().contains(InstallIntegrity.ADD_HASH));
        }
    }

    @Test
    public void otherCommandsAndHashedInstallsPassThrough() throws Exception {
        AgentCommand lock = new AgentCommand();
        lock.setType("device.lock");
        lock.setPayload("{}");
        assertSame(lock.getPayload(), hashes.forDelivery(lock, 7));
        String out = hashes.forDelivery(install("{\"url\":\"https://apks.example/c.apk\",\"packageName\":\"x\",\"sha256\":\"" + ABC + "\"}"), 7);
        assertEquals(ABC, M.readTree(out).get("sha256").asText());
        assertEquals(null, dao.askedCustomer);
    }
}
