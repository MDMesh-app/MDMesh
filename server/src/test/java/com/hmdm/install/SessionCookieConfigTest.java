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
 * The console session cookie (JSESSIONID) is SameSite=Lax on every install, so a cross-site page cannot send a form
 * POST, PUT or DELETE with an admin's session. Tomcat sets it from the Context's CookieProcessor, and each install
 * writes its own Context file (conf/Catalina/localhost/ROOT.xml), which takes the place of the WAR's.
 */
public class SessionCookieConfigTest {

    private static final Pattern CONTEXT = Pattern.compile("<Context>(.*?)</Context>", Pattern.DOTALL);
    private static final Pattern PROCESSOR = Pattern.compile(
            "<CookieProcessor\\s+className=\"org\\.apache\\.tomcat\\.util\\.http\\.Rfc6265CookieProcessor\"\\s+"
                    + "sameSiteCookies=\"lax\"\\s*/>");

    @Test
    public void dockerSetsSameSiteLax() throws Exception {
        assertOneLaxProcessor("docker/entrypoint.sh");
    }

    @Test
    public void nativeSetsSameSiteLax() throws Exception {
        assertOneLaxProcessor("install/install-native.sh");
    }

    @Test
    public void theContextTemplateSetsSameSiteLax() throws Exception {
        assertOneLaxProcessor("server/conf/context.xml");
    }

    private static void assertOneLaxProcessor(String path) throws Exception {
        String text = read(path);
        Matcher context = CONTEXT.matcher(text);
        assertTrue(path + " writes a <Context>", context.find());
        String body = context.group(1);
        assertTrue(path + " writes exactly one <Context>", !context.find());
        Matcher processor = PROCESSOR.matcher(body);
        int count = 0;
        while (processor.find()) {
            count++;
        }
        assertEquals(path + ": one Rfc6265CookieProcessor with sameSiteCookies=\"lax\" in the Context", 1, count);
        assertEquals(path + ": no other CookieProcessor", 1, body.split("<CookieProcessor", -1).length - 1);
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
