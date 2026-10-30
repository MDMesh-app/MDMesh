package com.hmdm.persistence;

import com.hmdm.persistence.mapper.AgentCommandMapper;
import com.hmdm.persistence.mapper.ConfigurationMapper;
import com.hmdm.persistence.mapper.DeviceStateMapper;
import com.hmdm.testsupport.Fakes;
import com.hmdm.util.AppReconcileDecision;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * device_state.appsRevision is moved only by the app reconciler: the check-in's state upsert never writes it (a NULL
 * would make the device's whole set decided again), and the update that adds it seeds each enrolled device's current
 * set, in the text the reconciler computes, so the update queues nothing.
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

    private static String changeSet(String id) throws Exception {
        Path p = Paths.get("src/main/resources/liquibase/db.changelog.xml");
        if (!Files.exists(p)) p = Paths.get("server").resolve(p);
        String xml = squash(new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
        int at = xml.indexOf("<changeSet id=\"" + id + "\"");
        assertTrue(id, at >= 0);
        return xml.substring(at, xml.indexOf("</changeSet>", at));
    }

    @Test
    public void theColumnIsAddedAndSeededWithARollback() throws Exception {
        String cs = changeSet("26.10.29-deviceState-appsRevision");
        assertTrue(cs, cs.contains("<sql>ALTER TABLE device_state ADD COLUMN appsRevision TEXT;"));
        assertTrue(cs, cs.contains("<rollback>ALTER TABLE device_state DROP COLUMN appsRevision;</rollback>"));
        // The seed builds AppReconcileDecision.revision's text: configuration id, ':', the distinct install version
        // ids in numeric order (the column is INT), comma-joined, '' for none. Same filter as getInstallVersionIds.
        assertTrue(cs, cs.contains("SET appsRevision = d.configurationId || ':' || COALESCE(( "
                + "SELECT string_agg(s.v::text, ',' ORDER BY s.v) FROM ( "
                + "SELECT DISTINCT ca.applicationVersionId AS v FROM configurationApplications ca "
                + "WHERE ca.configurationId = d.configurationId AND ca.action = 1 "
                + "AND ca.applicationVersionId IS NOT NULL) s), '')"));
        assertTrue(cs, cs.contains("FROM devices d WHERE d.number = ds.deviceNumber AND d.configurationId IS NOT NULL;"));
    }

    /** What the seed's SQL computes for one configuration, step by step as written there. */
    private static String seeded(int configurationId, Integer... installRows) {
        java.util.List<Integer> distinctSorted = new java.util.ArrayList<>(new java.util.HashSet<>(Arrays.asList(installRows)));
        distinctSorted.removeIf(v -> v == null);
        Collections.sort(distinctSorted);
        StringBuilder agg = null;   // string_agg over no rows is NULL
        for (Integer v : distinctSorted) agg = agg == null ? new StringBuilder(v.toString()) : agg.append(',').append(v);
        return configurationId + ":" + (agg == null ? "" : agg.toString());
    }

    @Test
    public void theSeedMatchesTheRevisionTheCheckInComputes() {
        Integer[][] sets = {{}, {7}, {30, 4, 100, 4}, {9, 10, 2, 11}};
        for (Integer[] set : sets) {
            java.util.List<Integer> ids = new java.util.ArrayList<>(Arrays.asList(set));
            assertEquals(seeded(5, set), AppReconcileDecision.revision(5, ids));
        }
        assertEquals("5:2,9,10,11", seeded(5, 9, 10, 2, 11));
    }
}
