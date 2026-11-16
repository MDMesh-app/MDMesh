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
    private File main;
    private Uploaded files;
    private KioskBackgroundImageAssets assets;

    private static final class Uploaded extends UploadedFileDAO {
        final Map<Integer, UploadedFile> byId = new HashMap<>();
        final List<Integer> removed = new ArrayList<>();
        Uploaded() { super(null, null, null, "unused"); }
        int byIdReads;
        @Override public UploadedFile getById(Integer id) {
            byIdReads++;
            UploadedFile f = byId.get(id);
            if (f != null && f.getCustomerId() != 1) throw SecurityException.onAdminDataAccessViolation("foreign");
            return f == null || removed.contains(id) ? null : f;
        }
        @Override public UploadedFile getByIdUnsecure(Integer id) {
            UploadedFile f = byId.get(id);
            return f == null || removed.contains(id) ? null : f;
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
        int insertIds = 100;
        @Override public UploadedFile insert(UploadedFile f) {
            f.setId(insertIds++);
            byId.put(f.getId(), f);
            return f;
        }
    }

    private static final class Customers extends CustomerDAO {
        int reads;
        Customers() { super(null, null, null, null, null, null, null, "unused", 0, null); }
        @Override public Customer findById(int id) {
            reads++;
            // 1: the user's account; 2: the main (master) account, whose files are under "main".
            Customer c = new Customer(); c.setId(id); c.setFilesDir(id == 2 ? "main" : "acme"); c.setMaster(id == 2);
            return c;
        }
    }

    private Customers customers;

    @Before
    public void setUp() throws Exception {
        File root = temp.newFolder("files");
        tenant = new File(root, "acme");
        tenant.mkdirs();
        files = new Uploaded();
        customers = new Customers();
        main = new File(root, "main");
        main.mkdirs();
        assets = new KioskBackgroundImageAssets(files, customers, "https://mdm.example", root.getAbsolutePath());
        SecurityContext.init(Fakes.user(1, 1, "configurations", "edit_files"));
    }

    @After public void tearDown() { SecurityContext.release(); }

    private void host(int id, String path, byte[] bytes, long uploadTime, int customer) throws Exception {
        Files.write(new File(customer == 2 ? main : tenant, path).toPath(), bytes);
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

    /** A save that has chosen a file holds it until it is committed or fails: a gallery delete meanwhile is refused. */
    @Test
    public void refusesToDeleteAnImageASaveIsUsing() throws Exception {
        host(1, "kiosk-aaaaaaaaaaaa-beach.jpg", KioskImageMetadataTest.image("jpg", 4, 4), 1, 1);
        com.hmdm.persistence.domain.Configuration c = new com.hmdm.persistence.domain.Configuration();
        c.setBackgroundImageAction("replace");
        c.setBackgroundImageFileId(1);
        KioskBackgroundImageAssets.Applied save = assets.apply(c, null);
        try {
            assets.deleteUnused(1, id -> false);
            fail("expected a refusal");
        } catch (IllegalArgumentException e) {
            assertEquals(KioskBackgroundImageAssets.IN_USE, e.getMessage());
        }
        assertTrue(files.removed.isEmpty());
        save.release();
        // Committed (the configuration now references it) or not, the save no longer holds it.
        assets.deleteUnused(1, id -> false);
        assertEquals(Collections.singletonList(1), files.removed);
    }

    /** A delete that wins the race: the save that chose the file fails cleanly instead of committing a dangling id. */
    @Test
    public void aSaveOfAnImageDeletedMeanwhileFailsCleanly() throws Exception {
        host(1, "kiosk-aaaaaaaaaaaa-beach.jpg", KioskImageMetadataTest.image("jpg", 4, 4), 1, 1);
        assets.beforeHold = () -> {
            try {
                assets.deleteUnused(1, id -> false);
            } catch (IllegalArgumentException e) {
                throw new AssertionError(e);
            }
        };
        com.hmdm.persistence.domain.Configuration c = new com.hmdm.persistence.domain.Configuration();
        c.setBackgroundImageAction("replace");
        c.setBackgroundImageFileId(1);
        try {
            assets.apply(c, null);
            fail("expected a refusal");
        } catch (IllegalArgumentException e) {
            assertEquals(KioskBackgroundImageAssets.DELETED, e.getMessage());
        }
    }

    /** A failed save never undoes a copy another save is using, even before that one commits. */
    @Test
    public void undoNeverRemovesACopyASaveIsUsing() throws Exception {
        host(1, "photo.jpg", KioskImageMetadataTest.phoneJpeg(KioskImageMetadataTest.image("jpg", 8, 8), 1), 1, 1);
        files.insertIds = 100;
        com.hmdm.persistence.domain.Configuration a = new com.hmdm.persistence.domain.Configuration();
        a.setBackgroundImageAction("replace");
        a.setBackgroundImageFileId(1);
        KioskBackgroundImageAssets.Applied saveA = assets.apply(a, null);
        int copy = a.getBackgroundImageFileId();
        // B picks the copy from the gallery (a clean file: no copy of its own) and is still saving.
        com.hmdm.persistence.domain.Configuration b = new com.hmdm.persistence.domain.Configuration();
        b.setBackgroundImageAction("replace");
        b.setBackgroundImageFileId(copy);
        KioskBackgroundImageAssets.Applied saveB = assets.apply(b, null);
        saveA.undo(id -> false);
        saveA.release();
        assertFalse(files.removed.contains(copy));
        saveB.release();
    }

    /** The Files page's Remove goes through the same check. */
    @Test
    public void filesRemoveWaitsForNoSaveToHoldTheFile() throws Exception {
        host(1, "kiosk-aaaaaaaaaaaa-beach.jpg", KioskImageMetadataTest.image("jpg", 4, 4), 1, 1);
        com.hmdm.persistence.domain.Configuration c = new com.hmdm.persistence.domain.Configuration();
        c.setBackgroundImageAction("replace");
        c.setBackgroundImageFileId(1);
        KioskBackgroundImageAssets.Applied save = assets.apply(c, null);
        boolean[] ran = {false};
        assertEquals("held", assets.unlessHeld(1, () -> { ran[0] = true; return "removed"; }, "held", "editing"));
        assertFalse(ran[0]);
        save.release();
        save.release(); // idempotent
        assertEquals("removed", assets.unlessHeld(1, () -> { ran[0] = true; return "removed"; }, "held", "editing"));
    }

    private static com.hmdm.persistence.domain.Configuration replace(int fileId) {
        com.hmdm.persistence.domain.Configuration c = new com.hmdm.persistence.domain.Configuration();
        c.setBackgroundImageAction("replace");
        c.setBackgroundImageFileId(fileId);
        return c;
    }

    /** The file is held before its bytes are read and hashed: a content replace can't slip in between. */
    @Test
    public void aSaveHoldsItsFileBeforeReadingIt() throws Exception {
        host(1, "kiosk-aaaaaaaaaaaa-beach.jpg", KioskImageMetadataTest.image("jpg", 4, 4), 1, 1);
        boolean[] heldWhileReading = {false};
        assets.beforeRead = () -> heldWhileReading[0] = assets.isHeld(1);
        assets.apply(replace(1), null).release();
        assertTrue(heldWhileReading[0]);
        assertFalse(assets.isHeld(1));
    }

    /** Hosting a cleaned copy: the copy is held (taken under the lock), the original let go. */
    @Test
    public void aSaveThatHostsACopyHoldsTheCopy() throws Exception {
        host(1, "photo.jpg", KioskImageMetadataTest.phoneJpeg(KioskImageMetadataTest.image("jpg", 8, 8), 1), 1, 1);
        com.hmdm.persistence.domain.Configuration c = replace(1);
        KioskBackgroundImageAssets.Applied save = assets.apply(c, null);
        int copy = c.getBackgroundImageFileId();
        assertTrue(assets.isHeld(copy));
        assertFalse(assets.isHeld(1));
        save.release();
        assertFalse(assets.isHeld(copy));
    }

    /** A save refused after taking hold (not an image) lets go: the file stays deletable without a restart. */
    @Test
    public void aRefusedSaveLetsGoOfItsFile() throws Exception {
        host(1, "kiosk-aaaaaaaaaaaa-notes.png", "hello".getBytes(StandardCharsets.UTF_8), 1, 1);
        try {
            assets.apply(replace(1), null);
            fail("expected a refusal");
        } catch (IllegalArgumentException e) {
            assertEquals(KioskBackgroundImageAssets.NOT_AN_IMAGE, e.getMessage());
        }
        assertFalse(assets.isHeld(1));
    }

    /** An update that keeps its image holds it too, and a copy holds the source's (strict: refused once deleted). */
    @Test
    public void keepingOrCopyingAnImageHoldsIt() throws Exception {
        host(1, "kiosk-aaaaaaaaaaaa-beach.jpg", KioskImageMetadataTest.image("jpg", 4, 4), 1, 1);
        com.hmdm.persistence.domain.Configuration stored = new com.hmdm.persistence.domain.Configuration();
        stored.setBackgroundImageFileId(1);
        stored.setBackgroundImageUrl("https://mdm.example/files/acme/kiosk-aaaaaaaaaaaa-beach.jpg");
        stored.setBackgroundImageSha256("ab");
        KioskBackgroundImageAssets.Applied keep = assets.apply(new com.hmdm.persistence.domain.Configuration(), stored);
        assertTrue(assets.isHeld(1));
        keep.release();
        KioskBackgroundImageAssets.Applied copy = assets.holdExisting(1, true);
        assertTrue(assets.isHeld(1));
        copy.release();
        assertFalse(assets.isHeld(1));
        assets.deleteUnused(1, id -> false);
        try {
            assets.holdExisting(1, true);
            fail("expected a refusal");
        } catch (IllegalArgumentException e) {
            assertEquals(KioskBackgroundImageAssets.DELETED, e.getMessage());
        }
        // Lenient (an update that keeps what it has): no hold, no refusal.
        assets.holdExisting(1, false).release();
    }

    @Test
    public void aSaveOfAnImageDeletedMeanwhileSaysSo() throws Exception {
        host(1, "kiosk-aaaaaaaaaaaa-beach.jpg", KioskImageMetadataTest.image("jpg", 4, 4), 1, 1);
        assets.beforeHold = () -> assets.deleteUnused(1, id -> false);
        try {
            assets.apply(replace(1), null);
            fail("expected a refusal");
        } catch (IllegalArgumentException e) {
            assertEquals(KioskBackgroundImageAssets.DELETED, e.getMessage());
        }
    }

    /** A configuration copied from the master account's template uses the master's file: it can still be copied. */
    @Test
    public void anotherAccountsFileUsedByATemplateCopyIsHeldNotTreatedAsDeleted() throws Exception {
        host(6, "kiosk-dddddddddddd-x.png", KioskImageMetadataTest.image("png", 4, 4), 1, 2);
        KioskBackgroundImageAssets.Applied copy = assets.holdExisting(6, true);
        assertTrue(assets.isHeld(6));
        copy.release();
        assertFalse(assets.isHeld(6));
    }

    /** Keep: a delete between reading the stored configuration and taking the hold refuses the save, never dangles. */
    @Test
    public void keepingAnImageDeletedBeforeTheHoldIsRefused() throws Exception {
        host(1, "kiosk-aaaaaaaaaaaa-beach.jpg", KioskImageMetadataTest.image("jpg", 4, 4), 1, 1);
        com.hmdm.persistence.domain.Configuration stored = new com.hmdm.persistence.domain.Configuration();
        stored.setBackgroundImageFileId(1);
        stored.setBackgroundImageUrl("https://mdm.example/files/acme/kiosk-aaaaaaaaaaaa-beach.jpg");
        stored.setBackgroundImageSha256("ab");
        assets.beforeHold = () -> assets.deleteUnused(1, id -> false);
        try {
            assets.apply(new com.hmdm.persistence.domain.Configuration(), stored);
            fail("expected a refusal");
        } catch (IllegalArgumentException e) {
            assertEquals(KioskBackgroundImageAssets.DELETED, e.getMessage());
        }
        assertFalse(assets.isHeld(1));
    }

    /** Files → edit and saves exclude each other, with only the check under the lock. */
    @Test
    public void filesEditAndSavesExcludeEachOther() throws Exception {
        host(1, "kiosk-aaaaaaaaaaaa-beach.jpg", KioskImageMetadataTest.image("jpg", 4, 4), 1, 1);
        assertEquals(KioskBackgroundImageAssets.EditStart.STARTED, assets.beginEdit(1));
        try {
            assets.apply(replace(1), null);
            fail("expected a refusal");
        } catch (IllegalArgumentException e) {
            assertEquals(KioskBackgroundImageAssets.BEING_EDITED, e.getMessage());
        } finally {
            assets.endEdit(1);
        }
        KioskBackgroundImageAssets.Applied save = assets.apply(replace(1), null);
        assertEquals(KioskBackgroundImageAssets.EditStart.HELD, assets.beginEdit(1));
        save.release();
        assertEquals(KioskBackgroundImageAssets.EditStart.STARTED, assets.beginEdit(1));
        // A second edit of the same file waits for the first, and doesn't end it.
        assertEquals(KioskBackgroundImageAssets.EditStart.EDITING, assets.beginEdit(1));
        assets.endEdit(1);
    }

    /** A gallery delete never removes a file a Files → edit is changing. */
    @Test
    public void refusesToDeleteAnImageBeingEdited() throws Exception {
        host(1, "kiosk-aaaaaaaaaaaa-beach.jpg", KioskImageMetadataTest.image("jpg", 4, 4), 1, 1);
        assertEquals(KioskBackgroundImageAssets.EditStart.STARTED, assets.beginEdit(1));
        try {
            assets.deleteUnused(1, id -> false);
            fail("expected a refusal");
        } catch (IllegalArgumentException e) {
            assertEquals(KioskBackgroundImageAssets.BEING_EDITED, e.getMessage());
        } finally {
            assets.endEdit(1);
        }
        assertTrue(files.removed.isEmpty());
        assertTrue(new File(tenant, "kiosk-aaaaaaaaaaaa-beach.jpg").exists());
        assets.deleteUnused(1, id -> false);
        assertEquals(Collections.singletonList(1), files.removed);
    }

    /** The Files page's Remove waits for a Files → edit of the same file as well. */
    @Test
    public void filesRemoveWaitsForAnEditOfTheFile() throws Exception {
        host(1, "kiosk-aaaaaaaaaaaa-beach.jpg", KioskImageMetadataTest.image("jpg", 4, 4), 1, 1);
        assets.beginEdit(1);
        boolean[] ran = {false};
        assertEquals("editing", assets.unlessHeld(1, () -> { ran[0] = true; return "removed"; }, "held", "editing"));
        assertFalse(ran[0]);
        assets.endEdit(1);
        assertEquals("removed", assets.unlessHeld(1, () -> { ran[0] = true; return "removed"; }, "held", "editing"));
    }

    @Test
    public void deletesOnlyWhatTheGalleryLists() throws Exception {
        host(5, "notes.png", KioskImageMetadataTest.image("png", 4, 4), 1, 1);
        try {
            assets.deleteUnused(5, id -> false);
            fail("expected a refusal");
        } catch (IllegalArgumentException e) {
            assertEquals(KioskBackgroundImageAssets.NOT_A_GALLERY_IMAGE, e.getMessage());
        }
        assertTrue(files.removed.isEmpty());
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

    /**
     * A configuration copied from the main account's template uses the main account's image: its editor reads the
     * image's facts (so it offers Fill/Fit, focal point and the preview) at the URL devices get, built for that account.
     */
    @Test
    public void describesTheMainAccountsImageThatAConfigurationOfTheUserUses() throws Exception {
        host(6, "kiosk-dddddddddddd-x.png", KioskImageMetadataTest.image("png", 40, 30), 7, 2);
        KioskBackgroundImageInfo info = assets.describe(6, id -> id == 6);
        assertEquals(Integer.valueOf(6), info.getFileId());
        assertEquals("https://mdm.example/files/main/kiosk-dddddddddddd-x.png", info.getUrl());
        assertEquals(40, info.getWidth());
        assertEquals(30, info.getHeight());
        assertEquals("x.png", info.getName());
        assertTrue(info.getFromAnotherAccount());
        assertEquals(KioskBackgroundImageAssets.validate(KioskImageMetadataTest.image("png", 40, 30)), info.getSha256());
    }

    /** Only one a configuration of the user's uses: any other file of another account stays unknown. */
    @Test
    public void anotherAccountsImageNoConfigurationOfTheUserUsesIsNotDescribed() throws Exception {
        host(6, "kiosk-dddddddddddd-x.png", KioskImageMetadataTest.image("png", 40, 30), 7, 2);
        for (java.util.function.IntPredicate used : Arrays.<java.util.function.IntPredicate>asList(id -> false, id -> id == 7)) {
            try {
                assets.describe(6, used);
                fail("expected a refusal");
            } catch (IllegalArgumentException e) {
                assertEquals(KioskBackgroundImageAssets.NOT_FOUND, e.getMessage());
            }
        }
        try {
            assets.describe(6);
            fail("expected a refusal");
        } catch (IllegalArgumentException e) {
            assertEquals(KioskBackgroundImageAssets.NOT_FOUND, e.getMessage());
        }
    }

    /** The account's own images aren't marked as another account's. */
    @Test
    public void anOwnImageIsNotFromAnotherAccount() throws Exception {
        host(1, "kiosk-aaaaaaaaaaaa-beach.jpg", KioskImageMetadataTest.image("jpg", 4, 4), 1, 1);
        assertEquals(null, assets.describe(1, id -> true).getFromAnotherAccount());
    }

    /** Still not choosable: a save picking another account's file is refused as before. */
    @Test
    public void anotherAccountsImageCantBeChosen() throws Exception {
        host(6, "kiosk-dddddddddddd-x.png", KioskImageMetadataTest.image("png", 4, 4), 7, 2);
        try {
            assets.apply(replace(6), null);
            fail("expected a refusal");
        } catch (IllegalArgumentException e) {
            assertEquals(KioskBackgroundImageAssets.NOT_FOUND, e.getMessage());
        }
    }

    /** A page past the end, however far (page * size beyond int), is empty, not an error. */
    @Test
    public void aPageFarPastTheEndIsEmpty() throws Exception {
        host(1, "kiosk-aaaaaaaaaaaa-beach.jpg", KioskImageMetadataTest.image("jpg", 4, 4), 1, 1);
        KioskBackgroundImageAssets.GalleryPage far = assets.gallery(Collections.emptyMap(), Collections.emptySet(),
                21474837, 100);
        assertEquals(1, far.getTotal());
        assertTrue(far.getItems().isEmpty());
        KioskBackgroundImageAssets.GalleryPage max = assets.gallery(Collections.emptyMap(), Collections.emptySet(),
                Integer.MAX_VALUE, Integer.MAX_VALUE);
        assertTrue(max.getItems().isEmpty());
    }

    /**
     * Opening the gallery costs no lookup per file: the files come with the list and the account is read once. A file
     * that isn't an image the agent shows is read once per change too, not on every open.
     */
    @Test
    public void theGalleryReadsEachFileOncePerChangeWithoutALookupPerFile() throws Exception {
        for (int i = 0; i < 5; i++) {
            host(20 + i, "kiosk-eeeeeeeeeee" + i + "-p" + i + ".png", KioskImageMetadataTest.image("png", 2 + i, 2), 100 + i, 1);
        }
        host(30, "kiosk-ffffffffffff-notes.png", "not an image".getBytes(StandardCharsets.UTF_8), 1, 1);
        int lookups = files.byIdReads;
        int accounts = customers.reads;
        assertEquals(5, assets.gallery(Collections.emptyMap(), Collections.emptySet(), 0, 20).getTotal());
        assertEquals("no lookup per file", lookups, files.byIdReads);
        assertEquals("the account is read once", accounts + 1, customers.reads);
        int reads = assets.reads();
        assertEquals(6, reads);
        assertEquals(5, assets.gallery(Collections.emptyMap(), Collections.emptySet(), 0, 20).getTotal());
        assertEquals("nothing read again", reads, assets.reads());
        try {
            assets.describe(30);
            fail("expected a refusal");
        } catch (IllegalArgumentException e) {
            assertEquals(KioskBackgroundImageAssets.NOT_AN_IMAGE, e.getMessage());
        }
        assertEquals("the refusal is remembered too", reads, assets.reads());
        // Changed: read again.
        File notes = new File(tenant, "kiosk-ffffffffffff-notes.png");
        Files.write(notes.toPath(), KioskImageMetadataTest.image("png", 9, 9));
        notes.setLastModified(notes.lastModified() + 5000);
        assertEquals(6, assets.gallery(Collections.emptyMap(), Collections.emptySet(), 0, 20).getTotal());
        assertEquals(reads + 1, assets.reads());
    }

    /** A cleaned copy C that an earlier save hosted for the photo at id 1: its id. */
    private int hostedCleanCopy() throws Exception {
        host(1, "photo.jpg", KioskImageMetadataTest.phoneJpeg(KioskImageMetadataTest.image("jpg", 8, 8), 1), 1, 1);
        com.hmdm.persistence.domain.Configuration first = replace(1);
        assets.apply(first, null).release();
        int copy = first.getBackgroundImageFileId();
        assertTrue("a cleaned copy was hosted", copy != 1);
        return copy;
    }

    /**
     * A save of the original never reuses the cleaned copy while a Files → edit is changing it (the edit's file I/O runs
     * outside the lock): it would pin the clean bytes' SHA-256 on a file that is about to hold other bytes or move.
     */
    @Test
    public void aSaveDoesNotReuseACleanedCopyBeingEdited() throws Exception {
        int copy = hostedCleanCopy();
        assertEquals(KioskBackgroundImageAssets.EditStart.STARTED, assets.beginEdit(copy));
        try {
            assets.apply(replace(1), null);
            fail("expected a refusal");
        } catch (IllegalArgumentException e) {
            assertEquals(KioskBackgroundImageAssets.BEING_EDITED, e.getMessage());
        } finally {
            assets.endEdit(copy);
        }
        assertFalse(assets.isHeld(copy));
        assertFalse(assets.isHeld(1));
        // Once the edit is done, the next save finds the copy again.
        com.hmdm.persistence.domain.Configuration again = replace(1);
        assets.apply(again, null).release();
        assertEquals(Integer.valueOf(copy), again.getBackgroundImageFileId());
    }

    /**
     * The same race with the edit's delete first, on two threads: the save must not write the clean bytes into the
     * gap, where the edit's move would then find a file in its way.
     */
    @Test
    public void aSaveDoesNotFillTheGapOfACleanedCopyAnEditDeleted() throws Exception {
        int copy = hostedCleanCopy();
        File hosted = new File(tenant, files.byId.get(copy).getFilePath());
        java.util.concurrent.CountDownLatch deleted = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch saved = new java.util.concurrent.CountDownLatch(1);
        Throwable[] failed = new Throwable[1];
        Thread edit = new Thread(() -> {
            if (assets.beginEdit(copy) != KioskBackgroundImageAssets.EditStart.STARTED) return;
            try {
                // FilesResource.updateFileInternal: the old content goes, then the new one is moved in.
                assertTrue(hosted.delete());
                deleted.countDown();
                assertTrue(saved.await(10, java.util.concurrent.TimeUnit.SECONDS));
                Files.write(hosted.toPath(), KioskImageMetadataTest.image("png", 3, 3), java.nio.file.StandardOpenOption.CREATE_NEW);
            } catch (Throwable e) {
                failed[0] = e;
            } finally {
                assets.endEdit(copy);
            }
        });
        edit.start();
        assertTrue("the edit started", deleted.await(10, java.util.concurrent.TimeUnit.SECONDS));
        try {
            assets.apply(replace(1), null);
            fail("expected a refusal");
        } catch (IllegalArgumentException e) {
            assertEquals(KioskBackgroundImageAssets.BEING_EDITED, e.getMessage());
        } finally {
            assertFalse("nothing was written into the gap", hosted.exists());
            saved.countDown();
            edit.join(10_000);
        }
        assertEquals("the edit's move found no file in its way", null, failed[0]);
        assertFalse(assets.isHeld(copy));
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
