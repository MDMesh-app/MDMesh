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
        Configuration stored;
        final List<Configuration> updated = new ArrayList<>();
        FakeConfigurationDAO() {
            super(null, null, null, null, "https://mdm.example.com", System.getProperty("java.io.tmpdir"), null);
        }
        @Override public Configuration getConfigurationByName(String name) { return null; }
        @Override public boolean hasConfigurationAccess(int id) { return access; }
        @Override public void updateConfiguration(Configuration c) { updated.add(c); }
        @Override public Configuration getConfigurationById(Integer id) { return stored != null ? stored : new Configuration(); }
        @Override public Configuration getConfigurationByIdFull(Integer id) { return stored != null ? stored : new Configuration(); }
        final List<Configuration> inserted = new ArrayList<>();
        @Override public void insertConfiguration(Configuration c) { inserted.add(c); }
        @Override public List<Application> getPlainConfigurationApplications(Integer id) { return new ArrayList<>(); }
    }

    private static final class ForeignFiles extends UploadedFileDAO {
        int reads;
        boolean deleted;
        ForeignFiles() { super(null, null, null, "unused"); }
        @Override public UploadedFile getById(Integer id) {
            reads++;
            if (deleted) return null;
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
    public void describingAnImageNeedsTheConfigurationsPermission() {
        SecurityContext.release();
        SecurityContext.init(Fakes.user(5, 1, "devices"));
        Response r = resource.getBackgroundImage(42);
        assertEquals(Response.PERMISSION_DENIED().getMessage(), r.getMessage());
        assertEquals(0, files.reads);
    }

    @Test
    public void describingAnotherCustomersFileIsAnErrorWithAReadableMessage() {
        Response r = resource.getBackgroundImage(42);
        assertEquals(Response.ResponseStatus.ERROR, r.getStatus());
        assertTrue(r.getMessage(), r.getMessage().startsWith("The background image"));
    }

    private static Configuration plain() {
        Configuration c = new Configuration();
        c.setId(3);
        c.setName("lobby");
        c.setApplications(new ArrayList<>());
        return c;
    }

    private void assertBadRequest(Configuration c, String message) {
        try {
            resource.updateConfiguration(c);
            fail("expected HTTP 400");
        } catch (BadRequestException e) {
            assertEquals(400, e.getResponse().getStatus());
            assertTrue(((Response) e.getResponse().getEntity()).getMessage().contains(message));
        }
        assertTrue("nothing is stored", dao.updated.isEmpty());
    }

    @Test
    public void theGalleryNeedsTheConfigurationsPermission() {
        SecurityContext.release();
        SecurityContext.init(Fakes.user(5, 1, "devices"));
        assertEquals(Response.PERMISSION_DENIED().getMessage(), resource.getBackgroundImages(0, 20).getMessage());
    }

    @Test
    public void deletingFromTheGalleryAlsoNeedsEditFiles() {
        Response r = resource.deleteBackgroundImage(42);
        assertEquals(Response.PERMISSION_DENIED().getMessage(), r.getMessage());
        assertEquals(0, files.reads);
    }

    @Test
    public void deletingAnotherCustomersImageIsRefused() {
        SecurityContext.release();
        SecurityContext.init(Fakes.user(5, 1, "configurations", "edit_files"));
        Response r = resource.deleteBackgroundImage(42);
        assertEquals(Response.ResponseStatus.ERROR, r.getStatus());
        assertTrue(r.getMessage(), r.getMessage().startsWith("The background image"));
    }

    /** A copy holds the source's image until written; one deleted meanwhile is refused with a clear message. */
    @Test
    public void copyingAConfigurationWhoseImageWasDeletedIsRefused() {
        files.deleted = true;
        SecurityContext.release();
        SecurityContext.init(Fakes.user(5, 1, "copy_config"));
        dao.stored = plain();
        dao.stored.setBackgroundImageFileId(42);
        Configuration request = new Configuration();
        request.setId(3);
        request.setName("lobby copy");
        Response r = resource.copyConfiguration(request);
        assertEquals(Response.ResponseStatus.ERROR, r.getStatus());
        assertEquals(KioskBackgroundImageAssets.COPY_SOURCE_DELETED, r.getMessage());
        assertTrue(dao.inserted.isEmpty());
    }

    /** A configuration copied into this account from the master's template uses the master's image: copy still works. */
    @Test
    public void copyingAConfigurationThatUsesTheMasterAccountsImageWorks() {
        SecurityContext.release();
        SecurityContext.init(Fakes.user(5, 1, "copy_config"));
        dao.stored = plain();
        dao.stored.setBackgroundImageFileId(42);    // ForeignFiles: another customer's file
        Configuration request = new Configuration();
        request.setId(3);
        request.setName("lobby copy");
        Response r = resource.copyConfiguration(request);
        assertEquals(Response.ResponseStatus.OK, r.getStatus());
        assertEquals(Integer.valueOf(42), dao.inserted.get(0).getBackgroundImageFileId());
    }

    @Test
    public void fitMustBeFillOrFit() {
        Configuration c = plain();
        c.setBackgroundImageFit("stretch");
        assertBadRequest(c, "backgroundImageFit");
    }

    @Test
    public void focalPointMustBeWithinTheImage() {
        for (double bad : new double[]{-0.01, 1.01, Double.NaN, Double.POSITIVE_INFINITY}) {
            Configuration c = plain();
            c.setBackgroundImageFocalX(bad);
            assertBadRequest(c, "backgroundImageFocal");
            Configuration d = plain();
            d.setBackgroundImageFocalY(bad);
            assertBadRequest(d, "backgroundImageFocal");
        }
    }

    @Test
    public void anUpdateWithoutFitOrFocalKeepsTheStoredOnes() {
        dao.stored = plain();
        dao.stored.setBackgroundImageFit("fit");
        dao.stored.setBackgroundImageFocalX(0.25);
        dao.stored.setBackgroundImageFocalY(0.75);
        resource.updateConfiguration(plain());
        Configuration saved = dao.updated.get(0);
        assertEquals("fit", saved.getBackgroundImageFit());
        assertEquals(Double.valueOf(0.25), saved.getBackgroundImageFocalX());
        assertEquals(Double.valueOf(0.75), saved.getBackgroundImageFocalY());
    }

    @Test
    public void aSaveSendingOneFocalCoordinateKeepsTheOther() {
        dao.stored = plain();
        dao.stored.setBackgroundImageFocalX(0.25);
        dao.stored.setBackgroundImageFocalY(0.75);
        Configuration c = plain();
        c.setBackgroundImageFocalX(0.1);
        resource.updateConfiguration(c);
        Configuration saved = dao.updated.get(0);
        assertEquals(Double.valueOf(0.1), saved.getBackgroundImageFocalX());
        assertEquals(Double.valueOf(0.75), saved.getBackgroundImageFocalY());
    }

    @Test
    public void validFitAndFocalAreSaved() {
        Configuration c = plain();
        c.setBackgroundImageFit("fill");
        c.setBackgroundImageFocalX(0.0);
        c.setBackgroundImageFocalY(1.0);
        resource.updateConfiguration(c);
        Configuration saved = dao.updated.get(0);
        assertEquals("fill", saved.getBackgroundImageFit());
        assertEquals(Double.valueOf(0.0), saved.getBackgroundImageFocalX());
        assertEquals(Double.valueOf(1.0), saved.getBackgroundImageFocalY());
    }

    @Test
    public void aCopyKeepsFitAndFocal() {
        Configuration c = plain();
        c.setBackgroundImageFit("fit");
        c.setBackgroundImageFocalX(0.1);
        c.setBackgroundImageFocalY(0.2);
        Configuration copy = c.newCopy();
        assertEquals("fit", copy.getBackgroundImageFit());
        assertEquals(Double.valueOf(0.1), copy.getBackgroundImageFocalX());
        assertEquals(Double.valueOf(0.2), copy.getBackgroundImageFocalY());
    }

    @Test
    public void noFileIsReadForAUserWithoutAccessToTheConfiguration() {
        dao.access = false;
        Response r = resource.updateConfiguration(replacing());
        assertEquals(Response.PERMISSION_DENIED().getMessage(), r.getMessage());
        assertEquals(0, files.reads);
    }
}
