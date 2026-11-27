/*
 * Copyright (C) 2026 the MDMesh contributors. Licensed under the Apache License, Version 2.0.
 */

package com.hmdm.plugins.audit.rest.filter;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * <p>How an audit rule stores a request body. Every body goes through one of these: none is stored as sent.</p>
 *
 * <p>The body is parsed from its raw bytes with a default Jackson mapper, as the resource's JSON provider does, so the
 * record shows what the resource ran (for duplicate keys, the last one; the encoding comes from the bytes, not the
 * declared charset), and what is stored is the parsed value, never the sent text. A body that can't be parsed, or that
 * is not an object or an array, is not stored.</p>
 */
enum AuditBody {

    /** Secret keys masked at any depth ({@link #isSecretKey}), and the password in a URL. */
    MASKED {
        @Override
        JsonNode record(JsonNode body) {
            return maskSecrets(body);
        }
    },

    /**
     * An agent command, for one device ({"type", "payload", ...}) or many ({"deviceIds", "command": {...}}). Its
     * payload can carry a secret (a kiosk exit password, a new screen lock PIN), so it is never stored; the command
     * type, the device IDs and the capability are kept, other secrets are masked.
     */
    COMMAND {
        @Override
        JsonNode record(JsonNode body) {
            if (!body.isObject()) {
                return null;
            }
            maskCommandPayload((ObjectNode) body);
            JsonNode command = body.get("command");
            if (command != null && command.isObject()) {
                maskCommandPayload((ObjectNode) command);
            }
            return maskSecrets(body);
        }
    },

    /**
     * A configuration save or copy. Its free-form values (the Wi-Fi password, the kiosk exit PIN, managed app setting
     * values, QR parameters, admin extras, extension data) can't be told apart from the rest by key, so only the fields
     * that hold no secret are kept; every other field is named with a marker ({@link #marker}).
     */
    CONFIGURATION {
        @Override
        JsonNode record(JsonNode body) {
            return body.isObject() ? allowed((ObjectNode) body, CONFIGURATION_FIELDS) : null;
        }
    },

    /** A device save: as {@link #CONFIGURATION}, since a device carries custom fields and its configuration. */
    DEVICE {
        @Override
        JsonNode record(JsonNode body) {
            return body.isObject() ? allowed((ObjectNode) body, DEVICE_FIELDS) : null;
        }
    };

    static final String NOT_STORED = "(not valid JSON, not stored)";

    static final String TOO_LARGE = "(over 1 MB, not stored)";

    /**
     * The most of a body the public sign-in routes keep (LOGIN, JWT_LOGIN): a sign-in body is tiny, so anything past
     * this is someone else's text. Only the first {@value} bytes are read, masked, with {@link #TRUNCATED}.
     */
    static final int PUBLIC_BODY_CAP = 8 * 1024;

    static final String TRUNCATED = "(truncated)";

    private static final String MASK = "******";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The secret keys, lower case: passwords (a configuration's {@code password} is its kiosk exit PIN), tokens. */
    private static final Set<String> SECRET_KEYS = new HashSet<>(Arrays.asList(
            "newpassword", "oldpassword", "password", "confirm", "wifipassword", "authtoken", "passwordresettoken",
            "twofactorsecret", "signuptoken", "authdata", "devicesecret", "enrolltoken", "token", "apikey", "secret",
            "pin", "pwd", "passphrase", "passcode", "privatekey", "accesskey", "credential", "credentials", "psk",
            "jsessionid", "sessionid"));

    /**
     * Lower-case endings of a secret key ({@code wifiPassword}, {@code awsSecretAccessKey}). Only the endings that name
     * a secret: an id such as {@code qrCodeKey} or {@code packageKey} ends in "key" and is kept.
     */
    private static final List<String> SECRET_ENDINGS = Arrays.asList(
            "password", "secret", "token", "passphrase", "passcode", "privatekey", "accesskey", "secretkey", "apikey",
            "signingkey", "encryptionkey", "serverkey", "licensekey", "credential", "credentials", "psk",
            "authorization");

    /** URL query parameters that sign or authorize a URL, lower case, besides the secret keys. */
    private static final Set<String> SECRET_PARAMETERS = new HashSet<>(Arrays.asList("sig", "signature"));

    /**
     * A {@code key=} value kept as it is: a word ({@code ?key=lobby}). Any other value (Google's {@code key=AIza...},
     * a short token with digits) is masked: an API key passed so can be any length.
     */
    private static final Pattern WORD_KEY_VALUE = Pattern.compile("[A-Za-z_-]{1,15}");

    /** The password in a URL, {@code scheme://user:password@host}. */
    private static final Pattern URL_USER_INFO = Pattern.compile("(://)[^/@\\s\"]+@");


    /** The markers {@link #marker} writes: what a field held, never its value, so a marker is kept as it is. */
    private static final Pattern MARKER = Pattern.compile("<(set|empty|\\d+ items|\\d+ entries)>");

    /** A configuration's fields that hold no secret, kept as sent when they are a value or a list of values. */
    private static final Set<String> CONFIGURATION_FIELDS = new HashSet<>(Arrays.asList(
            "id", "name", "description", "type", "customerId", "blockStatusBar", "systemUpdateType",
            "systemUpdateFrom", "systemUpdateTo", "scheduleAppUpdate", "appUpdateFrom", "appUpdateTo",
            "downloadUpdates", "gps", "bluetooth", "camera", "wifi", "mobileData", "usbStorage", "requestUpdates",
            "disableLocation", "appPermissions", "pushOptions", "keepaliveTime", "autoBrightness", "brightness",
            "manageTimeout", "timeout", "lockVolume", "manageVolume", "volume", "passwordMode", "orientation",
            "runDefaultLauncher", "disableScreenshots", "disableFactoryReset", "disableSafeBoot", "kioskLockVolume",
            "wifiRadio", "locationServices", "locationServicesAction", "connectivityMode", "autostartForeground",
            "timeZone", "allowedClasses", "newServerUrl", "lockSafeSettings", "permissive", "kioskExit", "showWifi",
            "mainAppId", "eventReceivingComponent", "kioskMode", "contentAppId", "wifiSSID", "wifiSecurityType",
            "encryptDevice", "mobileEnrollment", "kioskHome", "kioskRecents", "kioskNotifications", "kioskSystemInfo",
            "kioskKeyguard", "kioskLockButtons", "kioskScreenOn", "launcherUrl", "restrictions",
            "useDefaultDesignSettings", "backgroundColor", "textColor", "backgroundImageUrl", "backgroundImageFileId",
            "backgroundImageSha256", "backgroundImageFit", "backgroundImageFocalX", "backgroundImageFocalY",
            "backgroundImageAction", "iconSize", "desktopHeader", "desktopHeaderTemplate", "displayStatus", "baseUrl",
            "defaultFilePath", "filesToRemove"));

    /** A device's fields that hold no secret: what the console's bulk move ({ids, configurationId}) sends, and more. */
    private static final Set<String> DEVICE_FIELDS = new HashSet<>(Arrays.asList(
            "id", "ids", "number", "oldNumber", "description", "configurationId", "imei", "phone"));

    abstract JsonNode record(JsonNode body);

    /**
     * <p>The body to store for a request body as the client sent it.</p>
     *
     * @param rawBody a request body as the client sent it.
     * @return the request body to store in the audit log.
     */
    String stored(byte[] rawBody) {
        try {
            JsonNode body = MAPPER.readTree(rawBody);
            JsonNode stored = body == null || !body.isContainerNode() ? null : record(body);
            return stored == null ? NOT_STORED : MAPPER.writeValueAsString(stored);
        } catch (IOException | RuntimeException e) {
            return NOT_STORED;
        }
    }

    /**
     * <p>The body to store, reading at most {@code cap} bytes of it (0: no cap). A longer body keeps only its first
     * {@code cap} bytes, masked as it is read ({@link #maskedPrefix}), and {@link #TRUNCATED}.</p>
     */
    String stored(byte[] rawBody, int cap) {
        if (cap <= 0 || rawBody.length <= cap) {
            return stored(rawBody);
        }
        return maskedPrefix(Arrays.copyOf(rawBody, cap)) + " " + TRUNCATED;
    }

    /**
     * The JSON tokens of a body cut short, copied as far as they can be read: the value of a secret key masked (an
     * object or array whole), every text masked ({@link #maskTextSecrets}). A token the cut splits is dropped.
     */
    static String maskedPrefix(byte[] prefix) {
        StringWriter out = new StringWriter();
        JsonGenerator generator;
        try {
            generator = MAPPER.getFactory().createGenerator(out);
        } catch (IOException e) {
            return NOT_STORED;
        }
        try (JsonParser parser = MAPPER.getFactory().createParser(prefix)) {
            boolean secret = false;
            JsonToken token;
            while ((token = parser.nextToken()) != null) {
                if (token == JsonToken.FIELD_NAME) {
                    secret = isSecretKey(parser.getCurrentName());
                    generator.writeFieldName(parser.getCurrentName());
                } else if (secret) {
                    secret = false;
                    if (token == JsonToken.VALUE_NULL) {
                        generator.writeNull();
                    } else {
                        if (token.isStructStart()) {
                            parser.skipChildren();
                        }
                        generator.writeString(MASK);
                    }
                } else if (token == JsonToken.VALUE_STRING) {
                    generator.writeString(maskTextSecrets(parser.getText()));
                } else {
                    generator.copyCurrentEvent(parser);
                }
            }
        } catch (IOException | RuntimeException cut) {
            // Where the cut is: what was read so far is kept
        }
        try {
            generator.flush();
        } catch (IOException e) {
            return NOT_STORED;
        }
        return out.toString();
    }

    /**
     * <p>Whether a key holds a secret, in any case: one of {@link #SECRET_KEYS}, or with one of
     * {@link #SECRET_ENDINGS}, or a PIN ({@code kioskPin}, {@code KIOSK_PIN}; not {@code pinPackage} or {@code spin}).</p>
     */
    static boolean isSecretKey(String name) {
        String key = normalized(name);
        if (SECRET_KEYS.contains(key) || SECRET_ENDINGS.stream().anyMatch(key::endsWith)) {
            return true;
        }
        if (!name.toLowerCase(Locale.ROOT).endsWith("pin")) {
            return false;
        }
        char start = name.charAt(name.length() - 3);
        char before = name.charAt(name.length() - 4);
        return start == 'P' || !Character.isLetterOrDigit(before);
    }

    /**
     * A key as the secret lists spell it: lower case, without {@code _}, {@code -} or {@code .}, so {@code api_key},
     * {@code x-api-key} and {@code client.secret} are the keys they look like. One spelling for the stored record and
     * the log line, which is made from it.
     */
    static String normalized(String name) {
        StringBuilder key = new StringBuilder(name.length());
        for (char c : name.toLowerCase(Locale.ROOT).toCharArray()) {
            if (c != '_' && c != '-' && c != '.') {
                key.append(c);
            }
        }
        return key.toString();
    }

    private static void maskCommandPayload(ObjectNode command) {
        JsonNode payload = command.get("payload");
        if (payload != null && !payload.isNull()) {
            command.put("payload", MASK);
        }
    }

    /** Masks, in place, the value of every secret key at any depth of objects and arrays, and every URL password. */
    private static JsonNode maskSecrets(JsonNode node) {
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            List<String> names = new ArrayList<>();
            object.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                if (isSecretKey(name) && !object.get(name).isNull()) {
                    object.put(name, MASK);
                } else {
                    object.set(name, maskSecrets(object.get(name)));
                }
            }
        } else if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            for (int i = 0; i < array.size(); i++) {
                array.set(i, maskSecrets(array.get(i)));
            }
        } else if (node.isTextual()) {
            return TextNode.valueOf(maskTextSecrets(node.asText()));
        }
        return node;
    }

    /**
     * <p>The password in a URL ({@code https://user:password@host}) and the value of every secret {@code name=value}
     * in a text, masked: a query parameter ({@code ?token=}, {@code &sig=}, {@code X-Amz-Signature=},
     * {@code user[password]=}) or free text ({@code wifi password=hunter2}, {@code password = x}, {@code password: x},
     * a line {@code token=...}, {@code Authorization: Bearer T}). A name starts at a word boundary.</p>
     *
     * <p>One pass, each character looked at a bounded number of times, whatever the text: a value is read on for names
     * of its own ({@code redirect=https://x/?token=...}), and the end of the value it is in is found once, not again
     * for each name inside it.</p>
     */
    static String maskTextSecrets(String text) {
        String s = text.contains("@") ? URL_USER_INFO.matcher(text).replaceAll("$1" + MASK + "@") : text;
        if (s.indexOf('=') < 0 && s.indexOf(':') < 0) {
            return s;
        }
        int n = s.length();
        StringBuilder out = null;
        int copied = 0;
        int valueEnd = -1;              // where the value the scan is in ends; found once per value
        int i = 0;
        while (i < n) {
            if (!isNameChar(s.charAt(i)) || (i > 0 && isNameChar(s.charAt(i - 1)))) {
                i++;
                continue;
            }
            int nameEnd = i;
            while (nameEnd < n && isNameChar(s.charAt(nameEnd))) {
                nameEnd++;
            }
            int valueStart = separatorEnd(s, nameEnd);
            if (valueStart < 0 && nameEnd < n && s.charAt(nameEnd) == ':'
                    && normalized(s.substring(i, nameEnd)).endsWith("authorization")) {
                valueStart = nameEnd + 1;           // Authorization:Bearer T, with no space
            }
            if (valueStart < 0) {
                i = nameEnd;
                continue;
            }
            if (valueEnd < valueStart) {
                valueEnd = valueStart;
                while (valueEnd < n && !isValueEnd(s.charAt(valueEnd))) {
                    valueEnd++;
                }
            }
            String name = s.substring(i, nameEnd);
            int[] masked = valueEnd > valueStart ? secret(s, name, valueStart, valueEnd) : null;
            if (masked == null) {
                // Not a secret: read on into the value, which may hold a name of its own
                i = valueStart;
                continue;
            }
            if (out == null) {
                out = new StringBuilder(n);
            }
            out.append(s, copied, masked[0]).append(MASK);
            copied = masked[1];
            i = masked[1];
        }
        if (out == null) {
            return s;
        }
        return out.append(s, copied, n).toString();
    }

    private static boolean isNameChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == '_' || c == '.' || c == '-' || c == '[' || c == ']';
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == 0x0B;
    }

    private static boolean isValueEnd(char c) {
        return c == '&' || c == '#' || c == ';' || c == '"' || isSpace(c);
    }

    /** Where the value after a name ending at {@code at} starts: after {@code =} (spaces around) or {@code ": "}. */
    private static int separatorEnd(String s, int at) {
        int n = s.length();
        int j = at;
        while (j < n && isSpace(s.charAt(j))) {
            j++;
        }
        if (j < n && s.charAt(j) == '=') {
            j++;
            while (j < n && isSpace(s.charAt(j))) {
                j++;
            }
            return j;
        }
        if (at < n && s.charAt(at) == ':' && at + 1 < n && isSpace(s.charAt(at + 1))) {
            j = at + 1;
            while (j < n && isSpace(s.charAt(j))) {
                j++;
            }
            return j;
        }
        return -1;
    }

    /**
     * The part of the value at {@code valueStart..valueEnd} to mask, as {start, end}, or null if {@code name} names no
     * secret. An authorization keeps its scheme and has the credential after it masked ({@code Bearer ******}). Only a
     * value short enough to be a word is ever copied, so a long one costs nothing more.
     */
    private static int[] secret(String s, String name, int valueStart, int valueEnd) {
        boolean word = valueEnd - valueStart <= 15
                && WORD_KEY_VALUE.matcher(s.subSequence(valueStart, valueEnd)).matches();
        if (normalized(name).endsWith("authorization")) {
            int credential = valueEnd;
            while (credential < s.length() && (s.charAt(credential) == ' ' || s.charAt(credential) == '\t')) {
                credential++;
            }
            int end = credential;
            while (word && credential > valueEnd && end < s.length() && !isValueEnd(s.charAt(end))) {
                end++;
            }
            return end > credential ? new int[]{credential, end} : new int[]{valueStart, valueEnd};
        }
        return isSecretParameter(name, word) ? new int[]{valueStart, valueEnd} : null;
    }

    /**
     * Whether a parameter name names a secret. A name with brackets is each of its parts ({@code user[password]},
     * {@code x]pwd}, {@code f[0]pin}): secret if any part is.
     */
    private static boolean isSecretParameter(String name, boolean valueIsWord) {
        int start = 0;
        for (int i = 0; i <= name.length(); i++) {
            if (i == name.length() || name.charAt(i) == '[' || name.charAt(i) == ']') {
                if (i > start && isSecretPart(name.substring(start, i), valueIsWord)) {
                    return true;
                }
                start = i + 1;
            }
        }
        return false;
    }

    private static boolean isSecretPart(String name, boolean valueIsWord) {
        String key = name.toLowerCase(Locale.ROOT);
        return isSecretKey(name) || SECRET_PARAMETERS.contains(key) || key.startsWith("x-amz-")
                || key.startsWith("x-goog-") || ("key".equals(key) && !valueIsWord);
    }

    /** The fields of {@code body} in {@code allowed} that are a value or a list of values, a marker for the rest. */
    private static ObjectNode allowed(ObjectNode body, Set<String> allowed) {
        ObjectNode stored = MAPPER.createObjectNode();
        Iterator<Map.Entry<String, JsonNode>> fields = body.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode value = field.getValue();
            if (allowed.contains(field.getKey()) && !isSecretKey(field.getKey()) && holdsValuesOnly(value)) {
                stored.set(field.getKey(), maskSecrets(value));
            } else {
                stored.set(field.getKey(), marker(value));
            }
        }
        return stored;
    }

    private static boolean holdsValuesOnly(JsonNode value) {
        if (value.isArray()) {
            for (JsonNode item : value) {
                if (!item.isValueNode()) {
                    return false;
                }
            }
            return true;
        }
        return value.isValueNode();
    }

    /** What a field held, without its value: null, {@code <empty>}, {@code <set>}, or how many items or entries. */
    private static JsonNode marker(JsonNode value) {
        if (value.isNull() || (value.isTextual() && MARKER.matcher(value.asText()).matches())) {
            return value;
        }
        if (value.isArray()) {
            return TextNode.valueOf("<" + value.size() + " items>");
        }
        if (value.isObject()) {
            return TextNode.valueOf("<" + value.size() + " entries>");
        }
        return TextNode.valueOf(value.isTextual() && value.asText().isEmpty() ? "<empty>" : "<set>");
    }
}
