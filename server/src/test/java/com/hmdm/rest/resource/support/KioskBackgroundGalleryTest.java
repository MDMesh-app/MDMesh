package com.hmdm.rest.resource.support;

import com.hmdm.persistence.CustomerDAO;
import com.hmdm.persistence.UploadedFileDAO;
import com.hmdm.persistence.domain.Customer;
import com.hmdm.persistence.domain.UploadedFile;
import com.hmdm.security.SecurityContext;
import com.hmdm.security.SecurityException;
import com.hmdm.testsupport.Fakes;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** The console's "Choose from uploaded images": the customer's kiosk images, once per content, and safe deletion. */
public class KioskBackgroundGalleryTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private File tenant;
    private Uploaded files;
    private KioskBackgroundImageAssets assets;

    private static final class Uploaded extends UploadedFileDAO {
        final Map<Integer, UploadedFile> byId = new HashMap<>();
        final List<Integer> removed = new ArrayList<>();
        Uploaded() { super(null, null, null, "unused"); }
        @Override public UploadedFile getById(Integer id) {
            UploadedFile f = byId.get(id);
            if (f != null && f.getCustomerId() != 1) throw SecurityException.onAdminDataAccessViolation("foreign");
            return f;
        }
        @Override public List<UploadedFile> getAll() {
            List<UploadedFile> out = new ArrayList<>();
            for (UploadedFile f : byId.values()) if (f.getCustomerId() == 1 && !removed.contains(f.getId())) out.add(f);
            return out;
        }
        @Override public UploadedFile getByPath(Integer customerId, String path) {
            for (UploadedFile f : byId.values()) {
                if (!removed.contains(f.getId()) && f.getCustomerId() == customerId && f.getFilePath().equals(path)) return f;
            }
            return null;
        }
        @Override public void remove(int fileId) { removed.add(fileId); }
    }

    private static final class Customers extends CustomerDAO {
        Customers() { super(null, null, null, null, null, null, null, "unused", 0, null); }
        @Override public Customer findById(int id) {
            Customer c = new Customer(); c.setId(1); c.setFilesDir("acme");
            return c;
        }
    }

    @Before
    public void setUp() throws Exception {
        File root = temp.newFolder("files");
        tenant = new File(root, "acme");
        tenant.mkdirs();
        files = new Uploaded();
        assets = new KioskBackgroundImageAssets(files, new Customers(), "https://mdm.example", root.getAbsolutePath());
        SecurityContext.init(Fakes.user(1, 1, "configurations", "edit_files"));
    }

    @After public void tearDown() { SecurityContext.release(); }

    private void host(int id, String path, byte[] bytes, long uploadTime, int customer) throws Exception {
        Files.write(new File(tenant, path).toPath(), bytes);
        UploadedFile f = new UploadedFile();
        f.setId(id); f.setCustomerId(customer); f.setFilePath(path); f.setExternal(false); f.setUploadTime(uploadTime);
        files.byId.put(id, f);
    }

    private static KioskBackgroundImageAssets.Use use(int id, String name) {
        KioskBackgroundImageAssets.Use u = new KioskBackgroundImageAssets.Use();
        u.setId(id); u.setName(name);
        return u;
    }

    @Test
    public void listsTheCustomersKioskImagesOncePerContentNewestFirstWithTheirUses() throws Exception {
        byte[] a = KioskImageMetadataTest.image("jpg", 40, 30);
        byte[] b = KioskImageMetadataTest.image("png", 20, 10);
        host(1, "kiosk-aaaaaaaaaaaa-beach.jpg", a, 1000, 1);
        host(2, "kiosk-bbbbbbbbbbbb-beach-again.jpg", a, 3000, 1);        // same bytes: one entry
        host(3, "kiosk-cccccccccccc-logo.png", b, 2000, 1);
        host(4, "manual.png", b, 4000, 1);                                 // not a kiosk name, but used by a configuration
        host(5, "notes.txt", "hello".getBytes(StandardCharsets.UTF_8), 5000, 1);
        host(6, "kiosk-dddddddddddd-x.png", b, 6000, 2);                   // another customer's

        Map<Integer, List<KioskBackgroundImageAssets.Use>> uses = new HashMap<>();
        uses.put(1, Collections.singletonList(use(10, "Lobby")));
        uses.put(4, Arrays.asList(use(11, "Café"), use(12, "Hidden")));

        KioskBackgroundImageAssets.GalleryPage page = assets.gallery(uses, Collections.singleton(12), 0, 10);
        assertEquals(2, page.getTotal());
        List<KioskBackgroundImageAssets.GalleryItem> items = page.getItems();
        // Newest first by the newest upload of each content: b (4000), a (3000).
        assertEquals(Arrays.asList(3, 4), sorted(items.get(0).getFileIds()));
        assertEquals(Integer.valueOf(4), items.get(0).getFileId());        // the one in use is the one to pick
        assertEquals(Collections.singletonList("Café"), names(items.get(0).getUsedBy()));
        assertEquals(2, items.get(0).getUsedByCount());                    // one more the user can't see
        assertEquals(sorted(Arrays.asList(1, 2)), sorted(items.get(1).getFileIds()));
        assertEquals(Integer.valueOf(1), items.get(1).getFileId());
        assertEquals("beach.jpg", items.get(1).getName());
        assertEquals(40, items.get(1).getWidth());
    }

    @Test
    public void pages() throws Exception {
        for (int i = 0; i < 5; i++) host(20 + i, "kiosk-eeeeeeeeeee" + i + "-p" + i + ".png", KioskImageMetadataTest.image("png", 2 + i, 2), 100 + i, 1);
        KioskBackgroundImageAssets.GalleryPage first = assets.gallery(Collections.emptyMap(), Collections.emptySet(), 0, 2);
        KioskBackgroundImageAssets.GalleryPage third = assets.gallery(Collections.emptyMap(), Collections.emptySet(), 2, 2);
        assertEquals(5, first.getTotal());
        assertEquals(2, first.getItems().size());
        assertEquals(1, third.getItems().size());
        assertEquals(Integer.valueOf(24), first.getItems().get(0).getFileId());
    }

    @Test
    public void deletesAnUnusedImage() throws Exception {
        host(1, "kiosk-aaaaaaaaaaaa-beach.jpg", KioskImageMetadataTest.image("jpg", 4, 4), 1, 1);
        assets.deleteUnused(1, id -> false);
        assertEquals(Collections.singletonList(1), files.removed);
        assertFalse(new File(tenant, "kiosk-aaaaaaaaaaaa-beach.jpg").exists());
    }

    @Test
    public void refusesToDeleteAnImageInUse() throws Exception {
        host(1, "kiosk-aaaaaaaaaaaa-beach.jpg", KioskImageMetadataTest.image("jpg", 4, 4), 1, 1);
        try {
            assets.deleteUnused(1, id -> id == 1);
            fail("expected a refusal");
        } catch (IllegalArgumentException e) {
            assertEquals(KioskBackgroundImageAssets.IN_USE, e.getMessage());
        }
        assertTrue(files.removed.isEmpty());
        assertTrue(new File(tenant, "kiosk-aaaaaaaaaaaa-beach.jpg").exists());
    }

    @Test
    public void refusesAnotherCustomersImage() throws Exception {
        host(6, "kiosk-dddddddddddd-x.png", KioskImageMetadataTest.image("png", 4, 4), 1, 2);
        try {
            assets.deleteUnused(6, id -> false);
            fail("expected a refusal");
        } catch (IllegalArgumentException e) {
            assertEquals(KioskBackgroundImageAssets.NOT_FOUND, e.getMessage());
        }
        assertTrue(files.removed.isEmpty());
    }

    @Test
    public void keepsTheFileWhenAnotherRecordPointsAtIt() throws Exception {
        host(1, "kiosk-aaaaaaaaaaaa-beach.jpg", KioskImageMetadataTest.image("jpg", 4, 4), 1, 1);
        UploadedFile twin = new UploadedFile();
        twin.setId(2); twin.setCustomerId(1); twin.setFilePath("kiosk-aaaaaaaaaaaa-beach.jpg"); twin.setExternal(false);
        files.byId.put(2, twin);
        assets.deleteUnused(1, id -> false);
        assertTrue(new File(tenant, "kiosk-aaaaaaaaaaaa-beach.jpg").exists());
    }

    private static List<Integer> sorted(List<Integer> l) {
        List<Integer> c = new ArrayList<>(l);
        Collections.sort(c);
        return c;
    }

    private static List<String> names(List<KioskBackgroundImageAssets.Use> uses) {
        List<String> out = new ArrayList<>();
        for (KioskBackgroundImageAssets.Use u : uses) out.add(u.getName());
        return out;
    }
}
