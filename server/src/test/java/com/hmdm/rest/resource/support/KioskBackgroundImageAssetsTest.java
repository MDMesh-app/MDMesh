package com.hmdm.rest.resource.support;

import com.hmdm.persistence.CustomerDAO;
import com.hmdm.persistence.UploadedFileDAO;
import com.hmdm.persistence.domain.Configuration;
import com.hmdm.persistence.domain.Customer;
import com.hmdm.persistence.domain.UploadedFile;
import com.hmdm.security.SecurityContext;
import com.hmdm.testsupport.Fakes;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** The browser supplies only an uploaded-file id; URL and digest always come from server-owned bytes. */
public class KioskBackgroundImageAssetsTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private Customer customer;
    private UploadedFile uploaded;
    private KioskBackgroundImageAssets assets;

    private static final class Files extends UploadedFileDAO {
        UploadedFile file;
        Files() { super(null, null, null, "unused"); }
        @Override public UploadedFile getById(Integer id) { return file; }
    }

    private static final class Customers extends CustomerDAO {
        Customer customer;
        Customers() { super(null, null, null, null, null, null, null, "unused", 0, null); }
        @Override public Customer findById(int id) { return customer; }
    }

    @Before
    public void setUp() throws Exception {
        File root = temp.newFolder("files");
        File tenant = new File(root, "acme");
        tenant.mkdirs();
        java.nio.file.Files.write(new File(tenant, "wallpaper.png").toPath(), "image bytes".getBytes(StandardCharsets.UTF_8));

        customer = new Customer(); customer.setId(1); customer.setFilesDir("acme");
        uploaded = new UploadedFile(); uploaded.setId(42); uploaded.setCustomerId(1);
        uploaded.setFilePath("wallpaper.png"); uploaded.setExternal(false);
        Files files = new Files(); files.file = uploaded;
        Customers customers = new Customers(); customers.customer = customer;
        assets = new KioskBackgroundImageAssets(files, customers,
                new ApkHashResolver(root.getAbsolutePath(), "https://mdm.example"), "https://mdm.example");
        SecurityContext.init(Fakes.user(1, 1, "configurations"));
    }

    @After public void tearDown() { SecurityContext.release(); }

    @Test
    public void canonicalizesTheHostedCustomerFileRatherThanTheBrowsersUrlOrHash() {
        Configuration configuration = new Configuration();
        configuration.setBackgroundImageFileId(42);
        configuration.setBackgroundImageUrl("https://attacker.example/other.png");
        configuration.setBackgroundImageSha256("not-a-hash");

        assets.canonicalize(configuration);

        assertEquals("https://mdm.example/files/acme/wallpaper.png", configuration.getBackgroundImageUrl());
        assertEquals("de7030234493a8bea844dbe1d8676e68a2c1a4b014c721f0425a22b6df66faec",
                configuration.getBackgroundImageSha256());
    }

    @Test
    public void aNullSelectionClearsLegacyUrlAndHash() {
        Configuration configuration = new Configuration();
        configuration.setBackgroundImageUrl("https://legacy.example/image.png");
        configuration.setBackgroundImageSha256("stale");

        assets.canonicalize(configuration);

        assertNull(configuration.getBackgroundImageUrl());
        assertNull(configuration.getBackgroundImageSha256());
    }
}
