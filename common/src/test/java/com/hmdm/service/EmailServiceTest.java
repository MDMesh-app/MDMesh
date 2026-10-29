/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.service;

import org.junit.Test;

import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class EmailServiceTest {

    private static EmailService service() {
        return new EmailService("smtp.example.com", 587, false, true, "", "", "mdm", "pw", "mdm@example.com",
                "", "", "", "");
    }

    @Test
    public void aSilentMailServerTimesOutInsteadOfBlockingForever() {
        Properties p = service().smtpProperties();
        assertEquals(String.valueOf(EmailService.CONNECT_TIMEOUT_MS), p.getProperty("mail.smtp.connectiontimeout"));
        assertEquals(String.valueOf(EmailService.READ_TIMEOUT_MS), p.getProperty("mail.smtp.timeout"));
        assertEquals(String.valueOf(EmailService.WRITE_TIMEOUT_MS), p.getProperty("mail.smtp.writetimeout"));
    }

    @Test
    public void theMailLibraryIsJakartaMailWithTheSmtpInjectionFix() throws Exception {
        // CVE-2025-7962 (SMTP command injection) is fixed in Jakarta Mail 1.6.8; javax.mail:mail 1.5.0-b01 was before.
        String version = javax.mail.Session.class.getPackage().getImplementationVersion();
        assertTrue("javax.mail is " + version, version != null && atLeast(version, 1, 6, 8));
        java.util.List<java.net.URL> copies = java.util.Collections.list(
                EmailServiceTest.class.getClassLoader().getResources("javax/mail/Session.class"));
        assertEquals("one javax.mail on the classpath: " + copies, 1, copies.size());
        assertTrue(copies.get(0).toString(), copies.get(0).toString().contains("jakarta.mail-"));
    }

    private static boolean atLeast(String version, int... min) {
        String[] parts = version.split("[^0-9]+");
        for (int i = 0; i < min.length; i++) {
            int v = i < parts.length && !parts[i].isEmpty() ? Integer.parseInt(parts[i]) : 0;
            if (v != min[i]) {
                return v > min[i];
            }
        }
        return true;
    }

    @Test
    public void keepsTheExistingConnectionSettings() {
        Properties p = service().smtpProperties();
        assertEquals("smtp.example.com", p.get("mail.smtp.host"));
        assertEquals(587, p.get("mail.smtp.port"));
        assertEquals(true, p.get("mail.smtp.starttls.enable"));
        assertEquals(true, p.get("mail.smtp.auth"));
    }

    @Test
    public void aSingleAddressIsParsedStrictly() {
        assertEquals("a@example.com", EmailService.singleAddress(" a@example.com ").getAddress());
        assertEquals("a@example.com", EmailService.singleAddress("Ann <a@example.com>").getAddress());
        assertNull(EmailService.singleAddress("a@example.com, b@example.com"));
        assertNull(EmailService.singleAddress("a@example.com,b@example.com"));
        assertNull(EmailService.singleAddress("a@example.com\r\nBcc: c@example.com"));
        assertNull(EmailService.singleAddress("not-an-address"));
        assertNull(EmailService.singleAddress(""));
        assertNull(EmailService.singleAddress(null));
    }

    @Test
    public void aPlainTextMailToAnAddressListIsRefused() {
        // Refused before any connection is tried, so this needs no mail server.
        assertFalse(service().sendPlainTextEmail("a@example.com, b@example.com", "s", "b"));
    }
}
