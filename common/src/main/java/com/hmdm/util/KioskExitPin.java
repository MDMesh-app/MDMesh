/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * <p>The kiosk exit PIN's format: {@value #MIN_LENGTH} to {@value #MAX_LENGTH} digits, or none (a kiosk without an
 * on-device exit). Digits, because the device's prompt is a number pad. At least {@value #MIN_LENGTH}, because the
 * device allows about three guesses an hour once its lockouts reach their longest step, so a 6-digit PIN takes
 * decades to guess where a 4-digit one could fall within weeks.</p>
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
     * Why a {@code kiosk.enter} command may not be queued with the PIN in its payload ({@code password}), or null when
     * it may: no PIN (no exit on the device), a PIN that follows the rule, or exactly the PIN one of the account's
     * configurations stores ({@code configured}), so Return to configured kiosk keeps working on a configuration whose
     * PIN predates the rule. Any other type, and a payload that isn't a JSON object (the agent reports it), pass.
     */
    public static String enterCommandProblem(String type, String payload, Predicate<String> configured) {
        if (type == null || !"kiosk.enter".equalsIgnoreCase(type.trim()) || payload == null) return null;
        JsonNode pin;
        try {
            JsonNode root = MAPPER.readTree(payload);
            pin = root != null && root.isObject() ? root.get("password") : null;
        } catch (IOException e) {
            return null;
        }
        if (pin == null || pin.isNull()) return null;
        if (!pin.isTextual()) return FORMAT_MESSAGE;
        String text = pin.asText();
        return valid(text) || configured.test(text) ? null : FORMAT_MESSAGE;
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
