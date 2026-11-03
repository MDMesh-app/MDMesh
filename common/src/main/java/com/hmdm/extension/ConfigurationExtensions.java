package com.hmdm.extension;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The rules for a configuration's {@code extensions}: an object of namespaces, each an extension's JSON object.
 *
 * <p>A namespace is an extension id ({@code netaccess}) or {@code <id>.<name>} ({@code netaccess.vpn}), both parts
 * lowercase kebab-case of at most 32 characters, as the console's extension ids (web/src/ext/registry.ts). An id has
 * no dot, so the part before the first dot is always the owner, and no extension can claim another's namespace.</p>
 *
 * <p>The console sends every namespace it loaded back on a save, parsed and written again by a browser, so a value
 * must survive that: integers within &plusmn;2<sup>53</sup> (a 64-bit id goes in a string).</p>
 */
public final class ConfigurationExtensions {

    /** Each namespace's object, as serialized JSON in UTF-8. */
    public static final int NAMESPACE_MAX_BYTES = 16 * 1024;
    /** The whole of {@code extensions}, as serialized JSON in UTF-8. */
    public static final int TOTAL_MAX_BYTES = 64 * 1024;

    private static final int ID_MAX = 32;
    private static final int MESSAGE_NAMESPACE_MAX = 64;
    private static final String ID = "[a-z][a-z0-9]*(?:-[a-z0-9]+)*";
    private static final Pattern ID_PATTERN = Pattern.compile(ID);
    private static final Pattern NAMESPACE = Pattern.compile("(" + ID + ")(?:\\.(" + ID + "))?");

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Equal JSON values: numbers by value ({@code 1.0} as {@code 1}), everything else as JsonNode.equals (an object's
     * keys in any order). jsonb keeps a number as written and Jackson reads {@code 1.0} as a double, while the console
     * sends every namespace back as a browser's JSON has it ({@code 1}). Only 0 (equal) is meaningful.
     */
    private static final Comparator<JsonNode> BY_VALUE = (a, b) -> {
        if (a.equals(b)) return 0;
        if (b == null || !a.isNumber() || !b.isNumber()) return 1;
        try {
            return a.decimalValue().compareTo(b.decimalValue()) == 0 ? 0 : 1;
        } catch (NumberFormatException notFinite) {
            return 1;
        }
    };

    private ConfigurationExtensions() {
    }

    /** Whether {@code s} is an extension (or server plugin) id: lowercase kebab-case, at most 32 characters. */
    public static boolean isId(String s) {
        return s != null && s.length() <= ID_MAX && ID_PATTERN.matcher(s).matches();
    }

    /** The extension that owns {@code namespace} ({@code netaccess} for {@code netaccess.vpn}), or null for none. */
    public static String ownerOf(String namespace) {
        if (namespace == null) return null;
        Matcher m = NAMESPACE.matcher(namespace);
        if (!m.matches() || m.group(1).length() > ID_MAX || (m.group(2) != null && m.group(2).length() > ID_MAX)) {
            return null;
        }
        return m.group(1);
    }

    /** {@link #check(ObjectNode, ObjectNode, Collection)} for a new configuration: the validators check every namespace. */
    public static List<ExtensionError> check(ObjectNode extensions, Collection<ConfigurationExtensionValidator> validators) {
        return check(extensions, null, validators);
    }

    /**
     * What a save's {@code extensions} would be refused for, empty when it may be stored. Both core's checks and the
     * validators look only at the namespaces the save changes: all of them for a new configuration, else those whose
     * value differs from {@code stored} (numbers by value). Core's come first (a namespace's name, its JSON object, its
     * size; the total size counts every namespace); the validators run only when those pass, each on the namespaces its
     * extension owns, and each on its own copy. So data stored before a plugin's rules, by a copy or out of band never
     * blocks a save that leaves it as it is, just as the console checks only the sections it changed. A problem
     * without a message gets a default sentence.
     * Anything a validator throws, while it answers or while its list is read, is an IllegalStateException naming it:
     * an Error (NoClassDefFoundError, Kotlin's NotImplementedError, an AssertionError) and an undeclared checked
     * exception and a StackOverflowError included. Only the JVM's other failures (VirtualMachineError, such as
     * OutOfMemoryError) pass through as they are.
     */
    public static List<ExtensionError> check(ObjectNode extensions, ObjectNode stored,
                                             Collection<ConfigurationExtensionValidator> validators) {
        List<ExtensionError> errors = new ArrayList<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = extensions.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            // Sent back as stored (written out of band, say): kept as it is, as the console says saving does.
            if (unchanged(e, stored)) continue;
            String ns = e.getKey();
            if (ownerOf(ns) == null) {
                String shown = shortened(ns);
                errors.add(new ExtensionError(shown, "error.extensions.namespace", "\"" + shown + "\" is not an extension "
                        + "namespace: use the extension's id, or <id>.<name>, in lowercase kebab-case."));
            } else if (!e.getValue().isObject()) {
                errors.add(new ExtensionError(ns, "error.extensions.object", "The \"" + ns + "\" data must be a JSON object."));
            } else if (hasNul(e.getValue())) {
                // PostgreSQL's jsonb refuses it, so the save would fail in the database.
                errors.add(new ExtensionError(ns, "error.extensions.nul", "The \"" + ns + "\" data contains a NUL character."));
            } else if (bytes(e.getValue()) > NAMESPACE_MAX_BYTES) {
                errors.add(new ExtensionError(ns, "error.extensions.namespace.size", "The \"" + ns + "\" data is larger than "
                        + NAMESPACE_MAX_BYTES / 1024 + " KB."));
            }
        }
        if (bytes(extensions) > TOTAL_MAX_BYTES) {
            errors.add(new ExtensionError(null, "error.extensions.size", "The extensions' data is larger than "
                    + TOTAL_MAX_BYTES / 1024 + " KB in all."));
        }
        if (!errors.isEmpty() || validators == null) return errors;

        for (Iterator<Map.Entry<String, JsonNode>> it = extensions.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            if (unchanged(e, stored)) continue;
            String owner = ownerOf(e.getKey());
            for (ConfigurationExtensionValidator v : validators) {
                if (!owner.equals(v.extensionId())) continue;
                List<ExtensionError> found = new ArrayList<>();
                try {
                    // A copy: what a validator writes into it is never stored, nor seen by the next one.
                    List<ConfigurationExtensionValidator.Problem> problems = v.validate(e.getKey(), e.getValue().deepCopy());
                    if (problems == null) continue;
                    // Read inside the try, as a lazy list fails only now.
                    for (ConfigurationExtensionValidator.Problem p : problems) {
                        String code = isId(p.getCode()) ? p.getCode() : "invalid";
                        // People get a sentence, never only the key.
                        String message = p.getMessage() == null || p.getMessage().trim().isEmpty()
                                ? "The \"" + e.getKey() + "\" settings were refused (" + code + ")." : p.getMessage();
                        found.add(new ExtensionError(e.getKey(), "error.extension." + e.getKey() + "." + code, message));
                    }
                } catch (Throwable failure) {
                    // A validator that recurses (StackOverflowError) is its own bug; the JVM's other failures are not.
                    if (failure instanceof VirtualMachineError && !(failure instanceof StackOverflowError)) {
                        throw (VirtualMachineError) failure;
                    }
                    throw new IllegalStateException("ConfigurationExtensionValidator " + owner + " failed", failure);
                }
                errors.addAll(found);
            }
        }
        return errors;
    }

    /**
     * Whether a save sends {@code e} back as stored (never, for a new configuration): equal by value, whatever the
     * order of an object's keys, numbers by value.
     */
    private static boolean unchanged(Map.Entry<String, JsonNode> e, ObjectNode stored) {
        if (stored == null) return false;
        JsonNode before = stored.get(e.getKey());
        return before != null && e.getValue().equals(BY_VALUE, before);
    }

    /** A bad namespace as its error echoes it (message and namespace): at most 64 characters, then an ellipsis. */
    private static String shortened(String ns) {
        if (ns.length() <= MESSAGE_NAMESPACE_MAX) return ns;
        int end = MESSAGE_NAMESPACE_MAX;
        if (Character.isHighSurrogate(ns.charAt(end - 1))) end--;  // never half a character
        return ns.substring(0, end) + "\u2026";
    }

    /** Whether a text or a field name anywhere in {@code node} holds U+0000. */
    private static boolean hasNul(JsonNode node) {
        if (node.isTextual()) return node.textValue().indexOf('\0') >= 0;
        for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            if (e.getKey().indexOf('\0') >= 0 || hasNul(e.getValue())) return true;
        }
        if (node.isArray()) {
            for (JsonNode item : node) if (hasNul(item)) return true;
        }
        return false;
    }

    private static int bytes(JsonNode node) {
        try {
            return JSON.writeValueAsString(node).getBytes(StandardCharsets.UTF_8).length;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize extensions", e);
        }
    }
}
