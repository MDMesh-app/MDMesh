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
        UploadedFile byPath;
        /** The row belongs to another customer: the real DAO refuses it with a SecurityException. */
        boolean foreign;
        final List<UploadedFile> written = new ArrayList<>();
        FakeUploadedFileDAO() { super(null, null, null, "unused"); }
        @Override public UploadedFile getById(Integer id) {
            if (foreign) throw com.hmdm.security.SecurityException.onAdminDataAccessViolation("not this customer's file");
            return existing;
        }
        @Override public UploadedFile insert(UploadedFile file) { written.add(file); return file; }
        @Override public void update(UploadedFile file) { written.add(file); }
        final List<Integer> removed = new ArrayList<>();
        @Override public void remove(int fileId) { removed.add(fileId); }
        @Override public UploadedFile getByPath(Integer customerId, String filePath) { return byPath; }
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
    public void aDuplicateCommitReusesAnExistingFileOnlyWhenBytesMatch() throws IOException {
        File hosted = new File(filesDir, "acme/background.jpg");
        Files.write(hosted.toPath(), "same image bytes".getBytes(StandardCharsets.UTF_8));
        UploadedFile existing = hosted(19, "background.jpg");
        existing.setCustomerId(1);
        uploadedFileDAO.byPath = existing;

        File retry = com.hmdm.util.FileUtil.createUploadFile(customer, "background.jpg");
        Files.write(retry.toPath(), "same image bytes".getBytes(StandardCharsets.UTF_8));
        Response result = resource.updateFile(commit(retry.getAbsolutePath(), "background.jpg"));

        assertEquals(Response.ResponseStatus.OK, result.getStatus());
        assertEquals(existing, result.getData());
        assertFalse("the duplicate temporary upload is removed", retry.exists());
        assertTrue(hosted.exists());
        assertEquals(0, uploadedFileDAO.written.size());
    }

    @Test
    public void aDifferentFileWithTheSameNameRemainsRejected() throws IOException {
        File hosted = new File(filesDir, "acme/background.jpg");
        Files.write(hosted.toPath(), "old image".getBytes(StandardCharsets.UTF_8));
        UploadedFile existing = hosted(19, "background.jpg");
        existing.setCustomerId(1);
        uploadedFileDAO.byPath = existing;

        File retry = com.hmdm.util.FileUtil.createUploadFile(customer, "background.jpg");
        Files.write(retry.toPath(), "new image".getBytes(StandardCharsets.UTF_8));
        Response result = resource.updateFile(commit(retry.getAbsolutePath(), "background.jpg"));

        assertEquals(Response.ResponseStatus.ERROR, result.getStatus());
        assertEquals("error.duplicate.file", result.getMessage());
        assertTrue("a rejected upload is retained for the caller's normal error handling", retry.exists());
        assertEquals("old image", new String(Files.readAllBytes(hosted.toPath()), StandardCharsets.UTF_8));
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

    /** Files → edit of a file a configuration save holds is refused (the save hashed these bytes). */
    @Test
    public void editingAFileASaveHoldsIsRefused() throws IOException {
        uploadedFileDAO.existing = hosted(5, "kiosk-aaaaaaaaaaaa-beach.jpg");
        resource.setKioskImages(new com.hmdm.rest.resource.support.KioskBackgroundImageAssets(uploadedFileDAO,
                new FakeCustomerDAO(customer), "https://mdm.example.com", filesDir.getAbsolutePath()) {
            @Override public EditStart beginEdit(int fileId) {
                return fileId == 5 ? EditStart.HELD : super.beginEdit(fileId);
            }
        });
        UploadedFile edit = hosted(5, "kiosk-aaaaaaaaaaaa-beach.jpg");
        edit.setDescription("renamed");
        Response r = resource.updateFile(edit);
        assertEquals("error.used.file", r.getMessage());
        assertTrue(uploadedFileDAO.written.isEmpty());
    }

    /** Background images whose own rows (for holds) are always there, whatever the Files fake says. */
    private com.hmdm.rest.resource.support.KioskBackgroundImageAssets realKioskImages() {
        FakeUploadedFileDAO rows = new FakeUploadedFileDAO();
        rows.existing = hosted(5, "kiosk-aaaaaaaaaaaa-beach.jpg");
        com.hmdm.rest.resource.support.KioskBackgroundImageAssets assets = new com.hmdm.rest.resource.support.KioskBackgroundImageAssets(
                rows, new FakeCustomerDAO(customer), "https://mdm.example.com", filesDir.getAbsolutePath());
        resource.setKioskImages(assets);
        return assets;
    }

    private static com.hmdm.rest.json.view.FileView removal(int id) {
        com.hmdm.rest.json.view.FileView req = new com.hmdm.rest.json.view.FileView();
        req.setId(id);
        req.setFilePath("kiosk-aaaaaaaaaaaa-beach.jpg");
        return req;
    }

    /**
     * Edit and Remove of another customer's file id get the same refusal whether or not one of that customer's saves
     * holds it or an edit of it runs, and never change that state.
     */
    @Test
    public void anotherCustomersFileIsRefusedTheSameWayAndItsStateIsUntouched() {
        com.hmdm.rest.resource.support.KioskBackgroundImageAssets assets = realKioskImages();
        uploadedFileDAO.foreign = true;
        UploadedFile edit = hosted(5, "kiosk-aaaaaaaaaaaa-beach.jpg");

        // Idle.
        assertDenied(resource.updateFile(edit));
        assertDenied(resource.removeFile(removal(5)));
        assertFalse("a refused edit leaves no mark", assets.isHeld(5));
        assertEquals(com.hmdm.rest.resource.support.KioskBackgroundImageAssets.EditStart.STARTED, assets.beginEdit(5));

        // Mid-edit by the owner.
        assertDenied(resource.updateFile(edit));
        assertDenied(resource.removeFile(removal(5)));
        assertEquals("the owner's edit is still running",
                com.hmdm.rest.resource.support.KioskBackgroundImageAssets.EditStart.EDITING, assets.beginEdit(5));
        assets.endEdit(5);

        // Held by the owner's save.
        com.hmdm.rest.resource.support.KioskBackgroundImageAssets.Applied save = assets.holdExisting(5, true);
        assertDenied(resource.updateFile(edit));
        assertDenied(resource.removeFile(removal(5)));
        assertTrue("the owner's save still holds it", assets.isHeld(5));
        save.release();
        assertFalse(assets.isHeld(5));
        assertEquals("no edit mark was left behind",
                com.hmdm.rest.resource.support.KioskBackgroundImageAssets.EditStart.STARTED, assets.beginEdit(5));
        assets.endEdit(5);
        assertTrue(uploadedFileDAO.written.isEmpty());
        assertTrue(uploadedFileDAO.removed.isEmpty());
    }

    /** Remove and a second edit of a file being changed in Files say so. */
    @Test
    public void removeAndEditOfAFileBeingEditedAreRefusedAsBeingChanged() {
        com.hmdm.rest.resource.support.KioskBackgroundImageAssets assets = realKioskImages();
        uploadedFileDAO.existing = hosted(5, "kiosk-aaaaaaaaaaaa-beach.jpg");
        assets.beginEdit(5);
        try {
            assertEquals(com.hmdm.rest.resource.support.KioskBackgroundImageAssets.BEING_EDITED,
                    resource.removeFile(removal(5)).getMessage());
            UploadedFile edit = hosted(5, "kiosk-aaaaaaaaaaaa-beach.jpg");
            edit.setDescription("renamed");
            assertEquals(com.hmdm.rest.resource.support.KioskBackgroundImageAssets.BEING_EDITED,
                    resource.updateFile(edit).getMessage());
            assertEquals("the second edit didn't end the first",
                    com.hmdm.rest.resource.support.KioskBackgroundImageAssets.EditStart.EDITING, assets.beginEdit(5));
        } finally {
            assets.endEdit(5);
        }
        assertTrue(uploadedFileDAO.written.isEmpty());
        assertTrue(uploadedFileDAO.removed.isEmpty());
    }
}
