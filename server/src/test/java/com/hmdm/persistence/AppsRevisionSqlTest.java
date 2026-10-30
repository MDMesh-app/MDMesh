package com.hmdm.persistence;

import com.hmdm.persistence.mapper.AgentCommandMapper;
import com.hmdm.persistence.mapper.ConfigurationMapper;
import com.hmdm.persistence.mapper.DeviceStateMapper;
import com.hmdm.testsupport.Fakes;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * device_state.appsRevision is moved only by the app reconciler: the check-in's state upsert never writes it (a NULL
 * would re-baseline the device), and it is added NULL on upgrade, which the reconciler reads as "record a baseline".
 */
public class AppsRevisionSqlTest {

    private static String squash(String sql) {
        return sql.replaceAll("\\s+", " ");
    }

    @Test
    public void theStateUpsertLeavesTheAppsRevisionAlone() {
        assertFalse(Fakes.sqlOf(DeviceStateMapper.class, "upsert").contains("appsRevision"));
        assertTrue(Fakes.sqlOf(DeviceStateMapper.class, "findByDeviceNumber").contains("appsRevision"));
        String update = squash(Fakes.sqlOf(DeviceStateMapper.class, "updateAppsRevision"));
        assertTrue(update, update.contains("UPDATE device_state SET appsRevision = #{revision} WHERE deviceNumber = #{deviceNumber}"));
    }

    @Test
    public void theHistoryIsTheDevicesOwnAppCommands() {
        String where = Fakes.whereOf(Fakes.sqlOf(AgentCommandMapper.class, "listLatestAppCommands"));
        assertTrue(where, where.contains("where devicenumber = #{devicenumber} and type in ('app.install','app.uninstall')"));
        assertTrue(where, where.contains("order by id desc limit #{limit}"));
    }

    @Test
    public void theInstallSetIsTheConfigurationsInstallRows() {
        String where = Fakes.whereOf(Fakes.sqlOf(ConfigurationMapper.class, "getInstallVersionIds"));
        assertTrue(where, where.contains("where configurationid = #{id} and action = 1 and applicationversionid is not null"));
    }

    @Test
    public void theColumnIsAddedNullWithARollback() throws Exception {
        Path p = Paths.get("src/main/resources/liquibase/db.changelog.xml");
        if (!Files.exists(p)) p = Paths.get("server").resolve(p);
        String xml = squash(new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
        int at = xml.indexOf("<changeSet id=\"26.10.29-deviceState-appsRevision\"");
        assertTrue(at >= 0);
        String cs = xml.substring(at, xml.indexOf("</changeSet>", at));
        assertTrue(cs, cs.contains("<sql>ALTER TABLE device_state ADD COLUMN appsRevision TEXT;</sql>"));
        assertTrue(cs, cs.contains("<rollback>ALTER TABLE device_state DROP COLUMN appsRevision;</rollback>"));
    }
}
