package com.hmdm.install;

import com.hmdm.util.CryptoUtil;
import com.hmdm.util.PasswordUtil;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pins what a fresh database starts with: the installer seed ({@code install/sql/hmdm_init.en.sql}) carries no
 * value that is the same on every install (configuration QR keys, kiosk exit PIN), and the admin row the Liquibase
 * changelog inserts before any installer runs cannot be signed in to with a known password. Also that no text the
 * console shows still names Headwind: the seed's only Headwind rows are the com.hmdm.* apps post_seed.sql removes, and
 * the changelog and post_seed.sql mention the old Background (Agent) Mode description only to replace it.
 */
public class SeedDefaultsTest {

    private static final String[] PUBLISHED_QR_KEYS = {
            "6fb9c8dc81483173a0c0e9f8b2e46be1", "8e6ca072ddb926a1af61578dfa9fc334", "350e0fb7fc3f4b7fe61cac75fd43818f"};

    private static final String OLD_AGENT_MODE_DESCRIPTION = "Headwind MDM is running in the background, it is used to "
            + "install apps and track the device status. User can do everything (permissive mode).";
    private static final String AGENT_MODE_DESCRIPTION = "The MDMesh agent runs in the background to install apps and "
            + "report device status. The user can use the device freely (permissive mode).";
    private static final String[] REMOVED_SEED_PACKAGES = {
            "com.hmdm.launcher", "com.hmdm.pager", "com.hmdm.phoneproxy", "com.hmdm.emuilauncherrestarter"};
    private static final Pattern HEADWIND = Pattern.compile("headwind", Pattern.CASE_INSENSITIVE);
    private static final Pattern SQL_STRING = Pattern.compile("'(?:[^']|'')*'");

    @Test
    public void theSeedHasNoFixedQrKeysOrKioskPin() throws Exception {
        String seed = read("install/sql/hmdm_init.en.sql");
        assertTrue("the seed still inserts configurations", seed.contains("INSERT INTO configurations"));
        for (String key : PUBLISHED_QR_KEYS) {
            assertFalse("fixed QR key " + key, seed.contains(key));
        }
        assertFalse("fixed kiosk exit PIN", seed.contains("12345678"));
    }

    @Test
    public void theSeedDrawsThePinApartFromTheQrKey() throws Exception {
        String seed = read("install/sql/hmdm_init.en.sql");
        Matcher pin = Pattern.compile("password = [^\\n]*substr\\(%s").matcher(seed);
        assertTrue("the PIN comes from its own random draw", pin.find());
        assertTrue(seed.contains("qr_key, pin_hex)"));
    }

    @Test
    public void postSeedReplacesThePublishedQrKeys() throws Exception {
        String postSeed = read("install/sql/post_seed.sql");
        Matcher update = Pattern.compile("UPDATE configurations SET qrcodekey = [^;]*WHERE qrcodekey IN \\(([^)]*)\\)",
                Pattern.CASE_INSENSITIVE).matcher(postSeed);
        assertTrue("post_seed.sql rotates QR keys", update.find());
        for (String key : PUBLISHED_QR_KEYS) {
            assertTrue("rotated: " + key, update.group(1).contains("'" + key + "'"));
        }
    }

    @Test
    public void theObserverRoleLosesTheDeviceDescriptionGrant() throws Exception {
        String changelog = read("server/src/main/resources/liquibase/db.changelog.xml");
        assertTrue(Pattern.compile("DELETE FROM userRolePermissions WHERE roleId = 100\\s+AND permissionId IN "
                + "\\(SELECT id FROM permissions WHERE name = 'edit_device_desc'\\)").matcher(changelog).find());
    }

    @Test
    public void theChangelogAdminRowAcceptsNoPassword() throws Exception {
        String changelog = read("server/src/main/resources/liquibase/db.changelog.xml");
        Matcher m = Pattern.compile("INSERT INTO users\\(login, email, name, password\\)\\s*VALUES \\('admin', '[^']*', "
                + "'admin', '([0-9a-f]+)'\\)").matcher(changelog);
        assertTrue("found the changelog's admin row", m.find());
        String stored = m.group(1);
        // The console and the JWT login send MD5(password); the server compares SHA-1(that + salt), 40 hex characters,
        // with the stored value. The changelog stores a bare 32-character MD5, which no input can produce.
        assertFalse(PasswordUtil.passwordMatch(CryptoUtil.getMD5String("admin"), stored));
        assertFalse(PasswordUtil.passwordMatch("admin", stored));
        assertFalse(PasswordUtil.passwordMatch(stored, stored));
        assertTrue(PasswordUtil.getHashFromRaw("admin").length() != stored.length());
    }

    @Test
    public void theSeedShowsNoHeadwindText() throws Exception {
        String seed = read("install/sql/hmdm_init.en.sql");
        String postSeed = read("install/sql/post_seed.sql");
        assertTrue("the Background (Agent) Mode description", seed.contains("(3, 'Background (Agent) Mode', '"
                + AGENT_MODE_DESCRIPTION + "'"));
        for (String line : seed.split("\n")) {
            if (line.trim().startsWith("--") || !HEADWIND.matcher(line).find()) {
                continue;
            }
            // An app row of a Headwind package is fine: post_seed.sql deletes it, and its rows go with it.
            boolean removedApp = false;
            for (String pkg : REMOVED_SEED_PACKAGES) {
                removedApp |= line.contains("'" + pkg + "'") && postSeed.contains("'" + pkg + "'");
            }
            assertTrue("Headwind text in the seed: " + line.trim(), removedApp);
        }
    }

    @Test
    public void theChangelogAndPostSeedWriteNoHeadwindText() throws Exception {
        String changelog = read("server/src/main/resources/liquibase/db.changelog.xml")
                .replaceAll("(?s)<!--.*?-->", "").replaceAll("(?s)<comment>.*?</comment>", "");
        String postSeed = read("install/sql/post_seed.sql").replaceAll("(?m)--.*$", "");
        for (String sql : new String[]{changelog, postSeed}) {
            Matcher literal = SQL_STRING.matcher(sql);
            while (literal.find()) {
                String text = literal.group();
                if (!HEADWIND.matcher(text).find() || text.equals("'" + OLD_AGENT_MODE_DESCRIPTION + "'")) {
                    continue;
                }
                // A server-log notice is not shown in the console.
                int lineStart = sql.lastIndexOf('\n', literal.start()) + 1;
                assertTrue("Headwind text written to the database: " + text,
                        sql.substring(lineStart, literal.start()).trim().equals("RAISE NOTICE"));
            }
        }
    }

    @Test
    public void upgradesReplaceOnlyTheUneditedAgentModeDescription() throws Exception {
        Pattern update = Pattern.compile("UPDATE configurations\\s+SET description = '" + Pattern.quote(AGENT_MODE_DESCRIPTION)
                + "'\\s+WHERE id = 3 AND name = 'Background \\(Agent\\) Mode'\\s+AND description = '"
                + Pattern.quote(OLD_AGENT_MODE_DESCRIPTION) + "';");
        assertTrue("the changeset", update.matcher(read("server/src/main/resources/liquibase/db.changelog.xml")).find());
        assertTrue("post_seed.sql", update.matcher(read("install/sql/post_seed.sql")).find());
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
