/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */

package com.hmdm.persistence;

import com.google.inject.ImplementedBy;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.hmdm.persistence.domain.Device;
import com.hmdm.persistence.mapper.AgentDeviceMapper;
import com.hmdm.persistence.mapper.AgentEnrollmentTokenMapper;
import org.mybatis.guice.transactional.Transactional;

/**
 * <p>An agent enrollment as ONE database transaction, serialised per enrollment token: lock the token row, resume the
 * enrollment this attempt already made (a retry whose response was lost), else take a use of the token, create the
 * device and give it its secret. A failure anywhere, a crash or a lost connection rolls all of it back: no device
 * without a secret, no use spent, nothing half done for a retry to trip over. A retry that arrives while the first
 * request runs waits on the token lock and then resumes what it committed.</p>
 *
 * <p>Runs in the caller's transaction when there is one, else in its own (mybatis-guice {@link Transactional}, which
 * only applies to instances Guice creates). Everything that need not be atomic (queueing the configuration's apps,
 * the response) is the caller's, after the commit.</p>
 */
@Singleton
public class AgentEnrollmentDAO {

    /** How long after an enrollment the agent may retry it (a lost response); its first check-in closes it sooner. */
    public static final long RETRY_WINDOW_MS = 24L * 60L * 60L * 1000L;

    /** Creates the device row for a token enrollment (in the token's customer and configuration), or null if refused. */
    @ImplementedBy(UnsecureDeviceFactory.class)
    public interface DeviceFactory {
        Device create(String deviceNumber, int customerId, Integer configurationId);
    }

    /** The production {@link DeviceFactory}: {@link UnsecureDAO#createNewDeviceForToken}. */
    static final class UnsecureDeviceFactory implements DeviceFactory {
        private final UnsecureDAO unsecureDAO;

        @Inject
        UnsecureDeviceFactory(UnsecureDAO unsecureDAO) {
            this.unsecureDAO = unsecureDAO;
        }

        @Override
        public Device create(String deviceNumber, int customerId, Integer configurationId) {
            return unsecureDAO.createNewDeviceForToken(deviceNumber, customerId, configurationId);
        }
    }

    /** Device creation was refused (settings): thrown inside the transaction so the use it took is rolled back. */
    public static final class CreationRefusedException extends RuntimeException {
        public CreationRefusedException() {
            super("device creation refused by settings");
        }
    }

    /** The customer has as many devices as its limit allows: thrown inside the transaction, so nothing is kept. */
    public static final class DeviceLimitException extends RuntimeException {
        public final int limit;

        public DeviceLimitException(int limit) {
            super("customer device limit reached (" + limit + ")");
            this.limit = limit;
        }
    }

    /** What an enrollment did. */
    public static final class Outcome {
        public enum Kind {
            /** A new device: {@link #device} (committed). */
            CREATED,
            /** This attempt's earlier enrollment, given the new secret: {@link #deviceNumber}. */
            RESUMED,
            /** The token has no use left (used up, stopped or expired), or is gone. */
            NO_USE
        }

        public final Kind kind;
        public final String deviceNumber;
        public final Device device;

        private Outcome(Kind kind, String deviceNumber, Device device) {
            this.kind = kind;
            this.deviceNumber = deviceNumber;
            this.device = device;
        }

        static Outcome created(Device device) {
            return new Outcome(Kind.CREATED, device.getNumber(), device);
        }

        static Outcome resumed(String deviceNumber) {
            return new Outcome(Kind.RESUMED, deviceNumber, null);
        }

        static Outcome noUse() {
            return new Outcome(Kind.NO_USE, null, null);
        }
    }

    private final AgentEnrollmentTokenMapper tokenMapper;
    private final AgentDeviceMapper deviceMapper;
    private final DeviceFactory deviceFactory;

    @Inject
    public AgentEnrollmentDAO(AgentEnrollmentTokenMapper tokenMapper, AgentDeviceMapper deviceMapper,
                              DeviceFactory deviceFactory) {
        this.tokenMapper = tokenMapper;
        this.deviceMapper = deviceMapper;
        this.deviceFactory = deviceFactory;
    }

    /**
     * Enrolls a device with token {@code tokenId}, or resumes the enrollment {@code attemptHash} already made.
     *
     * @param customerId      the token's customer (fixed when it was made)
     * @param configurationId the token's configuration, or null (the customer's setting for new devices)
     * @param deviceNumber the number for a new device
     * @param secretHash   SHA-256 of the secret the caller returns: the new device's, or the resumed one's new secret
     * @param attemptHash  the enroll attempt's hash, or null (an agent that sends none)
     * @throws CreationRefusedException settings refused the device (rolled back, the use not spent)
     * @throws DeviceLimitException     the customer has reached its device limit (rolled back, the use not spent)
     * @throws RuntimeException         a database failure, including the token lock not obtained in time (rolled back)
     */
    @Transactional
    public Outcome enroll(int tokenId, int customerId, Integer configurationId, String deviceNumber, String secretHash, String capabilitiesJson,
                          String hardwareId, String attemptHash) {
        long now = System.currentTimeMillis();
        // Enrolls of one token run one after another; a request that waits longer than this fails retryably.
        tokenMapper.setEnrollLockTimeout();
        if (tokenMapper.lockForEnroll(tokenId) == null) {
            return Outcome.noUse();
        }
        if (attemptHash != null) {
            // Under the lock, so a retry that waited for the first request sees what it committed.
            String resumable = deviceMapper.findEnrollRetry(attemptHash, now - RETRY_WINDOW_MS);
            if (resumable != null && deviceMapper.reissueSecret(resumable, attemptHash, secretHash) > 0) {
                return Outcome.resumed(resumable);
            }
        }
        // A new device: within the customer's device limit, counted under the customer's row lock so two enrolls of
        // the same customer (any codes) can't both take the last place. A resumed enrollment (above) adds no device.
        enforceDeviceLimit(customerId);
        if (tokenMapper.claim(tokenId, now) != 1) {
            return Outcome.noUse();
        }
        Device device = deviceFactory.create(deviceNumber, customerId, configurationId);
        if (device == null) {
            throw new CreationRefusedException();
        }
        if (deviceMapper.completeEnrollment(deviceNumber, secretHash, capabilitiesJson, hardwareId, attemptHash,
                now) != 1) {
            throw new IllegalStateException("device " + deviceNumber + " not found to complete its enrollment");
        }
        return Outcome.created(device);
    }

    /**
     * Refuses a new device when the customer has as many as its limit allows. As on-demand creation: only on an install
     * with several customers (the first customer carries a default limit nobody set), and 0 means no limit.
     */
    private void enforceDeviceLimit(int customerId) {
        Integer limit = deviceMapper.lockCustomerDeviceLimit(customerId);
        if (limit == null || limit <= 0 || !deviceMapper.hasSeveralCustomers()) {
            return;
        }
        if (deviceMapper.countCustomerDevices(customerId) >= limit) {
            throw new DeviceLimitException(limit);
        }
    }
}
