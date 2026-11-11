/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.persistence;

import com.hmdm.persistence.mapper.AgentDeviceMapper;
import com.hmdm.persistence.mapper.DeviceMapper;
import com.hmdm.persistence.mapper.RolloutMapper;
import com.hmdm.testsupport.Fakes;
import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Scanner;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * devices.wipedAt (#137): the latest wipe's delivery sets it, only that wipe's failure clears it (a late failure of an
 * earlier wipe leaves a later wipe's mark alone), a rollout leaves a marked device out, the sync summary gets it marked,
 * and a check-in reads the mark.
 */
public class WipedSqlTest {

    private static String squash(String sql) {
        return sql.replaceAll("\\s+", " ");
    }

    @Test
    public void theLatestDeliverySetsTheMark() {
        String update = squash(Fakes.sqlOf(AgentDeviceMapper.class, "markWiped"));
        assertTrue(update, update.contains("UPDATE devices SET wipedAt = #{ts} WHERE number = #{deviceNumber}"));
    }

    @Test
    public void onlyTheFailureOfTheWipeThatSetTheMarkClearsIt() {
        String where = Fakes.whereOf(Fakes.sqlOf(AgentDeviceMapper.class, "clearWipedForCommand"));
        assertTrue(where, where.contains("where number = #{devicenumber} and wipedat is not null"));
        assertTrue(where, where.contains("c.id = #{commandid} and c.devicenumber = #{devicenumber}"));
        assertTrue(where, where.contains("c.type = 'device.wipe'"));
        assertTrue(where, where.contains("c.deliveredat >= devices.wipedat"));
    }

    @Test
    public void aCheckInClearsOnlyAMarkOlderThanTheGrace() {
        String where = Fakes.whereOf(Fakes.sqlOf(AgentDeviceMapper.class, "clearWipedBefore"));
        assertTrue(where, where.contains("where number = #{devicenumber} and wipedat < #{cutoff}"));
    }

    @Test
    public void aRolloutLeavesWipedDevicesOut() {
        String where = Fakes.whereOf(Fakes.sqlOf(RolloutMapper.class, "listCustomerDevices"));
        assertTrue(where, where.contains("where d.customerid = #{customerid} and d.wipedat is null"));
    }

    @Test
    public void theSyncSummaryGetsWipedDevicesMarked() {
        // Not filtered: ConfigurationMapper.countDevices counts them, so a configuration with only wiped devices has to
        // reach the console for it to warn before a delete that will fail.
        String sql = squash(Fakes.sqlOf(AgentDeviceMapper.class, "listDevicesForSync")).toLowerCase();
        String where = Fakes.whereOf(sql);
        assertTrue(sql, sql.contains("d.wipedat is not null as wiped"));
        assertFalse(where, where.contains("wipedat"));
    }

    @Test
    public void aCheckInReadsTheMark() throws Exception {
        // AgentResource.checkIn loads the device through getDeviceByNumber: without the column it always sees null,
        // and neither way of removing the mark runs.
        String xml;
        try (InputStream in = DeviceMapper.class.getResourceAsStream("DeviceMapper.xml")) {
            assertNotNull("DeviceMapper.xml on the classpath", in);
            xml = squash(new Scanner(in, "UTF-8").useDelimiter("\\A").next());
        }
        String select = xml.substring(xml.indexOf("<select id=\"getDeviceByNumber\""));
        select = select.substring(0, select.indexOf("</select>"));
        assertTrue(select, select.contains("resultMap=\"deviceResult\""));
        assertTrue(select, select.contains("<include refid=\"deviceWithGroupsSelect\"/>"));

        String columns = xml.substring(xml.indexOf("<sql id=\"deviceWithGroupsSelect\">"));
        columns = columns.substring(0, columns.indexOf(" FROM devices"));
        assertTrue(columns, columns.contains("devices.wipedAt"));
        String map = xml.substring(xml.indexOf("<resultMap id=\"deviceResult\""));
        map = map.substring(0, map.indexOf("</resultMap>"));
        assertTrue(map, map.contains("<result property=\"wipedAt\" column=\"wipedAt\"/>"));
    }

    @Test
    public void theColumnIsAddedWithARollback() throws Exception {
        Path p = Paths.get("src/main/resources/liquibase/db.changelog.xml");
        if (!Files.exists(p)) p = Paths.get("server").resolve(p);
        String xml = squash(new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
        int at = xml.indexOf("<changeSet id=\"26.11.04-devices-wipedAt\"");
        assertTrue("changeset", at >= 0);
        String cs = xml.substring(at, xml.indexOf("</changeSet>", at));
        assertTrue(cs, cs.contains("<sql>ALTER TABLE devices ADD COLUMN wipedAt BIGINT;</sql>"));
        assertTrue(cs, cs.contains("<rollback>ALTER TABLE devices DROP COLUMN wipedAt;</rollback>"));
    }
}
