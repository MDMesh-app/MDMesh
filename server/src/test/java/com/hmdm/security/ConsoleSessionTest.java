/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.security;

import com.hmdm.persistence.domain.User;
import com.hmdm.rest.filter.AuthFilter;
import com.hmdm.testsupport.FakeSessions;
import org.junit.Test;

import javax.servlet.http.HttpSession;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** The one place a console session is opened for a user or moved to a new id. */
public class ConsoleSessionTest {

    @Test
    public void startEndsTheSessionTheRequestCameWith() {
        FakeSessions sessions = new FakeSessions();
        FakeSessions.Session before = sessions.existing("before");
        before.attributes.put("left-over", "x");
        User user = new User();

        HttpSession started = ConsoleSession.start(sessions.request(), user);

        assertTrue(before.invalidated);
        assertNotEquals("before", started.getId());
        assertSame(started, sessions.current().proxy);
        assertSame(user, started.getAttribute(AuthFilter.sessionCredentials));
        assertFalse(sessions.current().attributes.containsKey("left-over"));
    }

    @Test
    public void startWithoutASessionOpensOne() {
        FakeSessions sessions = new FakeSessions();
        User user = new User();

        HttpSession started = ConsoleSession.start(sessions.request(), user);

        assertEquals(1, sessions.created);
        assertSame(user, started.getAttribute(AuthFilter.sessionCredentials));
    }

    @Test
    public void startCopesWithASessionThatEndedMeanwhile() {
        FakeSessions sessions = new FakeSessions();
        FakeSessions.Session before = sessions.existing("before");
        // The container hands out the session, which then times out (or another request ends it) before invalidate.
        before.invalidated = true;

        HttpSession started = ConsoleSession.start(sessions.request(), new User());

        assertNotEquals("before", started.getId());
    }

    @Test
    public void renewMovesEveryAttributeToANewId() {
        FakeSessions sessions = new FakeSessions();
        FakeSessions.Session before = sessions.existing("before");
        User user = new User();
        before.attributes.put(AuthFilter.sessionCredentials, user);
        before.attributes.put(AuthFilter.twoFactorNeeded, "true");

        HttpSession renewed = ConsoleSession.renew(sessions.request());

        assertTrue(before.invalidated);
        assertNotEquals("before", renewed.getId());
        assertSame(user, renewed.getAttribute(AuthFilter.sessionCredentials));
        assertEquals("true", renewed.getAttribute(AuthFilter.twoFactorNeeded));
    }

    @Test
    public void renewWithoutASessionOpensNone() {
        FakeSessions sessions = new FakeSessions();

        assertNull(ConsoleSession.renew(sessions.request()));
        assertEquals(0, sessions.created);
    }

    /**
     * Every sign-in, user switch and password change goes through ConsoleSession: no other server code opens a
     * session or stores a user in one, so none can keep the id the browser had before.
     */
    @Test
    public void nothingElseOpensASessionOrStoresAUserInOne() throws IOException {
        // Best effort, by source text: any getSession( but getSession(false), and any store of the credentials key.
        Pattern opens = Pattern.compile("getSession\\s*\\((?!\\s*false\\s*\\))");
        Pattern storesUser = Pattern.compile("setAttribute\\s*\\(\\s*(\\w+\\.)?(sessionCredentials|\"credentials\")");
        List<String> offenders = new ArrayList<>();
        for (Path file : mainSources()) {
            if (file.endsWith(Path.of("com", "hmdm", "security", "ConsoleSession.java"))) {
                continue;
            }
            String source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            if (opens.matcher(source).find() || storesUser.matcher(source).find()) {
                offenders.add(file.toString());
            }
        }
        assertEquals("open sessions with ConsoleSession.start / renew", new ArrayList<String>(), offenders);
    }

    @Test
    public void theGuardSeesTheServerSources() throws IOException {
        List<Path> sources = mainSources();
        assertTrue(sources.stream().anyMatch(p -> p.endsWith(Path.of("com", "hmdm", "rest", "resource", "AuthResource.java"))));
        assertTrue(sources.stream().anyMatch(p -> p.endsWith(Path.of("com", "hmdm", "rest", "filter", "AuthFilter.java"))));
    }

    private static List<Path> mainSources() throws IOException {
        File root = repoRoot();
        List<Path> all = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root.toPath())) {
            all.addAll(walk
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toString().contains(File.separator + "src" + File.separator + "main" + File.separator))
                    .filter(p -> !p.toString().contains(File.separator + "node_modules" + File.separator))
                    .filter(p -> !p.toString().contains(File.separator + "agent-android" + File.separator))
                    .filter(p -> !p.toString().contains(File.separator + "target" + File.separator))
                    .collect(Collectors.toList()));
        }
        return all;
    }

    private static File repoRoot() {
        File dir = new File(System.getProperty("basedir", ".")).getAbsoluteFile();
        while (dir != null && !(new File(dir, "server").isDirectory() && new File(dir, "common").isDirectory())) {
            dir = dir.getParentFile();
        }
        if (dir == null) {
            throw new IllegalStateException("repository root not found");
        }
        return dir;
    }
}
