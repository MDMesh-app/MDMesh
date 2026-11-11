package com.hmdm.rest.resource.support;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.imageio.ImageIO;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.CRC32;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * A kiosk background is public by URL: its location, camera serials, thumbnails and editing history are removed
 * without decoding the image, the orientation survives (alone), and the pixels stay byte for byte.
 */
public class KioskImageMetadataTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private static final String SERIAL = "CameraSerial-0042";
    private static final String GPS = "GPS-51.5007N-0.1246W";

    // ---- JPEG -------------------------------------------------------------------------------------------------------

    @Test
    public void aPhoneJpegLosesEverythingButItsOrientationAndColourProfile() throws Exception {
        byte[] plain = image("jpg", 40, 30);
        byte[] dirty = phoneJpeg(plain, 6);
        byte[] clean = KioskImageMetadata.strip(dirty);

        assertNotNull(clean);
        assertAbsent(clean, SERIAL, GPS, "xmpmeta", "a private comment", "Photoshop 3.0", "TRAILING-GAIN-MAP");
        assertPresent(clean, "ICC_PROFILE");
        assertEquals(6, KioskImageMetadata.orientation(clean));
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(clean));
        assertEquals(40, decoded.getWidth());
        assertEquals(30, decoded.getHeight());
        KioskImageHeader header = KioskImageHeader.read(clean);
        assertEquals(40, header.width);
        assertEquals(30, header.height);
        assertTrue("smaller", clean.length < dirty.length);
    }

    @Test
    public void thePixelsAreUntouched() throws Exception {
        byte[] plain = image("jpg", 40, 30);
        byte[] clean = KioskImageMetadata.strip(phoneJpeg(plain, 1));
        BufferedImage a = ImageIO.read(new ByteArrayInputStream(plain));
        BufferedImage b = ImageIO.read(new ByteArrayInputStream(clean));
        assertArrayEquals(a.getRGB(0, 0, 40, 30, null, 0, 40), b.getRGB(0, 0, 40, 30, null, 0, 40));
        // Only the metadata segments differ: what is left is the encoder's own output plus the ICC profile.
        int icc = 4 + 14 + ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData().length;
        assertEquals(plain.length + icc, clean.length);
    }

    @Test
    public void anUprightPhotoKeepsNoExifAtAll() throws Exception {
        byte[] clean = KioskImageMetadata.strip(phoneJpeg(image("jpg", 8, 8), 1));
        assertAbsent(clean, "Exif");
        assertEquals(1, KioskImageMetadata.orientation(clean));
    }

    @Test
    public void aJpegWithNothingToRemoveIsUnchanged() throws Exception {
        byte[] plain = image("jpg", 16, 9);
        assertArrayEquals(plain, KioskImageMetadata.strip(plain));
    }

    @Test
    public void stripIsIdempotent() throws Exception {
        byte[] once = KioskImageMetadata.strip(phoneJpeg(image("jpg", 8, 8), 8));
        assertArrayEquals(once, KioskImageMetadata.strip(once));
        byte[] png = KioskImageMetadata.strip(phonePng(image("png", 8, 8), 3));
        assertArrayEquals(png, KioskImageMetadata.strip(png));
        byte[] webp = KioskImageMetadata.strip(extendedWebp(true, 6));
        assertArrayEquals(webp, KioskImageMetadata.strip(webp));
    }

    @Test
    public void aJfifThumbnailIsDropped() throws Exception {
        byte[] plain = image("jpg", 8, 8);
        // Replace the encoder's JFIF (no thumbnail) with one carrying a 2x1 RGB thumbnail.
        int app0Length = ((plain[4] & 0xFF) << 8) | (plain[5] & 0xFF);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(plain, 0, 2);
        o.write(0xFF); o.write(0xE0); o.write(0); o.write(16 + 6);
        o.write(plain, 6, 12);
        o.write(2); o.write(1);
        o.write(new byte[]{1, 2, 3, 4, 5, 6});
        o.write(plain, 4 + app0Length, plain.length - 4 - app0Length);
        assertArrayEquals(plain, KioskImageMetadata.strip(o.toByteArray()));
    }

    @Test
    public void aProgressiveLikeJpegWithMarkersBetweenScansKeepsThem() throws Exception {
        // SOI, DQT, SOF2, DHT, SOS + data with stuffing and a restart, a comment between scans, DHT, SOS, EOI.
        ByteArrayOutputStream o = bytes(0xFF, 0xD8);
        seg(o, 0xDB, new byte[65]);
        seg(o, 0xC2, new byte[]{8, 0, 2, 0, 2, 1, 1, 0x11, 0});
        seg(o, 0xC4, new byte[]{0, 0, 0, 0});
        seg(o, 0xDA, new byte[]{1, 1, 0, 0, 0x3F, 0});
        o.write(new byte[]{0x12, (byte) 0xFF, 0x00, 0x34, (byte) 0xFF, (byte) 0xD0, 0x56});
        seg(o, 0xFE, "a private comment".getBytes(StandardCharsets.US_ASCII));
        seg(o, 0xC4, new byte[]{1, 0, 0, 0});
        seg(o, 0xDA, new byte[]{1, 1, 0, 0, 0x3F, 0x10});
        o.write(new byte[]{0x78, (byte) 0xFF, 0x00});
        o.write(0xFF); o.write(0xD9);
        byte[] clean = KioskImageMetadata.strip(o.toByteArray());
        assertAbsent(clean, "a private comment");
        assertEquals(o.size() - (4 + 17), clean.length);
        KioskImageHeader header = KioskImageHeader.read(clean);
        assertEquals(2, header.width);
    }

    // ---- PNG --------------------------------------------------------------------------------------------------------

    @Test
    public void aPngLosesItsTextTimeAndExifButKeepsOrientationAndColour() throws Exception {
        byte[] plain = image("png", 12, 7);
        byte[] clean = KioskImageMetadata.strip(phonePng(plain, 8));
        assertNotNull(clean);
        assertAbsent(clean, SERIAL, GPS, "xmpmeta", "tIME", "tEXt", "iTXt", "AFTER-IEND");
        assertPresent(clean, "gAMA");
        assertEquals(8, KioskImageMetadata.orientation(clean));
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(clean));   // CRCs and order still valid
        assertEquals(12, decoded.getWidth());
        assertEquals(7, decoded.getHeight());
    }

    @Test
    public void aPngWithNothingToRemoveIsUnchanged() throws Exception {
        byte[] plain = image("png", 5, 5);
        assertArrayEquals(plain, KioskImageMetadata.strip(plain));
    }

    @Test
    public void aPngWithAnUnknownCriticalChunkIsRefused() throws Exception {
        byte[] plain = image("png", 5, 5);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(plain, 0, 33);                          // signature + IHDR
        chunk(o, "ZZZZ", new byte[]{1});
        o.write(plain, 33, plain.length - 33);
        assertNull(KioskImageMetadata.strip(o.toByteArray()));
    }

    // ---- WebP -------------------------------------------------------------------------------------------------------

    @Test
    public void anExtendedWebpLosesExifAndXmpAndItsFlags() {
        byte[] clean = KioskImageMetadata.strip(extendedWebp(true, 1));
        assertNotNull(clean);
        assertAbsent(clean, SERIAL, "xmpmeta", "XMP ", "EXIF");
        assertPresent(clean, "ICCP", "VP8L");
        assertEquals(0, clean[20] & 0x0C);               // EXIF and XMP flags cleared
        assertEquals(clean.length - 8, le32(clean, 4));  // RIFF size fixed
        KioskImageHeader header = KioskImageHeader.read(clean);
        assertEquals(64, header.width);
        assertEquals(48, header.height);
    }

    @Test
    public void anExtendedWebpKeepsOnlyItsOrientation() {
        byte[] clean = KioskImageMetadata.strip(extendedWebp(true, 6));
        assertAbsent(clean, SERIAL, "xmpmeta");
        assertEquals(6, KioskImageMetadata.orientation(clean));
        assertEquals(0x08, clean[20] & 0x0C);
        assertEquals(clean.length - 8, le32(clean, 4));
    }

    @Test
    public void aSimpleWebpOnlyLosesBytesAfterTheRiff() {
        byte[] simple = simpleWebp();
        byte[] trailing = Arrays.copyOf(simple, simple.length + 5);
        assertArrayEquals(simple, KioskImageMetadata.strip(simple));
        assertArrayEquals(simple, KioskImageMetadata.strip(trailing));
    }

    // ---- refusals, robustness ---------------------------------------------------------------------------------------

    @Test
    public void otherFormatsAreNotImagesHere() throws Exception {
        assertNull(KioskImageMetadata.strip(image("gif", 2, 2)));
        assertNull(KioskImageMetadata.strip("<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes(StandardCharsets.UTF_8)));
        assertNull(KioskImageMetadata.strip(new byte[0]));
        assertNull(KioskImageMetadata.strip(null));
    }

    @Test
    public void aSegmentLengthPastTheEndIsRefusedNotTrusted() throws Exception {
        byte[] jpeg = phoneJpeg(image("jpg", 8, 8), 6);
        byte[] lying = jpeg.clone();
        lying[4] = (byte) 0xFF;                          // APP0 claims ~65 KB
        lying[5] = (byte) 0xF0;
        assertNull(KioskImageMetadata.strip(lying));
    }

    /** Truncated or corrupted files never throw: they are simply not images. */
    @Test
    public void corruptInputNeverThrows() throws Exception {
        byte[][] samples = {
                phoneJpeg(image("jpg", 16, 16), 6), phonePng(image("png", 16, 16), 6), extendedWebp(true, 6),
                simpleWebp()};
        Random random = new Random(170);
        for (byte[] sample : samples) {
            for (int cut = 0; cut < sample.length; cut += Math.max(1, sample.length / 200)) {
                KioskImageMetadata.strip(Arrays.copyOf(sample, cut));
                KioskImageMetadata.orientation(Arrays.copyOf(sample, cut));
            }
            for (int n = 0; n < 500; n++) {
                byte[] corrupt = sample.clone();
                for (int k = 0; k < 4; k++) corrupt[random.nextInt(corrupt.length)] = (byte) random.nextInt(256);
                KioskImageMetadata.strip(corrupt);
                KioskImageMetadata.orientation(corrupt);
            }
        }
    }

    // ---- uploads committed from the console -------------------------------------------------------------------------

    @Test
    public void aKioskUploadIsCleanedInPlaceBeforeItIsHosted() throws Exception {
        File upload = temp.newFile("upload.tmp");
        byte[] dirty = phoneJpeg(image("jpg", 8, 8), 1);
        Files.write(upload.toPath(), dirty);
        assertTrue(KioskImageMetadata.stripUpload(upload, "kiosk-0123456789ab-beach.jpg"));
        assertArrayEquals(KioskImageMetadata.strip(dirty), Files.readAllBytes(upload.toPath()));
    }

    @Test
    public void otherUploadsAreLeftAlone() throws Exception {
        File upload = temp.newFile("upload.tmp");
        byte[] dirty = phoneJpeg(image("jpg", 8, 8), 1);
        Files.write(upload.toPath(), dirty);
        assertFalse(KioskImageMetadata.stripUpload(upload, "beach.jpg"));
        assertFalse(KioskImageMetadata.stripUpload(upload, "docs/kiosk-0123456789ab-beach.jpg.apk"));
        assertArrayEquals(dirty, Files.readAllBytes(upload.toPath()));
        File notAnImage = temp.newFile("text.tmp");
        Files.write(notAnImage.toPath(), "hello".getBytes(StandardCharsets.UTF_8));
        assertFalse(KioskImageMetadata.stripUpload(notAnImage, "kiosk-0123456789ab-hello.png"));
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(notAnImage.toPath()));
    }

    // ---- fixtures ---------------------------------------------------------------------------------------------------

    static byte[] image(String format, int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) image.setRGB(x, y, (x * 37 + y * 91) * 0x010203);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, format, out)) throw new IllegalStateException("no writer for " + format);
        return out.toByteArray();
    }

    /** {@code plain} as a phone would save it: EXIF (camera, GPS, orientation), XMP, IPTC, comment, ICC, a trailer. */
    static byte[] phoneJpeg(byte[] plain, int orientation) throws IOException {
        int app0Length = ((plain[4] & 0xFF) << 8) | (plain[5] & 0xFF);
        int afterApp0 = 4 + app0Length;
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(plain, 0, afterApp0);
        ByteArrayOutputStream exif = bytes('E', 'x', 'i', 'f', 0, 0);
        exif.write(tiff(orientation));
        seg(o, 0xE1, exif.toByteArray());
        seg(o, 0xE1, ("http://ns.adobe.com/xap/1.0/\0<x:xmpmeta>" + GPS + "</x:xmpmeta>").getBytes(StandardCharsets.UTF_8));
        seg(o, 0xED, ("Photoshop 3.0\0" + SERIAL).getBytes(StandardCharsets.UTF_8));
        seg(o, 0xFE, "a private comment".getBytes(StandardCharsets.UTF_8));
        byte[] icc = ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData();
        ByteArrayOutputStream app2 = new ByteArrayOutputStream();
        app2.write("ICC_PROFILE\0".getBytes(StandardCharsets.US_ASCII));
        app2.write(1); app2.write(1);
        app2.write(icc);
        seg(o, 0xE2, app2.toByteArray());
        o.write(plain, afterApp0, plain.length - afterApp0);
        o.write(("TRAILING-GAIN-MAP " + GPS).getBytes(StandardCharsets.US_ASCII));
        return o.toByteArray();
    }

    /** A little-endian TIFF: IFD0 = Make (serial), Orientation, GPS pointer; a GPS IFD with a text tag. */
    static byte[] tiff(int orientation) {
        byte[] make = (SERIAL + "\0").getBytes(StandardCharsets.US_ASCII);
        byte[] gps = (GPS + "\0").getBytes(StandardCharsets.US_ASCII);
        int ifd0 = 8;
        int ifd0Size = 2 + 3 * 12 + 4;
        int makeAt = ifd0 + ifd0Size;
        int gpsIfd = makeAt + make.length;
        int gpsText = gpsIfd + 2 + 12 + 4;
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(gpsText + gps.length).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(ifd0);
        b.putShort((short) 3);
        b.putShort((short) 0x010F).putShort((short) 2).putInt(make.length).putInt(makeAt);
        b.putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) orientation).putShort((short) 0);
        b.putShort((short) 0x8825).putShort((short) 4).putInt(1).putInt(gpsIfd);
        b.putInt(0);
        b.put(make);
        b.putShort((short) 1);
        b.putShort((short) 0x001B).putShort((short) 7).putInt(gps.length).putInt(gpsText);
        b.putInt(0);
        b.put(gps);
        return b.array();
    }

    static byte[] phonePng(byte[] plain, int orientation) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(plain, 0, 33);                           // signature + IHDR
        chunk(o, "gAMA", new byte[]{0, 0, (byte) 0xB1, (byte) 0x8F});
        chunk(o, "eXIf", tiff(orientation));
        chunk(o, "tEXt", ("Comment\0" + SERIAL).getBytes(StandardCharsets.ISO_8859_1));
        chunk(o, "iTXt", ("XML:com.adobe.xmp\0\0\0\0\0<x:xmpmeta>" + GPS + "</x:xmpmeta>").getBytes(StandardCharsets.UTF_8));
        chunk(o, "tIME", new byte[]{0x07, (byte) 0xEA, 11, 11, 8, 0, 0});
        o.write(plain, 33, plain.length - 33);
        o.write("AFTER-IEND".getBytes(StandardCharsets.US_ASCII));
        return o.toByteArray();
    }

    /** VP8X (ICC, EXIF, XMP flags), ICCP, VP8L (64 x 48), EXIF, XMP. */
    static byte[] extendedWebp(boolean withMetadata, int orientation) {
        ByteArrayOutputStream body = bytes('W', 'E', 'B', 'P');
        riffChunk(body, "VP8X", new byte[]{(byte) (0x20 | 0x08 | 0x04), 0, 0, 0, 63, 0, 0, 47, 0, 0});
        riffChunk(body, "ICCP", new byte[]{1, 2, 3});
        riffChunk(body, "VP8L", vp8l(64, 48));
        if (withMetadata) {
            riffChunk(body, "EXIF", tiff(orientation));
            riffChunk(body, "XMP ", ("<x:xmpmeta>" + GPS + "</x:xmpmeta>").getBytes(StandardCharsets.UTF_8));
        }
        return riff(body.toByteArray());
    }

    static byte[] simpleWebp() {
        ByteArrayOutputStream body = bytes('W', 'E', 'B', 'P');
        riffChunk(body, "VP8L", vp8l(10, 10));
        return riff(body.toByteArray());
    }

    private static byte[] vp8l(int width, int height) {
        long bits = (width - 1) | ((long) (height - 1) << 14);
        return new byte[]{0x2F, (byte) bits, (byte) (bits >> 8), (byte) (bits >> 16), (byte) (bits >> 24), 0, 0};
    }

    private static byte[] riff(byte[] body) {
        ByteArrayOutputStream o = bytes('R', 'I', 'F', 'F');
        le32(o, body.length);
        o.write(body, 0, body.length);
        return o.toByteArray();
    }

    private static void riffChunk(ByteArrayOutputStream o, String type, byte[] data) {
        o.write(type.getBytes(StandardCharsets.US_ASCII), 0, 4);
        le32(o, data.length);
        o.write(data, 0, data.length);
        if ((data.length & 1) == 1) o.write(0);
    }

    private static void seg(ByteArrayOutputStream o, int marker, byte[] data) {
        o.write(0xFF); o.write(marker);
        o.write((data.length + 2) >> 8); o.write((data.length + 2) & 0xFF);
        o.write(data, 0, data.length);
    }

    private static void chunk(ByteArrayOutputStream o, String type, byte[] data) {
        byte[] t = type.getBytes(StandardCharsets.US_ASCII);
        be32(o, data.length);
        o.write(t, 0, 4);
        o.write(data, 0, data.length);
        CRC32 crc = new CRC32();
        crc.update(t); crc.update(data);
        be32(o, crc.getValue());
    }

    private static ByteArrayOutputStream bytes(int... values) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (int v : values) o.write(v);
        return o;
    }

    private static void be32(ByteArrayOutputStream o, long v) {
        o.write((int) (v >> 24)); o.write((int) (v >> 16)); o.write((int) (v >> 8)); o.write((int) v);
    }

    private static void le32(ByteArrayOutputStream o, long v) {
        o.write((int) v); o.write((int) (v >> 8)); o.write((int) (v >> 16)); o.write((int) (v >> 24));
    }

    private static long le32(byte[] b, int i) {
        return (b[i] & 0xFF) | ((b[i + 1] & 0xFF) << 8) | ((b[i + 2] & 0xFF) << 16) | ((long) (b[i + 3] & 0xFF) << 24);
    }

    private static void assertAbsent(byte[] haystack, String... needles) {
        String s = new String(haystack, StandardCharsets.ISO_8859_1);
        for (String n : needles) assertFalse("still contains " + n, s.contains(n));
    }

    private static void assertPresent(byte[] haystack, String... needles) {
        String s = new String(haystack, StandardCharsets.ISO_8859_1);
        for (String n : needles) assertTrue("lost " + n, s.contains(n));
    }
}
