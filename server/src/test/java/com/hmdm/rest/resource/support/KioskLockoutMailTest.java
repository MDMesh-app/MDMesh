/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.rest.resource.support;

import com.hmdm.persistence.domain.Device;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class KioskLockoutMailTest {

    /** 2026-10-21 14:03:12 UTC */
    private static final long RECEIVED_AT = 1792591392000L;

    private static Device device(String number) {
        Device d = new Device();
        d.setNumber(number);
        d.setDescription("Front desk tablet");
        d.setConfigName("Lobby kiosk");
        return d;
    }

    @Test
    public void subjectNamesTheDevice() {
        assertEquals("MDMesh: kiosk exit PIN locked on device 1234", KioskLockoutMail.subject(device("1234")));
        assertEquals("MDMesh: kiosk exit PIN locked on a device", KioskLockoutMail.subject(device(null)));
    }

    @Test
    public void theSubjectNeverCarriesLineBreaksOrControlCharacters() {
        String subject = KioskLockoutMail.subject(device("12\r\nBcc: victim@example.com\u0000\u001b"));
        assertFalse(subject, subject.matches("(?s).*[\\p{Cntrl}].*"));
        assertTrue(subject, subject.startsWith("MDMesh: kiosk exit PIN locked on device 12"));
    }

    @Test
    public void bodySaysWhatHappenedAndWhatToDo() {
        String body = KioskLockoutMail.body(device("1234"), RECEIVED_AT,
                "3 wrong exit PINs; exit locked for 30 s", "https://mdm.example.com/");
        assertTrue(body, body.contains("Front desk tablet"));
        assertTrue(body, body.contains("Lobby kiosk"));
        assertTrue(body, body.contains("still in kiosk mode"));
        assertTrue(body, body.contains("change the exit PIN on the configuration \"Lobby kiosk\""));
        assertTrue(body, body.contains("Reported by the device: 3 wrong exit PINs; exit locked for 30 s"));
        assertTrue(body, body.contains("https://mdm.example.com/devices/1234"));
    }

    @Test
    public void theTimeIsWhenTheServerHeardOfIt() {
        String body = KioskLockoutMail.body(device("1234"), RECEIVED_AT, null, "");
        assertTrue(body, body.contains("Reported to the server: 2026-10-21 14:03:12 UTC"));
    }

    @Test
    public void theDeviceLinkIsEncodedAndLeftOutWithoutABaseUrl() {
        assertTrue(KioskLockoutMail.body(device("a b/c"), RECEIVED_AT, null, "https://mdm.example.com")
                .contains("https://mdm.example.com/devices/a%20b%2Fc"));
        String body = KioskLockoutMail.body(device("1234"), RECEIVED_AT, null, "");
        assertFalse(body, body.contains("http"));
        assertFalse(body, body.contains("Reported by the device"));
    }

    @Test
    public void theDeviceDetailStaysOnItsOwnLineAndIsCapped() {
        String hostile = "3 wrong\r\nexit PINs\u0000\u001b[31m‮" + repeat('x', 5000);
        String body = KioskLockoutMail.body(device("1234"), RECEIVED_AT, hostile, "");
        String line = Arrays.stream(body.split("\n"))
                .filter(l -> l.startsWith("Reported by the device: ")).findFirst().orElse("");
        String detail = line.substring("Reported by the device: ".length());

        assertTrue(detail, detail.startsWith("3 wrong exit PINs [31m"));
        assertFalse(detail, detail.matches("(?s).*[\\p{Cntrl}\\u202a-\\u202e].*"));
        assertTrue(detail.length() <= KioskLockoutMail.MAX_DETAIL_CHARS);
        assertFalse(body, body.contains("\nexit PINs"));
    }

    private static String repeat(char c, int n) {
        char[] a = new char[n];
        Arrays.fill(a, c);
        return new String(a);
    }
}
