/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.persistence;

import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectProvider;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A MyBatis select that writes (UPDATE/INSERT/DELETE ... RETURNING, a writing CTE, TRUNCATE, CALL, or DDL such as
 * CREATE TEMP TABLE) must say {@code affectData = true}: else MyBatis doesn't mark the session dirty, the transaction's commit is skipped, and the
 * write is committed only by the connection reset on close, whose errors are swallowed. Every mapper of every module
 * (plugins included), annotated or XML.
 *
 * <p>Not detected, so set affectData by hand: a select that calls a function which writes. XML includes are followed
 * within the same file, one level deep; cross-file and nested ones are not. E'' strings, dollar quotes and quoted
 * identifiers aren't parsed.</p>
 */
public class MapperWritesAffectDataTest {

    /** String literals and both comment styles, matched in one left-to-right pass (a quote in a comment is not one). */
    private static final Pattern LITERALS_AND_COMMENTS = Pattern.compile("'(?:[^']|'')*'|--[^\\n]*|(?s:/\\*.*?\\*/)");
    private static final Pattern ROW_LOCKS_AND_FK_ACTIONS = Pattern.compile(
            "\\bFOR\\s+(NO\\s+KEY\\s+)?UPDATE\\b|\\bON\\s+(UPDATE|DELETE)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern WRITES = Pattern.compile(
            "\\b(UPDATE|INSERT|DELETE|RETURNING|TRUNCATE|CALL|CREATE|DROP|ALTER)\\b", Pattern.CASE_INSENSITIVE);

    /** Skipped whole: builds, dependencies, VCS and tool state, and the Android agent (no MyBatis there). */
    private static final Set<String> SKIPPED = new HashSet<>(Arrays.asList(
            "target", "node_modules", ".git", ".claude", "brain", "agent-android", "web", "site"));

    static boolean writes(String sql) {
        Matcher m = LITERALS_AND_COMMENTS.matcher(sql);
        StringBuffer stripped = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(stripped, " ");
        }
        m.appendTail(stripped);
        return WRITES.matcher(ROW_LOCKS_AND_FK_ACTIONS.matcher(stripped).replaceAll(" ")).find();
    }

    @Test
    public void theDetectorTellsWritesFromReads() {
        assertTrue(writes("UPDATE devices SET x = 1 WHERE id = 2 RETURNING id"));
        assertTrue(writes("WITH d AS (DELETE FROM t RETURNING id) SELECT count(*) FROM d"));
        assertTrue(writes("INSERT INTO t (a) VALUES (1) RETURNING id"));
        assertTrue(writes("TRUNCATE t; SELECT 1"));
        assertTrue(writes("CALL tidy(); SELECT 1"));
        assertTrue(writes("CREATE TEMP TABLE t ON COMMIT DROP AS SELECT 1; SELECT * FROM t"));
        // The quote in the comment doesn't open a literal that would hide the UPDATE after it
        assertTrue(writes("SELECT 1 -- it's a note\n; UPDATE t SET a = 'x'"));
        assertTrue(writes("SELECT 1 /* it's */ ; UPDATE t SET a = 'x'"));
        assertFalse(writes("SELECT id FROM devices WHERE id = ANY(#{ids}) ORDER BY id FOR UPDATE"));
        assertFalse(writes("SELECT lastUpdate, deleted FROM devices FOR NO KEY UPDATE"));
        assertFalse(writes("SELECT id FROM t WHERE action = 'delete' OR kind = 'it''s an update'"));
        assertFalse(writes("SELECT id FROM t -- then UPDATE it\n/* or DELETE\n it */ WHERE a = 1"));
    }

    @Test
    public void everyAnnotatedSelectThatWritesSaysSo() throws Exception {
        List<String> classes = annotatedMapperClasses();
        // One of each kind of module (by name: a plugin is only on the runtime classpath); a module dropped from
        // the scan fails here
        for (String known : Arrays.asList("com.hmdm.persistence.mapper.DeviceMapper",
                "com.hmdm.notification.persistence.mapper.NotificationMapper",
                "com.hmdm.plugins.audit.persistence.mapper.AuditMapper")) {
            assertTrue(known + " not scanned: " + classes, classes.contains(known));
        }
        List<String> offenders = new ArrayList<>();
        for (String name : classes) {
            Class<?> type = Class.forName(name); // a mapper not on the test classpath fails here, not silently
            for (Method m : type.getDeclaredMethods()) {
                for (Select select : m.getAnnotationsByType(Select.class)) {
                    if (!select.affectData() && writes(String.join(" ", select.value()))) {
                        offenders.add(name + "." + m.getName());
                    }
                }
                for (SelectProvider provider : m.getAnnotationsByType(SelectProvider.class)) {
                    if (!provider.affectData()) {
                        // Its SQL is built at run time and can't be read here: decide, and say so with affectData
                        offenders.add(name + "." + m.getName() + " (@SelectProvider: mark affectData = true if it"
                                + " writes, or teach this test the provider)");
                    }
                }
            }
        }
        assertTrue("selects that write without affectData = true: " + offenders, offenders.isEmpty());
    }

    @Test
    public void everyXmlSelectThatWritesSaysSo() throws Exception {
        List<Path> files = xmlMappers();
        List<String> names = files.stream().map(p -> p.getFileName().toString()).collect(Collectors.toList());
        for (String known : Arrays.asList("DeviceMapper.xml", "NotificationMapper.xml", "AuditMapper.xml")) {
            assertTrue(known + " not scanned: " + names, names.contains(known));
        }
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setValidating(false);
        f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        DocumentBuilder builder = f.newDocumentBuilder();
        List<String> offenders = new ArrayList<>();
        for (Path file : files) {
            Document doc = builder.parse(file.toFile());
            Map<String, String> fragments = new HashMap<>();
            NodeList sqls = doc.getElementsByTagName("sql");
            for (int i = 0; i < sqls.getLength(); i++) {
                Element sql = (Element) sqls.item(i);
                fragments.put(sql.getAttribute("id"), sql.getTextContent());
            }
            NodeList selects = doc.getElementsByTagName("select");
            for (int i = 0; i < selects.getLength(); i++) {
                Element select = (Element) selects.item(i);
                // The statement's text, its dynamic parts and the same-file <sql> fragments it includes
                StringBuilder text = new StringBuilder(select.getTextContent());
                NodeList includes = select.getElementsByTagName("include");
                for (int j = 0; j < includes.getLength(); j++) {
                    text.append(' ').append(fragments.getOrDefault(
                            ((Element) includes.item(j)).getAttribute("refid"), ""));
                }
                if (!"true".equals(select.getAttribute("affectData")) && writes(text.toString())) {
                    offenders.add(file.getFileName() + "#" + select.getAttribute("id"));
                }
            }
        }
        assertTrue("selects that write without affectData=\"true\": " + offenders, offenders.isEmpty());
    }

    // ---------------------------------------------------------------------------------------------------- helpers

    /** The repository root: the server module's parent (surefire runs in the module directory). */
    private static Path root() {
        Path module = Paths.get("").toAbsolutePath();
        Path root = Files.isDirectory(module.resolve("common")) ? module : module.getParent();
        assertTrue("repository root not found from " + module, Files.isDirectory(root.resolve("common")));
        return root;
    }

    /** Files with this extension under any module's src/main/java or src/main/resources. */
    private static List<Path> mainSources(String extension) throws IOException {
        List<Path> found = new ArrayList<>();
        Files.walkFileTree(root(), new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return SKIPPED.contains(String.valueOf(dir.getFileName()))
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String s = file.toString().replace('\\', '/');
                if (s.endsWith(extension) && (s.contains("/src/main/java/") || s.contains("/src/main/resources/"))) {
                    found.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        found.sort(null);
        return found;
    }

    private static String read(Path p) {
        try {
            return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static List<String> annotatedMapperClasses() throws IOException {
        return mainSources(".java").stream()
                .filter(p -> p.toString().replace('\\', '/').contains("/src/main/java/"))
                .filter(p -> {
                    String src = read(p);
                    return src.contains("org.apache.ibatis.annotations") && src.matches("(?s).*\\binterface\\s+\\w+.*");
                })
                .map(p -> {
                    String s = p.toString().replace('\\', '/');
                    s = s.substring(s.indexOf("/src/main/java/") + "/src/main/java/".length());
                    return s.substring(0, s.length() - ".java".length()).replace('/', '.');
                })
                .collect(Collectors.toList());
    }

    private static List<Path> xmlMappers() throws IOException {
        return mainSources(".xml").stream().filter(p -> read(p).contains("<mapper")).collect(Collectors.toList());
    }
}
