package com.hmdm.extension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Test;

import java.io.IOException;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** The namespace rules and size limits of configurations.extensions, and how a plugin's validator is asked. */
public class ConfigurationExtensionsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ObjectNode ext(String json) throws Exception {
        return (ObjectNode) JSON.readTree(json);
    }

    private static List<String> keys(List<ExtensionError> errors) {
        return errors.stream().map(ExtensionError::getKey).collect(Collectors.toList());
    }

    private static String rep(char c, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }

    /** An object whose serialized JSON is exactly {@code bytes} long: {"v":"xxx…"}. */
    private static ObjectNode sized(int bytes) {
        return JSON.createObjectNode().put("v", rep('x', bytes - "{\"v\":\"\"}".length()));
    }

    @Test
    public void aNamespaceIsAnExtensionIdOrIdDotName() {
        for (String ok : Arrays.asList("netaccess", "net-access", "a1", "netaccess.vpn", "netaccess.split-tunnel")) {
            assertEquals(ok, ok.split("\\.")[0], ConfigurationExtensions.ownerOf(ok));
        }
        for (String bad : Arrays.asList("", "Netaccess", "net_access", "-net", "net-", "1net", "netaccess.", ".vpn",
                "netaccess.vpn.more", "netaccess.VPN", "net access", rep('n', 33), "netaccess." + rep('v', 33))) {
            assertNull(bad, ConfigurationExtensions.ownerOf(bad));
        }
        // At most 32 characters each, as the console's extension ids.
        assertEquals(rep('n', 32), ConfigurationExtensions.ownerOf(rep('n', 32) + "." + rep('v', 32)));
    }

    @Test
    public void validDataPasses() throws Exception {
        assertEquals(Collections.emptyList(), ConfigurationExtensions.check(ext("{}"), null));
        assertEquals(Collections.emptyList(),
                ConfigurationExtensions.check(ext("{\"netaccess\":{\"on\":true},\"netaccess.vpn\":{\"hosts\":[\"a\"]}}"), null));
    }

    @Test
    public void refusesKeysThatAreNotNamespacesAndValuesThatAreNotObjects() throws Exception {
        List<ExtensionError> errors = ConfigurationExtensions.check(
                ext("{\"Bad Key\":{},\"netaccess\":[1],\"demo\":\"text\",\"ok\":{}}"), null);
        assertEquals(Arrays.asList("error.extensions.namespace", "error.extensions.object", "error.extensions.object"), keys(errors));
        assertEquals("Bad Key", errors.get(0).getNamespace());
        assertEquals("netaccess", errors.get(1).getNamespace());
        assertTrue(errors.get(0).getMessage(), errors.get(0).getMessage().contains("lowercase kebab-case"));
    }

    @Test
    public void eachNamespaceIsAtMost16KbOfJson() {
        ObjectNode at = JSON.createObjectNode();
        at.set("demo", sized(ConfigurationExtensions.NAMESPACE_MAX_BYTES));
        assertEquals(Collections.emptyList(), ConfigurationExtensions.check(at, null));

        ObjectNode over = JSON.createObjectNode();
        over.set("demo", sized(ConfigurationExtensions.NAMESPACE_MAX_BYTES + 1));
        List<ExtensionError> errors = ConfigurationExtensions.check(over, null);
        assertEquals(Collections.singletonList("error.extensions.namespace.size"), keys(errors));
        assertEquals("demo", errors.get(0).getNamespace());
    }

    @Test
    public void multiByteCharactersCountAsTheirUtf8Bytes() {
        ObjectNode o = JSON.createObjectNode();
        // 8192 characters of 2 bytes each: under 16 K characters, over 16 KB.
        o.set("demo", JSON.createObjectNode().put("v", rep('\u00e9', ConfigurationExtensions.NAMESPACE_MAX_BYTES / 2)));
        assertEquals(Collections.singletonList("error.extensions.namespace.size"), keys(ConfigurationExtensions.check(o, null)));
    }

    @Test
    public void allNamespacesTogetherAreAtMost64KbOfJson() {
        ObjectNode under = JSON.createObjectNode();
        for (int i = 0; i < 3; i++) under.set("ns" + i, sized(ConfigurationExtensions.NAMESPACE_MAX_BYTES));
        assertEquals(Collections.emptyList(), ConfigurationExtensions.check(under, null));

        ObjectNode over = JSON.createObjectNode();
        for (int i = 0; i < 4; i++) over.set("ns" + i, sized(ConfigurationExtensions.NAMESPACE_MAX_BYTES));
        // Four namespaces at the limit, plus the keys and commas around them: just over 64 KB.
        List<ExtensionError> errors = ConfigurationExtensions.check(over, null);
        assertEquals(Collections.singletonList("error.extensions.size"), keys(errors));
        assertNull(errors.get(0).getNamespace());
    }

    /** Records what it was asked, and finds a port out of range. */
    private static final class PortValidator implements ConfigurationExtensionValidator {
        final List<String> asked = new ArrayList<>();

        @Override public String extensionId() { return "netaccess"; }

        @Override public List<Problem> validate(String namespace, JsonNode value) {
            asked.add(namespace);
            int port = value.path("port").asInt(1);
            return port >= 1 && port <= 65535 ? null
                    : Collections.singletonList(new Problem("port-range", "The VPN port must be 1 to 65535."));
        }
    }

    @Test
    public void aValidatorIsAskedAboutEachNamespaceItsExtensionOwnsOnly() throws Exception {
        PortValidator v = new PortValidator();
        List<ExtensionError> errors = ConfigurationExtensions.check(
                ext("{\"netaccess\":{},\"netaccess.vpn\":{\"port\":70000},\"netaccessx\":{\"port\":0},\"other\":{}}"),
                Collections.singletonList(v));
        assertEquals(Arrays.asList("netaccess", "netaccess.vpn"), v.asked);
        assertEquals(Collections.singletonList("error.extension.netaccess.vpn.port-range"), keys(errors));
        assertEquals("netaccess.vpn", errors.get(0).getNamespace());
        assertEquals("The VPN port must be 1 to 65535.", errors.get(0).getMessage());
    }

    /**
     * On an update the validators check only what the save changes: data stored before the plugin's rules, left as it
     * is, never blocks a save of something else. Equal is by value, whatever the order of an object's keys.
     */
    @Test
    public void onAnUpdateAValidatorChecksOnlyTheNamespacesTheSaveChanges() throws Exception {
        PortValidator v = new PortValidator();
        ObjectNode stored = ext("{\"netaccess.vpn\":{\"port\":70000,\"on\":true},\"netaccess\":{\"port\":1}}");
        List<ExtensionError> errors = ConfigurationExtensions.check(
                ext("{\"netaccess.vpn\":{\"on\":true,\"port\":70000},\"netaccess\":{\"port\":2},\"netaccess.new\":{}}"),
                stored, Collections.singletonList(v));
        assertEquals(Collections.emptyList(), keys(errors));
        assertEquals(Arrays.asList("netaccess", "netaccess.new"), v.asked);

        // Changing the stale namespace is checked as any other change.
        assertEquals(Collections.singletonList("error.extension.netaccess.vpn.port-range"), keys(ConfigurationExtensions.check(
                ext("{\"netaccess.vpn\":{\"port\":70001}}"), stored, Collections.singletonList(new PortValidator()))));
    }

    /**
     * jsonb keeps {@code 1.0} as written, and Jackson reads it as a double, but the console sends it back as {@code 1}:
     * numbers compare by value, so a namespace the console only sent back is not a change.
     */
    @Test
    public void numbersCompareByValueSoANamespaceSentBackAsLoadedIsUnchanged() throws Exception {
        PortValidator v = new PortValidator();
        ObjectNode stored = ext("{\"netaccess\":{\"port\":70000.0,\"ratio\":1.50,\"list\":[1.0,{\"n\":2.0}]}}");
        assertEquals(Collections.emptyList(), keys(ConfigurationExtensions.check(
                ext("{\"netaccess\":{\"port\":70000,\"ratio\":1.5,\"list\":[1,{\"n\":2}]}}"), stored,
                Collections.singletonList(v))));
        assertEquals(Collections.emptyList(), v.asked);

        // A number of another value, or a value of another type, is a change.
        for (String changed : Arrays.asList("{\"netaccess\":{\"port\":70001,\"ratio\":1.5,\"list\":[1,{\"n\":2}]}}",
                "{\"netaccess\":{\"port\":\"70000\",\"ratio\":1.5,\"list\":[1,{\"n\":2}]}}",
                "{\"netaccess\":{\"port\":70000,\"ratio\":1.5,\"list\":[true,{\"n\":2}]}}")) {
            PortValidator w = new PortValidator();
            ConfigurationExtensions.check(ext(changed), stored, Collections.singletonList(w));
            assertEquals(changed, Collections.singletonList("netaccess"), w.asked);
        }
    }

    /**
     * On an update core's own checks, as the validators, look only at what the save changes: a namespace stored out of
     * band that isn't one, or isn't an object, goes back as it is (the console says saving keeps it). The total size
     * still counts everything.
     */
    @Test
    public void onAnUpdateCoreChecksOnlyTheNamespacesTheSaveChanges() throws Exception {
        ObjectNode stored = ext("{\"Bad Key\":{},\"demo\":[1],\"netaccess\":{\"port\":1}}");
        assertEquals(Collections.emptyList(), keys(ConfigurationExtensions.check(
                ext("{\"Bad Key\":{},\"demo\":[1.0],\"netaccess\":{\"port\":2}}"), stored,
                Collections.singletonList(new PortValidator()))));
        // Changed, they are checked as any other change; a new configuration has every namespace checked.
        assertEquals(Collections.singletonList("error.extensions.object"),
                keys(ConfigurationExtensions.check(ext("{\"demo\":[2]}"), stored, null)));
        assertEquals(Arrays.asList("error.extensions.namespace", "error.extensions.object"),
                keys(ConfigurationExtensions.check(stored, null)));

        ObjectNode big = JSON.createObjectNode();
        for (int i = 0; i < 4; i++) big.set("ns" + i, sized(ConfigurationExtensions.NAMESPACE_MAX_BYTES));
        assertEquals(Collections.singletonList("error.extensions.size"),
                keys(ConfigurationExtensions.check(big.deepCopy(), big, null)));
    }

    /** A Problem without a sentence still gives people one, never only its key. */
    @Test
    public void aProblemWithoutAMessageGetsADefaultSentence() throws Exception {
        ConfigurationExtensionValidator v = new ConfigurationExtensionValidator() {
            @Override public String extensionId() { return "demo"; }
            @Override public List<Problem> validate(String namespace, JsonNode value) {
                return Arrays.asList(new Problem("taken", null), new Problem("Bad.Code", "  "), new Problem("ok", "Fine."));
            }
        };
        List<String> messages = ConfigurationExtensions.check(ext("{\"demo.vpn\":{}}"), Collections.singletonList(v))
                .stream().map(ExtensionError::getMessage).collect(Collectors.toList());
        assertEquals(Arrays.asList("The \"demo.vpn\" settings were refused (taken).",
                "The \"demo.vpn\" settings were refused (invalid).", "Fine."), messages);
    }

    @Test
    public void validatorsRunOnlyOnceCoreChecksPass() throws Exception {
        PortValidator v = new PortValidator();
        List<ExtensionError> errors = ConfigurationExtensions.check(
                ext("{\"netaccess.vpn\":{\"port\":0},\"Bad\":{}}"), Collections.singletonList(v));
        assertEquals(Collections.singletonList("error.extensions.namespace"), keys(errors));
        assertEquals(Collections.emptyList(), v.asked);
    }

    @Test
    public void aCodeThatIsNotKebabCaseBecomesInvalid() throws Exception {
        ConfigurationExtensionValidator v = new ConfigurationExtensionValidator() {
            @Override public String extensionId() { return "demo"; }
            @Override public List<Problem> validate(String namespace, JsonNode value) {
                return Arrays.asList(new Problem("Bad.Code", "a"), new Problem(null, "b"));
            }
        };
        assertEquals(Arrays.asList("error.extension.demo.invalid", "error.extension.demo.invalid"),
                keys(ConfigurationExtensions.check(ext("{\"demo\":{}}"), Collections.singletonList(v))));
    }

    @Test
    public void refusesANulCharacterWhichJsonbCannotStore() throws Exception {
        for (String bad : Arrays.asList("{\"demo\":{\"v\":\"a\\u0000b\"}}", "{\"demo\":{\"a\\u0000\":1}}",
                "{\"demo\":{\"list\":[{\"v\":\"\\u0000\"}]}}")) {
            assertEquals(bad, Collections.singletonList("error.extensions.nul"), keys(ConfigurationExtensions.check(ext(bad), null)));
        }
        // An escaped backslash before "u0000" is plain text.
        assertEquals(Collections.emptyList(), ConfigurationExtensions.check(ext("{\"demo\":{\"v\":\"\\\\u0000\"}}"), null));
    }

    @Test
    public void aValidatorThatThrowsAnErrorIsNamed() throws Exception {
        ConfigurationExtensionValidator broken = new ConfigurationExtensionValidator() {
            @Override public String extensionId() { return "netaccess"; }
            @Override public List<Problem> validate(String namespace, JsonNode value) {
                throw new NoClassDefFoundError("org/example/Vpn");
            }
        };
        try {
            ConfigurationExtensions.check(ext("{\"netaccess\":{}}"), Collections.singletonList(broken));
            fail("the error was not wrapped");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("netaccess"));
            assertTrue(expected.getCause() instanceof NoClassDefFoundError);
        }
    }

    /** Kotlin's TODO() throws this: an Error that is neither a LinkageError nor a StackOverflowError. */
    private static final class NotImplementedError extends Error {
        NotImplementedError() { super("An operation is not implemented."); }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(Throwable t) throws T {
        throw (T) t;
    }

    private static ConfigurationExtensionValidator throwing(Throwable failure) {
        return new ConfigurationExtensionValidator() {
            @Override public String extensionId() { return "netaccess"; }
            @Override public List<Problem> validate(String namespace, JsonNode value) {
                ConfigurationExtensionsTest.<RuntimeException>sneakyThrow(failure);
                return null;
            }
        };
    }

    /**
     * Whatever a validator throws (an AssertionError, Kotlin's NotImplementedError, an undeclared IOException, a
     * StackOverflowError) is named; only the JVM's other failures (out of memory) pass through.
     */
    @Test
    public void aValidatorThatThrowsAnythingIsNamed() throws Exception {
        for (Throwable failure : Arrays.<Throwable>asList(new NotImplementedError(), new AssertionError("unreachable"),
                new IOException("sneaky"), new StackOverflowError())) {
            try {
                ConfigurationExtensions.check(ext("{\"netaccess\":{}}"), Collections.singletonList(throwing(failure)));
                fail(failure + " was not wrapped");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("netaccess"));
                assertSame(failure, expected.getCause());
            }
        }
        OutOfMemoryError oom = new OutOfMemoryError("test");
        try {
            ConfigurationExtensions.check(ext("{\"netaccess\":{}}"), Collections.singletonList(throwing(oom)));
            fail("the error was swallowed");
        } catch (OutOfMemoryError expected) {
            assertSame(oom, expected);
        }
    }

    /** A lazy list of problems that fails while it is read is the validator's failure too. */
    @Test
    public void aListThatFailsWhileItIsReadIsTheValidatorsFailure() throws Exception {
        ConfigurationExtensionValidator lazy = new ConfigurationExtensionValidator() {
            @Override public String extensionId() { return "netaccess"; }
            @Override public List<Problem> validate(String namespace, JsonNode value) {
                return new AbstractList<Problem>() {
                    @Override public int size() { return 1; }
                    @Override public Problem get(int index) { throw new NullPointerException("lazy"); }
                };
            }
        };
        try {
            ConfigurationExtensions.check(ext("{\"netaccess\":{}}"), Collections.singletonList(lazy));
            fail("the failure was not wrapped");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("netaccess"));
            assertTrue(expected.getCause() instanceof NullPointerException);
        }
    }

    /** A validator reads a copy: what it writes into it is never stored, nor seen by the next validator. */
    @Test
    public void aValidatorCannotChangeWhatIsStored() throws Exception {
        final List<String> seen = new ArrayList<>();
        ConfigurationExtensionValidator normalizer = new ConfigurationExtensionValidator() {
            @Override public String extensionId() { return "netaccess"; }
            @Override public List<Problem> validate(String namespace, JsonNode value) {
                seen.add(value.toString());
                ((ObjectNode) value).put("port", 443).put("raw", "a\u0000b");
                return null;
            }
        };
        ObjectNode sent = ext("{\"netaccess.vpn\":{\"on\":true}}");
        assertEquals(Collections.emptyList(), ConfigurationExtensions.check(sent, Arrays.asList(normalizer, normalizer)));
        assertEquals("{\"netaccess.vpn\":{\"on\":true}}", sent.toString());
        assertEquals(Arrays.asList("{\"on\":true}", "{\"on\":true}"), seen);
    }

    @Test
    public void aLongBadNamespaceIsCutShortInTheError() throws Exception {
        String key = "Bad" + rep('x', 60 * 1024);
        ObjectNode o = JSON.createObjectNode();
        o.set(key, JSON.createObjectNode());
        ExtensionError e = ConfigurationExtensions.check(o, null).get(0);
        assertEquals("error.extensions.namespace", e.getKey());
        assertTrue(e.getMessage().length() < 200);
        assertTrue(e.getMessage(), e.getMessage().startsWith("\"Bad" + rep('x', 61) + "\u2026\" is not"));
        // The error's namespace is cut short the same way: it echoes the caller's input.
        assertEquals("Bad" + rep('x', 61) + "\u2026", e.getNamespace());
    }
}
