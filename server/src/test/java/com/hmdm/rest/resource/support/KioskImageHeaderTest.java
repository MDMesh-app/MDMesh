package com.hmdm.rest.resource.support;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/** The kiosk image header reader: PNG, JPEG (any SOF) and WebP (VP8, VP8L, VP8X) dimensions, and nothing else. */
public class KioskImageHeaderTest {

    private static ByteArrayOutputStream out(int... bytes) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (int b : bytes) o.write(b);
        return o;
    }

    private static void ascii(ByteArrayOutputStream o, String s) {
        byte[] b = s.getBytes(StandardCharsets.US_ASCII);
        o.write(b, 0, b.length);
    }

    private static void be32(ByteArrayOutputStream o, long v) {
        o.write((int) (v >>> 24)); o.write((int) (v >>> 16)); o.write((int) (v >>> 8)); o.write((int) v);
    }

    /** A PNG signature and IHDR chunk header claiming the given size (the rest of the file is irrelevant here). */
    static byte[] pngHeader(long width, long height) {
        ByteArrayOutputStream o = out(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A);
        be32(o, 13);
        ascii(o, "IHDR");
        be32(o, width);
        be32(o, height);
        for (int i = 0; i < 9; i++) o.write(0);
        return o.toByteArray();
    }

    /** SOI, an APP0 segment, then a start-of-frame with the given marker and size. */
    private static byte[] jpeg(int sofMarker, int width, int height) {
        ByteArrayOutputStream o = out(0xFF, 0xD8);
        o.write(0xFF); o.write(0xE0); o.write(0); o.write(16);
        ascii(o, "JFIF"); for (int i = 0; i < 10; i++) o.write(0);
        o.write(0xFF); o.write(0xC4); o.write(0); o.write(4); o.write(0); o.write(0);   // a DHT before the frame
        o.write(0xFF); o.write(0xFF);                                                   // fill byte
        o.write(sofMarker); o.write(0); o.write(17); o.write(8);
        o.write(height >> 8); o.write(height & 0xFF); o.write(width >> 8); o.write(width & 0xFF);
        for (int i = 0; i < 10; i++) o.write(0);
        return o.toByteArray();
    }

    private static ByteArrayOutputStream riff(String chunk) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ascii(o, "RIFF"); o.write(0); o.write(0); o.write(0); o.write(0); ascii(o, "WEBP");
        ascii(o, chunk); o.write(10); o.write(0); o.write(0); o.write(0);
        return o;
    }

    private static byte[] webpLossy(int width, int height) {
        ByteArrayOutputStream o = riff("VP8 ");
        o.write(0); o.write(0); o.write(0);                    // frame tag
        o.write(0x9D); o.write(0x01); o.write(0x2A);           // start code
        o.write(width & 0xFF); o.write(width >> 8); o.write(height & 0xFF); o.write(height >> 8);
        return o.toByteArray();
    }

    private static byte[] webpLossless(int width, int height) {
        ByteArrayOutputStream o = riff("VP8L");
        long bits = (width - 1) | ((long) (height - 1) << 14);
        o.write(0x2F);
        o.write((int) bits); o.write((int) (bits >> 8)); o.write((int) (bits >> 16)); o.write((int) (bits >> 24));
        return o.toByteArray();
    }

    private static byte[] webpExtended(int width, int height) {
        ByteArrayOutputStream o = riff("VP8X");
        o.write(0); o.write(0); o.write(0); o.write(0);        // flags, reserved
        int w = width - 1, h = height - 1;
        o.write(w); o.write(w >> 8); o.write(w >> 16);
        o.write(h); o.write(h >> 8); o.write(h >> 16);
        return o.toByteArray();
    }

    private static void assertSize(String format, int width, int height, KioskImageHeader header) {
        assertNotNull(header);
        assertEquals(format, header.format);
        assertEquals(width, header.width);
        assertEquals(height, header.height);
    }

    @Test
    public void readsAPngHeader() {
        assertSize("PNG", 1920, 1080, KioskImageHeader.read(pngHeader(1920, 1080)));
    }

    @Test
    public void readsARealPngAndJpeg() throws Exception {
        assertSize("PNG", 5, 3, KioskImageHeader.read(KioskBackgroundImageAssetsTest.realImage("png", 5, 3)));
        assertSize("JPEG", 7, 9, KioskImageHeader.read(KioskBackgroundImageAssetsTest.realImage("jpg", 7, 9)));
    }

    @Test
    public void readsBaselineAndProgressiveJpegFrames() {
        assertSize("JPEG", 640, 480, KioskImageHeader.read(jpeg(0xC0, 640, 480)));
        assertSize("JPEG", 4000, 3000, KioskImageHeader.read(jpeg(0xC2, 4000, 3000)));
    }

    @Test
    public void readsAllThreeWebpBitstreams() {
        assertSize("WebP", 800, 600, KioskImageHeader.read(webpLossy(800, 600)));
        assertSize("WebP", 1024, 768, KioskImageHeader.read(webpLossless(1024, 768)));
        assertSize("WebP", 5000, 4000, KioskImageHeader.read(webpExtended(5000, 4000)));
    }

    @Test
    public void refusesOtherFormats() {
        assertNull(KioskImageHeader.read("<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes(StandardCharsets.UTF_8)));
        assertNull(KioskImageHeader.read("GIF89a\u0001\u0000\u0001\u0000".getBytes(StandardCharsets.ISO_8859_1)));
        assertNull(KioskImageHeader.read(new byte[] {'P', 'K', 3, 4, 0, 0, 0, 0}));
        assertNull(KioskImageHeader.read(new byte[0]));
        assertNull(KioskImageHeader.read(null));
    }

    @Test
    public void refusesTruncatedOrZeroSizedHeaders() {
        byte[] png = pngHeader(10, 10);
        assertNull(KioskImageHeader.read(java.util.Arrays.copyOf(png, 20)));
        assertNull(KioskImageHeader.read(pngHeader(0, 10)));
        assertNull(KioskImageHeader.read(pngHeader(1L << 31, 10)));
        assertNull("SOS before any frame", KioskImageHeader.read(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF,
                (byte) 0xDA, 0, 2}));
        assertNull(KioskImageHeader.read(java.util.Arrays.copyOf(jpeg(0xC0, 10, 10), 30)));
        assertNull(KioskImageHeader.read(jpeg(0xC0, 0, 10)));
        assertNull(KioskImageHeader.read(java.util.Arrays.copyOf(webpExtended(10, 10), 25)));
        assertNull("unknown WebP chunk", KioskImageHeader.read(riff("ALPH").toByteArray()));
    }
}
