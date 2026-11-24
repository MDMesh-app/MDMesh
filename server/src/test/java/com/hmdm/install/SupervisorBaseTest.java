/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.install;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Each install's Context file names the updater supervisor that UpdateProxyServlet passes the console's {@code /update}
 * routes to: on Docker the compose service {@code supervisor} (the servlet's loopback default would be the server's own
 * container), on a native install the loopback unit.
 */
public class SupervisorBaseTest {

    private static final Pattern BASE = Pattern.compile(
            "<Parameter\\s+name=\"supervisor\\.base\"\\s+value=\"([^\"]*)\"\\s*/>");

    @Test
    public void dockerNamesTheSupervisorService() throws Exception {
        assertEquals("http://supervisor:9000", base("docker/entrypoint.sh"));
        for (String compose : new String[]{"docker-compose.yml", "docker-compose.release.yml"}) {
            assertTrue(compose + " runs the supervisor service",
                    Pattern.compile("(?m)^  supervisor:$").matcher(read(compose)).find());
        }
    }

    @Test
    public void nativeNamesTheLoopbackSupervisor() throws Exception {
        assertEquals("http://127.0.0.1:9000", base("install/install-native.sh"));
    }

    private static String base(String path) throws Exception {
        Matcher m = BASE.matcher(read(path));
        assertTrue(path + " sets supervisor.base", m.find());
        String value = m.group(1);
        assertTrue(path + " sets supervisor.base once", !m.find());
        return value;
    }

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(new File(repoRoot(), path).toPath()), StandardCharsets.UTF_8);
    }

    private static File repoRoot() {
        File dir = new File(System.getProperty("basedir", ".")).getAbsoluteFile();
        while (dir != null && !(new File(dir, "server").isDirectory() && new File(dir, "install").isDirectory())) {
            dir = dir.getParentFile();
        }
        assertTrue("found the repository root", dir != null);
        return dir;
    }
}
