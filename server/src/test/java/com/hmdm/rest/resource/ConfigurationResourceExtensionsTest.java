package com.hmdm.rest.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hmdm.extension.ConfigurationExtensionValidator;
import com.hmdm.extension.DesiredConfigContributor;
import com.hmdm.extension.ExtensionError;
import com.hmdm.notification.PushService;
import com.hmdm.persistence.ConfigurationDAO;
import com.hmdm.persistence.domain.Application;
import com.hmdm.persistence.domain.Configuration;
import com.hmdm.rest.json.Response;
import com.hmdm.rest.resource.support.ExtensionPoints;
import com.hmdm.security.SecurityContext;
import com.hmdm.testsupport.Fakes;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/**
 * PUT /configurations checks a save's extensions (namespaces, sizes, the plugins' validators) before storing
 * anything, and answers a refusal with its message key; PUT /copy copies them.
 */
public class ConfigurationResourceExtensionsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Stores into fields instead of a database. */
    private static final class FakeConfigurationDAO extends ConfigurationDAO {
        Configuration stored;
        boolean access = true;
        final List<Configuration> updated = new ArrayList<>();
        final List<Configuration> inserted = new ArrayList<>();

        FakeConfigurationDAO() {
            super(null, null, null, null, "https://mdm.example.com", System.getProperty("java.io.tmpdir"), null);
        }

        @Override public Configuration getConfigurationByName(String name) { return null; }
        @Override public boolean hasConfigurationAccess(int id) { return access; }
        @Override public void updateConfiguration(Configuration c) { updated.add(c); }
        @Override public void insertConfiguration(Configuration c) { c.setId(99); inserted.add(c); }
        @Override public Configuration getConfigurationByIdFull(Integer id) { return stored; }
        @Override public Configuration getConfigurationById(Integer id) { return stored; }
        @Override public List<Application> getPlainConfigurationApplications(Integer id) { return new ArrayList<>(); }
        @Override public String getBaseUrl() { return "https://mdm.example.com"; }
    }

    /** The design's netaccess plugin: a VPN port must be 1 to 65535. */
    private static final ConfigurationExtensionValidator PORTS = new ConfigurationExtensionValidator() {
        @Override public String extensionId() { return "netaccess"; }
        @Override public List<Problem> validate(String namespace, JsonNode value) {
            int port = value.path("port").asInt(1);
            return port >= 1 && port <= 65535 ? Collections.<Problem>emptyList()
                    : Collections.singletonList(new Problem("port-range", "The VPN port must be 1 to 65535."));
        }
    };

    private static final Set<DesiredConfigContributor> NO_CONTRIBUTORS = Collections.emptySet();

    private FakeConfigurationDAO dao;
    private ConfigurationResource resource;

    private ConfigurationResource resource(ExtensionPoints extensionPoints) {
        return new ConfigurationResource(dao, null, new PushService(null, null, null, null, false), null, null,
                "https://mdm.example.com", extensionPoints);
    }

    @Before
    public void setUp() {
        SecurityContext.init(Fakes.user(5, 1, "configurations", "add_config", "copy_config"));
        dao = new FakeConfigurationDAO();
        dao.stored = configuration(3, null);
        resource = resource(Fakes.extensionPoints(Collections.singleton(PORTS), NO_CONTRIBUTORS));
    }

    @After
    public void tearDown() {
        SecurityContext.release();
    }

    private static Configuration configuration(Integer id, String extensions) {
        Configuration c = new Configuration();
        c.setId(id);
        c.setName("warehouse");
        c.setApplications(new ArrayList<>());
        try {
            c.setExtensions(extensions == null ? null : (ObjectNode) JSON.readTree(extensions));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return c;
    }

    @SuppressWarnings("unchecked")
    private static List<String> keys(Response r) {
        return ((List<ExtensionError>) r.getData()).stream().map(ExtensionError::getKey).collect(Collectors.toList());
    }

    @Test
    public void aSaveWithoutExtensionsReachesTheStoreWithoutThem() {
        Response r = resource.updateConfiguration(configuration(3, null));
        assertEquals(Response.ResponseStatus.OK, r.getStatus());
        assertEquals(1, dao.updated.size());
        // Null is what the update statement keeps the stored value for (ConfigurationMapperExtensionsTest).
        assertNull(dao.updated.get(0).getExtensions());
    }

    @Test
    public void aSaveWithExtensionsStoresThemAsSent() {
        Response r = resource.updateConfiguration(configuration(3, "{\"netaccess.vpn\":{\"port\":443},\"other\":{}}"));
        assertEquals(Response.ResponseStatus.OK, r.getStatus());
        assertEquals("{\"netaccess.vpn\":{\"port\":443},\"other\":{}}", dao.updated.get(0).getExtensions().toString());
    }

    /** Stored data the plugin now refuses, left as it is, doesn't block a save that changes another namespace. */
    @Test
    public void anUpdateIsCheckedOnlyOnTheNamespacesItChanges() {
        dao.stored = configuration(3, "{\"netaccess.vpn\":{\"port\":70000},\"demo\":{\"on\":false}}");
        Response r = resource.updateConfiguration(configuration(3, "{\"netaccess.vpn\":{\"port\":70000},\"demo\":{\"on\":true}}"));
        assertEquals(Response.ResponseStatus.OK, r.getStatus());
        assertEquals(1, dao.updated.size());

        Response changed = resource.updateConfiguration(configuration(3, "{\"netaccess.vpn\":{\"port\":70001}}"));
        assertEquals("error.extension.netaccess.vpn.port-range", changed.getMessage());
        // A new configuration's data is all new: every namespace is checked.
        Response created = resource.updateConfiguration(configuration(null, "{\"netaccess.vpn\":{\"port\":70000}}"));
        assertEquals("error.extension.netaccess.vpn.port-range", created.getMessage());
    }

    @Test
    public void aValidatorsRefusalIsTheResponseAndNothingIsStored() {
        Response r = resource.updateConfiguration(configuration(3, "{\"netaccess.vpn\":{\"port\":70000}}"));
        assertEquals(Response.ResponseStatus.ERROR, r.getStatus());
        assertEquals("error.extension.netaccess.vpn.port-range", r.getMessage());
        assertEquals(Collections.singletonList("error.extension.netaccess.vpn.port-range"), keys(r));
        assertEquals(0, dao.updated.size());
    }

    @Test
    public void aBadNamespaceOrSizeIsRefusedBeforeAnythingIsStored() {
        Response r = resource.updateConfiguration(configuration(null, "{\"Net Access\":{},\"demo\":[]}"));
        assertEquals(Response.ResponseStatus.ERROR, r.getStatus());
        assertEquals("error.extensions.namespace", r.getMessage());
        assertEquals(Arrays.asList("error.extensions.namespace", "error.extensions.object"), keys(r));
        assertEquals(0, dao.inserted.size());

        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 17 * 1024; i++) big.append('x');
        r = resource.updateConfiguration(configuration(3, "{\"demo\":{\"v\":\"" + big + "\"}}"));
        assertEquals("error.extensions.namespace.size", r.getMessage());
        assertEquals(0, dao.updated.size());
    }

    @Test
    public void aCopyTakesItsOwnCopyOfTheExtensions() {
        dao.stored = configuration(3, "{\"netaccess\":{\"vpn\":true}}");
        Configuration request = new Configuration();
        request.setId(3);
        request.setName("warehouse copy");
        Response r = resource.copyConfiguration(request);
        assertEquals(Response.ResponseStatus.OK, r.getStatus());
        Configuration copy = dao.inserted.get(0);
        assertEquals("warehouse copy", copy.getName());
        assertEquals(dao.stored.getExtensions(), copy.getExtensions());
        assertNotSame(dao.stored.getExtensions(), copy.getExtensions());
    }

    @Test
    public void withoutPluginsEveryValidNamespaceIsKept() {
        resource = resource(Fakes.noExtensionPoints());
        Configuration c = configuration(3, "{\"netaccess.vpn\":{\"port\":70000}}");
        assertEquals(Response.ResponseStatus.OK, resource.updateConfiguration(c).getStatus());
        assertSame(c.getExtensions(), dao.updated.get(0).getExtensions());
    }

    @Test
    public void aValidatorThatWritesIntoTheDataChangesNothingThatIsStored() {
        ConfigurationExtensionValidator normalizer = new ConfigurationExtensionValidator() {
            @Override public String extensionId() { return "netaccess"; }
            @Override public List<Problem> validate(String namespace, JsonNode value) {
                ((ObjectNode) value).put("port", 443);
                return null;
            }
        };
        resource = resource(Fakes.extensionPoints(Collections.singleton(normalizer), NO_CONTRIBUTORS));
        assertEquals(Response.ResponseStatus.OK,
                resource.updateConfiguration(configuration(3, "{\"netaccess.vpn\":{\"on\":true}}")).getStatus());
        assertEquals("{\"netaccess.vpn\":{\"on\":true}}", dao.updated.get(0).getExtensions().toString());
    }

    @Test
    public void aUserWhoMayNotSaveIsRefusedBeforeTheExtensionsAreLookedAt() {
        dao.access = false;
        Response r = resource.updateConfiguration(configuration(3, "{\"netaccess.vpn\":{\"port\":70000}}"));
        assertEquals(Response.ResponseStatus.ERROR, r.getStatus());
        assertEquals("error.permission.denied", r.getMessage());

        SecurityContext.init(Fakes.user(5, 1, "configurations"));  // no add_config
        r = resource.updateConfiguration(configuration(null, "{\"Bad\":{}}"));
        assertEquals("error.permission.denied", r.getMessage());
    }

    /** A plugin turned off for the user's customer doesn't check that customer's saves (its data is kept as sent). */
    @Test
    public void aPluginTurnedOffForTheUsersCustomerDoesNotCheckTheSave() {
        resource = resource(new ExtensionPoints(Collections.singleton(PORTS), NO_CONTRIBUTORS,
                Fakes.pluginStatus((plugin, customer) -> "netaccess".equals(plugin) && Integer.valueOf(2).equals(customer))));
        Configuration bad = configuration(3, "{\"netaccess.vpn\":{\"port\":70000}}");
        assertEquals("error.extension.netaccess.vpn.port-range", resource.updateConfiguration(bad).getMessage());

        SecurityContext.init(Fakes.user(5, 2, "configurations", "add_config"));
        assertEquals(Response.ResponseStatus.OK, resource.updateConfiguration(bad).getStatus());
        assertSame(bad.getExtensions(), dao.updated.get(0).getExtensions());
    }
}
