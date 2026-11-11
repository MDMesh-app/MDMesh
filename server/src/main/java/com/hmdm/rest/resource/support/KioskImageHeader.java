/*
 * MDMesh: reads the format and pixel size of a kiosk background image from its header.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 */

package com.hmdm.rest.resource.support;

/**
 * The format and dimensions of a PNG, JPEG or WebP image, read from its header only (nothing is decoded), so the
 * server can refuse a file the agent would reject before an administrator applies it. Anything else, SVG and GIF
 * included, is not an image here.
 */
public final class KioskImageHeader {

    public final String format;
    public final int width;
    public final int height;

    private KioskImageHeader(String format, int width, int height) {
        this.format = format;
        this.width = width;
        this.height = height;
    }

    /** The header of {@code bytes}, or null when they are not a PNG, JPEG or WebP image with readable dimensions. */
    public static KioskImageHeader read(byte[] bytes) {
        if (bytes == null) return null;
        if (isPng(bytes)) return png(bytes);
        if (bytes.length >= 3 && u8(bytes, 0) == 0xFF && u8(bytes, 1) == 0xD8 && u8(bytes, 2) == 0xFF) return jpeg(bytes);
        if (bytes.length >= 12 && ascii(bytes, 0, "RIFF") && ascii(bytes, 8, "WEBP")) return webp(bytes);
        return null;
    }

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    private static boolean isPng(byte[] b) {
        if (b.length < PNG_SIGNATURE.length) return false;
        for (int i = 0; i < PNG_SIGNATURE.length; i++) {
            if (b[i] != PNG_SIGNATURE[i]) return false;
        }
        return true;
    }

    /** The first chunk of a PNG is IHDR: width and height, 4 bytes each, big-endian. */
    private static KioskImageHeader png(byte[] b) {
        if (b.length < 24 || !ascii(b, 12, "IHDR")) return null;
        long w = be32(b, 16);
        long h = be32(b, 20);
        if (w > Integer.MAX_VALUE || h > Integer.MAX_VALUE) return null;
        return of("PNG", (int) w, (int) h);
    }

    /**
     * Walks the JPEG markers up to the first start-of-frame (SOF0 to SOF15, which covers baseline, extended,
     * progressive and lossless; not DHT C4, JPG C8 or DAC CC). Its body holds precision, then height and width.
     */
    private static KioskImageHeader jpeg(byte[] b) {
        int i = 2;
        while (i < b.length) {
            if (u8(b, i) != 0xFF) return null;
            while (i < b.length && u8(b, i) == 0xFF) i++;   // fill bytes
            if (i >= b.length) return null;
            int marker = u8(b, i++);
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD8)) continue;   // no length
            if (marker == 0xD9 || marker == 0xDA) return null;                    // end, or scan data before a frame
            if (i + 2 > b.length) return null;
            int length = be16(b, i);
            if (length < 2) return null;
            boolean sof = marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
            if (sof) {
                if (length < 7 || i + 7 > b.length) return null;
                return of("JPEG", be16(b, i + 5), be16(b, i + 3));
            }
            i += length;
        }
        return null;
    }

    /** A WebP's first chunk says which bitstream follows: lossy (VP8), lossless (VP8L) or extended (VP8X). */
    private static KioskImageHeader webp(byte[] b) {
        if (b.length < 20) return null;
        int data = 20;
        if (ascii(b, 12, "VP8 ")) {
            // Frame tag (3 bytes), start code 9D 01 2A, then 14-bit width and height, little-endian.
            if (b.length < data + 10) return null;
            if (u8(b, data + 3) != 0x9D || u8(b, data + 4) != 0x01 || u8(b, data + 5) != 0x2A) return null;
            return of("WebP", le16(b, data + 6) & 0x3FFF, le16(b, data + 8) & 0x3FFF);
        }
        if (ascii(b, 12, "VP8L")) {
            // Signature 0x2F, then width-1 and height-1 as 14 bits each, little-endian.
            if (b.length < data + 5 || u8(b, data) != 0x2F) return null;
            long bits = u8(b, data + 1) | (u8(b, data + 2) << 8) | (u8(b, data + 3) << 16) | ((long) u8(b, data + 4) << 24);
            return of("WebP", (int) (bits & 0x3FFF) + 1, (int) ((bits >> 14) & 0x3FFF) + 1);
        }
        if (ascii(b, 12, "VP8X")) {
            // Flags (1), reserved (3), then canvas width-1 and height-1 as 24 bits each, little-endian.
            if (b.length < data + 10) return null;
            return of("WebP", le24(b, data + 4) + 1, le24(b, data + 7) + 1);
        }
        return null;
    }

    private static KioskImageHeader of(String format, int width, int height) {
        return width > 0 && height > 0 ? new KioskImageHeader(format, width, height) : null;
    }

    private static boolean ascii(byte[] b, int at, String s) {
        if (at + s.length() > b.length) return false;
        for (int i = 0; i < s.length(); i++) {
            if (b[at + i] != (byte) s.charAt(i)) return false;
        }
        return true;
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

    private static int le16(byte[] b, int i) {
        return u8(b, i) | (u8(b, i + 1) << 8);
    }

    private static int le24(byte[] b, int i) {
        return u8(b, i) | (u8(b, i + 1) << 8) | (u8(b, i + 2) << 16);
    }
}
