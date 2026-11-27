/*
 * Copyright (C) 2026 the MDMesh contributors. Licensed under the Apache License, Version 2.0.
 */

package com.hmdm.plugins.audit.rest.filter;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.hmdm.persistence.domain.User;
import com.hmdm.plugins.audit.persistence.domain.AuditLogRecord;
import com.hmdm.security.SecurityContext;
import org.junit.Test;

import javax.servlet.ServletException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Every route that changes an account's email or password leaves an audit record, and no password is in it. */
public class AccountChangeAuditTest {

    private static final String DETAILS = "/rest/private/users/details";
    private static final String SUPERADMIN_PASSWORD = "/rest/private/users/superadmin/password";
    private static final String CUSTOMERS = "/rest/private/customers";
    private static final String USERS = "/rest/private/users";
    private static final String LOGIN = "/rest/public/auth/login";
    private static final String JWT_LOGIN = "/rest/public/jwt/login";

    private static final String OLD_MD5 = "5F4DCC3B5AA765D61D8327DEB882CF99";
    private static final String NEW_MD5 = "0123456789ABCDEF0123456789ABCDEF";
    private static final String NOT_STORED = "(not valid JSON, not stored)";
    private static final String OTHER_TEXT = "other-field-text";

    /** A surrogate encoded as UTF-8, which the parser only refuses when it decodes the value. */
    private static final byte[] BAD_TEXT = {'"', 'a', (byte) 0xED, (byte) 0xA0, (byte) 0x80, 'b', '"'};
    /** A string over the parser's limit, which it only refuses when it decodes the value. */
    private static final byte[] LONG_TEXT = longText();

    @Test
    public void theRoutesHaveARule() {
        assertEquals(Optional.of(ResourceAuditInfo.UPDATE_USER_DETAILS), ResourceAuditInfo.findAuditInfo("PUT", DETAILS));
        assertEquals(Optional.of(ResourceAuditInfo.SUPERADMIN_PASSWORD),
                ResourceAuditInfo.findAuditInfo("PUT", SUPERADMIN_PASSWORD));
        assertEquals(Optional.of(ResourceAuditInfo.UPDATE_CUSTOMER), ResourceAuditInfo.findAuditInfo("PUT", CUSTOMERS));
        assertEquals(Optional.of(ResourceAuditInfo.UPDATE_CUSTOMER),
                ResourceAuditInfo.findAuditInfo("PUT", CUSTOMERS + "/"));
    }

    @Test
    public void onlyThoseRoutes() {
        assertEquals(Optional.empty(), ResourceAuditInfo.findAuditInfo("GET", DETAILS));
        assertEquals(Optional.empty(), ResourceAuditInfo.findAuditInfo("PUT", DETAILS + "/x"));
        assertEquals(Optional.empty(), ResourceAuditInfo.findAuditInfo("PUT", SUPERADMIN_PASSWORD + "/x"));
        assertEquals(Optional.empty(), ResourceAuditInfo.findAuditInfo("GET", CUSTOMERS + "/1/edit"));
        assertEquals(Optional.empty(), ResourceAuditInfo.findAuditInfo("POST", CUSTOMERS + "/search"));
    }

    @Test
    public void aDetailsChangeIsRecordedWithoutThePassword() throws IOException, ServletException {
        AuditLogRecord record = audit(DETAILS, "{\"id\":11,\"login\":\"rbk\",\"email\":\"new@example.com\","
                + "\"oldPassword\":\"" + OLD_MD5 + "\"}");

        assertEquals("plugin.audit.action.update.user.details", record.getAction());
        assertTrue(record.getPayload(), record.getPayload().contains("new@example.com"));
        assertMasked(record);
    }

    @Test
    public void aSuperAdminPasswordSetIsRecordedWithoutThePasswords() throws IOException, ServletException {
        AuditLogRecord record = audit(SUPERADMIN_PASSWORD, "{\"id\":11,\"newPassword\":\"" + NEW_MD5
                + "\",\"oldPassword\":\"" + OLD_MD5 + "\"}");

        assertEquals("plugin.audit.action.password.superadmin", record.getAction());
        assertTrue(record.getPayload(), record.getPayload().contains("\"id\":11"));
        assertMasked(record);
    }

    @Test
    public void aCustomerChangeIsRecordedWithoutThePassword() throws IOException, ServletException {
        AuditLogRecord record = audit(CUSTOMERS, "{\"id\":2,\"name\":\"acme\",\"email\":\"new@example.com\","
                + "\"oldPassword\":\"" + OLD_MD5 + "\"}");

        assertEquals("plugin.audit.action.update.customer", record.getAction());
        assertTrue(record.getPayload(), record.getPayload().contains("acme"));
        assertMasked(record);
    }

    @Test
    public void aUserDeleteIsRecordedWithoutThePassword() throws IOException, ServletException {
        AuditLogRecord record = DeviceCommandAuditTest.audit("DELETE", USERS + "/other/11",
                "{\"oldPassword\":\"" + OLD_MD5 + "\"}", null);

        assertEquals("plugin.audit.action.remove.user", record.getAction());
        assertTrue(record.getPayload(), record.getPayload().contains(USERS + "/other/11"));
        assertTrue(record.getPayload(), record.getPayload().contains("\"oldPassword\":\"******\""));
        assertMasked(record);
    }

    @Test
    public void secretsAreMaskedAtAnyDepth() throws IOException, ServletException {
        String[] secrets = {"wifi-secret-1", "kiosk-pin-2", "nested-pw-3", "token-4", "totp-5", "reset-6"};
        String body = "{\"id\":1,\"name\":\"Kiosk\",\"wifiPassword\":\"" + secrets[0] + "\",\"password\":\""
                + secrets[1] + "\",\"applications\":[{\"id\":7,\"password\":\"" + secrets[2] + "\"}],"
                + "\"owner\":{\"authToken\":\"" + secrets[3] + "\",\"twoFactorSecret\":\"" + secrets[4] + "\","
                + "\"passwordResetToken\":\"" + secrets[5] + "\"}}";
        for (String uri : new String[]{"/rest/private/configurations", "/rest/private/configurations/copy", USERS}) {
            AuditLogRecord record = audit(uri, body);
            for (String secret : secrets) {
                assertFalse(record.getPayload(), record.getPayload().contains(secret));
                assertFalse(record.toLogString(), record.toLogString().contains(secret));
            }
            assertTrue(record.getPayload(), record.getPayload().contains("\"name\":\"Kiosk\""));
        }
        // A configuration keeps only its fields with no secret (AuditBodyMaskingTest); other bodies are masked by key
        AuditLogRecord record = audit(USERS, body);
        assertTrue(record.getPayload(), record.getPayload().contains("\"id\":7"));
        assertTrue(record.getPayload(), record.getPayload().contains("\"wifiPassword\":\"******\""));
    }

    @Test
    public void aBodyThatIsNotAJsonObjectOrArrayIsNotStored() throws IOException, ServletException {
        String[] bodies = {
                "{\"oldPassword\":\"" + OLD_MD5 + "\",",
                "\"" + OLD_MD5 + "\"",
                "oldPassword=" + OLD_MD5};
        for (String body : bodies) {
            for (String uri : new String[]{DETAILS, SUPERADMIN_PASSWORD, CUSTOMERS, USERS,
                    "/rest/private/users/current"}) {
                AuditLogRecord record = audit(uri, body);
                assertMasked(record);
                assertTrue(record.getPayload(), record.getPayload().contains("(not valid JSON, not stored)"));
            }
        }
        // An array is stored, masked like an object
        AuditLogRecord record = audit(USERS, "[{\"id\":11,\"oldPassword\":\"" + OLD_MD5 + "\"}]");
        assertTrue(record.getPayload(), record.getPayload().contains("\"oldPassword\":\"******\""));
        assertMasked(record);
    }

    @Test
    public void aSignInWithARepeatedKeyNamesTheAccount() throws IOException, ServletException {
        for (String uri : new String[]{LOGIN, JWT_LOGIN}) {
            AuditLogRecord record = DeviceCommandAuditTest.audit("POST", uri,
                    "{\"login\":\"other\",\"login\":\"admin\","
                    + "\"password\":\"" + OLD_MD5 + "\",\"password\":\"" + NEW_MD5 + "\"}", null);

            assertEquals("admin", record.getLogin());
            assertTrue(record.getPayload(), record.getPayload().contains("\"login\":\"admin\""));
            assertMasked(record);
        }
    }

    @Test
    public void aUserChangeWithARepeatedKeyKeepsItsFields() throws IOException, ServletException {
        AuditLogRecord record = audit(USERS, "{\"id\":11,\"id\":12,\"login\":\"rbk\",\"email\":\"new@example.com\","
                + "\"newPassword\":\"" + NEW_MD5 + "\",\"newPassword\":\"" + NEW_MD5 + "\","
                + "\"oldPassword\":\"" + OLD_MD5 + "\",\"oldPassword\":\"" + OLD_MD5 + "\","
                + "\"password\":\"" + OLD_MD5 + "\",\"password\":\"" + OLD_MD5 + "\","
                + "\"confirm\":\"" + NEW_MD5 + "\",\"confirm\":\"" + NEW_MD5 + "\"}");

        assertTrue(record.getPayload(), record.getPayload().contains("\"id\":12"));
        assertTrue(record.getPayload(), record.getPayload().contains("\"login\":\"rbk\""));
        assertTrue(record.getPayload(), record.getPayload().contains("new@example.com"));
        for (String key : new String[]{"newPassword", "oldPassword", "password", "confirm"}) {
            assertTrue(record.getPayload(), record.getPayload().contains("\"" + key + "\":\"******\""));
        }
        assertMasked(record);
    }

    @Test
    public void aPasswordIsMaskedHoweverItIsWritten() throws IOException, ServletException {
        String[] bodies = {
                "{\"id\":11,\"oldPassw\\u006frd\":\"" + OLD_MD5 + "\"}",   // an escaped key
                "{\"id\":11,\"oldPassword\":[\"" + OLD_MD5 + "\"]}",        // not a string
                "{\"id\":11,\"oldPassword\":{\"value\":\"" + OLD_MD5 + "\"}}",
                "{\"id\":11}{\"oldPassword\":\"" + OLD_MD5 + "\"}",         // text after the object
                "{\"id\":11} oldPassword=" + OLD_MD5};
        for (String body : bodies) {
            AuditLogRecord record = audit(USERS, body);
            assertTrue(record.getPayload(), record.getPayload().contains("\"id\":11"));
            assertMasked(record);
        }
    }

    @Test
    public void aLoginIsTakenOnlyFromAValue() throws IOException, ServletException {
        assertEquals("123", signIn("123").getLogin());       // read as text by the resource too
        assertNull(signIn("null").getLogin());
        assertNull(signIn("{\"a\":1}").getLogin());
        assertNull(signIn("[\"admin\"]").getLogin());
    }

    @Test
    public void aSignInWithAFieldThatCannotBeDecodedNamesNoAccountAndIsKept() throws IOException, ServletException {
        // Jackson (2.23 on) refuses invalid UTF-8 anywhere in a body, a skipped value too: the resource's binding refuses
        // it (a 400), so nobody signed in and there is no login to name. The record is kept, with the note.
        // A body over the audit's 1 MB cap (LONG_TEXT) is neither stored nor read for a login: AuditBodyMaskingTest
        for (byte[] value : new byte[][]{BAD_TEXT}) {
            byte[][] bodies = {
                    body("{\"login\":\"admin\",\"password\":\"" + OLD_MD5 + "\",\"note\":\"" + OTHER_TEXT
                            + "\",\"z\":", value, "}"),
                    body("{\"z\":", value, ",\"note\":\"" + OTHER_TEXT + "\",\"password\":\"" + OLD_MD5
                            + "\",\"login\":\"admin\"}"),
                    body("{\"z\":{\"y\":[", value, "]},\"note\":\"" + OTHER_TEXT + "\",\"login\":\"admin\","
                            + "\"password\":\"" + OLD_MD5 + "\"}")};
            for (byte[] body : bodies) {
                for (String uri : new String[]{LOGIN, JWT_LOGIN}) {
                    AuditLogRecord record = DeviceCommandAuditTest.audit("POST", uri, body, null);

                    assertNull(record.getLogin());
                    assertTrue(record.getPayload(), record.getPayload().startsWith("Method: POST\nURI: " + uri));
                    assertNotStored(record);
                }
            }
        }
    }

    @Test
    public void aMalformedBodyNamesNoAccount() throws IOException, ServletException {
        String signIn = "\"login\":\"admin\",\"password\":\"" + OLD_MD5 + "\"";
        byte[][] bodies = {
                utf8("{\"z\":tru," + signIn + "}"),                  // before the login
                utf8("{\"z\":1 2," + signIn + "}"),
                utf8("{\"z\":{\"y\":]," + signIn + "}"),
                utf8("{\"z\" " + signIn + "}"),
                utf8("{" + signIn + ",\"z\":tru}"),                  // after the login
                utf8("{" + signIn + ",}"),
                utf8("{" + signIn + " \"z\":1}"),
                utf8("{" + signIn + ",\"z\":{\"y\":1}"),
                utf8("{" + signIn),                                  // cut short
                utf8("{" + signIn + ","),
                utf8("{" + signIn.substring(0, signIn.length() - 1)),
                utf8("{\"login\":"),
                utf8("{\"login\":\"admin"),
                utf8("[{" + signIn + "},"),                          // not an object
                utf8("login=admin&password=" + OLD_MD5),
                body("{\"login\":", BAD_TEXT, ",\"password\":\"" + OLD_MD5 + "\"}"),    // the login itself
                body("{\"login\":", BAD_TEXT, ",\"login\":\"admin\"}"),
                body("{\"login\":", LONG_TEXT, ",\"password\":\"" + OLD_MD5 + "\"}"),
                new byte[]{'{', (byte) 0xFF}};
        for (byte[] body : bodies) {
            AuditLogRecord record = DeviceCommandAuditTest.audit("POST", LOGIN, body, null);

            assertNull(new String(Arrays.copyOf(body, Math.min(body.length, 80)), StandardCharsets.UTF_8),
                    record.getLogin());
            assertNotStored(record);
        }
    }

    @Test
    public void aPasswordThatCannotBeDecodedStaysOutOfTheRecord() throws IOException, ServletException {
        AuditLogRecord record = DeviceCommandAuditTest.audit("POST", LOGIN,
                body("{\"login\":\"admin\",\"note\":\"" + OTHER_TEXT + "\",\"password\":", BAD_TEXT, "}"), null);

        assertNull("refused by the binding: nobody signed in", record.getLogin());
        assertNotStored(record);
    }

    @Test
    public void theLoginNamedIsTheOneTheBindingReads() throws IOException, ServletException {
        // The last login counts, as for the resource; an object or array as a login fails the binding: none
        assertEquals("admin", bodySignIn("\"login\":\"other\",\"login\":\"admin\"").getLogin());
        assertEquals("admin", bodySignIn("\"login\":null,\"login\":\"admin\"").getLogin());
        assertNull(bodySignIn("\"login\":{\"a\":1},\"login\":\"admin\"").getLogin());
        assertNull(bodySignIn("\"login\":\"admin\",\"login\":null").getLogin());
        assertNull(bodySignIn("\"login\":\"admin\",\"login\":{\"a\":1}").getLogin());
        assertNull(bodySignIn("\"login\":\"admin\",\"login\":[\"a\"]").getLogin());
    }

    @Test
    public void aLoginIsTakenOnlyFromATopLevelValue() throws IOException, ServletException {
        assertEquals("123", bodySignIn("\"login\":123").getLogin());
        assertEquals("true", bodySignIn("\"login\":true").getLogin());
        assertEquals("admin", bodySignIn("\"l\\u006fgin\":\"admin\"").getLogin());
        assertNull(bodySignIn("\"login\":null").getLogin());
        assertNull(bodySignIn("\"login\":{\"login\":\"admin\"}").getLogin());
        assertNull(bodySignIn("\"login\":[\"admin\"]").getLogin());
        assertNull(bodySignIn("\"y\":{\"login\":\"admin\"}").getLogin());
        assertNull(bodySignIn("\"y\":[{\"login\":\"admin\"}]").getLogin());
        assertNull(bodySignIn("\"LOGIN\":\"admin\"").getLogin());
    }

    @Test
    public void aChangeWithAFieldThatCannotBeDecodedStoresNoBody() throws IOException, ServletException {
        for (byte[] value : new byte[][]{BAD_TEXT, LONG_TEXT}) {
            byte[] body = body("{\"id\":11,\"email\":\"" + OTHER_TEXT + "\",\"newPassword\":\"" + NEW_MD5
                    + "\",\"oldPassword\":\"" + OLD_MD5 + "\",\"z\":", value, "}");
            for (String uri : new String[]{DETAILS, SUPERADMIN_PASSWORD, CUSTOMERS, USERS,
                    "/rest/private/users/current"}) {
                assertNotStored(DeviceCommandAuditTest.audit("PUT", uri, body, null));
            }
        }
    }

    @Test
    public void aChangeWithAFieldThatCannotBeDecodedNamesTheSignedInUser() throws IOException, ServletException {
        User user = new User();
        user.setId(5);
        user.setLogin("alice");
        user.setCustomerId(3);
        SecurityContext.init(user);
        try {
            AuditLogRecord record = DeviceCommandAuditTest.audit("PUT", USERS, body("{\"login\":\"rbk\","
                    + "\"newPassword\":\"" + NEW_MD5 + "\",\"z\":", BAD_TEXT, "}"), null);

            assertEquals("alice", record.getLogin());
            assertNotStored(record);
        } finally {
            SecurityContext.release();
        }
    }

    private static AuditLogRecord bodySignIn(String fields) throws IOException, ServletException {
        return DeviceCommandAuditTest.audit("POST", LOGIN, utf8("{" + fields + ",\"password\":\"" + OLD_MD5 + "\"}"),
                null);
    }

    private static byte[] body(String before, byte[] value, String after) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, utf8(before));
        write(out, value);
        write(out, utf8(after));
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, byte[] bytes) {
        out.write(bytes, 0, bytes.length);
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] longText() {
        byte[] text = new byte[StreamReadConstraints.DEFAULT_MAX_STRING_LEN + 3];
        Arrays.fill(text, (byte) 'x');
        text[0] = '"';
        text[text.length - 1] = '"';
        return text;
    }

    private static AuditLogRecord signIn(String loginJson) throws IOException, ServletException {
        return DeviceCommandAuditTest.audit("POST", LOGIN,
                "{\"login\":" + loginJson + ",\"password\":\"" + OLD_MD5 + "\"}", null);
    }

    private static AuditLogRecord audit(String uri, String body) throws IOException, ServletException {
        return DeviceCommandAuditTest.audit("PUT", uri, body, null);
    }

    private static void assertNotStored(AuditLogRecord record) {
        assertTrue(record.getPayload(), record.getPayload().contains("\nBody: " + NOT_STORED + "\n")
                || record.getPayload().contains("\nBody: (over 1 MB, not stored)\n"));
        assertFalse(record.getPayload(), record.getPayload().contains(OTHER_TEXT));
        assertMasked(record);
    }

    private static void assertMasked(AuditLogRecord record) {
        assertFalse(record.getPayload(), record.getPayload().contains(OLD_MD5));
        assertFalse(record.getPayload(), record.getPayload().contains(NEW_MD5));
        assertFalse(record.toLogString(), record.toLogString().contains(OLD_MD5));
        assertFalse(record.toLogString(), record.toLogString().contains(NEW_MD5));
    }
}
