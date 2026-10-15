/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.rest.resource;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** /files/* serves only regular files inside files.directory, however the request URI is encoded. */
public class DownloadFilesServletPathTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File base;

    @Before
    public void setUp() throws IOException {
        base = tmp.newFolder("files");
        new File(base, "acme").mkdirs();
        Files.write(new File(base, "acme/app.apk").toPath(), new byte[]{1});
        Files.write(new File(base, "acme/a b.apk").toPath(), new byte[]{1});
        Files.write(tmp.newFile("secret.txt").toPath(), new byte[]{1});
        tmp.newFolder("outside");
        Files.write(new File(tmp.getRoot(), "outside/x.txt").toPath(), new byte[]{1});
        Files.createSymbolicLink(new File(base, "acme/out").toPath(), new File(tmp.getRoot(), "outside").toPath());
    }

    private File resolve(String uri) {
        return DownloadFilesServlet.resolveRequestedFile(base, uri);
    }

    @Test
    public void servesFilesInsideTheFilesDirectory() throws IOException {
        assertEquals(new File(base, "acme/app.apk").getCanonicalFile(), resolve("/files/acme/app.apk").getCanonicalFile());
        assertEquals(new File(base, "acme/app.apk").getCanonicalFile(), resolve("/ctx/files/acme/app.apk").getCanonicalFile());
        assertEquals(new File(base, "acme/a b.apk").getCanonicalFile(), resolve("/files/acme/a%20b.apk").getCanonicalFile());
        assertEquals(new File(base, "acme/app.apk").getCanonicalFile(), resolve("/files//acme/app.apk").getCanonicalFile());
    }

    @Test
    public void refusesAbsolutePaths() {
        assertNull(resolve("/files/%2F" + new File(tmp.getRoot(), "secret.txt").getAbsolutePath().substring(1)));
        assertNull(resolve("/files/" + new File(tmp.getRoot(), "secret.txt").getAbsolutePath()));
    }

    @Test
    public void refusesPlainTraversal() {
        assertNull(resolve("/files/../secret.txt"));
        assertNull(resolve("/files/acme/../../secret.txt"));
    }

    @Test
    public void refusesEncodedTraversal() {
        assertNull(resolve("/files/%2e%2e/secret.txt"));
        assertNull(resolve("/files/acme/..%2f..%2fsecret.txt"));
        assertNull(resolve("/files/acme/%2E%2E%2F%2E%2E%2Fsecret.txt"));
    }

    @Test
    public void decodesOnlyOnce() {
        // %252e -> %2e after the single decode: a literal (non-existent) name inside the base, never "..".
        File f = resolve("/files/%252e%252e/secret.txt");
        assertTrue(f == null || f.getAbsolutePath().startsWith(base.getAbsolutePath()));
    }

    @Test
    public void refusesSymlinksOutOfTheFilesDirectory() {
        assertNull(resolve("/files/acme/out/x.txt"));
    }

    @Test
    public void refusesTheDirectoryItselfAndMissingOrBrokenNames() {
        assertNull(resolve("/files/"));
        assertNull(resolve("/files/acme"));
        assertNull(resolve("/files/acme/missing.apk"));
        assertNull(resolve("/files/acme/app.apk%00.txt"));
        assertNull(resolve("/files/%zz"));
        assertNull(resolve("/nothing-here"));
    }
}
