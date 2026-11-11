/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.rest.resource;

import com.hmdm.persistence.AgentCommandDAO;
import com.hmdm.persistence.AgentEnrollmentDAO;
import com.hmdm.persistence.AgentEnrollmentTokenDAO;
import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.AgentEnrollmentToken;
import com.hmdm.persistence.domain.Configuration;
import com.hmdm.persistence.domain.Device;
import com.hmdm.persistence.mapper.AgentDeviceMapper;
import com.hmdm.persistence.mapper.AgentEnrollmentTokenMapper;
import com.hmdm.rest.json.Response;
import com.hmdm.rest.json.agent.AgentEnrollRequest;
import com.hmdm.rest.resource.support.ConfigAppInstaller;
import com.hmdm.testsupport.Fakes.FakeMapper;
import org.apache.ibatis.exceptions.PersistenceException;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The enroll endpoint over the real {@link AgentEnrollmentDAO} logic, with in-memory mappers (no database, so no
 * rollback: what a failed transaction keeps is covered by AgentEnrollmentTransactionTest against PostgreSQL).
 */
final class EnrollFixture {

    static final String TOKEN = "enroll-token";

    final AgentEnrollmentToken token = new AgentEnrollmentToken();
    final List<String> created = new ArrayList<>();
    final List<String> completed = new ArrayList<>();
    final Map<String, String> secretHashes = new HashMap<>();
    /** Attempt hash → device, while its enrollment can be resumed (until the device checks in). */
    final Map<String, String> retryable = new HashMap<>();
    final List<String> configAppsQueuedFor = new ArrayList<>();

    boolean creationEnabled = true;
    /** What completing does: "ok", "none" (no row) or "throw" (a database failure). */
    String completion = "ok";
    boolean lockTimesOut = false;
    boolean configAppsThrow = false;
    /** The customer is at its device limit (one device, limit one, several customers). */
    boolean atDeviceLimit = false;

    final AgentResource resource;

    EnrollFixture() {
        token.setId(1);
        token.setToken(TOKEN);
        token.setCustomerId(1);

        FakeMapper<AgentEnrollmentTokenMapper> tokens = new FakeMapper<>(AgentEnrollmentTokenMapper.class)
                .answer("lockForEnroll", a -> {
                    if (lockTimesOut) {
                        throw new PersistenceException(new SQLException("canceling statement due to lock timeout",
                                "55P03"));
                    }
                    return token;
                })
                .answer("claim", a -> claim() ? 1 : 0);
        FakeMapper<AgentDeviceMapper> devices = new FakeMapper<>(AgentDeviceMapper.class)
                .answer("customerDeviceLimit", a -> atDeviceLimit ? 1 : 0)
                .answer("hasSeveralCustomers", a -> atDeviceLimit)
                .answer("countCustomerDevices", a -> atDeviceLimit ? 1L : 0L)
                .answer("findEnrollRetry", a -> retryable.get((String) a[0]))
                .answer("getDeviceSecretHash", a -> secretHashes.get((String) a[0]))
                .answer("reissueSecret", a -> {
                    if (!a[0].equals(retryable.get((String) a[1]))) return 0;
                    secretHashes.put((String) a[0], (String) a[2]);
                    return 1;
                })
                .answer("completeEnrollment", a -> {
                    if ("throw".equals(completion)) throw new PersistenceException(new SQLException("reset", "08006"));
                    if ("none".equals(completion)) return 0;
                    String n = (String) a[0];
                    completed.add(n);
                    secretHashes.put(n, (String) a[1]);
                    if (a[4] != null) retryable.put((String) a[4], n);
                    return 1;
                });
        AgentEnrollmentDAO enrollments = new AgentEnrollmentDAO(tokens.mapper, devices.mapper, this::createDevice,
                "test-hash-secret");

        AgentEnrollmentTokenDAO tokenDAO = new AgentEnrollmentTokenDAO(null) {
            @Override public AgentEnrollmentToken findByToken(String t) { return TOKEN.equals(t) ? token : null; }
        };
        UnsecureDAO unsecure = new UnsecureDAO(null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, "/tmp", 0, "x") {
            @Override public Configuration getConfigurationById(Integer id) { return null; }
            @Override public Device getDeviceByNumber(String number) {
                Device d = new Device();
                d.setNumber(number);
                return d;
            }
        };
        ConfigAppInstaller configApps = new ConfigAppInstaller(null, null, null, null) {
            @Override public int enqueueConfigApps(Device device) {
                if (configAppsThrow) throw new IllegalStateException("queue down");
                configAppsQueuedFor.add(device.getNumber());
                return 0;
            }
        };
        resource = new AgentResource(unsecure, tokenDAO, new AgentCommandDAO(null, null, null, null), configApps,
                null, null, null, null, null, enrollments);
    }

    /** As AgentEnrollmentTokenMapper.claim: one use if any is left, it isn't stopped or expired, used with the last. */
    private boolean claim() {
        int max = token.getMaxUses() == null ? 1 : token.getMaxUses();
        boolean expired = token.getExpiresAt() != null && token.getExpiresAt() <= System.currentTimeMillis();
        if (token.isUsed() || token.getUseCount() >= max || token.getRevokedAt() != null || expired) return false;
        token.setUseCount(token.getUseCount() + 1);
        token.setUsed(token.getUseCount() >= max);
        return true;
    }

    private Device createDevice(String number, int customerId, Integer configurationId) {
        if (!creationEnabled) return null;
        created.add(number);
        Device d = new Device();
        d.setNumber(number);
        d.setCustomerId(customerId);
        d.setConfigurationId(configurationId);
        return d;
    }

    /** What an authenticated check-in does to the retry window (AgentDeviceMapper.markCheckedIn). */
    void checkIn(String number) {
        retryable.values().removeIf(number::equals);
    }

    Response enroll(String attempt) {
        AgentEnrollRequest req = new AgentEnrollRequest();
        req.setEnrollToken(TOKEN);
        req.setEnrollAttempt(attempt);
        req.setHardwareId("  hw-1 ");
        return resource.enroll(req);
    }
}
