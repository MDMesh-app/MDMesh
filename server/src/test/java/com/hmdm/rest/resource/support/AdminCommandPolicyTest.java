package com.hmdm.rest.resource.support;

import com.hmdm.persistence.domain.Device;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AdminCommandPolicyTest {

    @Test
    public void aWipedDeviceTakesNoCommandAndAnUnmarkedOneDoes() {
        Device device = new Device();
        assertNull(AdminCommandPolicy.refusalFor(device));
        device.setWipedAt(1000L);
        assertEquals("error.agent.command.wiped", AdminCommandPolicy.refusalFor(device));
    }

    @Test
    public void destructiveTypesNeverGoOutInBulkInAnyCase() {
        for (String type : new String[]{"device.wipe", "Device.PasscodeReset", " app.uninstall ", "APP.SETHIDDEN"}) {
            assertTrue(type, AdminCommandPolicy.isBulkForbidden(type));
        }
        for (String type : new String[]{"device.lock", "device.reboot", "kiosk.enter", "kiosk.exit", "app.install",
                "apps.scan", "device.alert"}) {
            assertFalse(type, AdminCommandPolicy.isBulkForbidden(type));
        }
    }

    @Test
    public void everyBulkForbiddenTypeIsLowercaseAndSaysWhatItDestroys() {
        AdminCommandPolicy.BULK_FORBIDDEN_TYPES.forEach((type, why) -> {
            assertEquals(type, type.toLowerCase(Locale.ROOT), type);
            assertFalse(type, why.trim().isEmpty());
        });
    }

    @Test
    public void configApplyIsServerOwnedInAnyCase() {
        assertTrue(AdminCommandPolicy.isServerOwned(" Config.Apply "));
        assertFalse(AdminCommandPolicy.isServerOwned("config.sync"));
    }

    /** The console hides what the server refuses in bulk: the two lists must not drift apart. */
    @Test
    public void theConsoleMirrorsTheBulkForbiddenTypes() throws Exception {
        String ts = new String(Files.readAllBytes(Paths.get("..", "web", "src", "api", "commands.ts")),
                StandardCharsets.UTF_8);
        Matcher list = Pattern.compile("const BULK_FORBIDDEN_TYPES[^=]*=\\s*\\[([^\\]]*)\\]").matcher(ts);
        assertTrue("BULK_FORBIDDEN_TYPES in web/src/api/commands.ts", list.find());
        Set<String> console = new TreeSet<>();
        Matcher item = Pattern.compile("'([^']*)'").matcher(list.group(1));
        while (item.find()) console.add(item.group(1));
        assertEquals(new TreeSet<>(AdminCommandPolicy.BULK_FORBIDDEN_TYPES.keySet()), console);
    }
}
