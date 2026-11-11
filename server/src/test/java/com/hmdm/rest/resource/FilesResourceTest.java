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
            for (File part : parts) {
                Assert.assertTrue(part.exists());
                Assert.assertTrue(part.length() > 0);
            }
        } finally {
            for (File part : parts) {
                part.delete();
            }
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
