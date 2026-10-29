/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.rest.resource.support;

import com.hmdm.persistence.domain.Device;
import com.hmdm.util.UntrustedText;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * The text of the kiosk lockout alert, a plain-text mail. Everything in it is server data, with one exception: the
 * device's own description of the event, which goes only into the body, on its own line, after
 * {@link UntrustedText#oneLine}. The device number is cleaned the same way, because the device chose it at enrollment.
 * The time is when the server received the event; the device's clock can be wrong or set back.
 */
final class KioskLockoutMail {

    static final int MAX_DETAIL_CHARS = 200;
    private static final int MAX_NAME_CHARS = 100;
    private static final int MAX_NUMBER_CHARS = 64;

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.ROOT).withZone(ZoneOffset.UTC);

    private KioskLockoutMail() {
    }

    static String subject(Device device) {
        String number = number(device);
        return number == null ? "MDMesh: kiosk exit PIN locked on a device"
                : "MDMesh: kiosk exit PIN locked on device " + number;
    }

    /**
     * @param receivedAt when the server received the event (ms)
     * @param detail     the device's text for the event (untrusted)
     * @param baseUrl    the server's public address, for a link to the device; may be empty
     */
    static String body(Device device, long receivedAt, String detail, String baseUrl) {
        String number = orDash(number(device));
        String config = UntrustedText.oneLine(device.getConfigName(), MAX_NAME_CHARS);
        String reported = UntrustedText.oneLine(detail, MAX_DETAIL_CHARS);
        String link = deviceLink(baseUrl, device.getNumber());

        StringBuilder b = new StringBuilder();
        b.append("Too many wrong kiosk exit PINs were entered on device ").append(number).append(".\n");
        b.append("The exit on the device is locked for a while. The device is still in kiosk mode.\n\n");
        b.append("Device: ").append(number).append('\n');
        b.append("Description: ").append(orDash(UntrustedText.oneLine(device.getDescription(), MAX_NAME_CHARS)))
                .append('\n');
        b.append("Configuration: ").append(orDash(config)).append('\n');
        b.append("Reported to the server: ").append(TIME.format(Instant.ofEpochMilli(receivedAt))).append('\n');
        if (reported != null) {
            b.append("Reported by the device: ").append(reported).append('\n');
        }
        b.append("\nWhat to do:\n");
        b.append("- Check the device and who has been using it.\n");
        b.append("- If the exit PIN may be known, change the exit PIN on the configuration")
                .append(config == null ? "" : " \"" + config + "\"").append(".\n");
        if (link != null) {
            b.append("\nDevice in the console: ").append(link).append('\n');
        }
        b.append("\nYou get this email because you can manage this device. ")
                .append("At most one such email is sent per device per hour.\n");
        return b.toString();
    }

    private static String number(Device device) {
        return UntrustedText.oneLine(device.getNumber(), MAX_NUMBER_CHARS);
    }

    /** The console's device page; the console routes /devices/{number}. Null without a base URL. */
    private static String deviceLink(String baseUrl, String number) {
        if (baseUrl == null || baseUrl.trim().isEmpty() || number == null || number.isEmpty()) {
            return null;
        }
        String base = baseUrl.trim().replaceAll("/+$", "");
        try {
            return base + "/devices/" + URLEncoder.encode(number, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            return null; // UTF-8 is always there
        }
    }

    private static String orDash(String s) {
        return s == null ? "-" : s;
    }
}
