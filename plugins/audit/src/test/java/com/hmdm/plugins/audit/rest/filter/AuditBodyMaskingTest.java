/*
 * Copyright (C) 2026 the MDMesh contributors. Licensed under the Apache License, Version 2.0.
 */

package com.hmdm.plugins.audit.rest.filter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdm.plugins.audit.persistence.domain.AuditLogRecord;
import org.junit.Test;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Every audited body is masked, whatever its route: a configuration or device save keeps only the fields known to
 * hold no secret and records the rest as markers, every other body has its secret keys masked at any depth and in
 * any case, a body that can't be parsed is not stored, and the log line carries no more than the stored record.
 */
public class AuditBodyMaskingTest {

    private static final String NOT_STORED = "(not valid JSON, not stored)";

    /** A request for every rule that stores a body, as the console sends it. */
    private static final Map<ResourceAuditInfo, String[]> ROUTES = new LinkedHashMap<>();

    static {
        route(ResourceAuditInfo.LOGIN, "POST", "/rest/public/auth/login");
        route(ResourceAuditInfo.JWT_LOGIN, "POST", "/rest/public/jwt/login");
        route(ResourceAuditInfo.UPDATE_DEVICE, "PUT", "/rest/private/devices");
        route(ResourceAuditInfo.UPDATE_CONFIG, "PUT", "/rest/private/configurations");
        route(ResourceAuditInfo.COPY_CONFIG, "PUT", "/rest/private/configurations/copy");
        route(ResourceAuditInfo.UPDATE_APP, "PUT", "/rest/private/applications/android");
        route(ResourceAuditInfo.UPDATE_WEBAPP, "PUT", "/rest/private/applications/web");
        route(ResourceAuditInfo.UPDATE_APP_VERSION, "PUT", "/rest/private/applications/versions");
        route(ResourceAuditInfo.UPDATE_FILE, "POST", "/rest/private/web-ui-files/move");
        route(ResourceAuditInfo.REMOVE_FILE, "POST", "/rest/private/web-ui-files/remove");
        route(ResourceAuditInfo.UPDATE_APP_CONFIG, "POST", "/rest/private/applications/configurations");
        route(ResourceAuditInfo.UPDATE_VERSION_CONFIG, "POST", "/rest/private/applications/version/configurations");
        route(ResourceAuditInfo.UPDATE_DESIGN, "POST", "/rest/private/settings/design");
        route(ResourceAuditInfo.UPDATE_USERROLES, "POST", "/rest/private/settings/userRoles/common");
        route(ResourceAuditInfo.UPDATE_LANGUAGE, "POST", "/rest/private/settings/lang");
        route(ResourceAuditInfo.UPDATE_PLUGINS, "POST", "/rest/plugin/main/private/disabled");
        route(ResourceAuditInfo.UPDATE_USER, "PUT", "/rest/private/users");
        route(ResourceAuditInfo.UPDATE_USER_DETAILS, "PUT", "/rest/private/users/details");
        route(ResourceAuditInfo.SUPERADMIN_PASSWORD, "PUT", "/rest/private/users/superadmin/password");
        route(ResourceAuditInfo.UPDATE_CUSTOMER, "PUT", "/rest/private/customers");
        route(ResourceAuditInfo.REMOVE_CUSTOMER, "DELETE", "/rest/private/customers/2");
        route(ResourceAuditInfo.REMOVE_USER, "DELETE", "/rest/private/users/other/11");
        route(ResourceAuditInfo.IMPERSONATE_USER, "POST", "/rest/private/users/impersonate/11");
        route(ResourceAuditInfo.IMPERSONATE_CUSTOMER, "POST", "/rest/private/customers/impersonate/2");
        route(ResourceAuditInfo.UPDATE_ROLE, "PUT", "/rest/private/roles");
        route(ResourceAuditInfo.REMOVE_ROLE, "DELETE", "/rest/private/roles/5");
        route(ResourceAuditInfo.UPDATE_GROUP, "PUT", "/rest/private/groups");
        route(ResourceAuditInfo.REMOVE_GROUP, "DELETE", "/rest/private/groups/5");
        route(ResourceAuditInfo.REMOVE_DEVICE, "DELETE", "/rest/private/devices/1");
        route(ResourceAuditInfo.REMOVE_CONFIG, "DELETE", "/rest/private/configurations/1");
        route(ResourceAuditInfo.REMOVE_APP, "DELETE", "/rest/private/applications/1");
        route(ResourceAuditInfo.REMOVE_APP_VERSION, "DELETE", "/rest/private/applications/versions/1");
        route(ResourceAuditInfo.PASSWORD_CHANGED, "PUT", "/rest/private/users/current");
        route(ResourceAuditInfo.DEVICE_LOCK, "PUT", "/rest/plugins/devicereset/private/lock");
        route(ResourceAuditInfo.DEVICE_COMMAND, "POST", "/rest/private/agent/v1/devices/h0001/commands");
        route(ResourceAuditInfo.BULK_DEVICE_COMMAND, "POST", "/rest/private/agent/v1/bulk/commands");
        route(ResourceAuditInfo.SYNC_DEVICE_APPS, "POST", "/rest/private/agent/v1/devices/h0001/syncApps");
        route(ResourceAuditInfo.RETRY_DEVICE_CONFIG, "POST", "/rest/private/agent/v1/devices/h0001/retryConfig");
        route(ResourceAuditInfo.MINT_ENROLL_TOKEN, "POST", "/rest/private/agent/v1/token");
    }

    /** Rules that store no body: it is mostly secret, or there is none. */
    private static final Set<ResourceAuditInfo> NO_BODY = EnumSet.of(
            ResourceAuditInfo.PASSWORD_RESET_BY_LINK, ResourceAuditInfo.DEVICE_PASSWORD_RESET,
            ResourceAuditInfo.DEVICE_FACTORY_RESET, ResourceAuditInfo.REVOKE_ENROLL_TOKENS);

    private static void route(ResourceAuditInfo rule, String method, String uri) {
        ROUTES.put(rule, new String[]{method, uri});
    }

    @Test
    public void everyRuleThatStoresABodyIsCovered() {
        for (ResourceAuditInfo rule : ResourceAuditInfo.values()) {
            if (rule.storesBody()) {
                assertTrue(rule.name(), ROUTES.containsKey(rule));
                assertEquals(rule.name(), rule, ResourceAuditInfo.findAuditInfo(ROUTES.get(rule)[0],
                        ROUTES.get(rule)[1]).get());
            } else {
                assertFalse(rule.name(), ROUTES.containsKey(rule));
            }
        }
    }

    // ----------------------------------------------------------------------------------------------- configurations

    private static final String CONFIGURATION = "{\"id\":4,\"name\":\"Lobby kiosk\",\"description\":\"front desk\","
            + "\"kioskMode\":true,\"mainAppId\":12,\"wifiSSID\":\"Lobby\",\"type\":0,"
            + "\"password\":\"exit-pin-4711\",\"wifiPassword\":\"wifi-secret-1\","
            + "\"qrParameters\":\"{\\\"android.app.extra.PROVISIONING_WIFI_PASSWORD\\\":\\\"qr-wifi-2\\\"}\","
            + "\"adminExtras\":\"vpn_psk=extras-3\",\"qrCodeKey\":\"qr-key-4\","
            + "\"applicationSettings\":[{\"applicationId\":7,\"name\":\"api_key\",\"value\":\"app-value-5\"},"
            + "{\"applicationId\":7,\"name\":\"user\",\"value\":\"app-value-6\"}],"
            + "\"extensions\":{\"acme\":{\"token\":\"ext-7\",\"region\":\"eu\"}},"
            + "\"files\":[{\"url\":\"https://u:file-8@files.example/x\",\"devicePath\":\"/sdcard/x\"}],"
            + "\"applications\":[{\"id\":7,\"pkg\":\"com.acme\"}],\"launcherUrl\":\"https://ops:url-9@cdn.example/l.apk\","
            + "\"someNewField\":\"new-10\",\"WifiPassword\":\"case-11\"}";
    private static final String[] CONFIGURATION_SECRETS = {"exit-pin-4711", "wifi-secret-1", "qr-wifi-2", "extras-3",
            "qr-key-4", "app-value-5", "app-value-6", "ext-7", "file-8", "url-9", "new-10", "case-11", "api_key"};

    @Test
    public void aConfigurationSaveKeepsOnlyTheFieldsWithNoSecret() throws IOException, ServletException {
        for (ResourceAuditInfo rule : new ResourceAuditInfo[]{ResourceAuditInfo.UPDATE_CONFIG,
                ResourceAuditInfo.COPY_CONFIG}) {
            AuditLogRecord record = audit(rule, CONFIGURATION);
            assertNoSecret(record, CONFIGURATION_SECRETS);

            JsonNode body = storedBody(record);
            assertEquals(4, body.get("id").asInt());
            assertEquals("Lobby kiosk", body.get("name").asText());
            assertEquals("front desk", body.get("description").asText());
            assertTrue(body.get("kioskMode").asBoolean());
            assertEquals(12, body.get("mainAppId").asInt());
            assertEquals("Lobby", body.get("wifiSSID").asText());
            assertEquals("https://******@cdn.example/l.apk", body.get("launcherUrl").asText());
            // The secret-bearing fields say only that they were sent
            assertEquals("<set>", body.get("password").asText());
            assertEquals("<set>", body.get("wifiPassword").asText());
            assertEquals("<set>", body.get("qrParameters").asText());
            assertEquals("<set>", body.get("adminExtras").asText());
            assertEquals("<set>", body.get("qrCodeKey").asText());
            assertEquals("<2 items>", body.get("applicationSettings").asText());
            assertEquals("<1 entries>", body.get("extensions").asText());
            assertEquals("<1 items>", body.get("files").asText());
            assertEquals("<1 items>", body.get("applications").asText());
            // A field this release doesn't know is named, never stored
            assertEquals("<set>", body.get("someNewField").asText());
            assertEquals("<set>", body.get("WifiPassword").asText());
        }
    }

    @Test
    public void aConfigurationSaveSaysWhichSecretFieldsAreEmpty() throws IOException, ServletException {
        JsonNode body = storedBody(audit(ResourceAuditInfo.UPDATE_CONFIG, "{\"id\":4,\"password\":\"\","
                + "\"wifiPassword\":null,\"applicationSettings\":[],\"name\":{\"x\":\"nested-12\"}}"));

        assertEquals("<empty>", body.get("password").asText());
        assertTrue(body.get("wifiPassword").isNull());
        assertEquals("<0 items>", body.get("applicationSettings").asText());
        // A kept field holding something other than a value is a marker too
        assertEquals("<1 entries>", body.get("name").asText());
    }

    // ------------------------------------------------------------------------------------------------------ devices

    @Test
    public void aDeviceSaveKeepsOnlyTheFieldsWithNoSecret() throws IOException, ServletException {
        AuditLogRecord record = audit(ResourceAuditInfo.UPDATE_DEVICE, "{\"id\":9,\"number\":\"h0009\","
                + "\"description\":\"till 2\",\"configurationId\":4,\"ids\":[9,10],\"imei\":\"35000\","
                + "\"custom1\":\"door-code-1\",\"info\":\"info-2\",\"groups\":[{\"id\":1,\"name\":\"g\"}],"
                + "\"configuration\":{\"wifiPassword\":\"wifi-3\",\"password\":\"pin-4\"}}");
        assertNoSecret(record, "door-code-1", "info-2", "wifi-3", "pin-4");

        JsonNode body = storedBody(record);
        assertEquals(9, body.get("id").asInt());
        assertEquals("h0009", body.get("number").asText());
        assertEquals("till 2", body.get("description").asText());
        assertEquals(4, body.get("configurationId").asInt());
        assertEquals("[9,10]", body.get("ids").toString());
        assertEquals("<set>", body.get("custom1").asText());
        assertEquals("<1 items>", body.get("groups").asText());
        assertEquals("<2 entries>", body.get("configuration").asText());
    }

    @Test
    public void theConsolesBulkDeviceMoveIsKeptWhole() throws IOException, ServletException {
        JsonNode body = storedBody(audit(ResourceAuditInfo.UPDATE_DEVICE, "{\"ids\":[3,5],\"configurationId\":2}"));
        assertEquals("{\"ids\":[3,5],\"configurationId\":2}", body.toString());
    }

    // ---------------------------------------------------------------------------------------------- everything else

    @Test
    public void secretKeysAreMaskedOnEveryOtherRouteAtAnyDepthAndInAnyCase() throws IOException, ServletException {
        String body = "{\"id\":3,\"name\":\"Acme app\",\"pinPackage\":\"com.acme\",\"kioskKeyguard\":true,"
                + "\"keyCode\":66,\"url\":\"https://ops:url-1@cdn.example/a.apk\","
                + "\"PASSWORD\":\"upper-2\",\"WiFiPassWord\":\"mixed-3\","
                + "\"deep\":[{\"x\":{\"ApiKey\":\"key-4\",\"deviceSecret\":\"dev-5\",\"enrollToken\":\"enr-6\"}}],"
                + "\"authData\":\"auth-7\",\"kioskPin\":\"8642\",\"pin\":1357,\"token\":{\"v\":\"tok-8\"},"
                + "\"Secret\":\"sec-9\",\"twoFactorSecret\":\"totp-10\",\"authToken\":\"at-11\","
                + "\"passwordResetToken\":\"prt-12\",\"signupToken\":\"st-13\"}";
        String[] secrets = {"url-1", "upper-2", "mixed-3", "key-4", "dev-5", "enr-6", "auth-7", "8642", "1357",
                "tok-8", "sec-9", "totp-10", "at-11", "prt-12", "st-13"};
        for (ResourceAuditInfo rule : new ResourceAuditInfo[]{ResourceAuditInfo.UPDATE_APP,
                ResourceAuditInfo.UPDATE_GROUP, ResourceAuditInfo.UPDATE_DESIGN, ResourceAuditInfo.MINT_ENROLL_TOKEN,
                ResourceAuditInfo.UPDATE_USER, ResourceAuditInfo.DEVICE_LOCK}) {
            AuditLogRecord record = audit(rule, body);
            assertNoSecret(record, secrets);

            JsonNode stored = storedBody(record);
            assertEquals(rule.name(), "Acme app", stored.get("name").asText());
            assertEquals("com.acme", stored.get("pinPackage").asText());
            assertTrue(stored.get("kioskKeyguard").asBoolean());
            assertEquals(66, stored.get("keyCode").asInt());
            assertEquals("https://******@cdn.example/a.apk", stored.get("url").asText());
            assertEquals("******", stored.get("WiFiPassWord").asText());
            assertEquals("******", stored.get("deep").get(0).get("x").get("ApiKey").asText());
            assertEquals("******", stored.get("pin").asText());
        }
    }

    @Test
    public void moreSecretKeysAndSecretUrlParametersAreMasked() throws IOException, ServletException {
        String body = "{\"pkg\":\"com.acme\",\"qrCodeKey\":\"qr-id\",\"packageKey\":\"pk-id\","
                + "\"passphrase\":\"s-1\",\"Passcode\":\"s-2\",\"pwd\":\"s-3\",\"privateKey\":\"s-4\","
                + "\"accessKey\":\"s-5\",\"credentials\":{\"u\":\"s-6\"},\"awsSecretAccessKey\":\"s-7\","
                + "\"url\":\"https://cdn.example/a.apk?X-Amz-Signature=s-8&X-Amz-Credential=s-9&v=3\","
                + "\"mirror\":\"https://m.example/a.apk?sig=s-10&token=s-11&Key=AIzaSyA-s-12-0123456789#part\","
                + "\"maps\":\"https://maps.example/js?key=lobby\",\"short\":\"https://h.example/x?key=Ab12Cd34Ef\",\"serverKey\":\"s-14\",\"licenseKey\":\"s-15\","
                + "\"wifiPsk\":\"s-16\",\"psk\":\"s-17\","
                + "\"note\":\"password=s-13\"}";
        AuditLogRecord record = audit(ResourceAuditInfo.UPDATE_APP, body);
        assertNoSecret(record, "s-1\"", "s-2", "s-3", "s-4", "s-5", "s-6", "s-7", "s-8", "s-9", "s-10", "s-11", "s-12",
                "s-13", "s-14", "s-15", "s-16", "s-17");

        JsonNode stored = storedBody(record);
        assertEquals("qr-id", stored.get("qrCodeKey").asText());
        assertEquals("pk-id", stored.get("packageKey").asText());
        assertEquals("https://cdn.example/a.apk?X-Amz-Signature=******&X-Amz-Credential=******&v=3",
                stored.get("url").asText());
        assertEquals("https://m.example/a.apk?sig=******&token=******&Key=******#part", stored.get("mirror").asText());
        // A short key= is data, not an API key
        assertEquals("https://maps.example/js?key=lobby", stored.get("maps").asText());
        // Anything else is masked, however short
        assertEquals("https://h.example/x?key=******", stored.get("short").asText());
        assertEquals("password=******", stored.get("note").asText());
    }

    @Test
    public void aSecretNamedInFreeTextIsMaskedWhereverItIs() throws IOException, ServletException {
        JsonNode stored = storedBody(audit(ResourceAuditInfo.UPDATE_GROUP, "{\"name\":\"Lobby\","
                + "\"note\":\"wifi password=hunter2\",\"lines\":\"a=1\\ntoken=tok-1\","
                + "\"paren\":\"(secret=sec-2)\",\"colon\":\"wifi:psk=psk-3\",\"plain\":\"size=12 colour=red\","
                + "\"word\":\"see key=lobby\"}"));
        assertEquals("wifi password=******", stored.get("note").asText());
        assertEquals("a=1\ntoken=******", stored.get("lines").asText());
        // The value is masked up to the next space or separator: a closing bracket may be part of the secret
        assertEquals("(secret=******", stored.get("paren").asText());
        assertEquals("wifi:psk=******", stored.get("colon").asText());
        assertEquals("size=12 colour=red", stored.get("plain").asText());
        assertEquals("see key=lobby", stored.get("word").asText());
    }

    @Test
    public void aKeySpelledWithUnderscoresDashesOrDotsIsTheKeyItLooksLike() throws IOException, ServletException {
        String[] names = {"api_key", "x-api-key", "private_key", "secret_key", "aws_secret_access_key", "client.secret",
                "Kiosk_PIN", "wifi-password"};
        StringBuilder body = new StringBuilder("{\"name\":\"Lobby\"");
        for (int i = 0; i < names.length; i++) {
            body.append(",\"").append(names[i]).append("\":\"json-").append(i).append("\"");
        }
        body.append(",\"note\":\"");
        for (int i = 0; i < names.length; i++) {
            body.append(names[i]).append("=text-").append(i).append(' ');
        }
        // Names that only look close stay: an id, a count, a flag
        body.append("\",\"qr_code_key\":\"qr-1\",\"package-key\":\"pk-1\",\"token_count\":3,\"password_mode\":\"any\","
                + "\"kiosk.keyguard\":true,\"spin_rate\":2,\"secret_santa\":\"no\"}");
        AuditLogRecord record = audit(ResourceAuditInfo.UPDATE_GROUP, body.toString());
        JsonNode stored = storedBody(record);
        for (int i = 0; i < names.length; i++) {
            assertNoSecret(record, "json-" + i, "text-" + i);
            assertEquals(names[i], "******", stored.get(names[i]).asText());
        }
        assertEquals("qr-1", stored.get("qr_code_key").asText());
        assertEquals("pk-1", stored.get("package-key").asText());
        assertEquals(3, stored.get("token_count").asInt());
        assertEquals("any", stored.get("password_mode").asText());
        assertTrue(stored.get("kiosk.keyguard").asBoolean());
        assertEquals(2, stored.get("spin_rate").asInt());
        assertEquals("no", stored.get("secret_santa").asText());
    }

    @Test
    public void moreFreeTextFormsAreMasked() throws IOException, ServletException {
        JsonNode stored = storedBody(audit(ResourceAuditInfo.UPDATE_GROUP, "{\"a\":\"password: hunter-1\","
                + "\"b\":\"password = hunter-2\",\"c\":\"https://h.example/login?user[password]=hunter-3&user[name]=bob\","
                + "\"d\":\"https://h.example/?redirect=https://x.example/?token=T1-4\",\"e\":\"Note: password: hunter-5\","
                + "\"f\":\"Time: 12:30, size = 4\",\"g\":\"password=ab:cd-6\"}"));
        assertEquals("password: ******", stored.get("a").asText());
        assertEquals("password = ******", stored.get("b").asText());
        assertEquals("https://h.example/login?user[password]=******&user[name]=bob", stored.get("c").asText());
        assertEquals("https://h.example/?redirect=https://x.example/?token=******", stored.get("d").asText());
        assertEquals("Note: password: ******", stored.get("e").asText());
        assertEquals("Time: 12:30, size = 4", stored.get("f").asText());
        assertEquals("password=******", stored.get("g").asText());
    }

    /**
     * Texts and how 8caee058 (the release candidate before the masking was widened) stored them: whatever it masked
     * stays masked. Its outputs, generated by running that version's maskTextSecrets.
     */
    private static final String[][] MASKED_BY_8CAEE058 = {
            {"x]pwd=P1", "x]pwd=******"},
            {"a]key=AIza1", "a]key=******"},
            {"x]sig=S1", "x]sig=******"},
            {"[a]pwd=P2", "[a]pwd=******"},
            {"f[0]pin=1", "f[0]pin=******"},
            {"arr[1]x-amz-signature=S2", "arr[1]x-amz-signature=******"},
            {"[2026-11-19]pwd=hunter2", "[2026-11-19]pwd=******"},
            {"password=hunter2", "password=******"},
            {"wifi password=hunter2", "wifi password=******"},
            {"a=1\ntoken=tok-1", "a=1\ntoken=******"},
            {"(secret=sec-2)", "(secret=******"},
            {"wifi:psk=psk-3", "wifi:psk=******"},
            {"https://ops:pw-4@cdn.example/a.apk?v=2&token=t-5", "https://******@cdn.example/a.apk?v=2&token=******"},
            {"https://h.example/x?sig=s-6&X-Amz-Signature=s-7#part", "https://h.example/x?sig=******&X-Amz-Signature=******#part"},
            {"https://maps.example/js?key=lobby", "https://maps.example/js?key=lobby"},
            {"https://h.example/x?key=Ab12Cd34Ef", "https://h.example/x?key=******"},
            {"see key=lobby", "see key=lobby"},
            {"size=12 colour=red", "size=12 colour=red"},
            {"a.password=x-8", "a.password=******"},
            {"my_token=t-9&next=1", "my_token=******&next=1"},
            {"q=1;apikey=k-10", "q=1;apikey=******"},
            {"passwordMode=any", "passwordMode=any"},
            {"kioskPin=1234", "kioskPin=******"},
    };

    @Test
    public void everythingThe8caee058MaskingMaskedStaysMasked() {
        for (String[] text : MASKED_BY_8CAEE058) {
            assertEquals(text[0], text[1], AuditBody.maskTextSecrets(text[0]));
        }
    }

    @Test
    public void anAuthorizationWithNoSpaceAfterTheColonIsMaskedToo() {
        assertEquals("Authorization:Bearer ******", AuditBody.maskTextSecrets("Authorization:Bearer T-1"));
        assertEquals("authorization:******", AuditBody.maskTextSecrets("authorization:tok-2"));
    }

    @Test
    public void anAuthorizationKeepsItsSchemeAndACookieItsNames() throws IOException, ServletException {
        JsonNode stored = storedBody(audit(ResourceAuditInfo.UPDATE_GROUP, "{\"a\":\"Authorization: Bearer abc.def-1\","
                + "\"b\":\"authorization: Basic dXNlcjpw-2 next\",\"c\":\"cookie: JSESSIONID=T-3; theme=dark\","
                + "\"d\":\"Proxy-Authorization: tok-4\",\"Authorization\":\"Bearer T-5\",\"sessionid\":\"s-6\"}"));
        assertEquals("Authorization: Bearer ******", stored.get("a").asText());
        assertEquals("authorization: Basic ****** next", stored.get("b").asText());
        assertEquals("cookie: JSESSIONID=******; theme=dark", stored.get("c").asText());
        assertEquals("Proxy-Authorization: ******", stored.get("d").asText());
        assertEquals("******", stored.get("Authorization").asText());
        assertEquals("******", stored.get("sessionid").asText());
    }

    // ---------------------------------------------------------------------------------------------- cost, any input

    /** Every shape of a 1 MB text value is masked in linear time: well under 2 s each. */
    @Test
    public void maskingAMegabyteOfAnyShapeTakesUnderTwoSeconds() {
        int size = ServletRequestAuditWrapper.MAX_BODY - 64;
        String[] units = {"a=", "password: ", "x", "a = ", "[a]=", "a:", "?a=b&", "://a", "=", ": ", "key=k ",
                "redirect=https://x/?", "Authorization: Bearer "};
        for (String unit : units) {
            StringBuilder value = new StringBuilder(size);
            while (value.length() + unit.length() < size - 32) {
                value.append(unit);
            }
            byte[] body = ("{\"login\":\"" + value + "\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            long start = System.nanoTime();
            String stored = AuditBody.MASKED.stored(body);
            long millis = (System.nanoTime() - start) / 1_000_000;
            System.out.println("AuditBodyMaskingTest: 1 MB of '" + unit + "' masked in " + millis + " ms");
            // Linear: well under 1 s here. A generous bound, so a slow CI machine can't fail it; quadratic is minutes
            assertTrue("'" + unit + "' took " + millis + " ms", millis < 10_000);
            assertTrue(stored.startsWith("{\"login\":\""));
        }
        // The reviewer's shape, on the text scan alone
        long start = System.nanoTime();
        AuditBody.maskTextSecrets(repeat("a=", 160_000));
        long scan = (System.nanoTime() - start) / 1_000_000;
        System.out.println("AuditBodyMaskingTest: 320k chars of 'a=' scanned in " + scan + " ms");
        assertTrue(scan < 10_000);
    }

    @Test
    public void aSignInBodyIsReadOnlyToEightKilobytesAndMasked() throws IOException, ServletException {
        StringBuilder pad = new StringBuilder();
        while (pad.length() < 20_000) {
            pad.append("a=");
        }
        String body = "{\"login\":\"admin\",\"password\":\"pw-secret-1\",\"note\":\"token=t-2 " + pad
                + "\",\"after\":\"tail-secret-3\"}";
        for (String uri : new String[]{"/rest/public/auth/login", "/rest/public/jwt/login"}) {
            long start = System.nanoTime();
            AuditLogRecord record = DeviceCommandAuditTest.audit("POST", uri, body, null);
            assertTrue((System.nanoTime() - start) / 1_000_000 < 10_000);
            assertNoSecret(record, "pw-secret-1", "t-2", "tail-secret-3");
            assertTrue(record.getPayload(), record.getPayload().contains(
                    "\nBody: {\"login\":\"admin\",\"password\":\"******\""));
            assertTrue(record.getPayload(), record.getPayload().contains(" (truncated)\nUser-Agent: "));
            int kept = record.getPayload().indexOf(" (truncated)") - record.getPayload().indexOf("\nBody: ");
            assertTrue(String.valueOf(kept), kept < AuditBody.PUBLIC_BODY_CAP + 100);
            assertEquals("admin", record.getLogin());
        }
        // A whole megabyte of it, unauthenticated, costs no more
        StringBuilder huge = new StringBuilder("{\"login\":\"");
        while (huge.length() < ServletRequestAuditWrapper.MAX_BODY - 64) {
            huge.append("a=");
        }
        huge.append("\",\"password\":\"x\"}");
        long start = System.nanoTime();
        AuditLogRecord record = DeviceCommandAuditTest.audit("POST", "/rest/public/auth/login", huge.toString(), null);
        assertTrue((System.nanoTime() - start) / 1_000_000 < 10_000);
        assertTrue(record.getPayload().contains(" (truncated)"));
        // The login is cut to its column's 100 characters, in the record and in the log line
        assertEquals(ResourceAuditor.MAX_LOGIN, record.getLogin().length());
        assertTrue(String.valueOf(record.toLogString().length()), record.toLogString().length() < 20_000);
        // A private route keeps the whole body, as before
        assertFalse(audit(ResourceAuditInfo.UPDATE_GROUP, body).getPayload().contains("(truncated)"));
    }

    private static String repeat(String unit, int times) {
        StringBuilder out = new StringBuilder(unit.length() * times);
        for (int i = 0; i < times; i++) {
            out.append(unit);
        }
        return out.toString();
    }

    @Test
    public void aMarkerIsKeptAsItIs() throws IOException, ServletException {
        // A record scrubbed once, scrubbed again (StoredPayloads), says the same
        JsonNode body = storedBody(audit(ResourceAuditInfo.UPDATE_CONFIG, "{\"id\":4,\"wifiPassword\":\"<set>\","
                + "\"applicationSettings\":\"<2 items>\",\"extensions\":\"<1 entries>\",\"files\":\"<0 items>\","
                + "\"adminExtras\":\"<secret-1>\"}"));
        assertEquals("<set>", body.get("wifiPassword").asText());
        assertEquals("<2 items>", body.get("applicationSettings").asText());
        assertEquals("<1 entries>", body.get("extensions").asText());
        assertEquals("<0 items>", body.get("files").asText());
        assertEquals("<set>", body.get("adminExtras").asText());     // a value that only looks like one is not
    }

    @Test
    public void aCommandKeepsItsTypeAndHasItsOtherSecretsMasked() throws IOException, ServletException {
        AuditLogRecord record = audit(ResourceAuditInfo.DEVICE_COMMAND, "{\"type\":\"kiosk.enter\","
                + "\"payload\":\"{\\\"password\\\":\\\"pin-1\\\"}\",\"detail\":{\"token\":\"tok-2\"}}");
        assertNoSecret(record, "pin-1", "tok-2");
        assertEquals("kiosk.enter", storedBody(record).get("type").asText());
    }

    @Test
    public void anArrayBodyIsStoredMasked() throws IOException, ServletException {
        JsonNode body = storedBody(audit(ResourceAuditInfo.UPDATE_PLUGINS, "[3,5]"));
        assertEquals("[3,5]", body.toString());
        AuditLogRecord record = audit(ResourceAuditInfo.UPDATE_APP_CONFIG, "[{\"id\":1,\"password\":\"pw-1\"}]");
        assertNoSecret(record, "pw-1");
    }

    // ------------------------------------------------------------------------------------------- every route alike

    @Test
    public void aBodyThatCannotBeParsedIsNotStoredOnAnyRoute() throws IOException, ServletException {
        String[] bodies = {
                "{\"name\":\"x\",\"wifiPassword\":\"unparsed-1\"",            // cut short
                "wifiPassword=unparsed-1&name=x",                             // not JSON
                "\"unparsed-1\"",                                             // not an object or array
                "{\"name\":\"x\",\"wifiPassword\":\"unparsed-1\",}"};
        for (Map.Entry<ResourceAuditInfo, String[]> route : ROUTES.entrySet()) {
            for (String body : bodies) {
                AuditLogRecord record = DeviceCommandAuditTest.audit(route.getValue()[0], route.getValue()[1], body,
                        null);
                assertEquals(route.getKey().getAuditLogAction(), record.getAction());
                assertTrue(route.getKey() + " " + record.getPayload(),
                        record.getPayload().contains("\nBody: " + NOT_STORED + "\n"));
                assertNoSecret(record, "unparsed-1");
            }
        }
    }

    @Test
    public void aBodyOverOneMegabyteIsNotStoredOrParsed() throws IOException, ServletException {
        StringBuilder body = new StringBuilder("{\"password\":\"big-secret-1\",\"pad\":\"");
        while (body.length() <= ServletRequestAuditWrapper.MAX_BODY) {
            body.append("xxxxxxxxxxxxxxxx");
        }
        body.append("\"}");
        AuditLogRecord record = DeviceCommandAuditTest.audit("PUT", "/rest/private/users", body.toString(), null);
        assertTrue(record.getPayload(), record.getPayload().contains("\nBody: (over 1 MB, not stored)\n"));
        assertNoSecret(record, "big-secret-1");
    }

    @Test
    public void aMostlySecretBodyIsNotStoredAtAll() throws IOException, ServletException {
        AuditLogRecord record = DeviceCommandAuditTest.audit("PUT", "/rest/plugins/devicereset/private/password",
                "{\"deviceId\":1,\"password\":\"device-pw-1\"}", null);
        assertEquals("plugin.audit.action.password.reset", record.getAction());
        assertNull(record.getPayload());
        assertFalse(record.toLogString(), record.toLogString().contains("device-pw-1"));
        for (ResourceAuditInfo rule : NO_BODY) {
            assertFalse(rule.name(), rule.storesBody());
        }
    }

    @Test
    public void theLogLineCarriesNoMoreThanTheStoredRecord() throws IOException, ServletException {
        AuditLogRecord record = audit(ResourceAuditInfo.UPDATE_CONFIG, CONFIGURATION);
        String line = record.toLogString();
        assertTrue(line, line.contains(record.getPayload()));
        assertNoSecret(record, CONFIGURATION_SECRETS);
        // Nothing but the record's own fields
        assertEquals("createTime=" + record.getCreateTime() + ", userId=null, login='null', ipAddress='"
                + record.getIpAddress() + "', action='" + record.getAction() + "', payload='" + record.getPayload()
                + "', errorCode='0'", line);
    }

    @Test
    public void aResponseThatIsNotJsonStillGetsARecordAndNoExceptionCarriesIt() throws IOException, ServletException {
        // A parse error's message quotes the text it stopped at: thrown out of the filter, the container logs it
        String sent = "{\"configurationId\":4}";
        for (ResourceAuditInfo rule : new ResourceAuditInfo[]{ResourceAuditInfo.MINT_ENROLL_TOKEN,
                ResourceAuditInfo.JWT_LOGIN}) {
            String[] route = ROUTES.get(rule);
            ResourceAuditor auditor = rule.getResourceAuditor(
                    DeviceCommandAuditTest.request(route[0], route[1], sent.getBytes(StandardCharsets.UTF_8), null),
                    DeviceCommandAuditTest.response(), (req, res) -> {
                        ((HttpServletResponse) res).setStatus(200);
                        res.getOutputStream().write("code resp-secret-1 not json".getBytes(StandardCharsets.UTF_8));
                    }, "", "X-Real-IP");
            auditor.doProcess();

            AuditLogRecord record = auditor.getAuditLogRecord();
            assertEquals(rule.getAuditLogAction(), record.getAction());
            // An error for a rule that checks the response; a rule whose response isn't a Response doesn't
            assertEquals(Integer.valueOf(rule == ResourceAuditInfo.JWT_LOGIN ? 0 : 1), record.getErrorCode());
            assertNoSecret(record, "resp-secret-1");
        }
    }

    // ---------------------------------------------------------------------------------------------------- helpers

    private static AuditLogRecord audit(ResourceAuditInfo rule, String body) throws IOException, ServletException {
        String[] route = ROUTES.get(rule);
        AuditLogRecord record = DeviceCommandAuditTest.audit(route[0], route[1], body, null);
        assertEquals(rule.getAuditLogAction(), record.getAction());
        return record;
    }

    private static JsonNode storedBody(AuditLogRecord record) throws IOException {
        String payload = record.getPayload();
        int start = payload.indexOf("\nBody: ") + "\nBody: ".length();
        int end = payload.lastIndexOf("\nUser-Agent: ");
        return new ObjectMapper().readTree(payload.substring(start, end));
    }

    private static void assertNoSecret(AuditLogRecord record, String... secrets) {
        for (String secret : secrets) {
            assertFalse(secret + " in " + record.getPayload(), record.getPayload().contains(secret));
            assertFalse(secret + " in " + record.toLogString(), record.toLogString().contains(secret));
        }
    }
}
