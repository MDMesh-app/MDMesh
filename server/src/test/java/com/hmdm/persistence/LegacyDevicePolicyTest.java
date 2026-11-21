package com.hmdm.persistence;

import com.hmdm.persistence.mapper.DeviceMapper;
import com.hmdm.testsupport.Fakes;
import com.hmdm.testsupport.LegacyDeviceWorld;
import org.junit.Before;
import org.junit.Test;

import static com.hmdm.testsupport.LegacyDeviceWorld.AGENT_CONFIG;
import static com.hmdm.testsupport.LegacyDeviceWorld.AGENT_QR_KEY;
import static com.hmdm.testsupport.LegacyDeviceWorld.LAUNCHER_CONFIG;
import static com.hmdm.testsupport.LegacyDeviceWorld.LAUNCHER_QR_KEY;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The Headwind launcher API's one rule (UnsecureDAO): what an agent-enrolled device and an agent-managed configuration
 * are, how far each lookup resolves, and the parts the route tests don't reach (lookup by id, the QR code).
 */
public class LegacyDevicePolicyTest {

    private LegacyDeviceWorld world;

    @Before
    public void setUp() {
        world = new LegacyDeviceWorld();
        world.agent("agent-1");
        world.launcher("launcher-1", LAUNCHER_CONFIG);
    }

    @Test
    public void anAgentDeviceIsOneWithACheckInSecret() {
        String sql = Fakes.whereOf(Fakes.sqlOf(DeviceMapper.class, "isAgentDevice"));
        assertTrue(sql, sql.contains("where id = #{id} and agentsecrethash is not null"));
    }

    @Test
    public void anAgentManagedConfigurationIsOneAnAgentDeviceUsesOrALiveTokenEnrollsInto() {
        String sql = Fakes.sqlOf(DeviceMapper.class, "isAgentConfiguration").replaceAll("\\s+", " ").toLowerCase();
        assertTrue(sql, sql.contains("where configurationid = #{configurationid} and agentsecrethash is not null"));
        assertTrue(sql, sql.contains("from agentenrollmenttoken where "
                + com.hmdm.persistence.mapper.AgentEnrollmentTokenMapper.LIVE.toLowerCase()));
        assertTrue(sql, sql.contains("configurationid is null and customerid in (select customerid from settings "
                + "where newdeviceconfigurationid = #{configurationid})"));
        // The same liveness enrollment claims a use by.
        String claim = Fakes.sqlOf(com.hmdm.persistence.mapper.AgentEnrollmentTokenMapper.class, "claim");
        assertTrue(claim, claim.contains(com.hmdm.persistence.mapper.AgentEnrollmentTokenMapper.LIVE));
    }

    @Test
    public void eachLookupResolvesOnlyAsFarAsTheRouteAsks() {
        assertEquals(LegacyDevice.Status.ABSENT,
                world.dao.findLegacyDevice("agent-1-old", LegacyDevice.Lookup.NUMBER).getStatus());
        assertEquals(LegacyDevice.Status.AGENT_DEVICE,
                world.dao.findLegacyDevice("agent-1-old", LegacyDevice.Lookup.OLD_NUMBER).getStatus());
        assertEquals(LegacyDevice.Status.ABSENT,
                world.dao.findLegacyDevice("agent-1-imei", LegacyDevice.Lookup.OLD_NUMBER).getStatus());
        assertEquals(LegacyDevice.Status.AGENT_DEVICE,
                world.dao.findLegacyDevice("agent-1-imei", LegacyDevice.Lookup.IMEI_OR_SERIAL).getStatus());
        LegacyDevice served = world.dao.findLegacyDevice("launcher-1", LegacyDevice.Lookup.IMEI_OR_SERIAL);
        assertEquals(LegacyDevice.Lookup.NUMBER, served.getMatch());
        assertEquals("launcher-1", served.getDevice().getNumber());
    }

    @Test
    public void aRefusedDeviceIsNeverHandedOut() {
        LegacyDevice refused = world.dao.findLegacyDevice("agent-1", LegacyDevice.Lookup.IMEI_OR_SERIAL);
        assertNull(refused.getDevice());
        assertEquals("error.notfound.device", refused.refusal().getMessage());
    }

    @Test
    public void lookupByIdFollowsTheSameRule() {
        world.launcher("launcher-in-agent-config", AGENT_CONFIG);
        assertEquals(LegacyDevice.Status.AGENT_DEVICE,
                world.dao.findLegacyDeviceById(world.byNumber.get("agent-1").getId()).getStatus());
        assertEquals(LegacyDevice.Status.AGENT_CONFIGURATION,
                world.dao.findLegacyDeviceById(world.byNumber.get("launcher-in-agent-config").getId()).getStatus());
        assertEquals(LegacyDevice.Status.ABSENT, world.dao.findLegacyDeviceById(null).getStatus());
        assertTrue(world.dao.findLegacyDeviceById(world.byNumber.get("launcher-1").getId()).isServed());
    }

    @Test
    public void theProvisioningQrCodeIsNeverServedForAnAgentManagedConfiguration() {
        assertNull(world.dao.getLegacyConfigurationByQRCodeKey(AGENT_QR_KEY));
        assertEquals(LAUNCHER_CONFIG, (int) world.dao.getLegacyConfigurationByQRCodeKey(LAUNCHER_QR_KEY).getId());
    }

    @Test
    public void onlyASingleCustomerInstallCreatesDevicesWithoutOptions() {
        assertTrue(world.dao.createLegacyDevice("new-1").isServed());
        assertEquals(LAUNCHER_CONFIG, (int) world.byNumber.get("new-1").getConfigurationId());

        world.multiTenant = true;
        assertEquals(LegacyDevice.Status.ABSENT, world.dao.createLegacyDevice("new-2").getStatus());
        assertNull(world.byNumber.get("new-2"));
    }
}
