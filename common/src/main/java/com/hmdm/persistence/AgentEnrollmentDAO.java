/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */

package com.hmdm.persistence;

import com.google.inject.ImplementedBy;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.hmdm.persistence.domain.AgentEnrollmentToken;
import com.hmdm.persistence.domain.Device;
import com.hmdm.persistence.mapper.AgentDeviceMapper;
import com.hmdm.persistence.mapper.AgentEnrollmentTokenMapper;
import com.hmdm.util.CryptoUtil;
import org.mybatis.guice.transactional.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.UUID;
import java.util.regex.Pattern;

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
            /** This attempt's earlier enrollment, with the same secret: {@link #deviceNumber}. */
            RESUMED,
            /** The token has no use left (used up, stopped or expired), or is gone. */
            NO_USE,
            /** A resume-only request (the agent's recovery) found no enrollment of this attempt to resume. */
            NOT_RESUMABLE
        }

        public final Kind kind;
        public final String deviceNumber;
        public final Device device;
        /** The device's secret (CREATED or RESUMED), returned to the agent; only its hash is stored. */
        public final String secret;

        private Outcome(Kind kind, String deviceNumber, Device device, String secret) {
            this.kind = kind;
            this.deviceNumber = deviceNumber;
            this.device = device;
            this.secret = secret;
        }

        static Outcome created(Device device, String secret) {
            return new Outcome(Kind.CREATED, device.getNumber(), device, secret);
        }

        static Outcome resumed(String deviceNumber, String secret) {
            return new Outcome(Kind.RESUMED, deviceNumber, null, secret);
        }

        static Outcome noUse() {
            return new Outcome(Kind.NO_USE, null, null, null);
        }

        static Outcome notResumable() {
            return new Outcome(Kind.NOT_RESUMABLE, null, null, null);
        }
    }

    /** How long an enrollment waits for its code's (and customer's) lock before failing retryably. */
    private volatile long lockTimeoutMs = 15_000L;

    /** Tests: a short lock wait, to drive a real lock timeout. */
    void setLockTimeoutMs(long lockTimeoutMs) {
        this.lockTimeoutMs = lockTimeoutMs;
    }

    private final AgentEnrollmentTokenMapper tokenMapper;
    private final AgentDeviceMapper deviceMapper;
    private final DeviceFactory deviceFactory;
    /** The server-only key enrollment secrets are derived with (random, in the database; never a setting). */
    private final AgentEnrollmentKey enrollmentKey;

    @Inject
    public AgentEnrollmentDAO(AgentEnrollmentTokenMapper tokenMapper, AgentDeviceMapper deviceMapper,
                              DeviceFactory deviceFactory, AgentEnrollmentKey enrollmentKey) {
        this.tokenMapper = tokenMapper;
        this.deviceMapper = deviceMapper;
        this.deviceFactory = deviceFactory;
        this.enrollmentKey = enrollmentKey;
    }

    /** An enroll attempt id: random, URL-safe, long enough not to be guessed; anything else is ignored. */
    private static final Pattern ENROLL_ATTEMPT = Pattern.compile("[A-Za-z0-9_-]{16,128}");

    /** {@code attempt} when it is a usable enroll attempt id, else null (the enrollment is then not resumable). */
    public static String validAttempt(String attempt) {
        return attempt != null && ENROLL_ATTEMPT.matcher(attempt).matches() ? attempt : null;
    }

    /**
     * What identifies a retryable enrollment: the SHA-256 of the token id and the agent's attempt id, so the stored
     * value reveals neither and an attempt id only resumes enrollments made with the same code.
     *
     * @return the hash, or null when there is no usable attempt id
     */
    public static String attemptHash(Integer tokenId, String attempt) {
        String valid = validAttempt(attempt);
        return tokenId == null || valid == null ? null : CryptoUtil.getSHA256String(tokenId + ":" + valid);
    }

    /**
     * The device's credential for a resumed enrollment: the same secret its first answer carried (derived, see
     * {@link #deriveSecret}), so a late duplicate of a request, or a retry, never replaces a secret the agent may have
     * saved. Only when it doesn't match what is stored (the enrollment key changed in between) is the derived one
     * stored.
     *
     * @return the outcome, or null when the attempt no longer owns the device (it checked in meanwhile)
     */
    private Outcome resume(String deviceNumber, String attemptHash, String secret) {
        String hash = CryptoUtil.getSHA256String(secret);
        if (hash.equals(deviceMapper.getDeviceSecretHash(deviceNumber))
                || deviceMapper.reissueSecret(deviceNumber, attemptHash, hash) > 0) {
            return Outcome.resumed(deviceNumber, secret);
        }
        return null;
    }

    /**
     * The secret of a device enrolled with an attempt id: HMAC-SHA256 keyed with the server-only
     * {@link AgentEnrollmentKey}, over a version label, the device number and the raw attempt id, as 64 lowercase hex
     * characters. Deterministic, so every request for one attempt (a retry, or the original arriving late, on any
     * server instance, before or after a restart) gets the same, working credential. It needs both the key (random,
     * only in the database) and the attempt id (only on the agent and in the request that carries it); the database
     * keeps neither the attempt id nor the secret, only hashes. If the key is rotated during an enrollment's retry
     * window, the resume stores the newly derived secret (see {@link #resume}).
     */
    static String deriveSecret(byte[] key, String deviceNumber, String attempt) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            // Domain separation: the label names this use and its version; "\n" can't occur in either value.
            byte[] digest = mac.doFinal(("mdmesh-enroll-secret-v1\n" + deviceNumber + "\n" + attempt)
                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b & 0xff));
            }
            return hex.toString();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    /** A random device secret: 256 bits as 64 lowercase hex characters. */
    static String randomSecret() {
        return UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * Enrolls a device with token {@code tokenId}, or resumes the enrollment {@code attempt} already made. The
     * outcome carries the device's secret (derived from the attempt when there is one, else random).
     *
     * @param customerId       the token's customer (fixed when it was made)
     * @param configurationId  the token's configuration, or null (the customer's setting for new devices)
     * @param deviceNumber     the number for a new device
     * @param capabilitiesJson the agent's capability matrix, stored on the device
     * @param hardwareId       the agent's stable hardware id, or null
     * @param attempt          the agent's raw enroll attempt id, or null (an agent that sends none, or an unusable one)
     * @param resumeOnly       the agent's credential recovery: resume this attempt's device or answer NOT_RESUMABLE,
     *                         never create one or take a use
     * @throws CreationRefusedException settings refused the device (rolled back, the use not spent)
     * @throws DeviceLimitException     the customer has reached its device limit (rolled back, the use not spent)
     * @throws RuntimeException         a database failure, including the token lock not obtained in time (rolled back)
     */
    @Transactional
    public Outcome enroll(int tokenId, int customerId, Integer configurationId, String deviceNumber,
                          String capabilitiesJson, String hardwareId, String attempt, boolean resumeOnly) {
        long now = System.currentTimeMillis();
        String attemptHash = attemptHash(tokenId, attempt);
        // Before any lock: on first use the key is made on a connection of its own, which must not be awaited while
        // this transaction holds the token's row or the customer's lock.
        byte[] key = attemptHash == null ? null : enrollmentKey.bytes();
        // Enrolls of one token run one after another; a request that waits longer than this fails retryably.
        tokenMapper.setEnrollLockTimeout(lockTimeoutMs + "ms");
        AgentEnrollmentToken locked = tokenMapper.lockForEnroll(tokenId);
        if (locked == null || locked.getRevokedAt() != null) {
            // Gone, or stopped: read under the lock, so a stop that committed while this request waited (after the
            // caller's own check) is seen. A stopped code resumes nothing and enrolls nothing.
            return Outcome.noUse();
        }
        if (attemptHash != null) {
            // Under the lock, so a retry that waited for the first request sees what it committed.
            String resumable = deviceMapper.findEnrollRetry(attemptHash, now - RETRY_WINDOW_MS);
            if (resumable != null) {
                Outcome resumed = resume(resumable, attemptHash, deriveSecret(key, resumable, validAttempt(attempt)));
                if (resumed != null) {
                    return resumed;
                }
            }
        }
        if (resumeOnly) {
            // The agent's recovery: it may only get back the device its attempt made, never a new one (that device
            // was deleted, checked in, or is past the window, or the request carries no usable attempt).
            return Outcome.notResumable();
        }
        // The claim first: a used-up, stopped or expired code gets its final answer, never the device limit's
        // retryable one. A refusal below rolls the claim back, so no use is spent.
        if (tokenMapper.claim(tokenId, now) != 1) {
            return Outcome.noUse();
        }
        // A new device: within the customer's device limit. A resumed enrollment (above) adds no device, so it is never
        // counted again.
        enforceDeviceLimit(customerId);
        Device device = deviceFactory.create(deviceNumber, customerId, configurationId);
        if (device == null) {
            throw new CreationRefusedException();
        }
        // Derived from the attempt when there is one, so every answer to this attempt carries the same secret.
        String secret = attemptHash == null ? randomSecret() : deriveSecret(key, deviceNumber, validAttempt(attempt));
        if (deviceMapper.completeEnrollment(deviceNumber, CryptoUtil.getSHA256String(secret), capabilitiesJson,
                hardwareId, attemptHash, now) != 1) {
            throw new IllegalStateException("device " + deviceNumber + " not found to complete its enrollment");
        }
        return Outcome.created(device, secret);
    }

    /**
     * Refuses a new device when the customer has as many as its limit allows, by the console's rule
     * (DeviceResource, CommonDAO.loadCustomerSettings): only on an install with several customers (the first customer
     * carries a default limit nobody set), none for a master customer, 0 means no limit, and a negative one blocks.
     *
     * <p>Counted under {@link CustomerDeviceLimit}'s per-customer lock, which console and on-demand device creation
     * take too, so adds through any path (and enrolls through any codes) can't together pass the limit. No row is
     * locked; taken only when a positive limit applies.</p>
     */
    private void enforceDeviceLimit(int customerId) {
        if (!deviceMapper.hasSeveralCustomers()) {
            return;
        }
        Integer limit = deviceMapper.customerDeviceLimit(customerId);
        if (limit == null || limit == 0) {
            return;
        }
        if (!CustomerDeviceLimit.roomForOne(limit, () -> deviceMapper.lockCustomerEnrollments(customerId),
                () -> deviceMapper.countCustomerDevices(customerId))) {
            throw new DeviceLimitException(limit);
        }
    }
}
