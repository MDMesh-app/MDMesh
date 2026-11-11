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
import static org.junit.Assert.assertNull;
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
    private KioskBackgroundImageAssets assets;

    private static final class Files extends UploadedFileDAO {
        UploadedFile file;
        boolean foreign;
        int reads;
        final java.util.List<UploadedFile> inserted = new java.util.ArrayList<>();
        Files() { super(null, null, null, "unused"); }
        @Override public UploadedFile getById(Integer id) {
            reads++;
            if (foreign) throw SecurityException.onAdminDataAccessViolation("read another customer's file");
            return file;
        }
        @Override public UploadedFile insert(UploadedFile f) {
            f.setId(100 + inserted.size());
            inserted.add(f);
            return f;
        }
        @Override public UploadedFile getByPath(Integer customerId, String path) {
            for (UploadedFile f : inserted) if (f.getFilePath().equals(path)) return f;
            return null;
        }
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
        Customers customers = new Customers(); customers.customer = customer;
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
