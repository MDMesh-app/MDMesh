package com.hmdm.rest.resource;

import com.hmdm.persistence.domain.Customer;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class FilesResourceTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private Customer customer() {
        Customer customer = new Customer();
        customer.setId(1);
        return customer;
    }

    @Test
    public void testExtractApkPartsWithStoredAndDeflatedEntries() throws IOException {
        File zipFile = temporaryFolder.newFile("test_bundle.xapk");

        byte[] baseApkBytes = "dummy base apk content".getBytes("UTF-8");
        byte[] configApkBytes = "dummy config apk content".getBytes("UTF-8");

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))) {
            // Write STORED entry for base.apk
            ZipEntry storedEntry = new ZipEntry("base.apk");
            storedEntry.setMethod(ZipEntry.STORED);
            storedEntry.setSize(baseApkBytes.length);
            storedEntry.setCompressedSize(baseApkBytes.length);
            CRC32 crc32 = new CRC32();
            crc32.update(baseApkBytes);
            storedEntry.setCrc(crc32.getValue());
            zos.putNextEntry(storedEntry);
            zos.write(baseApkBytes);
            zos.closeEntry();

            // Write DEFLATED entry for config.apk
            ZipEntry deflatedEntry = new ZipEntry("config.apk");
            deflatedEntry.setMethod(ZipEntry.DEFLATED);
            zos.putNextEntry(deflatedEntry);
            zos.write(configApkBytes);
            zos.closeEntry();

            // Write a non-apk entry that should be ignored
            ZipEntry nonApkEntry = new ZipEntry("icon.png");
            nonApkEntry.setMethod(ZipEntry.DEFLATED);
            zos.putNextEntry(nonApkEntry);
            zos.write("icon image bytes".getBytes("UTF-8"));
            zos.closeEntry();
        }

        FilesResource filesResource = new FilesResource();
        List<File> parts = filesResource.extractApkParts(zipFile, customer());

        try {
            Assert.assertEquals(2, parts.size());
            Assert.assertArrayEquals(baseApkBytes, readBytes(parts.get(0)));
            Assert.assertArrayEquals(configApkBytes, readBytes(parts.get(1)));
        } finally {
            for (File part : parts) {
                part.delete();
            }
        }
    }

    @Test
    public void testExtractStoredEntryWithDataDescriptor() throws IOException {
        File zipFile = temporaryFolder.newFile("stored_data_descriptor.xapk");
        byte[] apkBytes = "stored apk with data descriptor".getBytes("UTF-8");
        writeStoredApk(zipFile, apkBytes);
        setStoredEntryDataDescriptor(zipFile);

        List<File> parts = new FilesResource().extractApkParts(zipFile, customer());
        try {
            Assert.assertEquals(1, parts.size());
            Assert.assertArrayEquals(apkBytes, readBytes(parts.get(0)));
        } finally {
            for (File part : parts) part.delete();
        }
    }

    @Test
    public void testExtractRejectsEntryWithInvalidCrc() throws IOException {
        File zipFile = temporaryFolder.newFile("invalid_crc.xapk");
        byte[] apkBytes = "stored apk with bad crc".getBytes("UTF-8");
        writeStoredApk(zipFile, apkBytes);
        byte[] zipBytes = readBytes(zipFile);
        int dataOffset = 30 + littleEndianShort(zipBytes, 26) + littleEndianShort(zipBytes, 28);
        zipBytes[dataOffset] ^= 1;
        try (FileOutputStream out = new FileOutputStream(zipFile)) {
            out.write(zipBytes);
        }

        try {
            new FilesResource().extractApkParts(zipFile, customer());
            Assert.fail("Expected an IOException for an APK with an invalid CRC");
        } catch (IOException expected) {
            // The entry must not be returned as an extracted APK.
        }
    }

    @Test
    public void testFailedExtractionLeavesNoTempFiles() throws IOException {
        File zipFile = temporaryFolder.newFile("bad_second_entry.xapk");
        writeGoodSplitThenBadStoredApk(zipFile);

        Customer customer = customer(920001);
        int before = bundlePartCount(customer);
        try {
            new FilesResource().extractApkParts(zipFile, customer);
            Assert.fail("Expected an IOException for an APK with an invalid CRC");
        } catch (IOException expected) {
            // The split extracted before the bad entry must be removed too.
        }
        Assert.assertEquals(before, bundlePartCount(customer));
    }

    @Test
    public void testLaterUniversalApkReplacesEarlierOneWithoutLeaking() throws IOException {
        File zipFile = temporaryFolder.newFile("two_universal.apks");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))) {
            zos.putNextEntry(new ZipEntry("a/universal.apk"));
            zos.write("first universal".getBytes("UTF-8"));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("b/universal.apk"));
            zos.write("second universal".getBytes("UTF-8"));
            zos.closeEntry();
        }

        Customer customer = customer(920002);
        int before = bundlePartCount(customer);
        List<File> parts = new FilesResource().extractApkParts(zipFile, customer);
        try {
            Assert.assertEquals(1, parts.size());
            Assert.assertEquals("second universal", new String(readBytes(parts.get(0)), "UTF-8"));
            Assert.assertEquals(before + 1, bundlePartCount(customer));
        } finally {
            for (File part : parts) part.delete();
        }
    }

    @Test
    public void testExtractFromNonZipFindsNoApks() throws IOException {
        File notAZip = temporaryFolder.newFile("encrypted.apkm");
        try (FileOutputStream out = new FileOutputStream(notAZip)) {
            out.write("not a zip archive at all".getBytes("UTF-8"));
        }
        Assert.assertTrue(new FilesResource().extractApkParts(notAZip, customer()).isEmpty());
        Assert.assertTrue(new FilesResource().extractApkParts(temporaryFolder.newFile("empty.xapk"), customer())
                .isEmpty());
    }

    @Test
    public void testExtractRejectsEntryLargerThanDeclared() throws IOException {
        File zipFile = temporaryFolder.newFile("understated_size.xapk");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))) {
            zos.putNextEntry(new ZipEntry("base.apk"));
            zos.write(new byte[1024 * 1024]); // compresses to almost nothing
            zos.closeEntry();
        }
        byte[] zipBytes = readBytes(zipFile);
        putLittleEndianInt(zipBytes, findSignature(zipBytes, 0x02014b50) + 24, 16); // declare 16 bytes
        try (FileOutputStream out = new FileOutputStream(zipFile)) {
            out.write(zipBytes);
        }

        Customer customer = customer(920003);
        int before = bundlePartCount(customer);
        try {
            new FilesResource().extractApkParts(zipFile, customer);
            Assert.fail("Expected an IOException for an entry larger than its declared size");
        } catch (IOException expected) {
            Assert.assertTrue(expected.getMessage(), expected.getMessage().contains("declared size"));
        }
        Assert.assertEquals(before, bundlePartCount(customer));
    }

    @Test
    public void testExtractRefusesBundleOverTotalUnpackedLimit() throws IOException {
        File zipFile = temporaryFolder.newFile("too_large_total.xapk");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))) {
            for (String name : new String[] {"base.apk", "split_config.arm64_v8a.apk", "obb/main.obb"}) {
                zos.putNextEntry(new ZipEntry(name));
                zos.write(new byte[1000]); // each entry is within its declared size; only the total is too large
                zos.closeEntry();
            }
        }

        Customer customer = customer(920004);
        int before = bundlePartCount(customer);
        try {
            new FilesResource().extractApkParts(zipFile, customer, 1999);
            Assert.fail("Expected a BundleTooLargeException for APK entries over the total limit");
        } catch (FilesResource.BundleTooLargeException expected) {
            // Refused before any part was written.
        }
        Assert.assertEquals(before, bundlePartCount(customer));

        // Only the APK entries count: the 1000-byte .obb does not push 2000 bytes of APKs over a 2000-byte limit.
        List<File> parts = new FilesResource().extractApkParts(zipFile, customer, 2000);
        try {
            Assert.assertEquals(2, parts.size());
        } finally {
            for (File part : parts) part.delete();
        }
        Assert.assertEquals(4L * 1024 * 1024 * 1024, FilesResource.MAX_BUNDLE_UNPACKED_BYTES);
    }

    @Test
    public void testUnpackedSizeCheckDoesNotOverflow() throws IOException {
        // A small APK, then a ZIP64 entry declaring a size near Long.MAX_VALUE: the sum would wrap negative.
        ZipEntry small = new ZipEntry("base.apk");
        small.setSize(1000);
        ZipEntry huge = new ZipEntry("split.apk");
        huge.setSize(Long.MAX_VALUE - 10);
        try {
            FilesResource.checkUnpackedSize(java.util.Arrays.asList(small, huge),
                    FilesResource.MAX_BUNDLE_UNPACKED_BYTES);
            Assert.fail("Expected a BundleTooLargeException for an entry declaring a size near Long.MAX_VALUE");
        } catch (FilesResource.BundleTooLargeException expected) {
            // Refused, not wrapped around.
        }
        FilesResource.checkUnpackedSize(java.util.Arrays.asList(small, small), 2000); // exactly the limit passes
    }

    private Customer customer(int id) {
        Customer customer = new Customer();
        customer.setId(id);
        return customer;
    }

    /** The extracted parts currently left in [customer]'s upload directory. */
    private int bundlePartCount(Customer customer) throws IOException {
        File probe = com.hmdm.util.FileUtil.createUploadFile(customer, "probe");
        try {
            File[] left = probe.getParentFile().listFiles((dir, name) -> name.startsWith("bundlepart"));
            return left == null ? 0 : left.length;
        } finally {
            probe.delete();
        }
    }

    /** A DEFLATED good split, then a STORED base.apk whose data no longer matches its CRC. */
    private void writeGoodSplitThenBadStoredApk(File zipFile) throws IOException {
        byte[] badBytes = "stored apk with bad crc".getBytes("UTF-8");
        CRC32 crc = new CRC32();
        crc.update(badBytes);
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))) {
            zos.putNextEntry(new ZipEntry("config.apk"));
            zos.write("good split".getBytes("UTF-8"));
            zos.closeEntry();
            ZipEntry stored = new ZipEntry("base.apk");
            stored.setMethod(ZipEntry.STORED);
            stored.setSize(badBytes.length);
            stored.setCompressedSize(badBytes.length);
            stored.setCrc(crc.getValue());
            zos.putNextEntry(stored);
            zos.write(badBytes);
            zos.closeEntry();
        }
        byte[] zipBytes = readBytes(zipFile);
        int localOffset = findLastSignature(zipBytes, 0x04034b50);
        int dataOffset = localOffset + 30 + littleEndianShort(zipBytes, localOffset + 26)
                + littleEndianShort(zipBytes, localOffset + 28);
        zipBytes[dataOffset] ^= 1;
        try (FileOutputStream out = new FileOutputStream(zipFile)) {
            out.write(zipBytes);
        }
    }

    private int findLastSignature(byte[] bytes, int signature) throws IOException {
        for (int i = bytes.length - 4; i >= 0; i--) {
            if (littleEndianInt(bytes, i) == signature) return i;
        }
        throw new IOException("ZIP signature not found");
    }

    private void writeStoredApk(File zipFile, byte[] apkBytes) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))) {
            ZipEntry entry = new ZipEntry("base.apk");
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(apkBytes.length);
            entry.setCompressedSize(apkBytes.length);
            CRC32 crc = new CRC32();
            crc.update(apkBytes);
            entry.setCrc(crc.getValue());
            zos.putNextEntry(entry);
            zos.write(apkBytes);
            zos.closeEntry();
        }
    }

    /**
     * Turn a one-entry STORED ZIP into a valid data-descriptor archive. ZipOutputStream does not
     * emit data descriptors for STORED entries when their size and CRC are known in advance.
     */
    private void setStoredEntryDataDescriptor(File zipFile) throws IOException {
        byte[] original = readBytes(zipFile);
        int centralOffset = findSignature(original, 0x02014b50);
        int endOffset = findSignature(original, 0x06054b50);
        int nameLength = littleEndianShort(original, 26);
        int extraLength = littleEndianShort(original, 28);
        int dataOffset = 30 + nameLength + extraLength;
        int compressedSize = littleEndianInt(original, 18);
        int descriptorOffset = dataOffset + compressedSize;
        long crc = littleEndianInt(original, 14) & 0xffffffffL;

        byte[] updated = new byte[original.length + 16];
        System.arraycopy(original, 0, updated, 0, descriptorOffset);
        putLittleEndianInt(updated, descriptorOffset, 0x08074b50);
        putLittleEndianInt(updated, descriptorOffset + 4, (int) crc);
        putLittleEndianInt(updated, descriptorOffset + 8, compressedSize);
        putLittleEndianInt(updated, descriptorOffset + 12, compressedSize);
        System.arraycopy(original, descriptorOffset, updated, descriptorOffset + 16,
                original.length - descriptorOffset);

        setDataDescriptorFlag(updated, 6);
        putLittleEndianInt(updated, 14, 0);
        putLittleEndianInt(updated, 18, 0);
        putLittleEndianInt(updated, 22, 0);
        int shiftedCentralOffset = centralOffset + 16;
        setDataDescriptorFlag(updated, shiftedCentralOffset + 8);
        putLittleEndianInt(updated, endOffset + 16 + 16, centralOffset + 16);
        try (FileOutputStream out = new FileOutputStream(zipFile)) {
            out.write(updated);
        }
    }

    private void setDataDescriptorFlag(byte[] bytes, int flagsOffset) {
        int flags = littleEndianShort(bytes, flagsOffset) | 0x0008;
        bytes[flagsOffset] = (byte) flags;
        bytes[flagsOffset + 1] = (byte) (flags >>> 8);
    }

    private int findSignature(byte[] bytes, int signature) throws IOException {
        for (int i = 0; i <= bytes.length - 4; i++) {
            if (littleEndianInt(bytes, i) == signature) return i;
        }
        throw new IOException("ZIP signature not found");
    }

    private int littleEndianShort(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
    }

    private int littleEndianInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8)
                | ((bytes[offset + 2] & 0xff) << 16) | ((bytes[offset + 3] & 0xff) << 24);
    }

    private void putLittleEndianInt(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
        bytes[offset + 2] = (byte) (value >>> 16);
        bytes[offset + 3] = (byte) (value >>> 24);
    }

    private byte[] readBytes(File file) throws IOException {
        try (java.io.InputStream in = new java.io.FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int n;
            while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }

    @Test
    public void testExtractApkPartsUniversalApk() throws IOException {
        File zipFile = temporaryFolder.newFile("bundletool.apks");

        byte[] universalBytes = "universal apk content".getBytes("UTF-8");
        byte[] splitBytes = "split apk content".getBytes("UTF-8");

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))) {
            ZipEntry universalEntry = new ZipEntry("universal.apk");
            zos.putNextEntry(universalEntry);
            zos.write(universalBytes);
            zos.closeEntry();

            ZipEntry splitEntry = new ZipEntry("base-master.apk");
            zos.putNextEntry(splitEntry);
            zos.write(splitBytes);
            zos.closeEntry();
        }

        FilesResource filesResource = new FilesResource();
        List<File> parts = filesResource.extractApkParts(zipFile, customer());

        try {
            Assert.assertEquals(1, parts.size());
            File universalFile = parts.get(0);
            Assert.assertTrue(universalFile.exists());
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (java.io.InputStream is = new java.io.FileInputStream(universalFile)) {
                byte[] buf = new byte[1024];
                int n;
                while ((n = is.read(buf)) >= 0) baos.write(buf, 0, n);
            }
            Assert.assertEquals("universal apk content", new String(baos.toByteArray(), "UTF-8"));
        } finally {
            for (File part : parts) {
                part.delete();
            }
        }
    }
}
