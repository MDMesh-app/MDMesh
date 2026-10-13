package com.hmdm.rest.resource.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdm.notification.AgentWakeHub;
import com.hmdm.persistence.AgentCommandDAO;
import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.AgentCommand;
import com.hmdm.persistence.domain.Application;
import com.hmdm.persistence.domain.ApplicationVersion;
import com.hmdm.persistence.domain.Device;
import com.hmdm.util.InstallIntegrity;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Configuration apps queued as app.install: real URLs only, and always with the APK's SHA-256. */
public class ConfigAppInstallerTest {

    private static final ObjectMapper M = new ObjectMapper();
    /** sha256("abc") as hex and in the base64url form applicationVersions.apkHash stores. */
    private static final String HEX = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
    private static final String APK_HASH = "ungWv48Bz-pBQUDeXa4iI7ADYaOWF3qctBD_YfIAFa0=";
    private static final String OTHER_HEX = "cd".repeat(32);

    private static Application app(String url) {
        Application a = new Application();
        a.setUrl(url);
        return a;
    }

    @Test
    public void skipsHeadwindSeedUrls() {
        assertNull(ConfigAppInstaller.firstUsableUrl(app("https://h-mdm.com/files/_HMDM_APK_")));
        assertNull(ConfigAppInstaller.firstUsableUrl(app("https://h-mdm.com/files/pager-1.02.apk")));
        assertNull(ConfigAppInstaller.firstUsableUrl(app("http://www.h-mdm.com/files/LauncherRestarter-1.04.apk")));
    }

    @Test
    public void keepsRealUrls() {
        assertEquals("https://mdm.example.com/files/app.apk",
                ConfigAppInstaller.firstUsableUrl(app(" https://mdm.example.com/files/app.apk ")));
        assertEquals("https://f-droid.org/repo/org.fdroid.fdroid_1.apk",
                ConfigAppInstaller.firstUsableUrl(app("https://f-droid.org/repo/org.fdroid.fdroid_1.apk")));
        assertNull(ConfigAppInstaller.firstUsableUrl(app("ftp://x/app.apk")));
    }

    // --- integrity ---------------------------------------------------------------------------------------------

    private static final class FakeUnsecureDAO extends UnsecureDAO {
        final List<Application> apps = new ArrayList<>();
        final Map<Integer, ApplicationVersion> versions = new HashMap<>();
        final Map<Integer, String> savedHashes = new HashMap<>();

        FakeUnsecureDAO() {
            super(null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                    "/nonexistent", 0, "com.mdmesh.agent");
        }

        @Override public List<Application> getPlainConfigurationApplications(Integer customerId, Integer id) { return apps; }
        @Override public ApplicationVersion findApplicationVersionById(Integer id) { return versions.get(id); }
        @Override public void saveApkFileHash(Integer appVersionId, String hashValue) { savedHashes.put(appVersionId, hashValue); }
    }

    private static final class FakeCommandDAO extends AgentCommandDAO {
        final List<AgentCommand> inserted = new ArrayList<>();
        FakeCommandDAO() { super(null, null, null, null); }
        @Override public void insert(AgentCommand command) { inserted.add(command); }
    }

    private static final class FakeWakeHub extends AgentWakeHub {
        int wakes;
        FakeWakeHub() { super(null); }
        @Override public void wake(String deviceNumber, String wakeKind) { wakes++; }
    }

    /** Like the real resolver, hashes only this server's files (to HEX) and refuses anything else; records calls. */
    private static final class FakeResolver extends ApkHashResolver {
        final List<String> asked = new ArrayList<>();
        FakeResolver() { super("/nonexistent", "http://mdm.lan:8080"); }
        @Override public String sha256Hex(String url) throws IOException {
            asked.add(url);
            if (!url.startsWith("http://mdm.lan:8080/files/")) throw new IOException(NOT_HOSTED);
            return HEX;
        }
    }

    private FakeUnsecureDAO dao;
    private FakeCommandDAO commands;
    private FakeResolver resolver;
    private ConfigAppInstaller installer;
    private Device device;

    @Before
    public void setUp() {
        dao = new FakeUnsecureDAO();
        commands = new FakeCommandDAO();
        resolver = new FakeResolver();
        installer = new ConfigAppInstaller(dao, commands, new FakeWakeHub(), resolver);
        device = new Device();
        device.setNumber("d1");
        device.setCustomerId(1);
        device.setConfigurationId(5);
    }

    private Application configApp(int versionId, String pkg, String url, String apkHash) {
        Application a = app(url);
        a.setPkg(pkg);
        a.setAction(1);
        a.setUsedVersionId(versionId);
        ApplicationVersion v = new ApplicationVersion();
        v.setId(versionId);
        v.setUrl(url);
        v.setApkHash(apkHash);
        dao.versions.put(versionId, v);
        dao.apps.add(a);
        return a;
    }

    private JsonNode payload(int i) throws Exception {
        return M.readTree(commands.inserted.get(i).getPayload());
    }

    @Test
    public void hostedApkIsHashedLiveEvenWithAStoredHash() throws Exception {
        // The stored hash is stale (the hosted file was replaced at the same URL): the file decides.
        configApp(10, "com.a", "http://mdm.lan:8080/files/a.apk", InstallIntegrityStale.APK_HASH);
        assertEquals(1, installer.enqueueConfigApps(device));
        assertEquals(HEX, payload(0).get("sha256").asText());
        assertEquals(1, resolver.asked.size());
    }

    @Test
    public void legacyHostedRowWithoutHashIsHashedFromTheFile() throws Exception {
        configApp(11, "com.b", "http://mdm.lan:8080/files/b.apk", null);
        assertEquals(1, installer.enqueueConfigApps(device));
        assertEquals(HEX, payload(0).get("sha256").asText());
        assertTrue(dao.savedHashes.isEmpty());
    }

    /** A valid but wrong apkHash (sha256 of nothing). */
    private static final class InstallIntegrityStale {
        static final String APK_HASH = "47DEQpj8HBSa-_TImW-5JCeuQeRkm5NMpJWZG3hSuFU=";
    }

    @Test
    public void externalAppWithAStoredHashIsQueued() throws Exception {
        configApp(13, "com.d", "https://f-droid.org/repo/d.apk", APK_HASH);
        assertEquals(1, installer.enqueueConfigApps(device));
        assertEquals("pending", commands.inserted.get(0).getStatus());
        assertEquals(HEX, payload(0).get("sha256").asText());
        assertEquals(1, resolver.asked.size()); // not hosted here, so the stored hash answers
    }

    @Test
    public void externalAppWithoutAHashIsRecordedAsFailedNotSent() throws Exception {
        configApp(12, "com.c", "http://apks.lan/c.apk", null);
        assertEquals(0, installer.enqueueConfigApps(device));
        assertEquals(1, commands.inserted.size());
        AgentCommand c = commands.inserted.get(0);
        assertEquals("failed", c.getStatus());
        assertTrue(c.getDetail(), c.getDetail().contains("no sha256 for http://apks.lan/c.apk"));
        assertTrue(c.getDetail(), c.getDetail().contains(InstallIntegrity.ADD_HASH));
        assertEquals(c.getCreatedAt(), c.getCompletedAt());
        assertTrue(dao.savedHashes.isEmpty());
    }

    @Test
    public void bundlePartsKeepTheirHashesAndMissingOnesAreFilled() throws Exception {
        Application a = configApp(14, "com.e", null, null);
        a.setParts("[{\"url\":\"http://mdm.lan:8080/files/base.apk\",\"sha256\":\"" + OTHER_HEX + "\"},"
                + "{\"url\":\"http://mdm.lan:8080/files/split.apk\"}]");
        assertEquals(1, installer.enqueueConfigApps(device));
        JsonNode parts = payload(0).get("parts");
        assertEquals(OTHER_HEX, parts.get(0).get("sha256").asText());
        assertEquals(HEX, parts.get(1).get("sha256").asText());
        assertEquals(1, resolver.asked.size());
        assertTrue(dao.savedHashes.isEmpty());
    }
}
