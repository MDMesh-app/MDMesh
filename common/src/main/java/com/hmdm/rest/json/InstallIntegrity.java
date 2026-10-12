package com.hmdm.rest.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Base64;
import java.util.Locale;

/**
 * Makes an agent-v1 {@code app.install} payload name the exact bytes it installs: the single APK, or every part
 * of a split bundle, carries a lowercase hex {@code sha256}. The agent refuses an install without one, and the
 * digest travels over the authenticated check-in, so a plain-http (LAN) or third-party APK host can't swap the
 * file. A hash already in the payload is kept (normalized from the base64 form {@code applicationVersions.apkHash}
 * uses); a missing one comes from a {@link Sha256Source} (the server hashes only APKs it hosts itself).
 */
public final class InstallIntegrity {

    /** Computes the hex SHA-256 of the APK at a URL. */
    @FunctionalInterface
    public interface Sha256Source {
        String sha256Hex(String url) throws Exception;
    }

    public static final String INSTALL_TYPE = "app.install";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private InstallIntegrity() {}

    /** {@link #ensureSha256} for an {@code app.install}; any other command's payload is returned as is. */
    public static String forCommand(String type, String payload, Sha256Source source) {
        if (type == null || !INSTALL_TYPE.equals(type.trim())) {
            return payload;
        }
        return ensureSha256(payload, source);
    }

    /**
     * @return the payload with a hex {@code sha256} on the APK (or on every part when {@code parts} is present).
     * @throws IllegalArgumentException with an operator-readable reason when a hash is malformed or can't be had.
     */
    public static String ensureSha256(String payload, Sha256Source source) {
        JsonNode root;
        try {
            root = payload == null ? null : MAPPER.readTree(payload);
        } catch (Exception e) {
            root = null;
        }
        if (!(root instanceof ObjectNode)) {
            throw new IllegalArgumentException("app.install payload is not a JSON object");
        }
        ObjectNode p = (ObjectNode) root;
        JsonNode parts = p.get("parts");
        if (parts != null && parts.isArray() && parts.size() > 0) {
            for (JsonNode part : parts) {
                if (!(part instanceof ObjectNode)) {
                    throw new IllegalArgumentException("app.install part is not a JSON object");
                }
                fill((ObjectNode) part, source);
            }
        } else {
            fill(p, source);
        }
        return p.toString();
    }

    private static void fill(ObjectNode apk, Sha256Source source) {
        String url = text(apk, "url");
        String given = text(apk, "sha256");
        String hex;
        if (given != null) {
            hex = normalizeHex(given);
            if (hex == null) {
                throw new IllegalArgumentException("malformed sha256 for " + (url != null ? url : "the APK")
                        + " (expected 64 hex characters)");
            }
        } else {
            if (url == null) {
                throw new IllegalArgumentException("an APK without a URL must carry its sha256");
            }
            try {
                hex = normalizeHex(source.sha256Hex(url));
            } catch (Exception e) {
                throw new IllegalArgumentException("no sha256 for " + url + ": " + e.getMessage());
            }
            if (hex == null) {
                throw new IllegalArgumentException("no sha256 for " + url);
            }
        }
        apk.put("sha256", hex);
    }

    /**
     * A SHA-256 as lowercase hex: accepts 64 hex characters, or the base64/base64url encoding of 32 bytes (the form
     * QR provisioning stores in {@code applicationVersions.apkHash}). Anything else is null.
     */
    public static String normalizeHex(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        if (v.matches("[0-9a-fA-F]{64}")) {
            return v.toLowerCase(Locale.ROOT);
        }
        byte[] bytes = decode(Base64.getUrlDecoder(), v);
        if (bytes == null) {
            bytes = decode(Base64.getDecoder(), v);
        }
        if (bytes == null || bytes.length != 32) {
            return null;
        }
        StringBuilder sb = new StringBuilder(64);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** {@code applicationVersions.apkHash} form (base64url, as QR provisioning computes it) of a hex SHA-256. */
    public static String toApkHash(String hex) {
        String h = normalizeHex(hex);
        if (h == null) {
            return null;
        }
        byte[] bytes = new byte[32];
        for (int i = 0; i < 32; i++) {
            bytes[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
        }
        return Base64.getUrlEncoder().encodeToString(bytes);
    }

    private static byte[] decode(Base64.Decoder decoder, String v) {
        try {
            return decoder.decode(v);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String text(ObjectNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.asText().trim();
        return s.isEmpty() ? null : s;
    }
}
