package com.hmdm.rest.resource.support;

import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.Application;
import com.hmdm.persistence.domain.Configuration;
import com.hmdm.persistence.domain.Customer;
import com.hmdm.persistence.domain.Device;
import com.hmdm.persistence.domain.ManagedAppPermission;
import com.hmdm.persistence.domain.UploadedFile;
import com.hmdm.rest.json.agent.DesiredConfig;
import com.hmdm.rest.json.agent.DesiredKioskBackgroundImage;
import com.hmdm.testsupport.Fakes;
import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

/**
 * The kiosk background image URL is built from the server's current base URL when the configuration is sent, not
 * taken from the value stored when the image was picked: an install moved to HTTPS or to a new domain keeps showing
 * the image without anyone picking it again. Configurations saved before this keep working unchanged.
 */
public class ConfigReconcilerBackgroundUrlTest {
    private static final String SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String STORED = "http://old.example:8080/files/acme/kiosk-0123456789ab-beach.jpg";

    private static final class Dao extends UnsecureDAO {
        Configuration configuration;
        UploadedFile file;
        Customer customer;
        int fileReads;

        Dao() {
            super(null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                    "/tmp", 0, "com.mdmesh.agent");
        }

        @Override public Configuration getConfigurationById(Integer id) { return configuration; }
        @Override public List<Application> getPlainConfigurationAppsOptimized(Integer id) {
            return Collections.emptyList();
        }
        @Override public List<ManagedAppPermission> getManagedAppPermissions(Integer id) { return null; }
        @Override public UploadedFile getUploadedFileById(Integer id) {
            fileReads++;
            return file != null && id.equals(file.getId()) ? file : null;
        }
        @Override public Customer getCustomerByIdUnsecure(Integer id) {
            return customer != null && id.equals(customer.getId()) ? customer : null;
        }
    }

    private static Dao dao() {
        Dao dao = new Dao();
        Configuration c = new Configuration();
        c.setId(7); c.setCustomerId(1); c.setKioskMode(true);
        c.setBackgroundImageFileId(42); c.setBackgroundImageUrl(STORED); c.setBackgroundImageSha256(SHA);
        dao.configuration = c;
        UploadedFile f = new UploadedFile();
        f.setId(42); f.setCustomerId(1); f.setFilePath("kiosk-0123456789ab-beach.jpg"); f.setExternal(false);
        dao.file = f;
        Customer customer = new Customer(); customer.setId(1); customer.setFilesDir("acme");
        dao.customer = customer;
        return dao;
    }

    private static DesiredKioskBackgroundImage sentImage(Dao dao, String baseUrl) {
        ConfigReconciler r = new ConfigReconciler(dao, null, Fakes.noExtensionPoints(),
                new KioskBackgroundImageUrls(dao, baseUrl));
        Device device = new Device(); device.setConfigurationId(7);
        DesiredConfig doc = r.currentDocument(device);
        return doc.getKiosk().getTheme().getBackgroundImage();
    }

    @Test
    public void theUrlIsBuiltFromTheCurrentBaseUrlWhenSent() {
        DesiredKioskBackgroundImage image = sentImage(dao(), "https://mdm.example");
        assertEquals("https://mdm.example/files/acme/kiosk-0123456789ab-beach.jpg", image.getUrl());
        assertEquals(SHA, image.getSha256());
    }

    @Test
    public void aBaseUrlChangeChangesTheRevisionSoDevicesGetTheNewUrl() {
        ConfigReconciler before = new ConfigReconciler(dao(), null, Fakes.noExtensionPoints(),
                new KioskBackgroundImageUrls(dao(), "http://old.example:8080"));
        ConfigReconciler after = new ConfigReconciler(dao(), null, Fakes.noExtensionPoints(),
                new KioskBackgroundImageUrls(dao(), "https://mdm.example"));
        Device device = new Device(); device.setConfigurationId(7);
        assertNotEquals(before.currentDocument(device).getRevision(), after.currentDocument(device).getRevision());
    }

    /** Upgrading must not resend every kiosk configuration: same base URL, same document, same revision. */
    @Test
    public void anUnchangedBaseUrlSendsWhatWasStored() {
        Dao dao = dao();
        String withResolver = new ConfigReconciler(dao, null, Fakes.noExtensionPoints(),
                new KioskBackgroundImageUrls(dao, "http://old.example:8080"))
                .currentDocument(device()).getRevision();
        String storedOnly = new ConfigReconciler(dao(), null, Fakes.noExtensionPoints())
                .currentDocument(device()).getRevision();
        assertEquals(storedOnly, withResolver);
    }

    @Test
    public void aMissingFileRowKeepsTheStoredUrl() {
        Dao dao = dao();
        dao.file = null;
        assertEquals(STORED, sentImage(dao, "https://mdm.example").getUrl());
    }

    @Test
    public void anExternalFileKeepsTheStoredUrl() {
        Dao dao = dao();
        dao.file.setExternal(true);
        dao.file.setExternalUrl("https://cdn.example/x.jpg");
        assertEquals(STORED, sentImage(dao, "https://mdm.example").getUrl());
    }

    @Test
    public void anUnknownCustomerKeepsTheStoredUrl() {
        Dao dao = dao();
        dao.customer = null;
        assertEquals(STORED, sentImage(dao, "https://mdm.example").getUrl());
    }

    @Test
    public void aLegacyWallpaperUrlIsNeitherResolvedNorSent() {
        Dao dao = dao();
        dao.configuration.setBackgroundImageFileId(null);
        dao.configuration.setBackgroundImageSha256(null);
        assertNull(sentImage(dao, "https://mdm.example"));
        assertEquals(0, dao.fileReads);
    }

    @Test
    public void aNewImageWithoutAStoredUrlIsStillSent() {
        Dao dao = dao();
        dao.configuration.setBackgroundImageUrl(null);
        assertEquals("https://mdm.example/files/acme/kiosk-0123456789ab-beach.jpg",
                sentImage(dao, "https://mdm.example").getUrl());
    }

    @Test
    public void aTrailingSlashInTheBaseUrlIsNotDoubled() {
        assertEquals("https://mdm.example/files/acme/kiosk-0123456789ab-beach.jpg",
                sentImage(dao(), "https://mdm.example/").getUrl());
    }

    /** A base.url ending in a slash stored "//files" URLs: built again they are the same address, not a new revision. */
    @Test
    public void aTrailingSlashBaseUrlKeepsTheStoredUrlAndRevision() {
        Dao dao = dao();
        dao.configuration.setBackgroundImageUrl("https://mdm.example//files/acme/kiosk-0123456789ab-beach.jpg");
        String withResolver = new ConfigReconciler(dao, null, Fakes.noExtensionPoints(),
                new KioskBackgroundImageUrls(dao, "https://mdm.example/")).currentDocument(device()).getRevision();
        Dao storedOnly = dao();
        storedOnly.configuration.setBackgroundImageUrl("https://mdm.example//files/acme/kiosk-0123456789ab-beach.jpg");
        assertEquals(new ConfigReconciler(storedOnly, null, Fakes.noExtensionPoints()).currentDocument(device()).getRevision(),
                withResolver);
    }

    private static Device device() {
        Device device = new Device(); device.setConfigurationId(7);
        return device;
    }
}
