package com.hmdm.rest.resource.support;

import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

/** Hosted APKs hash from disk (contained in the files dir); nothing else is hashed, and nothing is fetched. */
public class ApkHashResolverTest {

    /** sha256("abc") */
    private static final String ABC = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    private File dir;
    private HttpServer http;

    @Before
    public void setUp() throws IOException {
        dir = Files.createTempDirectory("files").toFile();
        File sub = new File(dir, "cust1");
        sub.mkdirs();
        Files.write(new File(sub, "app.apk").toPath(), "abc".getBytes(StandardCharsets.US_ASCII));
        Files.write(new File(sub, "my app.apk").toPath(), "abc".getBytes(StandardCharsets.US_ASCII));
        Files.write(new File(dir.getParentFile(), "outside-" + dir.getName() + ".apk").toPath(), "x".getBytes());
    }

    @After
    public void tearDown() {
        if (http != null) http.stop(0);
        new File(dir.getParentFile(), "outside-" + dir.getName() + ".apk").delete();
    }

    private ApkHashResolver resolver(String baseUrl) {
        return new ApkHashResolver(dir.getAbsolutePath(), baseUrl);
    }

    @Test
    public void hostedFileHashesFromDisk() throws Exception {
        // Port 9 (discard): any network attempt would fail, so a correct answer proves it came from disk.
        ApkHashResolver r = resolver("http://192.168.1.10:9/");
        assertEquals(ABC, r.sha256Hex("http://192.168.1.10:9/files/cust1/app.apk"));
        assertEquals(ABC, r.sha256Hex("http://192.168.1.10:9/files/cust1/my%20app.apk"));
        assertEquals(ABC, r.sha256Hex("http://192.168.1.10:9/files/cust1/app.apk?v=2"));
    }

    @Test
    public void hostedUnderAContextPath() throws Exception {
        assertEquals(ABC, resolver("https://mdm.example.com/hmdm").sha256Hex("https://mdm.example.com/hmdm/files/cust1/app.apk"));
    }

    @Test
    public void localLookupStaysInsideTheFilesDir() {
        ApkHashResolver r = resolver("http://mdm.local");
        assertNull(r.localFile("http://mdm.local/files/../outside-" + dir.getName() + ".apk"));
        assertNull(r.localFile("http://mdm.local/files/cust1/missing.apk"));
        assertNull(r.localFile("http://other.host/files/cust1/app.apk"));
    }

    @Test
    public void externalUrlIsNeverFetched() throws Exception {
        final int[] hits = {0};
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/", ex -> {
            hits[0]++;
            byte[] body = "abc".getBytes(StandardCharsets.US_ASCII);
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(body); }
        });
        http.start();
        String host = "http://127.0.0.1:" + http.getAddress().getPort();

        ApkHashResolver r = resolver("https://mdm.example.com");
        for (String url : new String[]{host + "/repo/x.apk", "https://mdm.example.com/files/cust1/missing.apk"}) {
            try {
                r.sha256Hex(url);
                fail("an APK not hosted here must not be hashed: " + url);
            } catch (IOException expected) {
                assertEquals(ApkHashResolver.NOT_HOSTED, expected.getMessage());
            }
        }
        assertEquals(0, hits[0]);
    }

    @Test(expected = IOException.class)
    public void nonHttpUrlIsRejected() throws Exception {
        resolver("https://mdm.example.com").sha256Hex("file:///etc/passwd");
    }
}
