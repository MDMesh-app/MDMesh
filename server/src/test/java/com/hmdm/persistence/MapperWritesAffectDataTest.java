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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A MyBatis select that writes (UPDATE/INSERT/DELETE ... RETURNING, or a writing CTE) must say {@code affectData =
 * true}: else MyBatis doesn't mark the session dirty, the transaction's commit is skipped, and the write is committed
 * only by the connection reset on close, whose errors are swallowed. Every mapper of every module (plugins included),
 * annotated or XML.
 */
public class MapperWritesAffectDataTest {

    /** A write keyword outside string literals, row locks (FOR UPDATE / FOR NO KEY UPDATE) and comments. */
    private static final Pattern WRITES = Pattern.compile("\\b(UPDATE|INSERT|DELETE|RETURNING)\\b",
            Pattern.CASE_INSENSITIVE);

    static boolean writes(String sql) {
        String s = sql.replaceAll("'(?:[^']|'')*'", "''")
                .replaceAll("--[^\\n]*", " ")
                .replaceAll("(?is)/\\*.*?\\*/", " ")
                .replaceAll("(?i)\\bFOR\\s+(NO\\s+KEY\\s+)?UPDATE\\b", " ")
                .replaceAll("(?i)\\bON\\s+(UPDATE|DELETE)\\b", " ");
        return WRITES.matcher(s).find();
    }

    @Test
    public void theDetectorTellsWritesFromReads() {
        assertTrue(writes("UPDATE devices SET x = 1 WHERE id = 2 RETURNING id"));
        assertTrue(writes("WITH d AS (DELETE FROM t RETURNING id) SELECT count(*) FROM d"));
        assertTrue(writes("INSERT INTO t (a) VALUES (1) RETURNING id"));
        assertFalse(writes("SELECT id FROM devices WHERE id = ANY(#{ids}) ORDER BY id FOR UPDATE"));
        assertFalse(writes("SELECT lastUpdate, deleted FROM devices FOR NO KEY UPDATE"));
        assertFalse(writes("SELECT id FROM t WHERE action = 'delete' OR kind = 'update'"));
    }

    @Test
    public void everyAnnotatedSelectThatWritesSaysSo() throws Exception {
        List<String> offenders = new ArrayList<>();
        List<String> classes = annotatedMapperClasses();
        assertTrue("no mapper found: the scan is broken", classes.size() > 10);
        for (String name : classes) {
            Class<?> type = Class.forName(name); // a mapper not on the test classpath fails here, not silently
            for (Method m : type.getDeclaredMethods()) {
                Select select = m.getAnnotation(Select.class);
                if (select != null && !select.affectData() && writes(String.join(" ", select.value()))) {
                    offenders.add(name + "." + m.getName());
                }
                SelectProvider provider = m.getAnnotation(SelectProvider.class);
                if (provider != null && !provider.affectData()) {
                    // Its SQL is built at run time and can't be read here: decide, and say so with affectData
                    offenders.add(name + "." + m.getName() + " (@SelectProvider: mark affectData = true if it writes,"
                            + " or teach this test the provider)");
                }
            }
        }
        assertTrue("selects that write without affectData = true: " + offenders, offenders.isEmpty());
    }

    @Test
    public void everyXmlSelectThatWritesSaysSo() throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setValidating(false);
        f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        DocumentBuilder builder = f.newDocumentBuilder();
        List<String> offenders = new ArrayList<>();
        List<Path> files = xmlMappers();
        assertTrue("no XML mapper found: the scan is broken", files.size() > 5);
        for (Path file : files) {
            Document doc = builder.parse(file.toFile());
            java.util.Map<String, String> fragments = new java.util.HashMap<>();
            NodeList sqls = doc.getElementsByTagName("sql");
            for (int i = 0; i < sqls.getLength(); i++) {
                Element sql = (Element) sqls.item(i);
                fragments.put(sql.getAttribute("id"), sql.getTextContent());
            }
            NodeList selects = doc.getElementsByTagName("select");
            for (int i = 0; i < selects.getLength(); i++) {
                Element select = (Element) selects.item(i);
                // The statement's text, its dynamic parts and the <sql> fragments it includes
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

    /** Skipped whole: builds, dependencies, VCS and tool state, and the Android agent (no MyBatis there). */
    private static final java.util.Set<String> SKIPPED = new java.util.HashSet<>(java.util.Arrays.asList(
            "target", "node_modules", ".git", ".claude", "brain", "agent-android", "web", "site"));

    private static Stream<Path> mainSources(String extension) throws IOException {
        List<Path> found = new ArrayList<>();
        Files.walkFileTree(root(), new java.nio.file.SimpleFileVisitor<Path>() {
            @Override
            public java.nio.file.FileVisitResult preVisitDirectory(Path dir,
                                                                   java.nio.file.attribute.BasicFileAttributes attrs) {
                return SKIPPED.contains(String.valueOf(dir.getFileName()))
                        ? java.nio.file.FileVisitResult.SKIP_SUBTREE : java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult visitFile(Path file,
                                                           java.nio.file.attribute.BasicFileAttributes attrs) {
                if (file.toString().endsWith(extension)
                        && file.toString().replace('\\', '/').contains("/src/main/java/")) {
                    found.add(file);
                }
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
        return found.stream();
    }

    private static List<String> annotatedMapperClasses() throws IOException {
        try (Stream<Path> files = mainSources(".java")) {
            return files.filter(p -> {
                try {
                    String src = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                    return src.contains("org.apache.ibatis.annotations") && src.matches("(?s).*\\binterface\\s+\\w+.*");
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }).map(p -> {
                String s = p.toString().replace('\\', '/');
                s = s.substring(s.indexOf("/src/main/java/") + "/src/main/java/".length());
                return s.substring(0, s.length() - ".java".length()).replace('/', '.');
            }).sorted().collect(Collectors.toList());
        }
    }

    private static List<Path> xmlMappers() throws IOException {
        try (Stream<Path> files = mainSources(".xml")) {
            return files.filter(p -> {
                try {
                    return new String(Files.readAllBytes(p), StandardCharsets.UTF_8).contains("<mapper");
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }).sorted().collect(Collectors.toList());
        }
    }
}
