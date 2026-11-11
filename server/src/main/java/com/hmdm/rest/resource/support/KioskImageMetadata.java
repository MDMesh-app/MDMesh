/*
 * MDMesh: removes the metadata of a kiosk background image without decoding it.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 */

package com.hmdm.rest.resource.support;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Removes the metadata of a PNG, JPEG or WebP image by rewriting its container, never its pixels: nothing is decoded,
 * so an untrusted file can't exhaust memory here, and the image data and its colour profile stay byte for byte as they
 * were. A kiosk background is served publicly by URL, so a phone photo must not carry its GPS position, camera serial
 * numbers, embedded thumbnails or editing history to anyone who has the link.
 *
 * <p>Kept, by allow-list: what decoding and colour need (JPEG frames, tables, restart interval and scans, JFIF without
 * its thumbnail, ICC profile, the 12 bytes of the Adobe colour transform; PNG critical chunks, transparency, gamma,
 * chromaticities, sRGB, ICC, cICP and HDR chunks, pixel density, background, and an animated PNG's frames as they
 * are; WebP VP8X (first chunk only), ICCP, ALPH, ANIM, ANMF and image data). Everything else goes: EXIF, XMP,
 * IPTC/Photoshop, comments, PNG text and time, multi-picture and gain-map data after the end of the image, unknown
 * and reserved segments and chunks.</p>
 *
 * <p>Every pass is linear in the input, which is at most {@link KioskBackgroundImageAssets#MAX_BYTES}.</p>
 *
 * <p>The one tag kept is a JPEG's EXIF orientation, rewritten as a minimal EXIF block with nothing else: removing it
 * would turn an upright phone photo on its side, and the agent applies it when it decodes. Orientation is JPEG-only
 * (the agent reads it from JPEG only): a PNG's eXIf and a WebP's EXIF go whole, orientation included, so the server,
 * the console and the device all show such an image as stored.</p>
 */
public final class KioskImageMetadata {

    private KioskImageMetadata() {}

    /** The name the console hosts a kiosk background under (web/src/data/kioskBackground.ts kioskImageUploadName). */
    private static final Pattern KIOSK_UPLOAD_NAME = Pattern.compile("kiosk-[0-9a-f]{12}-[A-Za-z0-9_-]{1,40}(\\.[a-z0-9]{1,5})?");

    /**
     * Strips a console kiosk-background upload in place, before the commit moves it into the public files directory,
     * so its metadata is never served. Only a file the console names as a kiosk image, that is a well-formed image
     * within the size limit, is rewritten; anything else in Files is left exactly as uploaded.
     *
     * @return true when the file was rewritten
     */
    public static boolean stripUpload(File upload, String fileName) throws IOException {
        if (fileName == null || !KIOSK_UPLOAD_NAME.matcher(fileName).matches()) return false;
        if (!upload.isFile() || upload.length() > KioskBackgroundImageAssets.MAX_BYTES) return false;
        byte[] bytes = Files.readAllBytes(upload.toPath());
        if (KioskImageHeader.read(bytes) == null) return false;
        byte[] clean = strip(bytes);
        if (clean == null || Arrays.equals(bytes, clean)) return false;
        File staged = new File(upload.getParentFile(), upload.getName() + ".clean");
        Files.write(staged.toPath(), clean);
        Files.move(staged.toPath(), upload.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return true;
    }

    /**
     * The image without its metadata, or null when {@code bytes} are not a well-formed PNG, JPEG or WebP container.
     * Returns an equal array when there was nothing to remove; running it again on its result changes nothing.
     */
    public static byte[] strip(byte[] bytes) {
        // Every pass below is linear in the input; the size cap bounds the work of one call.
        if (bytes == null || bytes.length < 12 || bytes.length > KioskBackgroundImageAssets.MAX_BYTES) return null;
        try {
            if (isPng(bytes)) return png(bytes);
            if (u8(bytes, 0) == 0xFF && u8(bytes, 1) == 0xD8 && u8(bytes, 2) == 0xFF) return jpeg(bytes);
            if (ascii(bytes, 0, "RIFF") && ascii(bytes, 8, "WEBP")) return webp(bytes);
            return null;
        } catch (IndexOutOfBoundsException e) {
            // Every read is bounds-checked; this is only a backstop for a malformed length we missed.
            return null;
        }
    }

    /** The EXIF orientation (1 to 8) a {@link #strip} result keeps, read from its metadata; 1 when there is none. */
    public static int orientation(byte[] bytes) {
        try {
            if (bytes == null || bytes.length < 12 || bytes.length > KioskBackgroundImageAssets.MAX_BYTES) return 1;
            // JPEG only: the agent applies a JPEG's EXIF orientation and nothing else's, so a PNG or WebP keeps none.
            if (u8(bytes, 0) == 0xFF && u8(bytes, 1) == 0xD8) return jpegOrientation(bytes);
        } catch (IndexOutOfBoundsException e) {
            return 1;
        }
        return 1;
    }

    // ---- JPEG -------------------------------------------------------------------------------------------------------

    private static final byte[] EXIF_ID = {'E', 'x', 'i', 'f', 0, 0};

    private static byte[] jpeg(byte[] b) {
        ByteArrayOutputStream jfif = new ByteArrayOutputStream();
        ByteArrayOutputStream rest = new ByteArrayOutputStream();
        int orientation = 1;
        int i = 2;
        boolean ended = false;
        while (i < b.length) {
            if (u8(b, i) != 0xFF) return null;
            while (i < b.length && u8(b, i) == 0xFF) i++;   // fill bytes
            if (i >= b.length) return null;
            int marker = u8(b, i++);
            if (marker == 0xD9) {                           // end of image: anything after it is dropped
                ended = true;
                break;
            }
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD8)) {
                if (marker == 0xD8) return null;            // a second start of image
                continue;                                   // TEM or a restart outside a scan: nothing to keep
            }
            if (i + 2 > b.length) return null;
            int length = be16(b, i);
            if (length < 2 || i + length > b.length) return null;
            int data = i + 2;
            int dataLength = length - 2;
            if (marker == 0xE0) {
                // JFIF: keep the version and density, never the thumbnail. JFXX (thumbnails only) is dropped.
                if (jfif.size() == 0 && dataLength >= 14 && ascii(b, data, "JFIF\0")) {
                    jfif.write(0xFF);
                    jfif.write(0xE0);
                    jfif.write(0);
                    jfif.write(16);
                    jfif.write(b, data, 12);
                    jfif.write(0);                          // thumbnail width
                    jfif.write(0);                          // thumbnail height
                }
            } else if (marker == 0xE1) {
                if (orientation == 1 && dataLength >= 6 && startsWith(b, data, EXIF_ID)) {
                    orientation = tiffOrientation(b, data + 6, data + dataLength);
                }
            } else if (marker == 0xE2) {
                if (dataLength >= 12 && ascii(b, data, "ICC_PROFILE\0")) segment(rest, b, marker, i, length);
            } else if (marker == 0xEE) {
                // Adobe: its 12 defined bytes (the colour transform), never what a writer appended after them.
                if (dataLength >= ADOBE_LENGTH && ascii(b, data, "Adobe")) {
                    rest.write(0xFF);
                    rest.write(0xEE);
                    rest.write(0);
                    rest.write(ADOBE_LENGTH + 2);
                    rest.write(b, data, ADOBE_LENGTH);
                }
            } else if (isDecodingSegment(marker)) {
                segment(rest, b, marker, i, length);
            }
            // Anything else goes: other application segments (IPTC/Photoshop, maker data, ...), comments, reserved
            // and hierarchical markers.
            i += length;
            if (marker == 0xDA) {
                // Entropy-coded data runs to the next marker that isn't byte stuffing (FF00) or a restart (FFD0-D7).
                int start = i;
                while (i < b.length) {
                    if (u8(b, i) == 0xFF && i + 1 < b.length) {
                        int next = u8(b, i + 1);
                        if (next == 0x00 || (next >= 0xD0 && next <= 0xD7)) {
                            i += 2;
                            continue;
                        }
                        break;
                    }
                    i++;
                }
                int end = i;
                // A file cut off right after an 0xFF: that byte is not data, and kept it would turn the EOI written
                // below into fill, so a second strip would differ.
                if (end == b.length && end > start && u8(b, end - 1) == 0xFF) end--;
                rest.write(b, start, end - start);
            }
        }
        if (!ended && rest.size() == 0) return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream(b.length);
        out.write(0xFF);
        out.write(0xD8);
        out.write(jfif.toByteArray(), 0, jfif.size());
        if (orientation != 1) {
            byte[] tiff = minimalTiff(orientation);
            out.write(0xFF);
            out.write(0xE1);
            int length = 2 + EXIF_ID.length + tiff.length;
            out.write(length >> 8);
            out.write(length & 0xFF);
            out.write(EXIF_ID, 0, EXIF_ID.length);
            out.write(tiff, 0, tiff.length);
        }
        out.write(rest.toByteArray(), 0, rest.size());
        out.write(0xFF);
        out.write(0xD9);
        return out.toByteArray();
    }

    private static final int ADOBE_LENGTH = 12;

    /**
     * The segments a decoder needs, the only ones kept besides JFIF, ICC and Adobe: frames (SOF0-3, 5-7, 9-11, 13-15),
     * Huffman and arithmetic tables, quantization tables, restart interval, line count and scans.
     */
    private static boolean isDecodingSegment(int marker) {
        if (marker >= 0xC0 && marker <= 0xCF) return marker != 0xC8;   // SOFn, DHT (C4), DAC (CC); not JPG (C8)
        return marker == 0xDB || marker == 0xDD || marker == 0xDC || marker == 0xDA;
    }

    private static void segment(ByteArrayOutputStream out, byte[] b, int marker, int lengthAt, int length) {
        out.write(0xFF);
        out.write(marker);
        out.write(b, lengthAt, length);
    }

    private static int jpegOrientation(byte[] b) {
        int i = 2;
        while (i + 4 <= b.length && u8(b, i) == 0xFF) {
            int marker = u8(b, i + 1);
            if (marker == 0xDA || marker == 0xD9) return 1;
            int length = be16(b, i + 2);
            if (length < 2 || i + 2 + length > b.length) return 1;
            int data = i + 4;
            if (marker == 0xE1 && length - 2 >= 6 && startsWith(b, data, EXIF_ID)) {
                return tiffOrientation(b, data + 6, i + 2 + length);
            }
            i += 2 + length;
        }
        return 1;
    }

    // ---- PNG --------------------------------------------------------------------------------------------------------

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    /** Ancillary chunks that decoding or colour depend on; any other ancillary chunk is dropped. */
    private static final Set<String> PNG_KEEP = new HashSet<String>(Arrays.asList(
            "tRNS", "gAMA", "cHRM", "sRGB", "iCCP", "sBIT", "cICP", "mDCV", "cLLI", "pHYs", "bKGD",
            // Animated PNG: kept as it is (image data, not metadata); the agent shows the first frame.
            "acTL", "fcTL", "fdAT"));

    private static byte[] png(byte[] b) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(b.length);
        out.write(b, 0, PNG_SIGNATURE.length);
        int i = PNG_SIGNATURE.length;
        boolean first = true;
        while (true) {
            if (i + 12 > b.length) return null;             // no IEND
            long length = be32(b, i);
            if (length > b.length - i - 12) return null;
            String type = new String(b, i + 4, 4, StandardCharsets.ISO_8859_1);
            int total = 12 + (int) length;
            if (first && !"IHDR".equals(type)) return null;
            boolean critical = Character.isUpperCase(type.charAt(0));
            if (critical && !"IHDR".equals(type) && !"PLTE".equals(type) && !"IDAT".equals(type)
                    && !"IEND".equals(type)) {
                return null;                                 // an unknown critical chunk: not an image we can vouch for
            }
            if (critical || PNG_KEEP.contains(type)) out.write(b, i, total);
            first = false;
            i += total;
            if ("IEND".equals(type)) return out.toByteArray();   // anything after it is dropped
        }
    }



    // ---- WebP -------------------------------------------------------------------------------------------------------

    private static final Set<String> WEBP_KEEP = new HashSet<String>(Arrays.asList(
            "ICCP", "ANIM", "ANMF", "ALPH", "VP8 ", "VP8L"));
    private static final int VP8X_XMP = 0x04;
    private static final int VP8X_EXIF = 0x08;

    private static byte[] webp(byte[] b) {
        long riffSize = le32(b, 4);
        if (riffSize < 4 || riffSize > b.length - 8L) return null;
        int end = 8 + (int) riffSize;
        if (!ascii(b, 12, "VP8X")) {
            // The simple formats (one VP8 or VP8L chunk) have no room for metadata; only bytes after the RIFF go.
            if (!ascii(b, 12, "VP8 ") && !ascii(b, 12, "VP8L")) return null;
            return Arrays.copyOf(b, end);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(b.length);
        out.write(b, 0, 12);                                 // RIFF, size (fixed below), WEBP
        // VP8X: the first chunk, and only there. Its flags are patched in a copy of it and written once.
        if (end < 12 + 8 + 10) return null;
        long vp8xSize = le32(b, 16);
        if (vp8xSize < 10 || vp8xSize > end - 20L) return null;
        int vp8xTotal = 8 + (int) (vp8xSize + (vp8xSize & 1));
        if (vp8xTotal > end - 12) return null;
        byte[] vp8x = Arrays.copyOfRange(b, 12, 12 + vp8xTotal);
        vp8x[8] = (byte) ((vp8x[8] & 0xFF) & ~(VP8X_XMP | VP8X_EXIF));
        out.write(vp8x, 0, vp8x.length);
        int i = 12 + vp8xTotal;
        boolean image = false;
        while (i < end) {
            if (i + 8 > end) return null;
            String type = new String(b, i, 4, StandardCharsets.ISO_8859_1);
            long size = le32(b, i + 4);
            long padded = size + (size & 1);
            if (padded > end - i - 8L) return null;
            int total = 8 + (int) padded;
            if ("VP8X".equals(type)) return null;            // a second VP8X: not a well-formed WebP
            if (WEBP_KEEP.contains(type)) {
                out.write(b, i, total);
                if ("VP8 ".equals(type) || "VP8L".equals(type) || "ANMF".equals(type)) image = true;
            }
            i += total;
        }
        if (!image) return null;
        byte[] result = out.toByteArray();
        int size = result.length - 8;
        result[4] = (byte) size;
        result[5] = (byte) (size >> 8);
        result[6] = (byte) (size >> 16);
        result[7] = (byte) (size >> 24);
        return result;
    }


    // ---- EXIF (TIFF) ------------------------------------------------------------------------------------------------

    /** Orientation (tag 0x0112) from IFD0 of the TIFF structure in {@code b[start, end)}; 1 when absent or invalid. */
    static int tiffOrientation(byte[] b, int start, int end) {
        end = Math.min(end, b.length);
        if (start < 0 || start + 8 > end) return 1;
        boolean little;
        if (u8(b, start) == 'I' && u8(b, start + 1) == 'I') little = true;
        else if (u8(b, start) == 'M' && u8(b, start + 1) == 'M') little = false;
        else return 1;
        if (u16(b, start + 2, little) != 42) return 1;
        long ifd = u32(b, start + 4, little);
        if (ifd < 8 || ifd > end - start - 2L) return 1;
        int at = start + (int) ifd;
        int count = u16(b, at, little);
        at += 2;
        for (int n = 0; n < count && at + 12 <= end; n++, at += 12) {
            if (u16(b, at, little) != 0x0112) continue;
            if (u16(b, at + 2, little) != 3) return 1;        // SHORT
            int value = u16(b, at + 8, little);
            return value >= 1 && value <= 8 ? value : 1;
        }
        return 1;
    }

    /** A big-endian TIFF structure whose only entry is the orientation. */
    static byte[] minimalTiff(int orientation) {
        return new byte[]{
                'M', 'M', 0, 42, 0, 0, 0, 8,                 // header, IFD0 at 8
                0, 1,                                        // one entry
                0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, (byte) orientation, 0, 0,   // Orientation, SHORT, 1, value
                0, 0, 0, 0                                   // no next IFD
        };
    }

    // ---- bytes ------------------------------------------------------------------------------------------------------

    private static boolean isPng(byte[] b) {
        return startsWith(b, 0, PNG_SIGNATURE);
    }

    private static boolean startsWith(byte[] b, int at, byte[] prefix) {
        if (at < 0 || at + prefix.length > b.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (b[at + i] != prefix[i]) return false;
        }
        return true;
    }

    private static boolean ascii(byte[] b, int at, String s) {
        return startsWith(b, at, s.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static int u8(byte[] b, int i) {
        return b[i] & 0xFF;
    }

    private static int be16(byte[] b, int i) {
        return (u8(b, i) << 8) | u8(b, i + 1);
    }

    private static long be32(byte[] b, int i) {
        return ((long) u8(b, i) << 24) | (u8(b, i + 1) << 16) | (u8(b, i + 2) << 8) | u8(b, i + 3);
    }

    private static long le32(byte[] b, int i) {
        return u8(b, i) | (u8(b, i + 1) << 8) | (u8(b, i + 2) << 16) | ((long) u8(b, i + 3) << 24);
    }

    private static int u16(byte[] b, int i, boolean little) {
        return little ? u8(b, i) | (u8(b, i + 1) << 8) : be16(b, i);
    }

    private static long u32(byte[] b, int i, boolean little) {
        return little ? le32(b, i) : be32(b, i);
    }

    private static void writeBe32(ByteArrayOutputStream out, long v) {
        out.write((int) (v >> 24) & 0xFF);
        out.write((int) (v >> 16) & 0xFF);
        out.write((int) (v >> 8) & 0xFF);
        out.write((int) v & 0xFF);
    }

}
