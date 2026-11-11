package com.hmdm.rest.resource;

import com.hmdm.notification.PushService;
import com.hmdm.persistence.ConfigurationDAO;
import com.hmdm.persistence.CustomerDAO;
import com.hmdm.persistence.UploadedFileDAO;
import com.hmdm.persistence.domain.Application;
import com.hmdm.persistence.domain.Configuration;
import com.hmdm.persistence.domain.UploadedFile;
import com.hmdm.rest.json.Response;
import com.hmdm.rest.resource.support.KioskBackgroundImageAssets;
import com.hmdm.security.SecurityContext;
import com.hmdm.security.SecurityException;
import com.hmdm.testsupport.Fakes;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.ws.rs.BadRequestException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * PUT /configurations reads a background image only once the user may save, and refuses a bad one with HTTP 400 and
 * the usual ERROR envelope, so the console can show why.
 */
public class ConfigurationResourceBackgroundImageTest {

    private static final class FakeConfigurationDAO extends ConfigurationDAO {
        boolean access = true;
        final List<Configuration> updated = new ArrayList<>();
        FakeConfigurationDAO() {
            super(null, null, null, null, "https://mdm.example.com", System.getProperty("java.io.tmpdir"), null);
        }
        @Override public Configuration getConfigurationByName(String name) { return null; }
        @Override public boolean hasConfigurationAccess(int id) { return access; }
        @Override public void updateConfiguration(Configuration c) { updated.add(c); }
        @Override public Configuration getConfigurationById(Integer id) { return new Configuration(); }
        @Override public Configuration getConfigurationByIdFull(Integer id) { return new Configuration(); }
        @Override public List<Application> getPlainConfigurationApplications(Integer id) { return new ArrayList<>(); }
    }

    private static final class ForeignFiles extends UploadedFileDAO {
        int reads;
        ForeignFiles() { super(null, null, null, "unused"); }
        @Override public UploadedFile getById(Integer id) {
            reads++;
            throw SecurityException.onAdminDataAccessViolation("read another customer's file");
        }
    }

    private FakeConfigurationDAO dao;
    private ForeignFiles files;
    private ConfigurationResource resource;

    @Before
    public void setUp() {
        SecurityContext.init(Fakes.user(5, 1, "configurations"));
        dao = new FakeConfigurationDAO();
        files = new ForeignFiles();
        KioskBackgroundImageAssets assets = new KioskBackgroundImageAssets(files,
                new CustomerDAO(null, null, null, null, null, null, null, "unused", 0, null),
                "https://mdm.example.com", System.getProperty("java.io.tmpdir"));
        resource = new ConfigurationResource(dao, null, new PushService(null, null, null, null, false), null, null,
                "https://mdm.example.com", Fakes.extensionPoints(Collections.emptySet(), Collections.emptySet()), assets);
    }

    @After
    public void tearDown() {
        SecurityContext.release();
    }

    private static Configuration replacing() {
        Configuration c = new Configuration();
        c.setId(3);
        c.setName("lobby");
        c.setApplications(new ArrayList<>());
        c.setBackgroundImageAction("replace");
        c.setBackgroundImageFileId(42);
        return c;
    }

    @Test
    public void anotherCustomersFileIsABadRequestWithAReadableMessage() {
        try {
            resource.updateConfiguration(replacing());
            fail("expected HTTP 400");
        } catch (BadRequestException e) {
            assertEquals(400, e.getResponse().getStatus());
            Response body = (Response) e.getResponse().getEntity();
            assertEquals(Response.ResponseStatus.ERROR, body.getStatus());
            assertTrue(body.getMessage(), body.getMessage().startsWith("The background image"));
        }
        assertTrue("nothing is stored", dao.updated.isEmpty());
    }

    @Test
    public void noFileIsReadForAUserWithoutAccessToTheConfiguration() {
        dao.access = false;
        Response r = resource.updateConfiguration(replacing());
        assertEquals(Response.PERMISSION_DENIED().getMessage(), r.getMessage());
        assertEquals(0, files.reads);
    }
}
