package com.hmdm.util;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Source-level guard: server-side production code must not use a predictable random generator.
 * Tokens, passwords, JWT keys and reset links all come from this code; a {@code java.util.Random}
 * (48-bit state, recoverable from one output) anywhere in it is a vulnerability. A behavioural
 * unpredictability test cannot tell {@code Random} from {@code SecureRandom}, so the property is
 * enforced on the sources instead (security review deep-2 I2).
 */
public class WeakRandomGuardTest {

    /** Weak generators, by rule name. Fully-qualified uses are caught by the {@code java.util.Random} rule. */
    private static final Map<String, Pattern> RULES = new LinkedHashMap<>();
    static {
        RULES.put("new Random(", Pattern.compile("\\bnew\\s+Random\\s*\\("));
        RULES.put("java.util.Random", Pattern.compile("\\bjava\\.util\\.Random\\b"));
        RULES.put("Math.random", Pattern.compile("\\bMath\\s*\\.\\s*random\\s*\\("));
        RULES.put("ThreadLocalRandom", Pattern.compile("\\bThreadLocalRandom\\b"));
        RULES.put("SplittableRandom", Pattern.compile("\\bSplittableRandom\\b"));
        // commons-lang RandomStringUtils is backed by java.util.Random by default.
        RULES.put("RandomStringUtils", Pattern.compile("\\bRandomStringUtils\\b"));
    }

    /**
     * Explicit allow-list: "path relative to the repo root|rule" -> justification. Every entry needs one.
     * Only src/main/java trees are scanned, so test sources are excluded by construction, not listed here.
     */
    private static final Map<String, String> ALLOWED = new LinkedHashMap<>();
    static {
        // The field is declared as java.util.Random but holds a SecureRandom; PasswordUtilTest pins the
        // runtime type, and any `new Random(` in this file is still caught by the rule above.
        ALLOWED.put("common/src/main/java/com/hmdm/util/PasswordUtil.java|java.util.Random",
                "import for the declared type of a SecureRandom field");
        // Same pattern: RANDOM is a SecureRandom (pinned by PasswordUtilTest); it generates the default JWT key.
        ALLOWED.put("common/src/main/java/com/hmdm/util/CryptoUtil.java|java.util.Random",
                "import for the declared type of a SecureRandom field");
    }

    /** Java modules whose production sources must exist; plugins/** source roots are discovered below. */
    private static final List<String> REQUIRED_ROOTS = Arrays.asList(
            "common/src/main/java",
            "server/src/main/java",
            "jwt/src/main/java",
            "notification/src/main/java");

    @Test
    public void rulesDetectWeakGeneratorsAndIgnoreSecureRandom() {
        assertEquals(Collections.singletonList("new Random("), rulesHit("String t = token(new Random().nextInt(61));"));
        assertEquals(Collections.singletonList("new Random("), rulesHit("Random r = new Random (42);"));
        assertEquals(Collections.singletonList("java.util.Random"), rulesHit("import java.util.Random;"));
        assertEquals(Collections.singletonList("java.util.Random"), rulesHit("x = new java.util.Random();"));
        assertEquals(Collections.singletonList("Math.random"), rulesHit("double d = Math.random();"));
        assertEquals(Collections.singletonList("ThreadLocalRandom"), rulesHit("ThreadLocalRandom.current().nextInt()"));
        assertEquals(Collections.singletonList("SplittableRandom"), rulesHit("new SplittableRandom().nextLong()"));
        assertEquals(Collections.singletonList("RandomStringUtils"), rulesHit("RandomStringUtils.randomAlphanumeric(20)"));

        assertTrue(rulesHit("private static final Random random = new SecureRandom();").isEmpty());
        assertTrue(rulesHit("import java.security.SecureRandom;").isEmpty());
        assertTrue(rulesHit("UUID.randomUUID().toString()").isEmpty());
    }

    @Test
    public void productionSourcesUseNoWeakRandomGenerator() throws IOException {
        File repoRoot = repoRoot();
        List<Path> roots = sourceRoots(repoRoot);

        List<String> offenders = new ArrayList<>();
        Set<String> allowUsed = new HashSet<>();
        Set<String> scanned = new HashSet<>();
        Path rootPath = repoRoot.toPath();
        for (Path root : roots) {
            List<Path> files;
            try (Stream<Path> s = Files.walk(root)) {
                files = s.filter(p -> p.toString().endsWith(".java")).sorted().collect(Collectors.toList());
            }
            for (Path file : files) {
                String rel = rootPath.relativize(file).toString().replace(File.separatorChar, '/');
                scanned.add(rel);
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    for (String rule : rulesHit(lines.get(i))) {
                        String key = rel + "|" + rule;
                        if (ALLOWED.containsKey(key)) {
                            allowUsed.add(key);
                        } else {
                            offenders.add(rel + ":" + (i + 1) + " [" + rule + "] " + lines.get(i).trim());
                        }
                    }
                }
            }
        }

        // The canary files must actually be scanned, otherwise a wrong root would pass vacuously.
        assertTrue("PasswordUtil.java not scanned; scanned " + scanned.size() + " files",
                scanned.contains("common/src/main/java/com/hmdm/util/PasswordUtil.java"));
        assertTrue("CryptoUtil.java not scanned",
                scanned.contains("common/src/main/java/com/hmdm/util/CryptoUtil.java"));

        if (!offenders.isEmpty()) {
            fail("Weak random generator in production code (use java.security.SecureRandom, or add a justified "
                    + "allow-list entry in " + WeakRandomGuardTest.class.getSimpleName() + "):\n  "
                    + String.join("\n  ", offenders));
        }

        Set<String> stale = new HashSet<>(ALLOWED.keySet());
        stale.removeAll(allowUsed);
        assertTrue("Stale allow-list entries (remove them): " + stale, stale.isEmpty());
    }

    private static List<String> rulesHit(String line) {
        List<String> hits = new ArrayList<>();
        for (Map.Entry<String, Pattern> e : RULES.entrySet()) {
            if (e.getValue().matcher(line).find()) {
                hits.add(e.getKey());
            }
        }
        return hits;
    }

    /** Surefire runs with the module dir (common/) as basedir; the repo root is its parent. */
    private static File repoRoot() {
        File module = new File(System.getProperty("basedir", System.getProperty("user.dir"))).getAbsoluteFile();
        File root = module.getParentFile();
        if (root == null || !new File(root, "pom.xml").isFile() || !new File(module, "pom.xml").isFile()) {
            fail("Cannot resolve repo root from module dir " + module + " (expected <root>/common/pom.xml)");
        }
        return root;
    }

    private static List<Path> sourceRoots(File repoRoot) throws IOException {
        List<Path> roots = new ArrayList<>();
        for (String r : REQUIRED_ROOTS) {
            File dir = new File(repoRoot, r);
            if (!dir.isDirectory()) {
                fail("Required source root missing: " + dir + " (guard would pass vacuously)");
            }
            roots.add(dir.toPath());
        }
        File plugins = new File(repoRoot, "plugins");
        if (!plugins.isDirectory()) {
            fail("Required plugins dir missing: " + plugins);
        }
        List<Path> pluginRoots;
        try (Stream<Path> s = Files.walk(plugins.toPath())) {
            pluginRoots = s.filter(Files::isDirectory)
                    .filter(p -> p.endsWith("src/main/java"))
                    .filter(p -> !p.toString().contains(File.separator + "target" + File.separator))
                    .sorted()
                    .collect(Collectors.toList());
        }
        assertFalse("No plugins/**/src/main/java roots found under " + plugins, pluginRoots.isEmpty());
        roots.addAll(pluginRoots);
        return roots;
    }
}
