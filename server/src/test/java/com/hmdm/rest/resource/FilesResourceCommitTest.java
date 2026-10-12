/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.rest.resource;

import com.hmdm.persistence.CustomerDAO;
import com.hmdm.persistence.UploadedFileDAO;
import com.hmdm.persistence.domain.Customer;
import com.hmdm.persistence.domain.UploadedFile;
import com.hmdm.persistence.domain.User;
import com.hmdm.persistence.domain.UserRole;
import com.hmdm.rest.json.Response;
import com.hmdm.security.SecurityContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * POST /private/web-ui-files/update may only commit a file the upload endpoint created in the temp directory, for a
 * new record and when replacing the content of an existing one.
 */
public class FilesResourceCommitTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final class FakeCustomerDAO extends CustomerDAO {
        final Customer customer;
        FakeCustomerDAO(Customer customer) {
            super(null, null, null, null, null, null, null, "unused", 0, null);
            this.customer = customer;
        }
        @Override public Customer findById(int customerId) { return customer; }
    }

    private static final class FakeUploadedFileDAO extends UploadedFileDAO {
        UploadedFile existing;
        final List<UploadedFile> written = new ArrayList<>();
        FakeUploadedFileDAO() { super(null, null, null, "unused"); }
        @Override public UploadedFile getById(Integer id) { return existing; }
        @Override public UploadedFile insert(UploadedFile file) { written.add(file); return file; }
        @Override public void update(UploadedFile file) { written.add(file); }
    }

    private File filesDir;
    private File secret;
    private FakeUploadedFileDAO uploadedFileDAO;
    private FilesResource resource;

    @Before
    public void setUp() throws IOException {
        filesDir = tmp.newFolder("files");
        new File(filesDir, "acme").mkdirs();
        secret = tmp.newFile("secret.txt");
        Files.write(secret.toPath(), "top secret".getBytes(StandardCharsets.UTF_8));

        Customer customer = new Customer();
        customer.setId(1);
        customer.setFilesDir("acme");
        uploadedFileDAO = new FakeUploadedFileDAO();
        resource = new FilesResource(filesDir.getAbsolutePath(), "https://mdm.example.com",
                new FakeCustomerDAO(customer), null, null, null, uploadedFileDAO, null, null, null, null);

        UserRole role = new UserRole();
        role.setSuperAdmin(true);
        User user = new User();
        user.setCustomerId(1);
        user.setLogin("admin");
        user.setUserRole(role);
        SecurityContext.init(user);
    }

    @After
    public void tearDown() {
        SecurityContext.release();
    }

    private static UploadedFile commit(String tmpPath, String filePath) {
        UploadedFile f = new UploadedFile();
        f.setTmpPath(tmpPath);
        f.setFilePath(filePath);
        f.setExternal(false);
        return f;
    }

    private void assertDenied(Response r) {
        assertEquals(Response.ResponseStatus.ERROR, r.getStatus());
        assertEquals("error.permission.denied", r.getMessage());
    }

    @Test
    public void createRejectsTraversalThroughTheTempDir() {
        String tmpdir = System.getProperty("java.io.tmpdir");
        Response r = resource.updateFile(commit(tmpdir + "/.." + secret.getAbsolutePath(), "stolen.txt"));
        assertDenied(r);
        assertTrue(secret.exists());
        assertFalse(new File(filesDir, "acme/stolen.txt").exists());
        assertEquals(0, uploadedFileDAO.written.size());
    }

    @Test
    public void createRejectsAnArbitraryAbsolutePath() {
        // TemporaryFolder lives under java.io.tmpdir, so this also passes a naive prefix check.
        Response r = resource.updateFile(commit(secret.getAbsolutePath(), "stolen.txt"));
        assertDenied(r);
        assertTrue(secret.exists());
        assertFalse(new File(filesDir, "acme/stolen.txt").exists());
        assertEquals(0, uploadedFileDAO.written.size());
    }

    @Test
    public void replaceRejectsAnArbitrarySourceAndKeepsTheCurrentFile() throws IOException {
        File current = new File(filesDir, "acme/doc.txt");
        Files.write(current.toPath(), "current".getBytes(StandardCharsets.UTF_8));
        UploadedFile existing = new UploadedFile();
        existing.setId(5);
        existing.setCustomerId(1);
        existing.setFilePath("doc.txt");
        uploadedFileDAO.existing = existing;

        UploadedFile req = commit(secret.getAbsolutePath(), "doc.txt");
        req.setId(5);
        Response r = resource.updateFile(req);

        assertDenied(r);
        assertTrue(secret.exists());
        assertTrue("the current content must survive a rejected replace", current.exists());
        assertEquals("current", new String(Files.readAllBytes(current.toPath()), StandardCharsets.UTF_8));
        assertEquals(0, uploadedFileDAO.written.size());
    }
}
