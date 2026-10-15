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
import static org.junit.Assert.fail;

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
        final List<Integer> removed = new ArrayList<>();
        @Override public void remove(int fileId) { removed.add(fileId); }
        @Override public UploadedFile getByPath(Integer customerId, String filePath) { return null; }
    }

    private static final class UnusedConfigurationFileDAO extends com.hmdm.persistence.ConfigurationFileDAO {
        UnusedConfigurationFileDAO() { super(null, null, null); }
        @Override public boolean isFileUsed(Integer fileId) { return false; }
        @Override public List<String> getUsingConfigurations(Integer customerId, Integer fileId) { return new ArrayList<>(); }
    }

    private static final class UnusedIconDAO extends com.hmdm.persistence.IconDAO {
        UnusedIconDAO() { super(null); }
        @Override public boolean isFileUsed(Integer fileId) { return false; }
        @Override public List<String> getUsingIcons(Integer customerId, Integer fileId) { return new ArrayList<>(); }
    }

    private File filesDir;
    private File secret;
    private Customer customer;
    private FakeUploadedFileDAO uploadedFileDAO;
    private FilesResource resource;

    @Before
    public void setUp() throws IOException {
        filesDir = tmp.newFolder("files");
        new File(filesDir, "acme").mkdirs();
        secret = tmp.newFile("secret.txt");
        Files.write(secret.toPath(), "top secret".getBytes(StandardCharsets.UTF_8));

        customer = new Customer();
        customer.setId(1);
        customer.setFilesDir("acme");
        uploadedFileDAO = new FakeUploadedFileDAO();
        resource = new FilesResource(filesDir.getAbsolutePath(), "https://mdm.example.com",
                new FakeCustomerDAO(customer), null, null, null, uploadedFileDAO, null,
                new UnusedConfigurationFileDAO(), new UnusedIconDAO(), null);

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

    private static UploadedFile external(Integer id, String url) {
        UploadedFile f = new UploadedFile();
        f.setId(id);
        f.setExternal(true);
        f.setExternalUrl(url);
        f.setFilePath("");
        return f;
    }

    @Test
    public void anExternalFileNeedsAnHttpUrl() {
        for (String url : new String[]{"file:///etc/passwd", "ftp://host/a.png", "jar:file:/x.jar!/a"}) {
            Response r = resource.updateFile(external(null, url));
            assertEquals(url, FilesResource.EXTERNAL_URL_NOT_HTTP, r.getMessage());
        }
        assertEquals(0, uploadedFileDAO.written.size());
        assertEquals(Response.ResponseStatus.OK, resource.updateFile(external(null, "https://cdn.example.com/a.png")).getStatus());
    }

    @Test
    public void anOlderExternalFileKeepsItsUrlButCannotGetANonHttpOne() {
        uploadedFileDAO.existing = external(5, "ftp://legacy/a.png");
        assertEquals("unchanged legacy URL", Response.ResponseStatus.OK,
                resource.updateFile(external(5, "ftp://legacy/a.png")).getStatus());
        assertEquals(FilesResource.EXTERNAL_URL_NOT_HTTP, resource.updateFile(external(5, "file:///etc/passwd")).getMessage());
        assertEquals(Response.ResponseStatus.OK,
                resource.updateFile(external(5, "https://cdn.example.com/a.png")).getStatus());
    }

    private static UploadedFile hosted(Integer id, String filePath) {
        UploadedFile f = new UploadedFile();
        f.setId(id);
        f.setExternal(false);
        f.setFilePath(filePath);
        return f;
    }

    private static void expectKindChangeRefused(Response r) {
        assertEquals(FilesResource.FILE_KIND_CHANGE, r.getMessage());
    }

    @Test
    public void aHostedFileUpdateNeverStoresAnExternalUrlFromTheRequest() {
        uploadedFileDAO.existing = hosted(5, "a.png");
        UploadedFile req = hosted(5, "a.png");
        req.setExternalUrl("file:///etc/passwd");
        assertEquals(Response.ResponseStatus.OK, resource.updateFile(req).getStatus());
        assertEquals(1, uploadedFileDAO.written.size());
        assertEquals(null, uploadedFileDAO.written.get(0).getExternalUrl());
        assertFalse(uploadedFileDAO.written.get(0).isExternal());
    }

    @Test
    public void anUpdateCannotTurnAHostedFileExternal() {
        uploadedFileDAO.existing = hosted(5, "a.png");
        expectKindChangeRefused(resource.updateFile(external(5, "file:///etc/passwd")));
        expectKindChangeRefused(resource.updateFile(external(5, "https://cdn.example.com/a.png")));
        assertEquals(0, uploadedFileDAO.written.size());
    }

    @Test
    public void anUpdateCannotTurnAnExternalFileHosted() {
        uploadedFileDAO.existing = external(5, "https://cdn.example.com/a.png");
        UploadedFile req = hosted(5, "");
        req.setExternalUrl("file:///etc/passwd");
        expectKindChangeRefused(resource.updateFile(req));
        assertEquals(0, uploadedFileDAO.written.size());
    }

    @Test
    public void anUpdateOfAMissingFileIsDenied() {
        uploadedFileDAO.existing = null;
        assertDenied(resource.updateFile(external(5, "https://cdn.example.com/a.png")));
        assertDenied(resource.updateFile(hosted(5, "a.png")));
        assertEquals(0, uploadedFileDAO.written.size());
    }

    @Test
    public void aNewHostedFileNeverStoresAnExternalUrl() throws IOException {
        File tmpFile = com.hmdm.util.FileUtil.createUploadFile(customer, "new.png");
        UploadedFile req = commit(tmpFile.getAbsolutePath(), "new.png");
        req.setExternalUrl("file:///etc/passwd");
        assertEquals(Response.ResponseStatus.OK, resource.updateFile(req).getStatus());
        assertEquals(1, uploadedFileDAO.written.size());
        assertEquals(null, uploadedFileDAO.written.get(0).getExternalUrl());
        new File(filesDir, "acme/new.png").delete();
    }

    @Test
    public void createRejectsTraversalThroughTheTempDir() {
        String tmpdir = System.getProperty("java.io.tmpdir");
        Response r = resource.updateFile(commit(tmpdir + "/.." + secret.getAbsolutePath(), "copied.txt"));
        assertDenied(r);
        assertTrue(secret.exists());
        assertFalse(new File(filesDir, "acme/copied.txt").exists());
        assertEquals(0, uploadedFileDAO.written.size());
    }

    @Test
    public void createRejectsAnArbitraryAbsolutePath() {
        // TemporaryFolder lives under java.io.tmpdir, so this also passes a naive prefix check.
        Response r = resource.updateFile(commit(secret.getAbsolutePath(), "copied.txt"));
        assertDenied(r);
        assertTrue(secret.exists());
        assertFalse(new File(filesDir, "acme/copied.txt").exists());
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

    @Test
    public void createRejectsAnotherCustomersUpload() throws IOException {
        Customer other = new Customer();
        other.setId(2);
        other.setFilesDir("other");
        File theirs = com.hmdm.util.FileUtil.createUploadFile(other, "theirs.apk");
        theirs.deleteOnExit();

        Response r = resource.updateFile(commit(theirs.getAbsolutePath(), "copied.apk"));

        assertDenied(r);
        assertTrue(theirs.exists());
        assertFalse(new File(filesDir, "acme/copied.apk").exists());
    }

    @Test
    public void replaceIsRejectedBeforeDeletingWhenTheRecordedPathLeadsOut() throws IOException {
        File outside = tmp.newFolder("outside");
        File outsideFile = new File(outside, "doc.txt");
        Files.write(outsideFile.toPath(), "outside".getBytes(StandardCharsets.UTF_8));
        Files.createSymbolicLink(new File(filesDir, "acme/out").toPath(), outside.toPath());
        UploadedFile existing = new UploadedFile();
        existing.setId(7);
        existing.setCustomerId(1);
        existing.setFilePath("out/doc.txt");
        uploadedFileDAO.existing = existing;
        File upload = com.hmdm.util.FileUtil.createUploadFile(customer, "doc.txt");
        upload.deleteOnExit();

        UploadedFile req = commit(upload.getAbsolutePath(), "out/doc.txt");
        req.setId(7);
        assertEquals(FilesResource.UNSAFE_FILE_PATH, resource.updateFile(req).getMessage());
        assertTrue(outsideFile.exists());
        assertEquals(0, uploadedFileDAO.written.size());
    }

    @Test
    public void removeDeletesTheRecordsFileNotTheRequestedPath() throws IOException {
        File mine = new File(filesDir, "acme/mine.txt");
        File other = new File(filesDir, "acme/other.txt");
        Files.write(mine.toPath(), "x".getBytes(StandardCharsets.UTF_8));
        Files.write(other.toPath(), "y".getBytes(StandardCharsets.UTF_8));
        UploadedFile record = new UploadedFile();
        record.setId(5);
        record.setCustomerId(1);
        record.setFilePath("mine.txt");
        uploadedFileDAO.existing = record;

        com.hmdm.rest.json.view.FileView req = new com.hmdm.rest.json.view.FileView();
        req.setId(5);
        req.setFilePath("other.txt");
        Response r = resource.removeFile(req);

        assertEquals(Response.ResponseStatus.OK, r.getStatus());
        assertTrue("a file of another record must survive", other.exists());
        assertFalse(mine.exists());
    }
}
