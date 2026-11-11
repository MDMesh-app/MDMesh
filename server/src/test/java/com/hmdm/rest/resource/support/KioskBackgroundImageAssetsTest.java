package com.hmdm.rest.resource.support;

import com.hmdm.persistence.CustomerDAO;
import com.hmdm.persistence.UploadedFileDAO;
import com.hmdm.persistence.domain.Configuration;
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

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The browser supplies only an uploaded-file id; URL and digest always come from server-owned bytes, which must be an
 * image the agent will show. The same URL column is also the legacy launcher wallpaper an admin may still set.
 */
public class KioskBackgroundImageAssetsTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private static final String HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String MANAGED_URL = "https://mdm.example/files/acme/old.png";

    private File tenant;
    private byte[] png;
    private UploadedFile uploaded;
    private Files files;
    private Customers customers;
    private KioskBackgroundImageAssets assets;

    private static final class Files extends UploadedFileDAO {
        UploadedFile file;
        boolean foreign;
        int reads;
        final java.util.List<UploadedFile> inserted = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        final java.util.List<Integer> removed = new java.util.ArrayList<>();
        Files() { super(null, null, null, "unused"); }
        @Override public UploadedFile getById(Integer id) {
            reads++;
            if (foreign) throw SecurityException.onAdminDataAccessViolation("read another customer's file");
            return file;
        }
        @Override public UploadedFile insert(UploadedFile f) {
            synchronized (inserted) {
                f.setId(100 + inserted.size());
                Thread.yield();
                inserted.add(f);
            }
            return f;
        }
        @Override public UploadedFile getByPath(Integer customerId, String path) {
            synchronized (inserted) {
                for (UploadedFile f : inserted) if (f.getFilePath().equals(path) && !removed.contains(f.getId())) return f;
            }
            return null;
        }
        @Override public void remove(int fileId) { removed.add(fileId); }
    }

    private static final class Customers extends CustomerDAO {
        Customer customer;
        Customers() { super(null, null, null, null, null, null, null, "unused", 0, null); }
        @Override public Customer findById(int id) { return customer; }
    }

    @Before
    public void setUp() throws Exception {
        File root = temp.newFolder("files");
        tenant = new File(root, "acme");
        tenant.mkdirs();
        png = realImage("png", 4, 3);
        java.nio.file.Files.write(new File(tenant, "wallpaper.png").toPath(), png);

        Customer customer = new Customer(); customer.setId(1); customer.setFilesDir("acme");
        uploaded = new UploadedFile(); uploaded.setId(42); uploaded.setCustomerId(1);
        uploaded.setFilePath("wallpaper.png"); uploaded.setExternal(false);
        files = new Files(); files.file = uploaded;
        customers = new Customers(); customers.customer = customer;
        assets = new KioskBackgroundImageAssets(files, customers, "https://mdm.example", root.getAbsolutePath());
        SecurityContext.init(Fakes.user(1, 1, "configurations"));
    }

    @After public void tearDown() { SecurityContext.release(); }

    static byte[] realImage(String format, int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, format, out)) throw new IllegalStateException("no writer for " + format);
        return out.toByteArray();
    }

    /** A PNG header plus an empty IDAT and IEND: a well-formed container, never decoded here. */
    private static byte[] withPngEnd(byte[] header) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(header);
        o.write(new byte[]{0, 0, 0, 0, 'I', 'D', 'A', 'T', 0x35, (byte) 0xAF, 0x06, 0x1E});
        o.write(new byte[]{0, 0, 0, 0, 'I', 'E', 'N', 'D', (byte) 0xAE, 0x42, 0x60, (byte) 0x82});
        return o.toByteArray();
    }

    private static String sha256(byte[] bytes) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static Configuration managed() {
        Configuration stored = new Configuration();
        stored.setBackgroundImageUrl(MANAGED_URL);
        stored.setBackgroundImageFileId(7);
        stored.setBackgroundImageSha256(HASH);
        return stored;
    }

    private static Configuration legacy(String url) {
        Configuration c = new Configuration();
        c.setBackgroundImageUrl(url);
        return c;
    }

    private static void assertImage(Configuration c, String url, Integer fileId, String sha256) {
        assertEquals(url, c.getBackgroundImageUrl());
        assertEquals(fileId, c.getBackgroundImageFileId());
        assertEquals(sha256, c.getBackgroundImageSha256());
    }

    private void assertRefused(Configuration c, Configuration stored, String message) {
        try {
            assets.apply(c, stored);
            fail("expected a refusal: " + message);
        } catch (IllegalArgumentException e) {
            assertEquals(message, e.getMessage());
        }
    }

    private Configuration replaceWith(String fileName, byte[] bytes) throws IOException {
        java.nio.file.Files.write(new File(tenant, fileName).toPath(), bytes);
        uploaded.setFilePath(fileName);
        Configuration c = new Configuration();
        c.setBackgroundImageAction("replace");
        c.setBackgroundImageFileId(42);
        return c;
    }

    // ---- replace ----------------------------------------------------------------------------------------------------

    @Test
    public void replaceCanonicalizesTheHostedCustomerFileRatherThanTheBrowsersUrlOrHash() throws Exception {
        Configuration configuration = new Configuration();
        configuration.setBackgroundImageAction("replace");
        configuration.setBackgroundImageFileId(42);
        configuration.setBackgroundImageUrl("https://attacker.example/other.png");
        configuration.setBackgroundImageSha256("not-a-hash");

        assets.apply(configuration, managed());

        assertImage(configuration, "https://mdm.example/files/acme/wallpaper.png", 42, sha256(png));
    }

    @Test(expected = IllegalArgumentException.class)
    public void replacingWithoutAnUploadedFileIsRejected() {
        Configuration configuration = new Configuration();
        configuration.setBackgroundImageAction("replace");

        assets.apply(configuration, null);
    }

    @Test
    public void replaceAcceptsAJpeg() throws Exception {
        byte[] jpeg = realImage("jpg", 8, 8);
        Configuration c = replaceWith("photo.jpg", jpeg);
        assets.apply(c, null);
        assertImage(c, "https://mdm.example/files/acme/photo.jpg", 42, sha256(jpeg));
    }

    @Test
    public void replaceRefusesAFileThatIsNotAPngJpegOrWebp() throws Exception {
        String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\"/>";
        assertRefused(replaceWith("logo.svg", svg.getBytes(StandardCharsets.UTF_8)), null,
                KioskBackgroundImageAssets.NOT_AN_IMAGE);
        assertRefused(replaceWith("anim.gif", realImage("gif", 2, 2)), null, KioskBackgroundImageAssets.NOT_AN_IMAGE);
    }

    @Test
    public void replaceRefusesAFileOverTenMegabytes() throws Exception {
        Configuration c = replaceWith("huge.png", png);
        try (RandomAccessFile f = new RandomAccessFile(new File(tenant, "huge.png"), "rw")) {
            f.setLength(KioskBackgroundImageAssets.MAX_BYTES + 1);
        }
        assertRefused(c, null, KioskBackgroundImageAssets.TOO_LARGE);
    }

    @Test
    public void replaceRefusesAnImageOverSixteenMegapixels() throws Exception {
        // A PNG header claiming 4097 x 4096 pixels: nothing is decoded, so the header alone is enough.
        assertRefused(replaceWith("big.png", KioskImageHeaderTest.pngHeader(4097, 4096)), null,
                KioskBackgroundImageAssets.TOO_MANY_PIXELS);
        // Exactly 16 Mpx is allowed (a well-formed PNG container around that header).
        Configuration c = replaceWith("edge.png", withPngEnd(KioskImageHeaderTest.pngHeader(4096, 4096)));
        assets.apply(c, null);
        assertEquals(Integer.valueOf(42), c.getBackgroundImageFileId());
    }

    @Test
    public void replaceRefusesAnotherCustomersFileAsUnknown() {
        files.foreign = true;
        Configuration c = new Configuration();
        c.setBackgroundImageAction("replace");
        c.setBackgroundImageFileId(42);
        assertRefused(c, null, KioskBackgroundImageAssets.NOT_FOUND);
    }

    @Test
    public void replaceRefusesAnUnknownOrExternalFile() {
        files.file = null;
        Configuration c = new Configuration();
        c.setBackgroundImageAction("replace");
        c.setBackgroundImageFileId(42);
        assertRefused(c, null, KioskBackgroundImageAssets.NOT_FOUND);

        files.file = uploaded;
        uploaded.setExternal(true);
        assertRefused(c, null, KioskBackgroundImageAssets.NOT_FOUND);
    }

    @Test
    public void replaceRefusesAFileMissingOnDisk() {
        uploaded.setFilePath("gone.png");
        Configuration c = new Configuration();
        c.setBackgroundImageAction("replace");
        c.setBackgroundImageFileId(42);
        assertRefused(c, null, KioskBackgroundImageAssets.MISSING);
    }

    @Test(expected = IllegalArgumentException.class)
    public void anUnknownActionIsRefused() {
        Configuration c = new Configuration();
        c.setBackgroundImageAction("upload");
        assets.apply(c, null);
    }

    // ---- metadata ---------------------------------------------------------------------------------------------------

    @Test
    public void aPhotoWithMetadataIsHostedAgainWithoutItAndHashedAsCleaned() throws Exception {
        byte[] dirty = KioskImageMetadataTest.phoneJpeg(realImage("jpg", 8, 8), 6);
        byte[] clean = KioskImageMetadata.strip(dirty);
        Configuration c = replaceWith("kiosk-aaaaaaaaaaaa-beach.jpg", dirty);
        uploaded.setDescription("Beach");

        assets.apply(c, null);

        String name = "kiosk-" + sha256(clean).substring(0, 12) + "-beach.jpg";
        assertImage(c, "https://mdm.example/files/acme/" + name, 100, sha256(clean));
        assertArrayEquals(clean, java.nio.file.Files.readAllBytes(new File(tenant, name).toPath()));
        UploadedFile copy = files.inserted.get(0);
        assertEquals(name, copy.getFilePath());
        assertEquals(1, copy.getCustomerId());
        assertEquals("Beach", copy.getDescription());
        // The original is another uploaded file, possibly used elsewhere: never rewritten behind its users' backs.
        assertArrayEquals(dirty, java.nio.file.Files.readAllBytes(new File(tenant, "kiosk-aaaaaaaaaaaa-beach.jpg").toPath()));
    }

    @Test
    public void choosingTheSameDirtyFileAgainReusesItsCleanedCopy() throws Exception {
        byte[] dirty = KioskImageMetadataTest.phoneJpeg(realImage("jpg", 8, 8), 1);
        Configuration first = replaceWith("photo.jpg", dirty);
        assets.apply(first, null);
        Configuration second = replaceWith("photo.jpg", dirty);
        assets.apply(second, null);
        assertEquals(1, files.inserted.size());
        assertEquals(first.getBackgroundImageFileId(), second.getBackgroundImageFileId());
        assertEquals(first.getBackgroundImageSha256(), second.getBackgroundImageSha256());
    }

    @Test
    public void aCleanImageIsUsedAsUploaded() throws Exception {
        byte[] jpeg = realImage("jpg", 8, 8);
        Configuration c = replaceWith("kiosk-bbbbbbbbbbbb-plain.jpg", jpeg);
        assets.apply(c, null);
        assertImage(c, "https://mdm.example/files/acme/kiosk-bbbbbbbbbbbb-plain.jpg", 42, sha256(jpeg));
        assertEquals(0, files.inserted.size());
    }

    @Test
    public void aMalformedImageWhoseHeaderLooksFineIsRefused() throws Exception {
        // A PNG header with no image data after it: nothing an agent could show.
        assertRefused(replaceWith("cut.png", KioskImageHeaderTest.pngHeader(10, 10)), null,
                KioskBackgroundImageAssets.NOT_AN_IMAGE);
    }

    @Test
    public void concurrentSavesOfOneDirtyImageHostOneCopy() throws Exception {
        byte[] dirty = KioskImageMetadataTest.phoneJpeg(realImage("jpg", 16, 16), 1);
        java.nio.file.Files.write(new File(tenant, "photo.jpg").toPath(), dirty);
        uploaded.setFilePath("photo.jpg");
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        java.util.List<java.util.concurrent.Future<Integer>> results = new java.util.ArrayList<>();
        for (int n = 0; n < 16; n++) {
            results.add(pool.submit(() -> {
                SecurityContext.init(Fakes.user(1, 1, "configurations"));
                try {
                    Configuration c = new Configuration();
                    c.setBackgroundImageAction("replace");
                    c.setBackgroundImageFileId(42);
                    assets.apply(c, null);
                    return c.getBackgroundImageFileId();
                } finally {
                    SecurityContext.release();
                }
            }));
        }
        java.util.Set<Integer> ids = new java.util.HashSet<>();
        for (java.util.concurrent.Future<Integer> f : results) ids.add(f.get());
        pool.shutdown();
        assertEquals(1, files.inserted.size());
        assertEquals(1, ids.size());
        String[] left = tenant.list((d, name) -> name.endsWith(".tmp"));
        assertEquals("no staged files left", 0, left.length);
    }

    @Test
    public void aFailedSaveTakesBackTheCopyItHosted() throws Exception {
        byte[] dirty = KioskImageMetadataTest.phoneJpeg(realImage("jpg", 8, 8), 1);
        Configuration c = replaceWith("photo.jpg", dirty);
        KioskBackgroundImageAssets.Applied applied = assets.apply(c, null);
        File copy = new File(tenant, files.inserted.get(0).getFilePath());
        assertTrue(copy.isFile());
        applied.undo();
        assertFalse(copy.exists());
        assertEquals(java.util.Collections.singletonList(100), files.removed);
        // A copy an earlier save hosted is reused, and never taken back by a later failed save.
        Configuration again = replaceWith("photo.jpg", dirty);
        assets.apply(again, null);
        Configuration third = replaceWith("photo.jpg", dirty);
        assets.apply(third, null).undo();
        assertTrue(new File(tenant, files.inserted.get(1).getFilePath()).isFile());
    }

    @Test
    public void aCopyOverTheAccountsStorageLimitIsRefused() throws Exception {
        Customer full = new Customer(); full.setId(1); full.setFilesDir("acme"); full.setSizeLimit(1);
        java.nio.file.Files.write(new File(tenant, "filler.bin").toPath(), new byte[2 * 1024 * 1024]);
        customers.customer = full;
        byte[] dirty = KioskImageMetadataTest.phoneJpeg(realImage("jpg", 8, 8), 1);
        assertRefused(replaceWith("photo.jpg", dirty), null, KioskBackgroundImageAssets.STORAGE_FULL);
        assertEquals(0, files.inserted.size());
    }

    @Test
    public void describeReadsAFileOnceUntilItChanges() throws Exception {
        assets.describe(42);
        assets.describe(42);
        assertEquals(1, assets.reads());
        File f = new File(tenant, "wallpaper.png");
        java.nio.file.Files.write(f.toPath(), realImage("png", 6, 5));
        assertTrue(f.setLastModified(f.lastModified() + 5_000));
        assertEquals(6, assets.describe(42).getWidth());
        assertEquals(2, assets.reads());
    }

    @Test
    public void aTrailingSlashInTheBaseUrlIsNotDoubled() throws Exception {
        KioskBackgroundImageAssets slash = new KioskBackgroundImageAssets(files, customers, "https://mdm.example/",
                tenant.getParentFile().getAbsolutePath());
        assertEquals("https://mdm.example/files/acme/wallpaper.png", slash.describe(42).getUrl());
    }

    // ---- describe (the console's viewer) ----------------------------------------------------------------------------

    @Test
    public void describeShowsWhatDevicesWillGetAfterSave() throws Exception {
        byte[] dirty = KioskImageMetadataTest.phoneJpeg(realImage("jpg", 40, 30), 6);
        byte[] clean = KioskImageMetadata.strip(dirty);
        java.nio.file.Files.write(new File(tenant, "kiosk-aaaaaaaaaaaa-beach-day.jpg").toPath(), dirty);
        uploaded.setFilePath("kiosk-aaaaaaaaaaaa-beach-day.jpg");
        uploaded.setUploadTime(1_700_000_000_000L);

        KioskBackgroundImageInfo info = assets.describe(42);

        assertEquals(Integer.valueOf(42), info.getFileId());
        assertEquals("beach-day.jpg", info.getName());
        assertEquals("https://mdm.example/files/acme/kiosk-aaaaaaaaaaaa-beach-day.jpg", info.getUrl());
        assertEquals("JPEG", info.getFormat());
        // Orientation 6 (rotate 90): shown 30 wide and 40 high, as the console and the agent draw it.
        assertEquals(30, info.getWidth());
        assertEquals(40, info.getHeight());
        assertEquals(6, info.getOrientation());
        assertEquals(clean.length, info.getSize());
        assertEquals(sha256(clean), info.getSha256());
        assertEquals(Long.valueOf(1_700_000_000_000L), info.getUploadTime());
        assertEquals(Boolean.TRUE, info.getHasMetadata());
        assertEquals("nothing is written to describe a file", 0, files.inserted.size());
    }

    @Test
    public void describeOfACleanImageSaysNothingIsRemoved() throws Exception {
        KioskBackgroundImageInfo info = assets.describe(42);
        assertEquals("wallpaper.png", info.getName());
        assertEquals(4, info.getWidth());
        assertEquals(3, info.getHeight());
        assertEquals(png.length, info.getSize());
        assertEquals(sha256(png), info.getSha256());
        assertEquals(Boolean.FALSE, info.getHasMetadata());
    }

    @Test
    public void describeRefusesWhatSaveRefuses() throws Exception {
        files.foreign = true;
        try {
            assets.describe(42);
            fail("expected a refusal");
        } catch (IllegalArgumentException e) {
            assertEquals(KioskBackgroundImageAssets.NOT_FOUND, e.getMessage());
        }
        files.foreign = false;
        java.nio.file.Files.write(new File(tenant, "note.txt").toPath(), "hello".getBytes(StandardCharsets.UTF_8));
        uploaded.setFilePath("note.txt");
        try {
            assets.describe(42);
            fail("expected a refusal");
        } catch (IllegalArgumentException e) {
            assertEquals(KioskBackgroundImageAssets.NOT_AN_IMAGE, e.getMessage());
        }
    }

    // ---- remove -----------------------------------------------------------------------------------------------------

    @Test
    public void anExplicitRemoveClearsLegacyUrlManagedFileAndHash() {
        Configuration configuration = new Configuration();
        configuration.setBackgroundImageAction("remove");
        configuration.setBackgroundImageUrl("https://legacy.example/image.png");
        configuration.setBackgroundImageFileId(42);
        configuration.setBackgroundImageSha256("stale");

        assets.apply(configuration, managed());

        assertImage(configuration, null, null, null);
    }

    // ---- keep / absent on update ------------------------------------------------------------------------------------

    @Test
    public void anUpdateWithoutAUrlKeepsTheStoredManagedImage() {
        Configuration c = new Configuration();
        c.setBackgroundImageSha256("attacker-hash");
        c.setBackgroundImageFileId(99);
        assets.apply(c, managed());
        assertImage(c, MANAGED_URL, 7, HASH);
        assertEquals("no file is read for an unchanged image", 0, files.reads);
    }

    @Test
    public void anUpdateSendingTheStoredUrlBackKeepsTheManagedImage() {
        Configuration c = legacy(MANAGED_URL);
        c.setBackgroundImageAction("keep");
        assets.apply(c, managed());
        assertImage(c, MANAGED_URL, 7, HASH);
    }

    @Test
    public void anUpdateSendingABlankUrlKeepsTheManagedImage() {
        Configuration c = legacy("  ");
        assets.apply(c, managed());
        assertImage(c, MANAGED_URL, 7, HASH);
    }

    @Test
    public void anotherUrlOverAManagedImageBecomesTheLegacyWallpaperAndDropsTheImage() {
        Configuration c = legacy("https://legacy.example/wallpaper.jpg");
        c.setBackgroundImageSha256("attacker-hash");
        assets.apply(c, managed());
        assertImage(c, "https://legacy.example/wallpaper.jpg", null, null);
    }

    @Test
    public void anUpdateOfALegacyWallpaperStoresTheSentUrl() {
        Configuration stored = legacy("https://legacy.example/old.jpg");
        Configuration c = legacy("https://legacy.example/new.jpg");
        c.setBackgroundImageFileId(42);
        c.setBackgroundImageSha256("attacker-hash");
        assets.apply(c, stored);
        assertImage(c, "https://legacy.example/new.jpg", null, null);
    }

    @Test
    public void anUpdateWithoutAUrlClearsALegacyWallpaperLikeAnyColumnLeftOut() {
        Configuration c = new Configuration();
        assets.apply(c, legacy("https://legacy.example/old.jpg"));
        assertImage(c, null, null, null);
    }

    // ---- create -----------------------------------------------------------------------------------------------------

    @Test
    public void aNewConfigurationWithAFileIdGetsThatImageFromTheServersBytes() throws Exception {
        Configuration c = new Configuration();
        c.setBackgroundImageFileId(42);
        c.setBackgroundImageUrl("https://attacker.example/other.png");
        c.setBackgroundImageSha256(HASH);
        assets.apply(c, null);
        assertImage(c, "https://mdm.example/files/acme/wallpaper.png", 42, sha256(png));
    }

    @Test
    public void aNewConfigurationWithAFileIdIsValidatedToo() throws Exception {
        Configuration c = replaceWith("note.txt", "hello".getBytes(StandardCharsets.UTF_8));
        c.setBackgroundImageAction(null);
        assertRefused(c, null, KioskBackgroundImageAssets.NOT_AN_IMAGE);
    }

    @Test
    public void aNewConfigurationWithoutAFileIdStoresTheLegacyUrlOnly() {
        Configuration c = legacy("https://legacy.example/wallpaper.jpg");
        c.setBackgroundImageSha256(HASH);
        assets.apply(c, null);
        assertImage(c, "https://legacy.example/wallpaper.jpg", null, null);
        assertEquals(0, files.reads);
    }

    @Test
    public void aNewConfigurationWithNothingHasNoImage() {
        Configuration c = new Configuration();
        assets.apply(c, null);
        assertNull(c.getBackgroundImageUrl());
        assertNull(c.getBackgroundImageFileId());
        assertNull(c.getBackgroundImageSha256());
    }
}
