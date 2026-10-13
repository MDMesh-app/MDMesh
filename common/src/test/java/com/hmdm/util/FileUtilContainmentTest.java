/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.util;

import com.hmdm.persistence.domain.Customer;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Committing an upload may only consume a file the server itself created in the upload (temp) directory, and may
 * only write, rename or delete inside the customer's own files directory.
 */
public class FileUtilContainmentTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path uploadDir;   // the base all per-customer upload directories live under
    private Path cdir;        // this customer's upload directory
    private String filesDir;
    private Customer customer;
    private File secret;

    @Before
    public void setUp() throws IOException {
        uploadDir = tmp.newFolder("upload").toPath();
        filesDir = tmp.newFolder("files").getAbsolutePath();
        new File(filesDir, "acme").mkdirs();
        new File(filesDir, "other").mkdirs();
        customer = new Customer();
        customer.setId(1);
        customer.setFilesDir("acme");
        cdir = FileUtil.uploadDir(customer, uploadDir);
        cdir.toFile().mkdirs();
        secret = tmp.newFile("secret.txt");
        Files.write(secret.toPath(), "top secret".getBytes(StandardCharsets.UTF_8));
    }

    private File upload(String name) throws IOException {
        File f = File.createTempFile(name + FileUtil.TEMP_FILE_DELIMITER, ".temp", cdir.toFile());
        Files.write(f.toPath(), "payload".getBytes(StandardCharsets.UTF_8));
        return f;
    }

    private void assertRejected(Runnable r) {
        try {
            r.run();
            fail("expected UnsafePathException");
        } catch (UnsafePathException expected) {
            // ok
        }
    }

    // ---- source (upload) side ---------------------------------------------------------------------------------------

    @Test
    public void movesAGenuineUpload() throws IOException {
        File src = upload("app.apk");
        File moved = FileUtil.moveFile(customer, filesDir, null, src.getAbsolutePath(), null, uploadDir);
        assertNotNull(moved);
        assertEquals(new File(filesDir, "acme/app.apk").getCanonicalFile(), moved.getCanonicalFile());
        assertFalse(src.exists());
    }

    @Test
    public void movesAnUploadFromTheRealTempDirThroughThePublicOverload() throws IOException {
        // The production path: the upload endpoint creates the file with FileUtil.createUploadFile (java.io.tmpdir).
        File src = FileUtil.createUploadFile(customer, "real.apk");
        Files.write(src.toPath(), "payload".getBytes(StandardCharsets.UTF_8));
        assertTrue(FileUtil.isUploadedTempFile(src.getAbsolutePath(), customer));
        File moved = FileUtil.moveFile(customer, filesDir, null, src.getAbsolutePath());
        assertEquals(new File(filesDir, "acme/real.apk").getCanonicalFile(), moved.getCanonicalFile());
    }

    @Test
    public void movesAGenuineUploadIntoASubdirUnderANewName() throws IOException {
        File src = upload("app.apk");
        File moved = FileUtil.moveFile(customer, filesDir, "sub/dir", src.getAbsolutePath(), "/renamed.apk", uploadDir);
        assertEquals(new File(filesDir, "acme/sub/dir/renamed.apk").getCanonicalFile(), moved.getCanonicalFile());
    }

    @Test
    public void rejectsTraversalOutOfTheUploadDir() {
        String path = cdir + "/../../secret.txt";
        assertRejected(() -> FileUtil.moveFile(customer, filesDir, null, path, "x.txt", uploadDir));
        assertTrue(secret.exists());
        assertFalse(new File(filesDir, "acme/x.txt").exists());
    }

    @Test
    public void rejectsAnAbsolutePathElsewhere() {
        assertRejected(() -> FileUtil.moveFile(customer, filesDir, null, secret.getAbsolutePath(), "x.txt", uploadDir));
        assertTrue(secret.exists());
    }

    @Test
    public void rejectsASymlinkPlantedInTheUploadDir() throws IOException {
        Path link = cdir.resolve("link" + FileUtil.TEMP_FILE_DELIMITER + "1.temp");
        Files.createSymbolicLink(link, secret.toPath());
        assertRejected(() -> FileUtil.moveFile(customer, filesDir, null, link.toString(), "x.txt", uploadDir));
        assertTrue(secret.exists());
        assertFalse(new File(filesDir, "acme/x.txt").exists());
    }

    @Test
    public void rejectsAFileInTheUploadDirThatTheServerDidNotCreate() throws IOException {
        File foreign = cdir.resolve("tomcat-work.dat").toFile();
        Files.write(foreign.toPath(), "x".getBytes(StandardCharsets.UTF_8));
        assertRejected(() -> FileUtil.moveFile(customer, filesDir, null, foreign.getAbsolutePath(), "x.txt", uploadDir));
        assertTrue(foreign.exists());
    }

    @Test
    public void rejectsAFileInANestedDirectoryOfTheUploadDir() throws IOException {
        File nested = cdir.resolve("nested").toFile();
        nested.mkdirs();
        File f = File.createTempFile("a" + FileUtil.TEMP_FILE_DELIMITER, ".temp", nested);
        assertRejected(() -> FileUtil.moveFile(customer, filesDir, null, f.getAbsolutePath(), "x.txt", uploadDir));
    }

    @Test
    public void rejectsNullAndEmptySources() {
        assertFalse(FileUtil.isUploadedTempFile(null, customer, uploadDir));
        assertFalse(FileUtil.isUploadedTempFile("", customer, uploadDir));
        assertFalse(FileUtil.isUploadedTempFile(cdir.toString(), customer, uploadDir));
    }

    @Test
    public void recognisesGenuineUploads() throws IOException {
        assertTrue(FileUtil.isUploadedTempFile(upload("a.apk").getAbsolutePath(), customer, uploadDir));
        assertFalse(FileUtil.isUploadedTempFile(secret.getAbsolutePath(), customer, uploadDir));
        assertFalse(FileUtil.isUploadedTempFile(cdir + "/../../secret.txt", customer, uploadDir));
    }

    @Test
    public void anUploadBelongsToTheCustomerThatMadeIt() throws IOException {
        Customer other = new Customer();
        other.setId(2);
        other.setFilesDir("other");
        File theirs = upload("theirs.apk"); // made by customer 1
        assertFalse(FileUtil.isUploadedTempFile(theirs.getAbsolutePath(), other, uploadDir));
        assertRejected(() -> FileUtil.moveFile(other, filesDir, null, theirs.getAbsolutePath(), null, uploadDir));
        assertTrue(theirs.exists());
        assertFalse(new File(filesDir, "other/theirs.apk").exists());
    }

    @Test
    public void anUploadDirectlyInTheBaseIsNobodys() throws IOException {
        File loose = File.createTempFile("x" + FileUtil.TEMP_FILE_DELIMITER, ".temp", uploadDir.toFile());
        assertFalse(FileUtil.isUploadedTempFile(loose.getAbsolutePath(), customer, uploadDir));
    }

    @Test
    public void deleteDoesNotFollowASymlinkedSubdirOut() throws IOException {
        File outside = tmp.newFolder("outside");
        File outsideFile = new File(outside, "doc.txt");
        Files.write(outsideFile.toPath(), "x".getBytes(StandardCharsets.UTF_8));
        Files.createSymbolicLink(new File(filesDir, "acme/out").toPath(), outside.toPath());
        assertFalse(FileUtil.deleteFile(customer, filesDir, "out/doc.txt"));
        assertTrue(outsideFile.exists());
    }

    // ---- destination side -------------------------------------------------------------------------------------------

    @Test
    public void rejectsATraversingTargetName() throws IOException {
        File src = upload("a.apk");
        assertRejected(() -> FileUtil.moveFile(customer, filesDir, null, src.getAbsolutePath(), "../other/a.apk", uploadDir));
        assertRejected(() -> FileUtil.moveFile(customer, filesDir, null, src.getAbsolutePath(), "..", uploadDir));
        assertTrue("a rejected move must leave the upload in place", src.exists());
        assertFalse(new File(filesDir, "other/a.apk").exists());
    }

    @Test
    public void rejectsATraversingSubdir() throws IOException {
        File src = upload("a.apk");
        assertRejected(() -> FileUtil.moveFile(customer, filesDir, "../other", src.getAbsolutePath(), "a.apk", uploadDir));
        assertRejected(() -> FileUtil.moveFile(customer, filesDir, "sub/../../..", src.getAbsolutePath(), "a.apk", uploadDir));
        assertFalse(new File(filesDir, "other/a.apk").exists());
    }

    @Test
    public void rejectsASubdirThatIsASymlinkOutOfTheCustomerDir() throws IOException {
        Files.createSymbolicLink(new File(filesDir, "acme/out").toPath(), new File(filesDir, "other").toPath());
        File src = upload("a.apk");
        assertRejected(() -> FileUtil.moveFile(customer, filesDir, "out", src.getAbsolutePath(), "a.apk", uploadDir));
        assertFalse(new File(filesDir, "other/a.apk").exists());
    }

    @Test
    public void resolveFileRefusesToLeaveTheCustomerDir() {
        assertRejected(() -> FileUtil.resolveFile(customer, filesDir, null, "../other/x"));
        assertRejected(() -> FileUtil.resolveFile(customer, filesDir, "../other", "x"));
    }

    // ---- rename inside the customer dir -----------------------------------------------------------------------------

    @Test
    public void renamesWithinTheCustomerDir() throws IOException {
        File existing = new File(filesDir, "acme/old.txt");
        Files.write(existing.toPath(), "x".getBytes(StandardCharsets.UTF_8));
        File moved = FileUtil.renameFile(customer, filesDir, "old.txt", "sub/new.txt");
        assertEquals(new File(filesDir, "acme/sub/new.txt").getCanonicalFile(), moved.getCanonicalFile());
        assertFalse(existing.exists());
    }

    @Test
    public void renameRefusesSourcesAndTargetsOutsideTheCustomerDir() throws IOException {
        File theirs = new File(filesDir, "other/theirs.txt");
        Files.write(theirs.toPath(), "x".getBytes(StandardCharsets.UTF_8));
        File mine = new File(filesDir, "acme/mine.txt");
        Files.write(mine.toPath(), "x".getBytes(StandardCharsets.UTF_8));

        assertRejected(() -> FileUtil.renameFile(customer, filesDir, "../other/theirs.txt", "copied.txt"));
        assertRejected(() -> FileUtil.renameFile(customer, filesDir, "mine.txt", "../other/planted.txt"));
        assertTrue(theirs.exists());
        assertTrue(mine.exists());
        assertFalse(new File(filesDir, "other/planted.txt").exists());
    }

    // ---- delete -----------------------------------------------------------------------------------------------------

    @Test
    public void deleteStaysInsideTheCustomerDir() throws IOException {
        File theirs = new File(filesDir, "other/theirs.txt");
        Files.write(theirs.toPath(), "x".getBytes(StandardCharsets.UTF_8));
        assertFalse(FileUtil.deleteFile(customer, filesDir, "../other/theirs.txt"));
        assertTrue(theirs.exists());

        File mine = new File(filesDir, "acme/mine.txt");
        Files.write(mine.toPath(), "x".getBytes(StandardCharsets.UTF_8));
        assertTrue(FileUtil.deleteFile(customer, filesDir, "mine.txt"));
        assertFalse(mine.exists());
    }
}
