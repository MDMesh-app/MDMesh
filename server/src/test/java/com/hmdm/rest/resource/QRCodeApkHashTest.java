/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.rest.resource;

import com.hmdm.util.CryptoUtil;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileNotFoundException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * The provisioning QR's package checksum is computed only from a file served under files.directory; the server never
 * fetches a remote URL to compute it.
 */
public class QRCodeApkHashTest {

    private static final String BASE = "https://mdm.example.com";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File files;

    @Before
    public void setUp() throws Exception {
        files = tmp.newFolder("files");
        new File(files, "acme").mkdirs();
        Files.write(new File(files, "acme/launcher.apk").toPath(), "apk-bytes".getBytes(StandardCharsets.UTF_8));
        Files.write(tmp.newFile("secret.txt").toPath(), "secret".getBytes(StandardCharsets.UTF_8));
    }

    private String hash(String url) throws Exception {
        return QRCodeResource.calculateApkHash(url, BASE, files.getAbsolutePath());
    }

    @Test
    public void hashesAFileServedFromTheFilesDirectory() throws Exception {
        byte[] sha = MessageDigest.getInstance("SHA-256").digest("apk-bytes".getBytes(StandardCharsets.UTF_8));
        assertEquals(CryptoUtil.getBase64String(sha), hash(BASE + "/files/acme/launcher.apk"));
    }

    @Test
    public void refusesTraversalOutOfTheFilesDirectory() throws Exception {
        for (String url : new String[]{
                BASE + "/files/../secret.txt",
                BASE + "/files/acme/../../secret.txt",
                BASE + "/files/%2e%2e/secret.txt",
                BASE + "/files/acme/..%2f..%2fsecret.txt"}) {
            try {
                hash(url);
                fail("hashed a file outside files.directory: " + url);
            } catch (FileNotFoundException expected) {
                // ok
            }
        }
    }

    @Test(timeout = 10_000) // a fetch would block reading the (never sent) HTTP response
    public void neverFetchesARemoteUrl() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(300);
            String url = "http://127.0.0.1:" + server.getLocalPort() + "/launcher.apk";
            try {
                hash(url);
                fail("a remote APK must not be hashed");
            } catch (QRCodeResource.ApkChecksumUnavailableException expected) {
                // ok
            }
            try (Socket s = server.accept()) {
                fail("the server opened a connection to " + url);
            } catch (SocketTimeoutException expected) {
                // ok: nothing connected
            }
        }
    }

    @Test
    public void aLookalikeHostIsRemote() throws Exception {
        try {
            hash("https://mdm.example.com.other.test/files/acme/launcher.apk");
            fail("a different host must not count as local");
        } catch (QRCodeResource.ApkChecksumUnavailableException expected) {
            // ok
        }
    }
}
