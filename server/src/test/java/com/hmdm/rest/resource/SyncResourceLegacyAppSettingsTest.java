/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.rest.resource;

import com.google.inject.Guice;
import com.hmdm.persistence.AgentCommandDAO;
import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.ApplicationSetting;
import com.hmdm.persistence.domain.Device;
import com.hmdm.rest.json.Response;
import com.hmdm.rest.json.SyncApplicationSetting;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * POST /public/sync/applicationSettings/{number}, the Headwind launcher's route (LEGACY_DEVICE_API=1): it has no
 * authentication, and a device's settings are the MDMesh agent's managed app configuration. So a device the agent
 * enrolled is answered as unknown and nothing is written; a launcher device keeps working.
 */
public class SyncResourceLegacyAppSettingsTest {

    private static final String AGENT_DEVICE = "agent-1";
    private static final String LAUNCHER_DEVICE = "launcher-1";

    private final List<String> writes = new ArrayList<>();

    private SyncResource resource() {
        UnsecureDAO unsecure = new UnsecureDAO(null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, "files", 2, "launcher") {
            @Override public Device getDeviceByNumber(String number) {
                if (!AGENT_DEVICE.equals(number) && !LAUNCHER_DEVICE.equals(number)) return null;
                Device d = new Device();
                d.setId(AGENT_DEVICE.equals(number) ? 1 : 2);
                d.setNumber(number);
                return d;
            }
            @Override public void saveDeviceApplicationSettings(Device device, List<ApplicationSetting> settings) {
                writes.add(device.getNumber() + " " + settings.size());
            }
        };
        AgentCommandDAO agents = new AgentCommandDAO(null, null, null, null) {
            @Override public String getDeviceSecretHash(String number) {
                return AGENT_DEVICE.equals(number) ? "hash" : null;
            }
        };
        return new SyncResource(unsecure, null, Guice.createInjector(), null, null, agents, "https://mdm.example",
                false, "", false, "", "", "", "");
    }

    private static List<SyncApplicationSetting> oneSetting() {
        SyncApplicationSetting s = new SyncApplicationSetting();
        s.setPackageId("com.example.app");
        s.setName("serverUrl");
        s.setType(1);
        s.setValue("https://attacker.example");
        s.setReadonly(false);
        return Collections.singletonList(s);
    }

    @Test
    public void aDeviceTheAgentEnrolledIsAnsweredAsUnknownAndNothingIsWritten() {
        Response r = resource().saveApplicationSettings(AGENT_DEVICE, oneSetting());
        assertEquals(Response.ResponseStatus.ERROR, r.getStatus());
        assertEquals(Response.DEVICE_NOT_FOUND_ERROR().getMessage(), r.getMessage());
        assertTrue(writes.isEmpty());
    }

    @Test
    public void aLauncherDeviceStillSavesItsSettings() {
        Response r = resource().saveApplicationSettings(LAUNCHER_DEVICE, oneSetting());
        assertEquals(Response.ResponseStatus.OK, r.getStatus());
        assertEquals(Collections.singletonList(LAUNCHER_DEVICE + " 1"), writes);
    }

    @Test
    public void anUnknownDeviceIsUnknown() {
        Response r = resource().saveApplicationSettings("nobody", oneSetting());
        assertEquals(Response.DEVICE_NOT_FOUND_ERROR().getMessage(), r.getMessage());
        assertTrue(writes.isEmpty());
    }
}
