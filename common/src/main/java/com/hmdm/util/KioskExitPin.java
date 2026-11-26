/*
 * Copyright (C) 2026 the MDMesh contributors. Licensed under the Apache License, Version 2.0.
 */
package com.hmdm.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * <p>The kiosk exit PIN's format: {@value #MIN_LENGTH} to {@value #MAX_LENGTH} digits, or none (a kiosk without an
 * on-device exit). Digits, because the device's prompt is a number pad. At least {@value #MIN_LENGTH}, because the
 * device allows one guess an hour once its lockouts reach their longest step, so a 6-digit PIN takes decades to
 * guess where a 4-digit one could fall within months.</p>
 *
 * <p>A configuration saved before this rule may carry any text. It keeps working: devices take any PIN (with the full
 * keyboard for one that isn't digits), a save that keeps the stored PIN as it is goes through, and the console marks
 * such a configuration ({@link #legacy}) and asks for a new PIN. Only a new or changed PIN must follow the rule.</p>
 */
public final class KioskExitPin {

    public static final int MIN_LENGTH = 6;
    public static final int MAX_LENGTH = 12;

    /** Why a save was refused, as the console shows it. */
    public static final String FORMAT_MESSAGE =
            "The kiosk exit PIN must be " + MIN_LENGTH + " to " + MAX_LENGTH + " digits, or empty for no exit on the device.";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Pattern RULE = Pattern.compile("[0-9]{" + MIN_LENGTH + "," + MAX_LENGTH + "}");

    private KioskExitPin() {
    }

    /** No PIN (null or empty), or {@value #MIN_LENGTH} to {@value #MAX_LENGTH} ASCII digits. */
    public static boolean valid(String pin) {
        return pin == null || pin.isEmpty() || RULE.matcher(pin).matches();
    }

    /** A PIN saved before the rule that doesn't follow it: devices still take it, the console asks for a new one. */
    public static boolean legacy(String pin) {
        return !valid(pin);
    }

    /**
     * The {@code kiosk.enter} payload key that asks the server for the target device's own configuration PIN (Return to
     * configured kiosk), so no client ever sends a stored PIN back. See {@link #withConfiguredPin}.
     */
    public static final String CONFIGURED_PIN = "configuredPin";

    private static boolean isKioskEnter(String type) {
        return type != null && "kiosk.enter".equalsIgnoreCase(type.trim());
    }

    private static ObjectNode object(String payload) {
        if (payload == null) return null;
        try {
            JsonNode root = MAPPER.readTree(payload);
            return root instanceof ObjectNode ? (ObjectNode) root : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** A {@code kiosk.enter} whose payload has {@code "configuredPin": true}: the server fills the PIN per device. */
    public static boolean wantsConfiguredPin(String type, String payload) {
        if (!isKioskEnter(type)) return false;
        ObjectNode root = object(payload);
        return root != null && root.path(CONFIGURED_PIN).asBoolean(false);
    }

    /**
     * Why a {@code kiosk.enter} command may not be queued with the PIN in its payload ({@code password}), or null when
     * it may: no PIN (no exit on the device) or one that follows the rule. A payload asking for the configured PIN
     * ({@link #wantsConfiguredPin}) passes: whatever PIN it carries is replaced. Any other type, and a payload that
     * isn't a JSON object (the agent reports it), pass. There is no exemption to look up, so the answer says nothing
     * about any stored PIN.
     */
    public static String enterCommandProblem(String type, String payload) {
        if (!isKioskEnter(type) || wantsConfiguredPin(type, payload)) return null;
        ObjectNode root = object(payload);
        JsonNode pin = root == null ? null : root.get("password");
        if (pin == null || pin.isNull()) return null;
        return pin.isTextual() && valid(pin.asText()) ? null : FORMAT_MESSAGE;
    }

    /**
     * The payload for one device: {@link #CONFIGURED_PIN} removed, and {@code password} set to its configuration's
     * PIN as stored (left out when it has none), exactly as config.apply's kiosk carries it, so the agent sees the
     * same kiosk either way. Agents of any version get an ordinary {@code kiosk.enter}.
     */
    public static String withConfiguredPin(String payload, String configurationPin) {
        ObjectNode root = object(payload);
        if (root == null) return payload;
        root.remove(CONFIGURED_PIN);
        if (configurationPin == null) root.remove("password");
        else root.put("password", configurationPin);
        try {
            return MAPPER.writeValueAsString(root);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Why a configuration may not be saved with {@code pin}, or null when it may: it follows the rule, or it is the
     * PIN already stored ({@code storedPin}, null for a new configuration), kept as it was.
     */
    public static String problem(String pin, String storedPin) {
        if (valid(pin) || pin.equals(storedPin)) return null;
        return FORMAT_MESSAGE;
    }
}
