/*
 * Copyright (C) 2026 the MDMesh contributors. Licensed under the Apache License, Version 2.0.
 */
package com.hmdm.rest.resource;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hmdm.persistence.AgentCommandDAO;
import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.AgentCommand;
import com.hmdm.persistence.domain.Device;
import com.hmdm.persistence.domain.DeviceEvent;
import com.hmdm.persistence.domain.DeviceLocation;
import com.hmdm.persistence.domain.DeviceState;
import com.hmdm.rest.json.Response;
import com.hmdm.rest.json.agent.AgentCapabilities;
import com.hmdm.rest.json.agent.AgentCheckInRequest;
import com.hmdm.rest.json.agent.AgentCommandResult;
import com.hmdm.rest.json.agent.AgentDeviceState;
import com.hmdm.rest.json.agent.AgentProtocol;
import com.hmdm.rest.json.agent.AgentTelemetryEvent;
import com.hmdm.rest.resource.support.AppReconciler;
import com.hmdm.rest.resource.support.ConfigAppInstaller;
import com.hmdm.rest.resource.support.ConfigReconciler;
import com.hmdm.rest.resource.support.InstallHashes;
import com.hmdm.rest.resource.support.KioskLockoutAlert;
import com.hmdm.rest.resource.support.RolloutRequeuer;
import com.hmdm.testsupport.Fakes;
import com.hmdm.util.CryptoUtil;
import org.apache.ibatis.exceptions.PersistenceException;
import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.spi.LoggingEvent;
import org.junit.Before;
import org.junit.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** One authenticated check-in can only cause a bounded amount of database work and storage. */
public class AgentCheckInLimitsTest {

    static final String SECRET = "s3cret";
    static final String DEVICE = "dev-1";

    static final class FakeUnsecureDAO extends UnsecureDAO {
        FakeUnsecureDAO() {
            super(null, null, null, null, null, null, null, null, null, null, null, null, null, null, "unused", 0, null);
        }
        @Override public Device getDeviceByNumber(String number) {
            if (!DEVICE.equals(number)) return null;
            Device d = new Device();
            d.setNumber(DEVICE);
            d.setCustomerId(1);
            return d;
        }
    }

    private static final class FakeCommandDAO extends AgentCommandDAO {
        final List<String> details = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        final List<String> eventDetails = new ArrayList<>();
        final List<String> hardwareIds = new ArrayList<>();
        /** An event of this type is refused by the database, with {@link #rejectedState}. */
        String rejectedType;
        String rejectedState = "22001";
        final List<String> statuses = new ArrayList<>();
        final List<String> storedCaps = new ArrayList<>();
        final List<DeviceLocation> locations = new ArrayList<>();
        String telemetry;
        int capabilityReads;
        FakeCommandDAO() { super(null, null, null, null); }
        @Override public String getDeviceSecretHash(String n) { return CryptoUtil.getSHA256String(SECRET); }
        int lastSeenStamps;
        @Override public void touchLastUpdate(String n) { lastSeenStamps++; }
        @Override public void markResultWithTime(String n, Integer id, String status, String detail, Long at) {
            details.add(detail);
            statuses.add(status);
        }
        @Override public void expireStale(String n, long a, long b) { }
        @Override public void recordConfigApplyDone(String n, Integer id) { }
        @Override public void updateDeviceCapabilities(String n, String json) { storedCaps.add(json); }
        @Override public String getDeviceCapabilities(String n) {
            capabilityReads++;
            return "{\"device\":[\"lock\"]}";
        }
        @Override public List<AgentCommand> listPending(String n) { return Collections.emptyList(); }
        DeviceState state;
        final List<String> androidVersions = new ArrayList<>();
        /** Set: the database fails the state's write with this SQL state. */
        String stateWriteFails;
        @Override public void upsertState(DeviceState s) {
            if (stateWriteFails != null) {
                throw new PersistenceException(new SQLException("failed", stateWriteFails));
            }
            state = s;
            telemetry = s.getTelemetry();
        }
        @Override public void updateAndroidVersion(String n, String v) { androidVersions.add(v); }
        @Override public void recordLocation(DeviceLocation l) { locations.add(l); }
        @Override public void insertEvent(String n, String type, Long ts, String detail) {
            if (type.equals(rejectedType)) {
                throw new PersistenceException(new SQLException("refused", rejectedState));
            }
            events.add(type);
            eventDetails.add(detail);
        }
        /** Calls of {@link #insertEvents}: the batch, all or nothing like the real transaction. */
        int batches;
        @Override public void insertEvents(String n, List<DeviceEvent> rows) {
            if (rows.isEmpty()) return;
            batches++;
            for (DeviceEvent row : rows) {
                if (row.getType().equals(rejectedType)) {
                    throw new PersistenceException(new SQLException("refused", rejectedState));
                }
            }
            for (DeviceEvent row : rows) {
                events.add(row.getType());
                eventDetails.add(row.getDetail());
            }
        }
        @Override public void recordKioskReturn(String n, Long ts, String detail) {
            insertEvent(n, "kioskReturn", ts, detail);
        }
        @Override public void updateHardwareId(String n, String id) { hardwareIds.add(id); }
    }

    static final class NoopReconciler extends ConfigReconciler {
        Set<String> tokens;
        NoopReconciler() { super(null, null, Fakes.noExtensionPoints()); }
        @Override public boolean reconcile(Device d, Set<String> t, String rev, String agentVersion,
                                           String configAgentVersion, long now, long receivedAt) { tokens = t; return false; }
    }

    static final class NoopRequeuer extends RolloutRequeuer {
        NoopRequeuer() { super(null); }
        @Override public boolean requeue(Device d, long now) { return false; }
    }

    private static final class RecordingAlert extends KioskLockoutAlert {
        final List<String> types = new ArrayList<>();
        RecordingAlert() { super(null, null, null); }
        @Override public void onEvent(Device device, String type, String detail) { types.add(type); }
    }

    private RecordingAlert alerts;
    private FakeCommandDAO commands;
    private NoopReconciler reconciler;
    private AgentResource resource;

    @Before
    public void setUp() {
        commands = new FakeCommandDAO();
        alerts = new RecordingAlert();
        reconciler = new NoopReconciler();
        resource = new AgentResource(new FakeUnsecureDAO(), null, commands,
                new ConfigAppInstaller(null, null, null, null), reconciler, new AppReconciler(null, null, null, null),
                new NoopRequeuer(),
                new InstallHashes(null, null), alerts, null);
    }

    private Response checkin(AgentCheckInRequest req) {
        req.setDeviceId(DEVICE);
        return resource.checkin("Bearer " + SECRET, null, req);
    }

    private static AgentCommandResult result(int id, String detail) {
        AgentCommandResult r = new AgentCommandResult();
        r.setCommandId(String.valueOf(id));
        r.setStatus("done");
        r.setDetail(detail);
        return r;
    }

    private static int accepted(Response r) {
        return ((com.hmdm.rest.json.agent.AgentCheckInResponse) r.getData()).getResultsAccepted();
    }

    private static int eventsAccepted(Response r) {
        return ((com.hmdm.rest.json.agent.AgentCheckInResponse) r.getData()).getEventsAccepted();
    }

    private static AgentTelemetryEvent event(String type, String detail) {
        AgentTelemetryEvent e = new AgentTelemetryEvent();
        e.setType(type);
        e.setTs(1L);
        e.setDetail(detail);
        return e;
    }

    private Response checkinEvents(AgentTelemetryEvent... events) {
        AgentCheckInRequest req = new AgentCheckInRequest();
        req.setEvents(Arrays.asList(events));
        return checkin(req);
    }

    private static AgentCheckInRequest stateAndAnEvent() {
        AgentCheckInRequest req = new AgentCheckInRequest();
        req.setState(new AgentDeviceState());
        req.setEvents(Collections.singletonList(event("boot", null)));
        return req;
    }

    @Test
    public void aStateTheDatabaseRefusesCostsTheDeviceOnlyItsState() {
        for (String refused : Arrays.asList("22001", "23514")) {
            setUp();
            commands.stateWriteFails = refused;
            List<String> logged = new ArrayList<>();
            AppenderSkeleton capture = new AppenderSkeleton() {
                @Override protected void append(LoggingEvent event) { logged.add(event.getRenderedMessage()); }
                @Override public void close() { }
                @Override public boolean requiresLayout() { return false; }
            };
            // The repeats within the hour are at DEBUG, and another test may have had the hour's WARN.
            Logger log = Logger.getLogger(AgentResource.class);
            Level level = log.getLevel();
            log.setLevel(Level.DEBUG);
            log.addAppender(capture);
            Response r;
            try {
                r = checkin(stateAndAnEvent());
            } finally {
                log.removeAppender(capture);
                log.setLevel(level);
            }
            assertEquals(Response.ResponseStatus.OK, r.getStatus());
            assertTrue(logged.toString(), logged.contains(
                    "Device dev-1: state ignored, refused by the database (SQL state " + refused + ")"));
            assertEquals(1, commands.lastSeenStamps);
            assertEquals(Collections.singletonList("boot"), commands.events);
        }
    }

    @Test
    public void aDatabaseErrorInTheStateWriteFailsTheCheckInBeforeItsLastSeenStamp() {
        for (String outage : Arrays.asList("08006", "53300", "57P03", "40P01")) {
            setUp();
            commands.stateWriteFails = outage;
            try {
                checkin(stateAndAnEvent());
                fail(outage + " is not the state's fault");
            } catch (PersistenceException expected) {
                assertEquals(0, commands.lastSeenStamps);
                assertEquals(Collections.emptyList(), commands.events);
            }
        }
    }

    @Test
    public void aCheckInWithOrWithoutAStateIsStampedOnce() {
        checkin(stateAndAnEvent());
        assertEquals(1, commands.lastSeenStamps);
        setUp();
        checkin(new AgentCheckInRequest());
        assertEquals(1, commands.lastSeenStamps);
    }

    @Test
    public void capsTheResultsAppliedPerCheckInAndTellsTheAgentHowManyWereTaken() {
        List<AgentCommandResult> results = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) {
            results.add(result(i, null));
        }
        AgentCheckInRequest req = new AgentCheckInRequest();
        req.setResults(results);

        Response r = checkin(req);
        assertEquals(Response.ResponseStatus.OK, r.getStatus());
        assertEquals(AgentResource.MAX_RESULTS_PER_CHECKIN, commands.details.size());
        // The agent keeps (and resends) everything past this count instead of losing it.
        assertEquals(AgentResource.MAX_RESULTS_PER_CHECKIN, accepted(r));
    }

    @Test
    public void reportsEveryResultAcceptedWhenTheyFit() {
        List<AgentCommandResult> results = new ArrayList<>();
        results.add(null);
        results.add(result(1, null));
        results.add(result(2, "x"));
        AgentCheckInRequest req = new AgentCheckInRequest();
        req.setResults(results);

        Response r = checkin(req);
        assertEquals(3, accepted(r));
        assertEquals(2, commands.details.size());
    }

    @Test
    public void reportsZeroAcceptedWithoutResults() {
        assertEquals(0, accepted(checkin(new AgentCheckInRequest())));
    }

    @Test
    public void invalidEntriesCountTowardsTheCap() {
        List<AgentCommandResult> results = new ArrayList<>();
        for (int i = 0; i < AgentResource.MAX_RESULTS_PER_CHECKIN; i++) {
            results.add(null);
        }
        results.add(result(1, null));
        AgentCheckInRequest req = new AgentCheckInRequest();
        req.setResults(results);

        assertEquals(AgentResource.MAX_RESULTS_PER_CHECKIN, accepted(checkin(req)));
        assertEquals(0, commands.details.size());
    }

    private static List<AgentTelemetryEvent> events(int count) {
        List<AgentTelemetryEvent> events = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            AgentTelemetryEvent e = new AgentTelemetryEvent();
            e.setType("e" + i);
            e.setTs((long) i);
            events.add(e);
        }
        return events;
    }

    @Test
    public void storesAWholeOfflineBufferFromAnOlderAgent() {
        // Agents before 0.6.0 send their whole buffer, up to 500 events, in one check-in and count all of it delivered.
        AgentCheckInRequest req = new AgentCheckInRequest();
        req.setEvents(events(500));

        assertEquals(Response.ResponseStatus.OK, checkin(req).getStatus());
        assertEquals(500, commands.events.size());
        assertEquals("e499", commands.events.get(499));
    }

    @Test
    public void capsTheEventsStoredPerCheckIn() {
        AgentCheckInRequest req = new AgentCheckInRequest();
        req.setEvents(events(10_000));

        Response r = checkin(req);
        assertEquals(Response.ResponseStatus.OK, r.getStatus());
        assertEquals(AgentResource.MAX_EVENTS_PER_CHECKIN, commands.events.size());
        // The agent keeps (and resends) everything past this count instead of losing it.
        assertEquals(AgentResource.MAX_EVENTS_PER_CHECKIN, eventsAccepted(r));
    }

    @Test
    public void countsSkippedEventsAsAcceptedSoTheAgentDropsThem() {
        String tooLong = new String(new char[65]).replace('\0', 'a');
        Response r = checkinEvents(null, event(null, "x"), event(tooLong, null), event("has space", null),
                event("", null), event("boot", null));
        assertEquals(Response.ResponseStatus.OK, r.getStatus());
        assertEquals(Collections.singletonList("boot"), commands.events);
        assertEquals(6, eventsAccepted(r));
    }

    @Test
    public void skippedEventsDoNotUseUpTheCap() {
        List<AgentTelemetryEvent> events = events(AgentResource.MAX_EVENTS_PER_CHECKIN + 1);
        events.add(0, event("not valid!", null));
        AgentCheckInRequest req = new AgentCheckInRequest();
        req.setEvents(events);

        Response r = checkin(req);
        assertEquals(AgentResource.MAX_EVENTS_PER_CHECKIN, commands.events.size());
        assertEquals(AgentResource.MAX_EVENTS_PER_CHECKIN + 1, eventsAccepted(r));
    }

    @Test
    public void acceptsAnEventTypeOf64SafeCharacters() {
        String longest = "a.B_c-9" + new String(new char[57]).replace('\0', 'x');
        assertEquals(64, longest.length());
        checkinEvents(event(longest, null));
        assertEquals(Collections.singletonList(longest), commands.events);
    }

    @Test
    public void reportsZeroEventsAcceptedWithoutEvents() {
        assertEquals(0, eventsAccepted(checkin(new AgentCheckInRequest())));
    }

    @Test
    public void stripsNulFromAnEventDetail() {
        // PostgreSQL refuses a NUL in text: the insert would fail the check-in, and the agent would resend it forever.
        checkinEvents(event("boot", "a\0b\0"), event("kioskReturn", "\0x"));
        assertEquals(Arrays.asList("ab", "x"), commands.eventDetails);
    }

    @Test
    public void stripsNulAfterCuttingAnOverlongDetail() {
        StringBuilder detail = new StringBuilder();
        while (detail.length() < 4000) detail.append('a');
        detail.append("\0tail");
        checkinEvents(event("boot", "\0" + detail));
        assertEquals(3999, commands.eventDetails.get(0).length());
    }

    @Test
    public void anEventTheDatabaseRefusesCostsOnlyThatEvent() {
        commands.rejectedType = "kioskReturn";
        Response r = checkinEvents(event("boot", null), event("kioskReturn", "x"), event("lowBattery", "12%"));
        assertEquals(Response.ResponseStatus.OK, r.getStatus());
        assertEquals(Arrays.asList("boot", "lowBattery"), commands.events);
        assertEquals("the agent must not resend it forever", 3, eventsAccepted(r));
    }

    @Test
    public void anEventThatBreaksAConstraintIsAlsoSkipped() {
        commands.rejectedType = "boot";
        commands.rejectedState = "23505";
        Response r = checkinEvents(event("boot", null), event("lowBattery", "12%"));
        assertEquals(Collections.singletonList("lowBattery"), commands.events);
        assertEquals(2, eventsAccepted(r));
    }

    @Test
    public void aLostDatabaseStopsTheEventsThereSoTheAgentResendsTheRest() {
        // Not the event's fault: counted as consumed, the agent would delete it and every event after it.
        commands.rejectedType = "kioskReturn";
        commands.rejectedState = "08006";
        AgentCheckInRequest req = new AgentCheckInRequest();
        req.setProtocolVersion("1.12");
        req.setEvents(Arrays.asList(event("boot", null), event("kioskReturn", "x"), event("lowBattery", "12%")));
        Response r = checkin(req);
        assertEquals(Response.ResponseStatus.OK, r.getStatus());
        // The batch is one transaction: nothing stored, so nothing the agent resends is stored twice.
        assertTrue(commands.events.toString(), commands.events.isEmpty());
        assertEquals(0, eventsAccepted(r));
    }

    @Test
    public void aLostDatabaseCountsTheSkippedEventsBeforeTheBatch() {
        commands.rejectedType = "boot";
        commands.rejectedState = "08006";
        AgentCheckInRequest req = new AgentCheckInRequest();
        req.setProtocolVersion("1.12");
        req.setEvents(Arrays.asList(event("not valid!", null), event("boot", null)));
        assertEquals(1, eventsAccepted(checkin(req)));
    }

    @Test
    public void storesTheEventsAsOneBatch() {
        Response r = checkinEvents(event("boot", null), event("kioskReturn", "x"), event("lowBattery", "12%"));
        assertEquals(1, commands.batches);
        assertEquals(Arrays.asList("boot", "kioskReturn", "lowBattery"), commands.events);
        assertEquals(3, eventsAccepted(r));
    }

    @Test
    public void aRefusedEventRaisesNoLockoutAlert() {
        commands.rejectedType = "kioskPinLockout";
        checkinEvents(event("kioskPinLockout", "5 attempts"), event("boot", null));
        assertEquals(Collections.singletonList("boot"), alerts.types);
    }

    @Test
    public void storedEventsReachTheLockoutAlert() {
        checkinEvents(event("kioskPinLockout", "5 attempts"), event("boot", null));
        assertEquals(Arrays.asList("kioskPinLockout", "boot"), alerts.types);
    }

    @Test
    public void aLostDatabaseFailsTheCheckInOfAnAgentThatCountsItsWholeBatchDelivered() {
        // Agents before protocol 1.12 don't send their version and ignore eventsAccepted: a successful check-in would
        // cost them the event that wasn't stored and every one after it. A failed one keeps their batch for a retry.
        for (String version : Arrays.asList(null, "1.11", "1.x")) {
            commands.events.clear();
            commands.rejectedType = "kioskReturn";
            commands.rejectedState = "08006";
            AgentCheckInRequest req = new AgentCheckInRequest();
            req.setProtocolVersion(version);
            req.setEvents(Arrays.asList(event("boot", null), event("kioskReturn", "x"), event("lowBattery", "12%")));
            try {
                checkin(req);
                fail("protocol " + version + ": the check-in must fail");
            } catch (PersistenceException expected) {
                assertTrue(commands.events.toString(), commands.events.isEmpty());
            }
        }
    }

    @Test
    public void anOlderAgentStillSkipsAnEventTheDatabaseRefuses() {
        // Its data, not the database: a retry would fail the same way, forever.
        commands.rejectedType = "kioskReturn";
        Response r = checkinEvents(event("boot", null), event("kioskReturn", "x"), event("lowBattery", "12%"));
        assertEquals(Response.ResponseStatus.OK, r.getStatus());
        assertEquals(Arrays.asList("boot", "lowBattery"), commands.events);
    }

    @Test
    public void readsTheProtocolVersionAsNumbers() {
        assertTrue(AgentProtocol.atLeast("1.12", 1, 12));
        assertTrue(AgentProtocol.atLeast("1.100", 1, 12));
        assertTrue(AgentProtocol.atLeast("2.0", 1, 12));
        assertFalse(AgentProtocol.atLeast("1.9", 1, 12));
        assertFalse(AgentProtocol.atLeast("1.11", 1, 12));
        assertFalse(AgentProtocol.atLeast(null, 1, 12));
        assertFalse(AgentProtocol.atLeast("", 1, 12));
        assertFalse(AgentProtocol.atLeast("1.12.1", 1, 12));
        assertFalse(AgentProtocol.atLeast("1.99999999999", 1, 12));
        assertFalse(AgentProtocol.atLeast("-2.0", 1, 12));
    }

    private AgentDeviceState state(String androidRelease, String agentVersion, String powerMode) {
        AgentDeviceState s = new AgentDeviceState();
        s.setAndroidRelease(androidRelease);
        s.setAgentVersion(agentVersion);
        s.setPowerMode(powerMode);
        return s;
    }

    @Test
    public void storesTheReportedStateStrings() {
        AgentCheckInRequest req = new AgentCheckInRequest();
        req.setState(state(" 16 ", "0.5.2", "adaptive"));
        assertEquals(Response.ResponseStatus.OK, checkin(req).getStatus());
        assertEquals("16", commands.state.getAndroidRelease());
        assertEquals("0.5.2", commands.state.getAgentVersion());
        assertEquals("adaptive", commands.state.getPowerMode());
        assertEquals(Collections.singletonList("16"), commands.androidVersions);
    }

    @Test
    public void ignoresStateStringsTheDatabaseCantStoreSoTheCheckInGoesThrough() {
        // A NUL, or a value longer than its column, fails the state upsert, and with it every check-in from the device:
        // its events and results would never be delivered.
        String long33 = "abcdefghijklmnopqrstuvwxyz0123456";
        AgentCheckInRequest req = new AgentCheckInRequest();
        req.setState(state("1\0.1", long33 + long33, "alwaysOnAlwaysOn!"));
        req.setEvents(Collections.singletonList(event("boot", null)));
        Response r = checkin(req);
        assertEquals(Response.ResponseStatus.OK, r.getStatus());
        assertNull(commands.state.getAndroidRelease());
        assertNull(commands.state.getAgentVersion());
        assertNull(commands.state.getPowerMode());
        assertTrue(commands.androidVersions.isEmpty());
        assertEquals(1, eventsAccepted(r));

        req.setState(state(long33, null, null));
        checkin(req);
        assertNull(commands.state.getAndroidRelease());
    }

    @Test
    public void stripsNulFromAResultDetail() {
        List<AgentCommandResult> results = new ArrayList<>();
        results.add(result(1, "out\0put"));
        AgentCheckInRequest req = new AgentCheckInRequest();
        req.setResults(results);

        checkin(req);
        assertEquals(Collections.singletonList("output"), commands.details);
    }

    @Test
    public void logsADeviceSentTypeOnOneLineWithoutSplittingACharacter() {
        assertEquals("a?b?c?d", AgentResource.loggable("a\nb\u2028c\u2029d", 40));
        String emoji = "\uD83D\uDE00";
        assertEquals("ab" + emoji + "…", AgentResource.loggable("ab" + emoji + "cd", 3));
        assertEquals("ab" + emoji, AgentResource.loggable("ab" + emoji, 3));
    }

    @Test
    public void ignoresAHardwareIdWithControlCharacters() {
        for (String id : Arrays.asList("hw\0x", "hw\u0001x", "hw\nx", "hw\u007fx")) {
            AgentCheckInRequest req = new AgentCheckInRequest();
            req.setHardwareId(id);
            checkin(req);
        }
        assertTrue(commands.hardwareIds.toString(), commands.hardwareIds.isEmpty());
    }

    @Test
    public void ignoresAHardwareIdLongerThanItsColumn() {
        String max = new String(new char[128]).replace('\0', 'h');
        AgentCheckInRequest req = new AgentCheckInRequest();
        req.setHardwareId(max + "h");
        checkin(req);
        assertTrue(commands.hardwareIds.isEmpty());

        req = new AgentCheckInRequest();
        req.setHardwareId(" " + max + " ");
        checkin(req);
        assertEquals(Collections.singletonList(max), commands.hardwareIds);
    }

    @Test
    public void storesAnOversizedResultAsAShortFailure() {
        StringBuilder big = new StringBuilder();
        while (big.length() <= AgentResource.MAX_RESULT_DETAIL_CHARS) {
            big.append("0123456789abcdef");
        }
        List<AgentCommandResult> results = new ArrayList<>();
        results.add(result(1, big.toString()));
        results.add(result(2, "installed"));
        AgentCheckInRequest req = new AgentCheckInRequest();
        req.setResults(results);

        checkin(req);
        assertEquals("failed", commands.statuses.get(0));
        assertTrue(commands.details.get(0), commands.details.get(0).startsWith("result too large"));
        assertTrue(commands.details.get(0).length() < 200);
        assertEquals("done", commands.statuses.get(1));
        assertEquals("installed", commands.details.get(1));
    }

    @Test
    public void ignoresAnOversizedCapabilityTreeAndGatesOnTheStoredOne() {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ObjectNode cur = root;
        for (int i = 0; i < 100; i++) {
            cur = cur.putObject("n");
        }
        AgentCheckInRequest req = new AgentCheckInRequest();
        req.setCapabilities(new AgentCapabilities(root));

        assertEquals(Response.ResponseStatus.OK, checkin(req).getStatus());
        assertTrue("an oversized tree must not be stored", commands.storedCaps.isEmpty());
        assertEquals(1, commands.capabilityReads);
        assertTrue(reconciler.tokens.contains("device.lock"));
    }

    @Test
    public void storesANormalCapabilityTree() {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.putArray("device").add("wipe");
        AgentCheckInRequest req = new AgentCheckInRequest();
        req.setCapabilities(new AgentCapabilities(root));

        checkin(req);
        assertEquals(Collections.singletonList(root.toString()), commands.storedCaps);
        assertEquals(0, commands.capabilityReads);
        assertTrue(reconciler.tokens.contains("device.wipe"));
    }

    private DeviceLocation checkInWithProvider(String provider) {
        ObjectNode tel = JsonNodeFactory.instance.objectNode();
        ObjectNode loc = tel.putObject("dynamic").putObject("location");
        loc.put("lat", 52.1);
        loc.put("lon", 4.3);
        loc.put("capturedAt", 1L);
        loc.set("provider", JsonNodeFactory.instance.textNode(provider));
        AgentCheckInRequest req = new AgentCheckInRequest();
        req.setState(new AgentDeviceState());
        req.setTelemetry(tel);

        assertEquals(Response.ResponseStatus.OK, checkin(req).getStatus());
        assertEquals(1, commands.locations.size());
        return commands.locations.get(0);
    }

    @Test
    public void dropsAProviderThatIsNotAPlainNameAndKeepsTheFix() {
        DeviceLocation row = checkInWithProvider("<img src=x onerror=alert(1)>");
        assertNull(row.getProvider());
        assertEquals(52.1, row.getLat(), 0.0);
        assertEquals(4.3, row.getLon(), 0.0);
        assertFalse(commands.telemetry, commands.telemetry.contains("provider"));
        assertTrue(commands.telemetry, commands.telemetry.contains("\"lat\":52.1"));
    }

    @Test
    public void keepsAPlainProviderName() {
        assertEquals("fused", checkInWithProvider("fused").getProvider());
        assertTrue(commands.telemetry, commands.telemetry.contains("\"provider\":\"fused\""));
    }

    @Test
    public void dropsAnOverlongProviderButStillStoresTheRow() {
        String longName = "abcdefghijklmnopqrstuvwxyz0123456";
        assertEquals(33, longName.length());
        DeviceLocation row = checkInWithProvider(longName);
        assertNull(row.getProvider());
        assertEquals(52.1, row.getLat(), 0.0);
        assertFalse(commands.telemetry, commands.telemetry.contains("provider"));
    }

    @Test
    public void reportedImeisAreTheFirstFourPrintableStrings() {
        // The update that added device_state.reportedImeis applies the same rules to stored telemetry.
        ObjectNode tel = JsonNodeFactory.instance.objectNode();
        tel.putObject("identity").putArray("imei").add("359881234567890").add(359889876543210L).addNull().add("")
                .add("with space").add("a\0b").add("123456789012345678901234567890123").add("A0000012345678")
                .add("2").add("3").add("4");
        assertEquals(Arrays.asList("359881234567890", "A0000012345678", "2", "3"),
                Arrays.asList(AgentResource.reportedImeis(tel)));
    }

    @Test
    public void noReportedImeisIsNull() {
        JsonNodeFactory nodes = JsonNodeFactory.instance;
        assertNull(AgentResource.reportedImeis(null));
        assertNull(AgentResource.reportedImeis(nodes.objectNode()));
        assertNull(AgentResource.reportedImeis(nodes.arrayNode()));
        ObjectNode tel = nodes.objectNode();
        tel.putObject("identity").put("imei", "359881234567890");
        assertNull(AgentResource.reportedImeis(tel));
        tel.putObject("identity").putArray("imei");
        assertNull(AgentResource.reportedImeis(tel));
        tel.putObject("identity").putArray("imei").add(" ");
        assertNull(AgentResource.reportedImeis(tel));
    }
}
