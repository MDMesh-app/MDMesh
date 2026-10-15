package com.hmdm.persistence;

import com.hmdm.util.CryptoUtil;
import com.hmdm.util.HostedFiles;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/** The checksum stored for a configuration file's external URL: only files this server hosts are read, nothing fetched. */
public class ConfigurationFileChecksumTest {

    private static final String BASE = "https://mdm.example.com";

    private File dir;
    private File outside;
    private HostedFiles hosted;

    @Before
    public void setUp() throws Exception {
        dir = Files.createTempDirectory("cfgfiles").toFile();
        new File(dir, "acme").mkdirs();
        Files.write(new File(dir, "acme/wallpaper.png").toPath(), "hosted".getBytes(StandardCharsets.UTF_8));
        outside = new File(dir.getParentFile(), "outside-" + dir.getName() + ".txt");
        Files.write(outside.toPath(), "secret".getBytes(StandardCharsets.UTF_8));
        hosted = new HostedFiles(dir.getAbsolutePath(), BASE);
    }

    @After
    public void tearDown() {
        new File(dir, "acme/wallpaper.png").delete();
        new File(dir, "acme").delete();
        dir.delete();
        outside.delete();
    }

    @Test
    public void aHostedFileIsHashedFromDisk() throws Exception {
        String expected = CryptoUtil.calculateChecksum(
                new ByteArrayInputStream("hosted".getBytes(StandardCharsets.UTF_8)));
        assertEquals(expected, ConfigurationDAO.externalFileChecksum(BASE + "/files/acme/wallpaper.png", hosted));
    }

    @Test
    public void aUrlOnAnotherHostIsNeverFetched() throws Exception {
        try (ServerSocket listener = new ServerSocket(0)) {
            String url = "http://127.0.0.1:" + listener.getLocalPort() + "/files/acme/wallpaper.png";
            assertEquals("", ConfigurationDAO.externalFileChecksum(url, hosted));
            listener.setSoTimeout(300);
            try {
                listener.accept().close();
                fail("the server connected to the external URL");
            } catch (SocketTimeoutException expected) {
                // nothing connected
            }
        }
    }

    @Test
    public void aPathLeavingTheFilesDirectoryIsNotHashed() throws Exception {
        assertEquals("", ConfigurationDAO.externalFileChecksum(
                BASE + "/files/../outside-" + dir.getName() + ".txt", hosted));
        assertEquals("", ConfigurationDAO.externalFileChecksum(
                BASE + "/files/acme/..%2f..%2foutside-" + dir.getName() + ".txt", hosted));
    }

    @Test
    public void aBlankUrlHasNoChecksum() throws Exception {
        assertEquals("", ConfigurationDAO.externalFileChecksum("  ", hosted));
    }

    @Test
    public void oddOrNonHttpUrlsGetAnEmptyChecksumWithoutAnError() {
        for (String url : new String[]{"file://" + outside.getAbsolutePath(), "jar:file:/x.jar!/a", "ftp://h/x",
                "gopher://h/x", outside.getAbsolutePath(), "http://legacy host/a b|c.png",
                BASE + "/files/acme/a\u0000b", BASE + "/files/%zz", "::::"}) {
            assertEquals(url, "", ConfigurationDAO.externalFileChecksum(url, hosted));
        }
    }

    @Test
    public void onlyHttpUrlsAreHttpUrls() {
        for (String url : new String[]{"file:///etc/passwd", "ftp://h/x", "jar:file:/x.jar!/a", "http://", "", null,
                "/files/a"}) {
            assertEquals(String.valueOf(url), false, HostedFiles.isHttpUrl(url));
        }
        for (String url : new String[]{"http://cdn.example.com/a.png", "HTTPS://cdn.example.com/a b.png"}) {
            assertEquals(url, true, HostedFiles.isHttpUrl(url));
        }
    }
}
