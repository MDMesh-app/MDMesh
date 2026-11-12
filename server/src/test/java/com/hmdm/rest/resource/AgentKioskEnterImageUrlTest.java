package com.hmdm.rest.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Guice;
import com.hmdm.notification.AgentWakeHub;
import com.hmdm.persistence.AgentCommandDAO;
import com.hmdm.persistence.DeviceDAO;
import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.AgentCommand;
import com.hmdm.persistence.domain.Application;
import com.hmdm.persistence.domain.Configuration;
import com.hmdm.persistence.domain.Customer;
import com.hmdm.persistence.domain.Device;
import com.hmdm.persistence.domain.ManagedAppPermission;
import com.hmdm.persistence.domain.UploadedFile;
import com.hmdm.persistence.mapper.DeviceMapper;
import com.hmdm.rest.json.AgentBulkCommandRequest;
import com.hmdm.rest.json.Response;
import com.hmdm.rest.json.agent.DesiredConfig;
import com.hmdm.rest.resource.support.ConfigReconciler;
import com.hmdm.rest.resource.support.InstallHashes;
import com.hmdm.rest.resource.support.KioskBackgroundImageUrls;
import com.hmdm.rest.resource.support.KioskCommandImages;
import com.hmdm.security.SecurityContext;
import com.hmdm.testsupport.Fakes;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

/**
 * Enter kiosk (one device or many) carries the configuration's launcher theme. Its managed background image gets the
 * address config.apply sends, built from the server's current base URL as the command is queued: after a move from
 * HTTP to HTTPS, Enter kiosk doesn't take the image away from devices that config.apply gave it to.
 */
public class AgentKioskEnterImageUrlTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String PATH = "/files/acme/kiosk-0123456789ab-beach.jpg";
    /** Stored when the image was picked, under the old base URL. */
    private static final String STORED = "http://old.example:8080" + PATH;
    private static final String BASE_NOW = "https://mdm.example";
    private static final String CURRENT = BASE_NOW + PATH;

    private final Map<String, Device> devices = new HashMap<>();
    private final Map<Integer, Configuration> configurations = new HashMap<>();
    private final List<AgentCommand> queued = new ArrayList<>();
    private final List<String> shaLookups = new ArrayList<>();
    private UploadedFile file;
    private AgentAdminResource resource;
    private ConfigReconciler reconciler;

    private static Device device(int id, String number, int configurationId) {
        Device d = new Device();
        d.setId(id);
        d.setNumber(number);
        d.setCustomerId(1);
        d.setConfigurationId(configurationId);
        return d;
    }

    private static Configuration managed(int id, int customerId) {
        Configuration c = new Configuration();
        c.setId(id);
        c.setCustomerId(customerId);
        c.setKioskMode(true);
        c.setBackgroundImageFileId(42);
        c.setBackgroundImageUrl(STORED);
        c.setBackgroundImageSha256(SHA);
        c.setBackgroundImageFocalX(0.25);
        c.setBackgroundImageFocalY(0.75);
        return c;
    }

    @Before
    public void setUp() {
        devices.put("A", device(1, "A", 7));
        devices.put("B", device(2, "B", 7));
        configurations.put(7, managed(7, 1));
        file = new UploadedFile();
        file.setId(42);
        file.setCustomerId(1);
        file.setFilePath("kiosk-0123456789ab-beach.jpg");
        file.setExternal(false);

        UnsecureDAO unsecureDAO = new UnsecureDAO(null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, "files", 2, "launcher") {
            @Override public Device getDeviceByNumber(String number) { return devices.get(number); }
            @Override public Device getDeviceById(Integer id) {
                return devices.values().stream().filter(d -> d.getId().equals(id)).findFirst().orElse(null);
            }
            @Override public Configuration getConfigurationById(Integer id) { return copy(configurations.get(id)); }
            @Override public List<Configuration> getManagedBackgroundImageConfigurations(int customerId, String sha256) {
                shaLookups.add(customerId + ":" + sha256);
                List<Configuration> out = new ArrayList<>();
                for (Configuration c : configurations.values()) {
                    if (c.getCustomerId() == customerId && sha256.equals(c.getBackgroundImageSha256())
                            && c.getBackgroundImageFileId() != null) out.add(copy(c));
                }
                return out;
            }
            @Override public UploadedFile getUploadedFileById(Integer id) {
                return file != null && file.getId().equals(id) ? file : null;
            }
            @Override public Customer getCustomerByIdUnsecure(Integer id) {
                Customer c = new Customer();
                c.setId(id);
                c.setFilesDir("acme");
                return c;
            }
            @Override public List<ManagedAppPermission> getManagedAppPermissions(Integer id) { return null; }
            @Override public List<Application> getPlainConfigurationAppsOptimized(Integer id) { return new ArrayList<>(); }
        };
        AgentCommandDAO commandDAO = new AgentCommandDAO(null, null, null, null) {
            @Override public void insert(AgentCommand c) { queued.add(c); }
        };
        AgentWakeHub wakeHub = new AgentWakeHub(commandDAO) {
            @Override public void wake(String n, String kind) { }
        };
        DeviceDAO deviceDAO = new DeviceDAO(new Fakes.FakeMapper<>(DeviceMapper.class)
                .answer("isDeviceAccessibleToUser", a -> true).mapper, null, Guice.createInjector(), null, 3);
        KioskBackgroundImageUrls urls = new KioskBackgroundImageUrls(unsecureDAO, BASE_NOW);
        reconciler = new ConfigReconciler(unsecureDAO, commandDAO, Fakes.noExtensionPoints(), urls);
        resource = new AgentAdminResource(null, commandDAO, unsecureDAO, deviceDAO, wakeHub, null, reconciler,
                new InstallHashes(null, unsecureDAO), new KioskCommandImages(unsecureDAO, urls));
        SecurityContext.init(Fakes.user(5, 1, "edit_devices", "devices"));
    }

    @After
    public void tearDown() {
        SecurityContext.release();
    }

    private static Configuration copy(Configuration c) {
        if (c == null) return null;
        Configuration d = new Configuration();
        d.setId(c.getId());
        d.setCustomerId(c.getCustomerId());
        d.setKioskMode(c.isKioskMode());
        d.setBackgroundImageFileId(c.getBackgroundImageFileId());
        d.setBackgroundImageUrl(c.getBackgroundImageUrl());
        d.setBackgroundImageSha256(c.getBackgroundImageSha256());
        d.setBackgroundImageFit(c.getBackgroundImageFit());
        d.setBackgroundImageFocalX(c.getBackgroundImageFocalX());
        d.setBackgroundImageFocalY(c.getBackgroundImageFocalY());
        return d;
    }

    /** The payload the console builds (kiosk.ts buildKioskPayload with kioskTheme of the device's configuration). */
    private static String consolePayload(String imageUrl) {
        return "{\"mode\":\"launcher\",\"allowedPackages\":[\"com.example.a\",\"com.example.b\"],"
                + "\"exitMode\":\"gesture\",\"features\":{\"home\":true,\"notifications\":true},"
                + "\"theme\":{\"backgroundColor\":\"#102030\",\"backgroundImage\":{\"url\":\"" + imageUrl + "\","
                + "\"sha256\":\"" + SHA + "\",\"focalX\":0.25,\"focalY\":0.75}}}";
    }

    private static AgentCommand enter(String payload) {
        AgentCommand cmd = new AgentCommand();
        cmd.setType("kiosk.enter");
        cmd.setPayload(payload);
        return cmd;
    }

    private static JsonNode image(AgentCommand queued) throws Exception {
        return JSON.readTree(queued.getPayload()).path("theme").path("backgroundImage");
    }

    /** What config.apply sends the device for the same configuration. */
    private String configApplyUrl() {
        DesiredConfig doc = reconciler.currentDocument(devices.get("A"));
        return doc.getKiosk().getTheme().getBackgroundImage().getUrl();
    }

    @Test
    public void enterKioskOnOneDeviceSendsTheCurrentAddress() throws Exception {
        Response r = resource.queueCommand("A", enter(consolePayload(STORED)));
        assertEquals(Response.ResponseStatus.OK, r.getStatus());
        assertEquals(1, queued.size());
        JsonNode image = image(queued.get(0));
        assertEquals(CURRENT, image.path("url").asText());
        assertEquals(SHA, image.path("sha256").asText());
        // Everything else as the console sent it.
        assertEquals(0.25, image.path("focalX").asDouble(), 0);
        assertEquals(0.75, image.path("focalY").asDouble(), 0);
        JsonNode sent = JSON.readTree(queued.get(0).getPayload());
        assertEquals("#102030", sent.path("theme").path("backgroundColor").asText());
        assertEquals("launcher", sent.path("mode").asText());
        assertEquals(2, sent.path("allowedPackages").size());
    }

    @Test
    public void bulkEnterKioskSendsTheCurrentAddressToEveryDevice() throws Exception {
        AgentBulkCommandRequest req = new AgentBulkCommandRequest();
        req.setDeviceIds(Fakes.list(1, 2));
        req.setCommand(enter(consolePayload(STORED)));
        Response r = resource.queueCommandBulk(req);
        assertEquals(Response.ResponseStatus.OK, r.getStatus());
        assertEquals(2, queued.size());
        for (AgentCommand c : queued) assertEquals(CURRENT, image(c).path("url").asText());
    }

    /** The same address config.apply sends: the agent sees one image, so one kiosk, whichever command came last. */
    @Test
    public void enterKioskAndConfigApplySendTheSameAddress() throws Exception {
        resource.queueCommand("A", enter(consolePayload(STORED)));
        assertEquals(CURRENT, configApplyUrl());
        assertEquals(configApplyUrl(), image(queued.get(0)).path("url").asText());
    }

    /** Already the current address (an API client that read it from configStatus): queued exactly as sent. */
    @Test
    public void theCurrentAddressIsQueuedAsSent() {
        String payload = consolePayload(CURRENT);
        resource.queueCommand("A", enter(payload));
        assertEquals(payload, queued.get(0).getPayload());
    }

    /** An image elsewhere with the same bytes (an API client's CDN) is the client's choice: never rewritten. */
    @Test
    public void anotherAddressWithTheSameBytesIsQueuedAsSent() {
        String payload = consolePayload("https://cdn.example/beach.jpg");
        resource.queueCommand("A", enter(payload));
        assertEquals(payload, queued.get(0).getPayload());
    }

    /** Only the current account's configurations say where an image lives. */
    @Test
    public void anotherAccountsConfigurationIsNotUsed() {
        configurations.put(7, managed(7, 2));
        String payload = consolePayload(STORED);
        resource.queueCommand("A", enter(payload));
        assertEquals(payload, queued.get(0).getPayload());
        assertEquals(Collections.singletonList("1:" + SHA), shaLookups);
    }

    /** The file is gone: the stored address goes out, as config.apply would send it. */
    @Test
    public void anUnresolvableFileKeepsTheSentAddress() {
        file = null;
        String payload = consolePayload(STORED);
        resource.queueCommand("A", enter(payload));
        assertEquals(payload, queued.get(0).getPayload());
        assertEquals(STORED, configApplyUrl());
    }

    @Test
    public void aThemeWithoutAnImageIsNotLookedAt() {
        String payload = "{\"mode\":\"single\",\"pinPackage\":\"a\",\"allowedPackages\":[\"a\"],"
                + "\"theme\":{\"backgroundColor\":\"#000000\"}}";
        resource.queueCommand("A", enter(payload));
        assertEquals(payload, queued.get(0).getPayload());
        assertEquals(Collections.emptyList(), shaLookups);
    }

    /** Opaque to the server otherwise: a payload it can't read is queued as is (the agent reports it). */
    @Test
    public void anUnreadablePayloadIsQueuedAsIs() {
        resource.queueCommand("A", enter("{not json"));
        assertEquals("{not json", queued.get(0).getPayload());
    }

    @Test
    public void otherCommandsAreNotLookedAt() {
        AgentCommand lock = new AgentCommand();
        lock.setType("device.lock");
        lock.setPayload(consolePayload(STORED));
        resource.queueCommand("A", lock);
        assertEquals(consolePayload(STORED), queued.get(0).getPayload());
        assertEquals(Collections.emptyList(), shaLookups);
    }
}
