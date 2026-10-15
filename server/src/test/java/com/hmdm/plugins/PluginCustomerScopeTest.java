package com.hmdm.plugins;

import com.hmdm.persistence.UploadedFileDAO;
import com.hmdm.persistence.domain.UploadedFile;
import com.hmdm.persistence.mapper.ConfigurationFileMapper;
import com.hmdm.persistence.mapper.UploadedFileMapper;
import com.hmdm.plugins.devicelog.persistence.postgres.dao.PostgresDeviceLogPluginSettingsDAO;
import com.hmdm.plugins.devicelog.persistence.postgres.dao.domain.PostgresDeviceLogPluginSettings;
import com.hmdm.plugins.devicelog.persistence.postgres.dao.mapper.PostgresDeviceLogMapper;
import com.hmdm.plugins.messaging.persistence.MessagingDAO;
import com.hmdm.plugins.messaging.persistence.mapper.MessageMapper;
import com.hmdm.plugins.messaging.rest.MessagingResource;
import com.hmdm.rest.json.FileConfigurationLink;
import com.hmdm.rest.json.Response;
import com.hmdm.security.SecurityContext;
import com.hmdm.security.SecurityException;
import com.hmdm.testsupport.Fakes;
import com.hmdm.testsupport.Fakes.Call;
import com.hmdm.testsupport.Fakes.FakeMapper;
import org.junit.After;
import org.junit.Test;

import javax.ws.rs.DELETE;
import javax.ws.rs.Path;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Plugin and file-link writes stay within the caller's customer. */
public class PluginCustomerScopeTest {

    @After
    public void tearDown() {
        SecurityContext.release();
    }

    // ---- messaging: delete message ------------------------------------------------------------------------------

    @Test
    public void messageDeleteIsServedUnderTheAuthenticatedPrefix() {
        for (Method m : MessagingResource.class.getMethods()) {
            if (m.getAnnotation(DELETE.class) != null) {
                String path = m.getAnnotation(Path.class).value();
                assertTrue(path, path.startsWith("/private/"));
            }
        }
    }

    @Test
    public void messageDeleteWithoutAUserDeletesNothing() {
        FakeMapper<MessageMapper> fake = new FakeMapper<>(MessageMapper.class);
        MessagingResource resource = new MessagingResource(new MessagingDAO(fake.mapper), null, null, null, null);
        SecurityContext.init(1);   // a context with no user

        assertNotEquals(Response.ResponseStatus.OK, resource.removeDevice(42).getStatus());
        assertTrue(fake.calls.toString(), fake.callsTo("deleteMessage").isEmpty());
    }

    @Test
    public void messageDeleteIsBoundToTheCallersCustomer() {
        FakeMapper<MessageMapper> fake = new FakeMapper<>(MessageMapper.class);
        MessagingResource resource = new MessagingResource(new MessagingDAO(fake.mapper), null, null, null, null);
        SecurityContext.init(Fakes.user(5, 3, "plugin_messaging_delete"));

        assertEquals(Response.ResponseStatus.OK, resource.removeDevice(42).getStatus());
        List<Call> deletes = fake.callsTo("deleteMessage");
        assertEquals(1, deletes.size());
        List<Object> args = Arrays.asList(deletes.get(0).args);
        assertTrue(args.toString(), args.contains(42) && args.contains(3));
        String where = Fakes.whereOf(Fakes.sqlOf(MessageMapper.class, "deleteMessage"));
        assertTrue(where, where.contains("customerid"));
    }

    // ---- devicelog: plugin settings ------------------------------------------------------------------------------

    private static PostgresDeviceLogPluginSettings settings(int id, int customerId) {
        PostgresDeviceLogPluginSettings s = new PostgresDeviceLogPluginSettings();
        s.setId(id);
        s.setCustomerId(customerId);
        return s;
    }

    @Test
    public void anotherCustomersDeviceLogSettingsAreNotUpdatedWithAnotherCustomerId() {
        FakeMapper<PostgresDeviceLogMapper> fake = new FakeMapper<>(PostgresDeviceLogMapper.class)
                .answer("findPluginSettingsById", a -> settings((Integer) a[0], 2));
        PostgresDeviceLogPluginSettingsDAO dao = new PostgresDeviceLogPluginSettingsDAO(fake.mapper);
        SecurityContext.init(Fakes.user(5, 1));

        try {
            dao.updatePluginSettings(settings(9, 1));
            fail("expected a SecurityException");
        } catch (SecurityException expected) {
            // ok
        }
        assertTrue(fake.calls.toString(), fake.callsTo("updatePluginSettings").isEmpty());
        String where = Fakes.whereOf(Fakes.sqlOf(PostgresDeviceLogMapper.class, "updatePluginSettings"));
        assertTrue(where, where.contains("customerid"));
    }

    @Test
    public void ownDeviceLogSettingsAreUpdated() {
        FakeMapper<PostgresDeviceLogMapper> fake = new FakeMapper<>(PostgresDeviceLogMapper.class)
                .answer("findPluginSettingsById", a -> settings((Integer) a[0], 1));
        PostgresDeviceLogPluginSettingsDAO dao = new PostgresDeviceLogPluginSettingsDAO(fake.mapper);
        SecurityContext.init(Fakes.user(5, 1));

        dao.updatePluginSettings(settings(9, 0));
        assertEquals(1, fake.callsTo("updatePluginSettings").size());
    }

    // ---- file links: new link into a configuration -----------------------------------------------------------------

    @Test
    public void newFileLinkIsBoundToTheCallersConfigurations() {
        FakeMapper<UploadedFileMapper> files = new FakeMapper<>(UploadedFileMapper.class).answer("findById", a -> {
            UploadedFile f = new UploadedFile();
            f.setId((Integer) a[0]);
            f.setCustomerId(1);
            return f;
        });
        FakeMapper<ConfigurationFileMapper> links = new FakeMapper<>(ConfigurationFileMapper.class);
        UploadedFileDAO dao = new UploadedFileDAO(files.mapper, links.mapper, null, "files");
        SecurityContext.init(Fakes.user(5, 1, "edit_files"));

        FileConfigurationLink link = new FileConfigurationLink();
        link.setFileId(10);
        link.setConfigurationId(7);   // any configuration id, possibly another customer's
        link.setUpload(true);
        dao.updateFileConfigurations(Fakes.list(link));

        assertTrue("an unscoped insert must not be used: " + links.calls,
                links.callsTo("insertConfigurationFile").isEmpty());
        List<Call> inserts = links.callsStartingWith("insert");
        assertEquals(links.calls.toString(), 1, inserts.size());
        assertTrue(Arrays.asList(inserts.get(0).args).toString(), Arrays.asList(inserts.get(0).args).contains(1));
        String where = Fakes.whereOf(Fakes.sqlOf(ConfigurationFileMapper.class, inserts.get(0).method));
        assertTrue(where, where.contains("customerid"));
    }
}
