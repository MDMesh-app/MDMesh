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
        @Override public void updateConfiguration(Configuration c) { updated.add(c); }
        @Override public Configuration getConfigurationById(Integer id) { return stored != null ? stored : new Configuration(); }
        @Override public Configuration getConfigurationByIdFull(Integer id) { return stored != null ? stored : new Configuration(); }
        final List<Configuration> inserted = new ArrayList<>();
        @Override public void insertConfiguration(Configuration c) { inserted.add(c); }
        @Override public List<Application> getPlainConfigurationApplications(Integer id) { return new ArrayList<>(); }
        /** Configurations of the account with a managed image. */
        final List<Configuration> uses = new ArrayList<>();
        /** Of those, the ones the user may open. */
        final List<Configuration> accessibleUses = new ArrayList<>();
        int accessChecks;
        @Override public List<Configuration> getBackgroundImageUses() { return uses; }
        @Override public List<Configuration> getAccessibleBackgroundImageUses() { return accessibleUses; }
        @Override public boolean hasConfigurationAccess(int id) { accessChecks++; return access; }
    }

    private static final class ForeignFiles extends UploadedFileDAO {
        int reads;
        boolean deleted;
        ForeignFiles() { super(null, null, null, "unused"); }
        /** The main account's file 42, hosted as main/kiosk-dddddddddddd-x.png. */
        UploadedFile foreign;
        @Override public UploadedFile getByIdUnsecure(Integer id) { return foreign != null && foreign.getId().equals(id) ? foreign : null; }
        @Override public List<UploadedFile> getAll() { return new ArrayList<>(); }
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
                new CustomerDAO(null, null, null, null, null, null, null, "unused", 0, null) {
                    @Override public com.hmdm.persistence.domain.Customer findById(int id) {
                        com.hmdm.persistence.domain.Customer c = new com.hmdm.persistence.domain.Customer();
                        c.setId(id);
                        c.setFilesDir("acme");
                        return c;
                    }
                },
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

    /** A page past the end is an empty page, even where page * size is beyond int (it was HTTP 500). */
    @Test
    public void aGalleryPageFarPastTheEndIsEmpty() {
        Response r = resource.getBackgroundImages(21474837, 100);
        assertEquals(Response.ResponseStatus.OK, r.getStatus());
        assertTrue(((KioskBackgroundImageAssets.GalleryPage) r.getData()).getItems().isEmpty());
    }

    /** Which uses the user may not see comes from one query, not one access check per configuration. */
    @Test
    public void theGalleryChecksAccessOnceNotPerConfiguration() {
        for (int i = 1; i <= 3; i++) {
            Configuration c = plain();
            c.setId(i);
            c.setName("c" + i);
            c.setBackgroundImageFileId(40 + i);
            dao.uses.add(c);
            if (i != 2) dao.accessibleUses.add(c);
        }
        assertEquals(Response.ResponseStatus.OK, resource.getBackgroundImages(0, 20).getStatus());
        assertEquals(0, dao.accessChecks);
    }

    /**
     * A configuration copied from the main account's template uses that account's image: its editor gets the image's
     * facts (Fill/Fit, focal point and the preview), not "not an uploaded file of this account".
     */
    @Test
    public void describingTheMainAccountsImageThatAConfigurationOfTheUserUsesWorks() throws Exception {
        java.io.File root = java.nio.file.Files.createTempDirectory("kbg-main").toFile();
        java.io.File dir = new java.io.File(root, "main");
        dir.mkdirs();
        byte[] png = java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAEElEQVR4nGMQUDAAIgYIBQAKDgGBVjPu8wAAAABJRU5ErkJggg==");
        java.nio.file.Files.write(new java.io.File(dir, "kiosk-dddddddddddd-x.png").toPath(), png);
        try {
            UploadedFile f = new UploadedFile();
            f.setId(42);
            f.setCustomerId(2);
            f.setFilePath("kiosk-dddddddddddd-x.png");
            f.setExternal(false);
            files.foreign = f;
            CustomerDAO customers = new CustomerDAO(null, null, null, null, null, null, null, "unused", 0, null) {
                @Override public com.hmdm.persistence.domain.Customer findById(int id) {
                    com.hmdm.persistence.domain.Customer c = new com.hmdm.persistence.domain.Customer();
                    c.setId(id);
                    c.setFilesDir(id == 2 ? "main" : "acme");
                    return c;
                }
            };
            KioskBackgroundImageAssets assets = new KioskBackgroundImageAssets(files, customers,
                    "https://mdm.example.com", root.getAbsolutePath());
            ConfigurationResource r = new ConfigurationResource(dao, null, new PushService(null, null, null, null, false),
                    null, null, "https://mdm.example.com", Fakes.extensionPoints(Collections.emptySet(), Collections.emptySet()),
                    assets);
            // Not used by a configuration the user may open: unknown, as before.
            Configuration hidden = plain();
            hidden.setBackgroundImageFileId(42);
            dao.uses.add(hidden);
            assertEquals(Response.ResponseStatus.ERROR, r.getBackgroundImage(42).getStatus());
            dao.accessibleUses.add(hidden);
            Response described = r.getBackgroundImage(42);
            assertEquals(described.getMessage(), Response.ResponseStatus.OK, described.getStatus());
            com.hmdm.rest.resource.support.KioskBackgroundImageInfo info =
                    (com.hmdm.rest.resource.support.KioskBackgroundImageInfo) described.getData();
            assertEquals("https://mdm.example.com/files/main/kiosk-dddddddddddd-x.png", info.getUrl());
            assertEquals(2, info.getWidth());
            assertTrue(info.getFromAnotherAccount());
        } finally {
            org.apache.commons.io.FileUtils.deleteDirectory(root);
        }
    }

    private ConfigurationResource withoutImageAssets() {
        return new ConfigurationResource(dao, null, new PushService(null, null, null, null, false), null, null,
                "https://mdm.example.com", Fakes.extensionPoints(Collections.emptySet(), Collections.emptySet()), null);
    }

    /** Another image starts at the centre unless the client says where: the old image's focal point is for the old one. */
    @Test
    public void replacingTheImageWithoutAFocalPointCentresIt() {
        dao.stored = plain();
        dao.stored.setBackgroundImageFit("fit");
        dao.stored.setBackgroundImageFocalX(0.25);
        dao.stored.setBackgroundImageFocalY(0.75);
        withoutImageAssets().updateConfiguration(replacing());
        Configuration saved = dao.updated.get(0);
        assertEquals("Fill or Fit stays as chosen", "fit", saved.getBackgroundImageFit());
        assertEquals(null, saved.getBackgroundImageFocalX());
        assertEquals(null, saved.getBackgroundImageFocalY());
    }

    @Test
    public void replacingTheImageWithAFocalPointSavesIt() {
        dao.stored = plain();
        dao.stored.setBackgroundImageFocalX(0.25);
        dao.stored.setBackgroundImageFocalY(0.75);
        Configuration c = replacing();
        c.setBackgroundImageFocalX(0.1);
        withoutImageAssets().updateConfiguration(c);
        Configuration saved = dao.updated.get(0);
        assertEquals(Double.valueOf(0.1), saved.getBackgroundImageFocalX());
        assertEquals("one coordinate given: the other is the centre's", null, saved.getBackgroundImageFocalY());
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
