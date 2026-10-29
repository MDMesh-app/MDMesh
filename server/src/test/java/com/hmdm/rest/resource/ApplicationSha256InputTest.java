package com.hmdm.rest.resource;

import com.hmdm.persistence.ApplicationDAO;
import com.hmdm.persistence.domain.Application;
import com.hmdm.rest.json.Response;
import com.hmdm.security.SecurityContext;
import com.hmdm.testsupport.Fakes;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The APK SHA-256 an admin types (Library "SHA-256…" and the external-URL app form) is taken only as 64 hex
 * characters. A truncated hex hash would otherwise also read as base64 and be stored as a different digest.
 */
public class ApplicationSha256InputTest {

    private static final String HEX = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final List<String> writes = new ArrayList<>();
    private ApplicationResource resource;

    @Before
    public void setUp() throws Exception {
        ApplicationDAO dao = new ApplicationDAO(null, null, "files", "https://mdm.example.com", "", null) {
            @Override public boolean saveApkHash(Integer versionId, String apkHash) {
                writes.add("hash " + versionId);
                return true;
            }
            @Override public int insertApplication(Application application) {
                writes.add("insert " + application.getPkg());
                return 1;
            }
            @Override public Application findById(int id) {
                return new Application();
            }
        };
        resource = new ApplicationResource(dao, null, null, null, tmp.getRoot().getAbsolutePath());
        SecurityContext.init(Fakes.user(1, 1, "edit_applications", "edit_application_versions"));
    }

    @After
    public void tearDown() {
        SecurityContext.release();
    }

    @Test
    public void aVersionHashMustBe64Hex() {
        String truncated = HEX.substring(0, 43);
        assertEquals(Response.ResponseStatus.ERROR,
                resource.setApplicationVersionSha256(5, Collections.singletonMap("sha256", truncated)).getStatus());
        assertTrue(writes.toString(), writes.isEmpty());
        assertEquals(Response.ResponseStatus.OK,
                resource.setApplicationVersionSha256(5, Collections.singletonMap("sha256", HEX)).getStatus());
        assertEquals(Collections.singletonList("hash 5"), writes);
    }

    @Test
    public void anAppHashMustBe64Hex() {
        Application app = new Application();
        app.setPkg("com.example.app");
        app.setSha256(HEX.substring(0, 43));
        assertEquals(Response.ResponseStatus.ERROR, resource.updateApplication(app).getStatus());
        assertTrue(writes.toString(), writes.isEmpty());
    }
}
