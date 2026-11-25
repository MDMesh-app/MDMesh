/*
 *
 * Headwind MDM: Open Source Android MDM Software
 * https://h-mdm.com
 *
 * Copyright (C) 2019 Headwind Solutions LLC (http://h-sms.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.hmdm.rest.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdm.persistence.AgentCommandDAO;
import com.hmdm.persistence.AgentEnrollmentDAO;
import com.hmdm.persistence.DeviceGoneException;
import com.hmdm.persistence.AgentEnrollmentTokenDAO;
import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.AgentCommand;
import com.hmdm.persistence.domain.AgentEnrollmentToken;
import com.hmdm.persistence.domain.Configuration;
import com.hmdm.persistence.domain.Device;
import com.hmdm.persistence.domain.DeviceEvent;
import com.hmdm.persistence.domain.DeviceState;
import com.hmdm.rest.json.agent.AgentDeviceState;
import com.hmdm.rest.json.Response;
import com.hmdm.rest.json.agent.AgentCapabilities;
import com.hmdm.rest.json.agent.AgentCheckInRequest;
import com.hmdm.rest.json.agent.AgentCheckInResponse;
import com.hmdm.rest.json.agent.AgentCommandResult;
import com.hmdm.rest.json.agent.AgentEnrollRequest;
import com.hmdm.rest.json.agent.AgentEnrollResponse;
import com.hmdm.rest.json.agent.AgentProtocol;
import com.hmdm.rest.json.agent.AgentTelemetryEvent;
import com.hmdm.util.AgentCapabilityTokens;
import com.hmdm.util.CryptoUtil;
import com.hmdm.util.WindowLimiter;
import com.hmdm.util.DesiredConfigBuilder;
import com.hmdm.util.InstallIntegrity;
import com.hmdm.util.RolloutProgress;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.apache.ibatis.exceptions.PersistenceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.ws.rs.Consumes;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * <p>Public-facing resource for the from-scratch Android agent v1 protocol. Mirrors
 * {@code proto/endpoints.md}. Command {@code type}/{@code payload} are entirely opaque: this
 * resource never switches on them. Delivery is gated only by capability-token set membership
 * (see {@link AgentCapabilityTokens}).</p>
 */
@Singleton
@Path("/public/agent/v1")
@Api(tags = {"Agent v1"})
@com.hmdm.rest.filter.AgentBodyLimit
public class AgentResource {

    private static final Logger logger = LoggerFactory.getLogger(AgentResource.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Accepted command-result statuses (mirrors proto CommandStatus). Anything else is ignored. */
    private static final Set<String> ALLOWED_RESULT_STATUS = new HashSet<>(Arrays.asList(
            "accepted", "done", "failed", "unsupported", "expired"));

    /** The factory reset. Delivering it marks the device wiped: it erases itself before it can report. */
    static final String WIPE_COMMAND = "device.wipe";
    // A wiped device never checks in again under its old identity, so one that does, this long after its wipe was
    // delivered, didn't erase itself. The grace covers a check-in the agent may still make before the reset starts.
    static final long WIPE_GRACE_MS = 5L * 60L * 1000L;

    // Ingestion caps: an authenticated device is still untrusted input. These bound what a single
    // check-in (potentially every 5s in foreground mode) can write into the database.
    // Past this count the server stops and reports how many events it took (eventsAccepted, protocol 1.12), so the
    // agent resends the rest. Older agents ignore that and count their whole batch delivered: they send up to 500,
    // their whole buffer, so this cap takes all of it.
    static final int MAX_EVENTS_PER_CHECKIN = 500;
    private static final int MAX_EVENT_DETAIL_CHARS = 4000;
    /** device_event.type is VARCHAR(64); the agent's types are short names. */
    private static final Pattern EVENT_TYPE = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
    /** devices.hardwareid is VARCHAR(128). */
    private static final int MAX_HARDWARE_ID_CHARS = 128;
    /** device_state's androidRelease, agentVersion and powerMode columns. */
    private static final int MAX_ANDROID_RELEASE_CHARS = 32;
    private static final int MAX_AGENT_VERSION_CHARS = 64;
    private static final int MAX_POWER_MODE_CHARS = 16;
    private static final int MAX_TELEMETRY_CHARS = 256 * 1024;
    /**
     * device_state.reportedImeis: the first 4 identity.imei strings of 1-32 printable ASCII characters (one IMEI per
     * SIM slot). The update that added the column fills it from stored telemetry by the same rules.
     */
    private static final int MAX_REPORTED_IMEIS = 4;
    private static final Pattern REPORTED_IMEI = Pattern.compile("[!-~]{1,32}");
    // Each result is one UPDATE. Current agents send at most 1000 per check-in; the rest of a backlog follows on the
    // next ones. Past this count the server stops and reports how many it took (resultsAccepted), so the agent
    // resends the remainder rather than losing it. Older agents that send everything at once never get near it.
    static final int MAX_RESULTS_PER_CHECKIN = 1000;
    /**
     * The enroll failed on the server; its transaction rolled back, so nothing was kept and the code's use is not spent.
     * Agents retry it (it matches none of their final markers: token.used/invalid/expired, enrollment.disabled).
     */
    static final String ENROLLMENT_FAILED = "error.agent.enrollment.failed";
    /**
     * The enrollment couldn't get its locks in time (its code's, held by another enrollment with it, often the request
     * this one retries; the customer's, held by another device being added; or a row a console transaction held), or
     * too many enrollments with its code are running. Retryable like {@link #ENROLLMENT_FAILED}: nothing was kept, and
     * the next retry resumes or goes through.
     */
    static final String ENROLLMENT_IN_PROGRESS = "error.agent.enrollment.inProgress";
    /**
     * The token's customer has as many devices as its limit allows (installs with several customers). Agents retry it
     * (no final marker), so the device enrolls once there is room.
     */
    static final String DEVICE_LIMIT = "error.agent.enrollment.deviceLimit";
    /**
     * A resume-only request (the agent's credential recovery) found no device of its attempt to resume: deleted,
     * checked in, or past the retry window. Final for the recovery; nothing was created and no use taken.
     */
    static final String NOT_RESUMABLE = "error.agent.enrollment.notResumable";
    /**
     * Enrollments of one code allowed at once (waiting on its lock), per server instance (the count lives in this JVM);
     * more get ENROLLMENT_IN_PROGRESS at once. It bounds one code's burst, not the pool: see PerKeyLimit.
     */
    static final int MAX_ENROLLS_PER_CODE = 8;
    private static final com.hmdm.rest.resource.support.PerKeyLimit<Integer> enrollsPerCode =
            new com.hmdm.rest.resource.support.PerKeyLimit<>(MAX_ENROLLS_PER_CODE);
    // Details are short diagnostics, except apps.scan / apps.icons, which return JSON: the console asks for icons in
    // batches of 24 (96 px PNG, base64), well under 1 MiB even for incompressible icons. A larger detail is not
    // stored (cut JSON would be useless); the command is marked failed with a short note instead.
    static final int MAX_RESULT_DETAIL_CHARS = 2 * 1024 * 1024;
    // Location provider names as Android reports them ("gps", "network", "fused", ...). Fits device_location.provider.
    private static final java.util.regex.Pattern PROVIDER_NAME = java.util.regex.Pattern.compile("[A-Za-z0-9_.-]{1,32}");

    private UnsecureDAO unsecureDAO;
    private AgentEnrollmentTokenDAO tokenDAO;
    private AgentCommandDAO commandDAO;
    private com.hmdm.rest.resource.support.ConfigAppInstaller configAppInstaller;
    private com.hmdm.rest.resource.support.ConfigReconciler configReconciler;
    private com.hmdm.rest.resource.support.AppReconciler appReconciler;
    private com.hmdm.rest.resource.support.RolloutRequeuer rolloutRequeuer;
    private com.hmdm.rest.resource.support.InstallHashes installHashes;
    private com.hmdm.rest.resource.support.KioskLockoutAlert kioskLockoutAlert;
    private AgentEnrollmentDAO enrollments;

    /**
     * <p>A constructor required by Swagger.</p>
     */
    public AgentResource() {
    }

    @Inject
    public AgentResource(UnsecureDAO unsecureDAO,
                         AgentEnrollmentTokenDAO tokenDAO,
                         AgentCommandDAO commandDAO,
                         com.hmdm.rest.resource.support.ConfigAppInstaller configAppInstaller,
                         com.hmdm.rest.resource.support.ConfigReconciler configReconciler,
                         com.hmdm.rest.resource.support.AppReconciler appReconciler,
                         com.hmdm.rest.resource.support.RolloutRequeuer rolloutRequeuer,
                         com.hmdm.rest.resource.support.InstallHashes installHashes,
                         com.hmdm.rest.resource.support.KioskLockoutAlert kioskLockoutAlert,
                         AgentEnrollmentDAO enrollments) {
        this.unsecureDAO = unsecureDAO;
        this.tokenDAO = tokenDAO;
        this.commandDAO = commandDAO;
        this.configAppInstaller = configAppInstaller;
        this.configReconciler = configReconciler;
        this.appReconciler = appReconciler;
        this.rolloutRequeuer = rolloutRequeuer;
        this.installHashes = installHashes;
        this.kioskLockoutAlert = kioskLockoutAlert;
        this.enrollments = enrollments;
    }

    // =================================================================================================================
    @ApiOperation(value = "Enroll an agent", notes = "Validates an enrollment token, uses one of its uses, and registers the device.")
    @POST
    @Path("/enroll")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response enroll(AgentEnrollRequest request) {
        if (request == null || request.getEnrollToken() == null || request.getEnrollToken().trim().isEmpty()) {
            return Response.ERROR("error.agent.token.invalid");
        }

        AgentEnrollmentToken token = tokenDAO.findByToken(request.getEnrollToken());
        if (token == null) {
            // A code that was never made, was made on another server, or mistyped. DEBUG: scanners would fill the log.
            logger.debug("Agent enroll refused: unknown code");
            return Response.ERROR("error.agent.token.invalid");
        }
        if (token.getRevokedAt() != null) {
            // Stopped by an admin, and not resumed either. Answered as used up: every agent, older ones too, stops
            // retrying on that (an unknown error would be retried forever), and it says what matters, that this code
            // enrolls nothing more. Every refusal is logged with its reason, never the code.
            logger.info("Agent enroll refused: token {} was stopped", token.getId());
            return Response.ERROR("error.agent.token.used");
        }

        String hardwareId = storable("Enroll with token " + token.getId(), "hardware id", request.getHardwareId(),
                MAX_HARDWARE_ID_CHARS);
        // Enrolls of one code wait on its lock one after another, each holding a pooled connection: past a few, turn
        // the rest away now (retryable) instead of letting one code's burst take the pool.
        if (!enrollsPerCode.tryEnter(token.getId())) {
            logger.info("Agent enroll with token {}: {} already running, asked to retry", token.getId(),
                    MAX_ENROLLS_PER_CODE);
            return Response.ERROR(ENROLLMENT_IN_PROGRESS);
        }
        AgentEnrollmentDAO.Outcome outcome;
        try {
            // One transaction (AgentEnrollmentDAO): all of it or nothing, enrolls of this token one after another.
            // The device's secret is the DAO's: returned once, below, and only its hash is stored.
            outcome = enrollments.enroll(token.getId(), token.getCustomerId(), token.getConfigurationId(),
                    UUID.randomUUID().toString(), capabilitiesJson(request.getCapabilities()), hardwareId,
                    request.getEnrollAttempt(), Boolean.TRUE.equals(request.getResumeOnly()));
        } catch (AgentEnrollmentDAO.CreationRefusedException e) {
            // Named error (not a generic internal one) — this exact failure cost a debugging
            // session: valid token, reachable server, no device row.
            logger.warn("Agent enroll: on-demand device creation is disabled by settings (token {})", token.getId());
            return Response.ERROR("error.agent.enrollment.disabled");
        } catch (AgentEnrollmentDAO.DeviceLimitException e) {
            // Retryable for the agent: once an admin raises the limit or removes a device, the device enrolls.
            logger.warn("Agent enroll refused: customer {} has reached its device limit ({}) (token {})",
                    token.getCustomerId(), e.limit, token.getId());
            return Response.ERROR(DEVICE_LIMIT);
        } catch (RuntimeException e) {
            if (isLockTimeout(e)) {
                logger.info("Agent enroll with token {}: the enrollment couldn't get its locks in time; asked to retry",
                        token.getId());
                return Response.ERROR(ENROLLMENT_IN_PROGRESS);
            }
            // Rolled back: nothing kept, the use not spent; the agent retries. The class and SQL state only (a driver
            // message can echo values).
            logger.error("Agent enroll with token {} failed and was rolled back: {}{}", token.getId(),
                    e.getClass().getSimpleName(), sqlState(e).map(st -> " (SQL state " + st + ")").orElse(""));
            return Response.ERROR(ENROLLMENT_FAILED);
        } finally {
            enrollsPerCode.exit(token.getId());
        }

        switch (outcome.kind) {
            case NOT_RESUMABLE:
                // The agent's recovery found nothing of its own to resume: final for the recovery, no use taken.
                logger.info("Agent enroll refused: nothing to resume for this attempt (token {})", token.getId());
                return Response.ERROR(NOT_RESUMABLE);
            case NO_USE:
                if (token.getExpiresAt() != null && token.getExpiresAt() <= System.currentTimeMillis()
                        && !token.isUsed()) {
                    logger.info("Agent enroll refused: token {} expired at {}", token.getId(),
                            java.time.Instant.ofEpochMilli(token.getExpiresAt()));
                    return Response.ERROR("error.agent.token.expired");
                }
                logger.info("Agent enroll refused: token {} has no use left (all {} used, or stopped or expired since)",
                        token.getId(), token.getMaxUses() == null ? 1 : token.getMaxUses());
                return Response.ERROR("error.agent.token.used");
            case RESUMED: {
                logger.info("Agent enroll retried: device {} (token {}) gets its credentials again",
                        outcome.deviceNumber, token.getId());
                Device device = unsecureDAO.getDeviceByNumber(outcome.deviceNumber);
                return Response.OK(resumable(new AgentEnrollResponse(outcome.deviceNumber,
                        device == null ? null : configurationName(device), outcome.secret)));
            }
            case CREATED:
            default: {
                Device device = outcome.device;
                // After the commit, best effort: queue the configuration's action=install apps so a config acts as a
                // golden image. Failures are logged inside; the enrollment stands either way.
                int queuedApps = 0;
                try {
                    queuedApps = configAppInstaller.enqueueConfigApps(device);
                } catch (RuntimeException e) {
                    logger.warn("Agent enroll: config apps for device {} not queued: {}", device.getNumber(),
                            e.getClass().getSimpleName());
                }
                // The token id says which code enrolled the device: with a code for a batch, what to look for when it
                // leaked (the mint and revoke-all are in the audit log).
                logger.info("Agent enrolled device {} with token {} (configuration {}, {} config apps queued)",
                        device.getNumber(), token.getId(), device.getConfigurationId(), queuedApps);
                return Response.OK(resumable(new AgentEnrollResponse(device.getNumber(), configurationName(device),
                        outcome.secret)));
            }
        }
    }

    /** Tells the agent this server resumes enrollments (resumeOnly, protocol 1.9), so it may recover credentials. */
    private static AgentEnrollResponse resumable(AgentEnrollResponse response) {
        response.setResumeSupported(Boolean.TRUE);
        return response;
    }

    /** A PostgreSQL lock_timeout (SQL state 55P03) anywhere in the cause chain. */
    static boolean isLockTimeout(Throwable e) {
        return sqlState(e).map("55P03"::equals).orElse(false);
    }

    private static java.util.Optional<String> sqlState(Throwable e) {
        for (Throwable t = e; t != null && t.getCause() != t; t = t.getCause()) {
            if (t instanceof java.sql.SQLException && ((java.sql.SQLException) t).getSQLState() != null) {
                return java.util.Optional.of(((java.sql.SQLException) t).getSQLState());
            }
        }
        return java.util.Optional.empty();
    }

    /**
     * What identifies a retryable enrollment: the SHA-256 of the token id and the agent's attempt id, so the stored
     * value reveals neither and an attempt id only resumes enrollments made with the same code.
     *
     * @return the hash, or null when the request carries no usable attempt id
     */
    static String enrollAttemptHash(Integer tokenId, String attempt) {
        return AgentEnrollmentDAO.attemptHash(tokenId, attempt);
    }

    /** The device's configuration name for the enroll response; best effort (the response is still useful without). */
    private String configurationName(Device device) {
        if (device.getConfigurationId() == null) {
            return null;
        }
        try {
            Configuration configuration = unsecureDAO.getConfigurationById(device.getConfigurationId());
            return configuration == null ? null : configuration.getName();
        } catch (RuntimeException e) {
            logger.warn("Agent enroll: configuration {} name not read: {}", device.getConfigurationId(), e.getClass().getSimpleName());
            return null;
        }
    }

    /** A desired-state revision is a lowercase hex SHA-256 (the column is VARCHAR(64)). */
    private static final java.util.regex.Pattern REVISION_PATTERN = java.util.regex.Pattern.compile("^[0-9a-f]{64}$");

    /** @return the reported revision when well-formed, else null (an oversized value would break the upsert). */
    static String validRevision(String deviceNumber, String reported) {
        if (reported == null) return null;
        if (REVISION_PATTERN.matcher(reported).matches()) return reported;
        logger.debug("Device {} reported a malformed appliedConfigRevision ({} chars) — ignored",
                deviceNumber, reported.length());
        return null;
    }

    // =================================================================================================================
    @ApiOperation(value = "Agent check-in", notes = "Refreshes capabilities, acks results, returns gated commands.")
    @POST
    @Path("/checkin")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response checkin(@HeaderParam("Authorization") String authorization,
                            @javax.ws.rs.core.Context javax.servlet.http.HttpServletRequest httpRequest,
                            AgentCheckInRequest request) {
        if (request == null || request.getDeviceId() == null) {
            return Response.ERROR("error.agent.device.unknown");
        }

        String deviceNumber = request.getDeviceId();
        Device device = unsecureDAO.getDeviceByNumber(deviceNumber);
        // One indistinguishable error for unknown-device AND bad-secret: a distinguishable pair
        // is an enumeration oracle for probing valid device numbers on a public endpoint.
        if (device == null) {
            return Response.ERROR("error.agent.unauthorized");
        }

        // Authenticate the device by its per-device secret BEFORE any state change.
        // The body-supplied deviceId is untrusted until this passes (prevents IDOR/spoofing).
        if (!authenticate(authorization, deviceNumber)) {
            return Response.ERROR("error.agent.unauthorized");
        }

        try {
            return checkinAuthenticated(device, httpRequest, request);
        } catch (DeviceGoneException e) {
            // Deleted while this check-in ran: answered as a device that is unknown now (its history foreign keys
            // refused the write, so nothing of it is left behind).
            logger.warn("Agent check-in: device {} was deleted during its check-in", deviceNumber);
            return Response.ERROR("error.agent.unauthorized");
        }
    }

    /** {@link #checkin} once the device is authenticated. */
    private Response checkinAuthenticated(Device device, javax.servlet.http.HttpServletRequest httpRequest,
                                          AgentCheckInRequest request) {
        String deviceNumber = device.getNumber();

        // Before any result is recorded: a config.apply delivered before this moment and still open afterwards is
        // one the agent is not running (ConfigReconciler).
        long receivedAt = System.currentTimeMillis();

        // Stamp last-seen so the admin UI shows the device online.
        commandDAO.touchLastUpdate(deviceNumber);

        if (device.getWipedAt() != null && receivedAt - device.getWipedAt() > WIPE_GRACE_MS
                && commandDAO.clearWipedBefore(deviceNumber, receivedAt - WIPE_GRACE_MS)) {
            logger.info("Device {} checked in after its wipe was delivered, so it wasn't wiped: unmarked", deviceNumber);
        }

        // Backfill the stable hardware id for already-enrolled devices (set once / when it changes).
        String hardwareId = storable("Device " + deviceNumber, "hardware id", request.getHardwareId(),
                MAX_HARDWARE_ID_CHARS);
        if (hardwareId != null && !hardwareId.equals(device.getHardwareId())) {
            commandDAO.updateHardwareId(deviceNumber, hardwareId);
        }

        // Refresh the stored capability matrix (kept fresh every check-in). Computed once here and
        // reused for command gating below — no need to read back what we just wrote.
        String capsJson = null;
        if (request.getCapabilities() != null) {
            capsJson = capabilitiesJson(request.getCapabilities());
            if (capsJson != null) {
                commandDAO.updateDeviceCapabilities(deviceNumber, capsJson);
            } else if (request.getCapabilities().getTree() != null) {
                logger.warn("Device {} capability tree exceeds the size limits — ignored, keeping the stored one",
                        deviceNumber);
            }
        }

        // Persist the latest device-state snapshot (powers the admin console).
        String appliedRevision = validRevision(deviceNumber,
                request.getState() == null ? null : request.getState().getAppliedConfigRevision());
        // A state value the database can't store would fail its write, and with it every check-in from the device.
        String agentVersion = request.getState() == null ? null : storable("Device " + deviceNumber, "agentVersion",
                request.getState().getAgentVersion(), MAX_AGENT_VERSION_CHARS);
        if (request.getState() != null) {
            AgentDeviceState s = request.getState();
            String androidRelease = storable("Device " + deviceNumber, "androidRelease", s.getAndroidRelease(),
                    MAX_ANDROID_RELEASE_CHARS);
            DeviceState row = new DeviceState();
            row.setDeviceNumber(deviceNumber);
            row.setBattery(s.getBattery());
            row.setCharging(s.getCharging());
            row.setLocked(s.getLocked());
            row.setKioskActive(s.getKioskActive());
            row.setAndroidRelease(androidRelease);
            row.setLastBootAt(s.getLastBootAt());
            row.setAgentVersion(agentVersion);
            row.setPowerMode(storable("Device " + deviceNumber, "powerMode", s.getPowerMode(), MAX_POWER_MODE_CHARS));
            row.setAppliedConfigRevision(appliedRevision);
            row.setAppliedConfigAt(null);
            // The server (not the device) knows the public IP — inject it into the census JSON.
            JsonNode tel = request.getTelemetry();
            if (tel != null && tel.isObject()) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) tel).put("publicIp", clientIp(httpRequest));
            }
            dropLocationWhenNotTracked(device, tel);
            dropUnknownLocationProvider(tel);
            // An authenticated device can still be hostile/buggy: cap the stored blob so a single
            // client can't bloat device_state via the 5s foreground poll.
            String telJson = tel == null ? null : tel.toString();
            if (telJson != null && telJson.length() > MAX_TELEMETRY_CHARS) {
                logger.warn("Device {} telemetry oversized ({} chars) — dropped", deviceNumber, telJson.length());
                telJson = null;
            }
            row.setTelemetry(telJson);
            row.setReportedImeis(reportedImeis(tel));
            row.setUpdatedAt(System.currentTimeMillis());
            commandDAO.upsertState(row);
            // Mirror the Android version into the device row (infojson) so the device LIST — which
            // reads infojson->>'androidVersion', not the state table — shows it.
            if (androidRelease != null && !androidRelease.isEmpty()) {
                commandDAO.updateAndroidVersion(deviceNumber, androidRelease);
            }
            // Append the reported location (dynamic.location) to the device's breadcrumb trail.
            recordLocation(deviceNumber, tel);
        }

        int eventsAccepted = ingestEvents(device, request.getEvents(),
                AgentProtocol.atLeast(request.getProtocolVersion(), 1, 12));

        // Ack results from previously-delivered commands.
        int resultsAccepted = 0;
        if (request.getResults() != null) {
            List<AgentCommandResult> results = request.getResults();
            if (results.size() > MAX_RESULTS_PER_CHECKIN) {
                logger.info("Device {} sent {} results in one check-in — taking the first {}, the agent resends the rest",
                        deviceNumber, results.size(), MAX_RESULTS_PER_CHECKIN);
                results = results.subList(0, MAX_RESULTS_PER_CHECKIN);
            }
            resultsAccepted = results.size();
            for (AgentCommandResult result : results) {
                if (result == null || result.getCommandId() == null || result.getStatus() == null) {
                    continue;
                }
                // Allowlist the status so a device can't write arbitrary values into the queue.
                if (!ALLOWED_RESULT_STATUS.contains(result.getStatus())) {
                    continue;
                }
                Integer commandId = parseCommandId(result.getCommandId());
                if (commandId == null) {
                    continue;
                }
                // Ownership is enforced inside the UPDATE's WHERE (id + deviceNumber) — no
                // per-result pre-SELECT needed on the hot path.
                String status = result.getStatus();
                String detail = withoutNul(result.getDetail());
                if (detail != null && detail.length() > MAX_RESULT_DETAIL_CHARS) {
                    logger.warn("Device {} result detail for command {} oversized ({} chars) — stored as failed",
                            deviceNumber, commandId, detail.length());
                    status = "failed";
                    detail = "result too large (" + detail.length() + " chars)";
                }
                commandDAO.markResultWithTime(deviceNumber, commandId, status,
                        detail, System.currentTimeMillis());
                if ("done".equals(status)) {
                    // A completed config.apply records which agent version applied it (config resync baseline).
                    commandDAO.recordConfigApplyDone(deviceNumber, commandId);
                } else if (device.getWipedAt() != null && ("failed".equals(status) || "unsupported".equals(status))
                        && commandDAO.clearWipedForCommand(deviceNumber, commandId)) {
                    logger.info("Device {} reported its wipe (command {}) {}: no longer marked wiped",
                            deviceNumber, commandId, status);
                }
            }
        }

        // Lazily expire commands nobody acted on before delivering more. Undelivered (pending)
        // commands age out by creation (60 min — the doze-proof heartbeat ~10 min reaches a parked
        // device well before that). DELIVERED commands age by delivery time with a much longer
        // leash (6 h): the device HAS them — a slow install on metered network must not be
        // expired out from under its own genuine result.
        commandDAO.expireStale(deviceNumber, 60L * 60L * 1000L, 6L * 60L * 60L * 1000L);

        // Gate pending commands by capability-token set membership, reusing the matrix this very
        // request carried (fall back to the stored copy only when the agent omitted it).
        Set<String> deviceTokens = AgentCapabilityTokens.flatten(
                capsJson != null ? capsJson : commandDAO.getDeviceCapabilities(deviceNumber));
        long now = System.currentTimeMillis();

        String configAgentVersion = null;
        String appsRevision = null;
        boolean configCapable = AgentCapabilityTokens.isAllowed(DesiredConfigBuilder.CAPABILITY, deviceTokens);
        if (configCapable || AgentCapabilityTokens.isAllowed(RolloutProgress.INSTALL_CAPABILITY, deviceTokens)) {
            // Only capable agents pay for this read: its values are consumed solely by the reconcilers, which ignore
            // the others. A check-in that omitted the revision (state block absent, or invalid value) falls back
            // to the last stored value so a missing field never masquerades as drift.
            com.hmdm.persistence.domain.DeviceState stored = commandDAO.getState(deviceNumber);
            if (configCapable) {
                if (appliedRevision == null) {
                    appliedRevision = stored == null ? null : stored.getAppliedConfigRevision();
                }
                configAgentVersion = stored == null ? null : stored.getConfigAgentVersion();
            }
            appsRevision = stored == null ? null : stored.getAppsRevision();
        }

        // Desired-state reconciliation: queue config.apply when the device drifted from its configuration (or now
        // runs a different agent version), so the command rides THIS response instead of waiting for the next cycle.
        configReconciler.reconcile(device, deviceTokens, appliedRevision, agentVersion, configAgentVersion, now,
                receivedAt);
        // Configuration apps: queue app.install for the apps its configuration gained (an app added, a new version, a
        // move to another configuration) that the device doesn't have yet, so they ride this response too.
        appReconciler.reconcile(device, deviceTokens, appsRevision, now);
        // A staged agent update whose command expired (its result was lost) or was never queued: queue it again here.
        rolloutRequeuer.requeue(device, now);

        List<AgentCommand> pending = commandDAO.listPending(deviceNumber);
        List<com.hmdm.rest.json.agent.AgentCommand> commands = new ArrayList<>();
        for (AgentCommand stored : pending) {
            if (!AgentCapabilityTokens.isAllowed(stored.getRequiresCapability(), deviceTokens)) {
                continue;
            }
            // An app.install queued before hashes were required has none: give it one now (this response tells the
            // agent to refuse an install without), or end it with the reason when there is definitely none. A lookup
            // that failed (the database, say) leaves it pending for the next check-in.
            try {
                stored.setPayload(installHashes.forDelivery(stored, device.getCustomerId()));
            } catch (InstallIntegrity.LookupFailedException e) {
                logger.warn("Command {} for device {} held back: {}", stored.getId(), deviceNumber, e.toString());
                continue;
            } catch (IllegalArgumentException e) {
                commandDAO.markResultWithTime(deviceNumber, stored.getId(), "failed", e.getMessage(), now);
                continue;
            }
            // Atomically claim it so a concurrent check-in can't deliver the same command twice.
            if (commandDAO.claimForDelivery(stored, now)) {
                commands.add(toWire(stored));
                if (WIPE_COMMAND.equals(stored.getType())) {
                    commandDAO.markWiped(deviceNumber, now);
                    logger.info("Device {} received its wipe (command {}): marked wiped", deviceNumber, stored.getId());
                }
            }
        }

        AgentCheckInResponse response = new AgentCheckInResponse(commands);
        response.setResultsAccepted(resultsAccepted);
        response.setEventsAccepted(eventsAccepted);
        return Response.OK(response);
    }

    /**
     * Stores the check-in's buffered lifecycle events in the timeline and returns how many of them, counted from the
     * first, it consumed (protocol 1.12 {@code eventsAccepted}): the agent drops those and resends the rest. Capped, so
     * one check-in can't flood device_event. An event with no valid type is skipped, and so is one whose data the
     * database refuses (SQL state class 22 or 23): both count as consumed, so the agent doesn't resend them forever. Any
     * other database error (a lost connection, a failover) is not the event's fault: the events stop there, uncounted,
     * and the agent sends them again. The rest of the check-in goes on, so a short blip doesn't cost its results. Only
     * when {@code countsAccepted}, though: an agent before protocol 1.12 ignores {@code eventsAccepted} and drops its
     * whole batch on a successful check-in, so for it the error fails the check-in, as it did before 1.12, and the agent
     * sends the batch again.
     *
     * <p>The events go in as one transaction ({@link AgentCommandDAO#insertEvents}): one commit, and on a lost
     * connection nothing half-stored for the agent to send twice. Only when the database refuses that batch's data does
     * it store them one by one, to skip just the refused ones. That fallback runs outside any transaction on purpose:
     * each row commits on its own, so a refused row can't abort the rows after it. So this must not be called inside
     * an enclosing transaction, where the first refused row would fail every later one (SQL state 25P02, read here as a
     * lost database).</p>
     */
    private int ingestEvents(Device device, List<AgentTelemetryEvent> events, boolean countsAccepted) {
        if (events == null) {
            return 0;
        }
        String deviceNumber = device.getNumber();
        List<DeviceEvent> batch = new ArrayList<>();
        // Where each batch row is in the request: the prefix the agent may drop is counted in request positions.
        List<Integer> positions = new ArrayList<>();
        int consumable = events.size();
        int invalid = 0;
        String firstInvalid = null;
        for (int i = 0; i < events.size(); i++) {
            AgentTelemetryEvent e = events.get(i);
            if (e == null || e.getType() == null || !EVENT_TYPE.matcher(e.getType()).matches()) {
                if (invalid++ == 0) {
                    firstInvalid = e == null ? null : e.getType();
                }
                continue;
            }
            if (batch.size() == MAX_EVENTS_PER_CHECKIN) {
                logger.info("Device {} sent more than {} events in one check-in: the agent resends the rest",
                        deviceNumber, MAX_EVENTS_PER_CHECKIN);
                consumable = i;
                break;
            }
            batch.add(eventRow(deviceNumber, e));
            positions.add(i);
        }
        if (invalid > 0) {
            logger.warn("Device {} sent {} events without a valid type, skipped (the first: {})", deviceNumber,
                    invalid, firstInvalid == null ? "no type" : "\"" + loggable(firstInvalid, 40) + "\"");
        }
        if (batch.isEmpty()) {
            return consumable;
        }
        try {
            commandDAO.insertEvents(deviceNumber, batch);
        } catch (PersistenceException ex) {
            if (!isDataError(ex)) {
                return eventsStopped(deviceNumber, ex, batch.get(0).getType(), positions.get(0), countsAccepted);
            }
            logger.info("Device {} events refused as a batch (SQL state {}): storing them one by one", deviceNumber,
                    sqlState(ex).orElse(null));
            return ingestOneByOne(device, batch, positions, consumable, countsAccepted);
        }
        for (DeviceEvent row : batch) {
            kioskLockoutAlert.onEvent(device, row.getType(), row.getDetail());
        }
        return consumable;
    }

    /**
     * {@link #ingestEvents}'s fallback once the database refused the batch's data: each row on its own, outside any
     * transaction, skipping the refused ones. Returns what {@link #ingestEvents} returns.
     */
    private int ingestOneByOne(Device device, List<DeviceEvent> batch, List<Integer> positions, int consumable,
                               boolean countsAccepted) {
        String deviceNumber = device.getNumber();
        for (int k = 0; k < batch.size(); k++) {
            DeviceEvent row = batch.get(k);
            try {
                if (AgentCommandDAO.KIOSK_RETURN.equals(row.getType())) {
                    // Timeline keeps the device timestamp. The durable proof is ordered by the server-owned kiosk
                    // generation, so device clock skew cannot make it override a later kiosk command.
                    commandDAO.recordKioskReturn(deviceNumber, row.getTs(), row.getDetail());
                } else {
                    commandDAO.insertEvent(deviceNumber, row.getType(), row.getTs(), row.getDetail());
                }
            } catch (PersistenceException ex) {
                if (!isDataError(ex)) {
                    return eventsStopped(deviceNumber, ex, row.getType(), positions.get(k), countsAccepted);
                }
                // Not in the timeline, so no alert either.
                logger.warn("Device {} event {} refused by the database (SQL state {}): skipped", deviceNumber,
                        row.getType(), sqlState(ex).orElse(null));
                continue;
            }
            kioskLockoutAlert.onEvent(device, row.getType(), row.getDetail());
        }
        return consumable;
    }

    /** The row for a validated event: the device's timestamp (now if it sent none), the detail cut and NUL-free. */
    private static DeviceEvent eventRow(String deviceNumber, AgentTelemetryEvent e) {
        String detail = e.getDetail();
        if (detail != null && detail.length() > MAX_EVENT_DETAIL_CHARS) {
            detail = detail.substring(0, MAX_EVENT_DETAIL_CHARS);
        }
        DeviceEvent row = new DeviceEvent();
        row.setDeviceNumber(deviceNumber);
        row.setType(e.getType());
        row.setTs(e.getTs() == null ? System.currentTimeMillis() : e.getTs());
        row.setDetail(withoutNul(detail));
        return row;
    }

    /** The database refused the data itself (SQL state class 22 or 23): retrying the same values fails the same way. */
    private static boolean isDataError(PersistenceException ex) {
        String state = sqlState(ex).orElse(null);
        return state != null && (state.startsWith("22") || state.startsWith("23"));
    }

    /**
     * A database error not caused by the events stopped them at request position {@code position}: that is how many
     * were consumed. An agent that can't take a partial count gets the error instead, failing its check-in.
     */
    private static int eventsStopped(String deviceNumber, PersistenceException ex, String type, int position,
                                     boolean countsAccepted) {
        // The class and SQL state only: a driver message can echo values.
        logger.warn("Device {} events not stored ({}, SQL state {}): the agent resends them from {}", deviceNumber,
                ex.getClass().getSimpleName(), sqlState(ex).orElse(null), type);
        if (!countsAccepted) {
            throw ex;
        }
        return position;
    }

    /** PostgreSQL refuses a NUL character in text, so a value carrying one would fail its statement. */
    private static String withoutNul(String s) {
        return s == null || s.indexOf('\0') < 0 ? s : s.replace("\0", "");
    }

    /**
     * A device-sent value safe to log on one line: at most {@code max} code points (a surrogate pair is never split),
     * control characters and line or paragraph separators shown as "?".
     */
    static String loggable(String s, int max) {
        StringBuilder out = new StringBuilder();
        int count = 0;
        for (int i = 0; i < s.length(); i += Character.charCount(s.codePointAt(i))) {
            if (count++ == max) {
                return out.append('…').toString();
            }
            int cp = s.codePointAt(i);
            if (Character.isISOControl(cp) || cp == '\u2028' || cp == '\u2029') {
                out.append('?');
            } else {
                out.appendCodePoint(cp);
            }
        }
        return out.toString();
    }

    /**
     * One WARN per sender and field an hour for a value {@link #storable} ignores, DEBUG for the repeats: a device
     * keeps reporting the same value on every check-in (every 5 s in the foreground). Keyed by authenticated device
     * or token, capped all the same.
     */
    private static final WindowLimiter IGNORED_VALUE_WARNINGS =
            new WindowLimiter(WindowLimiter.MONOTONIC, 60L * 60L * 1000L, 1, 10_000, 20);

    /**
     * The trimmed {@code value} of {@code field} a device sent, or null when there is none, it carries a control
     * character (PostgreSQL refuses a NUL) or it is longer than its column's {@code max}: such a value would fail its
     * write, and with it every check-in (or enroll) from the device. {@code who} names the sender in the log: the
     * device, or the enrollment's token.
     */
    static String storable(String who, String field, String value, int max) {
        String v = value == null ? null : value.trim();
        if (v == null || v.isEmpty()) {
            return null;
        }
        if (v.chars().anyMatch(Character::isISOControl) || v.length() > max) {
            String message = "{}: {} ignored, the server can't store it ({} characters, at most {}, no control "
                    + "characters)";
            if (IGNORED_VALUE_WARNINGS.tryAcquire(who + "/" + field)) {
                logger.warn(message, who, field, v.length(), max);
            } else {
                logger.debug(message, who, field, v.length(), max);
            }
            return null;
        }
        return v;
    }

    /**
     * Verifies the {@code Authorization: Bearer <deviceSecret>} header against the
     * SHA-256 hash stored at enrollment. Constant-time compare; fails closed when the
     * header is missing or the device has no stored secret.
     */
    private boolean authenticate(String authorization, String deviceNumber) {
        String presented = bearer(authorization);
        if (presented == null) {
            return false;
        }
        String expectedHash = commandDAO.getDeviceSecretHash(deviceNumber);
        if (expectedHash == null) {
            return false;
        }
        return CryptoUtil.constantTimeEquals(CryptoUtil.getSHA256String(presented), expectedHash);
    }

    private static String bearer(String authorization) {
        if (authorization == null) {
            return null;
        }
        String s = authorization.trim();
        if (s.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String token = s.substring(7).trim();
            return token.isEmpty() ? null : token;
        }
        return null;
    }

    private com.hmdm.rest.json.agent.AgentCommand toWire(AgentCommand stored) {
        com.hmdm.rest.json.agent.AgentCommand wire = new com.hmdm.rest.json.agent.AgentCommand();
        wire.setProtocolVersion(AgentProtocol.VERSION);
        wire.setCommandId(stored.getId() == null ? null : String.valueOf(stored.getId()));
        wire.setType(stored.getType());
        wire.setRequiresCapability(stored.getRequiresCapability());
        if (stored.getCreatedAt() != null) {
            wire.setIssuedAt(String.valueOf(stored.getCreatedAt()));
        }
        wire.setPayload(parsePayload(stored.getPayload()));
        return wire;
    }

    private static JsonNode parsePayload(String payload) {
        if (payload == null || payload.trim().isEmpty()) {
            return null;
        }
        try {
            return MAPPER.readTree(payload);
        } catch (Exception e) {
            return null;
        }
    }

    /** The capability tree to store, or null when absent or over {@link AgentCapabilityTokens#boundedJson} limits. */
    private static String capabilitiesJson(AgentCapabilities capabilities) {
        if (capabilities == null) {
            return null;
        }
        return AgentCapabilityTokens.boundedJson(capabilities.getTree());
    }

    /**
     * "Do not track" is enforced here too, not only by the agent: agents before protocol 1.4 read the config's
     * {@code location.mode = off} as passive and keep reporting last-known fixes, and any agent reports one before
     * its first config.apply lands. So when the device's configuration says Do not track, the fix is removed from the
     * telemetry before it is stored (and so never reaches the breadcrumb trail). Looked up only when a fix is present.
     */
    private void dropLocationWhenNotTracked(Device device, JsonNode tel) {
        if (tel == null || !tel.path("dynamic").isObject() || !tel.path("dynamic").has("location")
                || device.getConfigurationId() == null) {
            return;
        }
        try {
            Configuration cfg = unsecureDAO.getConfigurationById(device.getConfigurationId());
            if (DesiredConfigBuilder.isDoNotTrack(cfg)) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) tel.path("dynamic")).remove("location");
            }
        } catch (Exception e) {
            // Fail closed: when the configuration can't be read, don't store a location this check-in.
            ((com.fasterxml.jackson.databind.node.ObjectNode) tel.path("dynamic")).remove("location");
            logger.warn("Location dropped for {}: configuration {} unreadable: {}", device.getNumber(),
                    device.getConfigurationId(), e.getMessage());
        }
    }

    /** Keep dynamic.location.provider in the stored telemetry only when it is a plain provider name. */
    private static void dropUnknownLocationProvider(JsonNode tel) {
        JsonNode loc = tel == null ? null : tel.path("dynamic").path("location");
        if (loc == null || !loc.isObject() || !loc.hasNonNull("provider")) {
            return;
        }
        if (!PROVIDER_NAME.matcher(loc.get("provider").asText()).matches()) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) loc).remove("provider");
        }
    }

    /**
     * The IMEIs this check-in's telemetry reports, for the device search; null when there are none. Read from the
     * telemetry as sent, so a device whose snapshot is too large to store is still found by its IMEI.
     */
    static String[] reportedImeis(JsonNode tel) {
        JsonNode imeis = tel == null ? null : tel.path("identity").path("imei");
        if (imeis == null || !imeis.isArray()) {
            return null;
        }
        List<String> found = new ArrayList<>();
        for (JsonNode imei : imeis) {
            if (imei.isTextual() && REPORTED_IMEI.matcher(imei.textValue()).matches()) {
                found.add(imei.textValue());
                if (found.size() == MAX_REPORTED_IMEIS) {
                    break;
                }
            }
        }
        return found.isEmpty() ? null : found.toArray(new String[0]);
    }

    /** Pull dynamic.location out of the telemetry JSON and append it to the device's trail. */
    private void recordLocation(String deviceNumber, JsonNode tel) {
        if (tel == null) {
            return;
        }
        JsonNode loc = tel.path("dynamic").path("location");
        if (loc.isMissingNode() || loc.isNull() || !loc.hasNonNull("lat") || !loc.hasNonNull("lon")) {
            return;
        }
        try {
            com.hmdm.persistence.domain.DeviceLocation row = new com.hmdm.persistence.domain.DeviceLocation();
            row.setDeviceNumber(deviceNumber);
            row.setLat(loc.get("lat").asDouble());
            row.setLon(loc.get("lon").asDouble());
            if (loc.hasNonNull("accuracyM")) {
                row.setAccuracy((float) loc.get("accuracyM").asDouble());
            }
            if (loc.hasNonNull("provider")) {
                // Android provider names ("gps", "network", "fused", ...); anything else is dropped, never stored.
                String provider = loc.get("provider").asText();
                if (PROVIDER_NAME.matcher(provider).matches()) {
                    row.setProvider(provider);
                }
            }
            row.setCapturedAt(loc.hasNonNull("capturedAt") ? loc.get("capturedAt").asLong() : System.currentTimeMillis());
            commandDAO.recordLocation(row);
        } catch (DeviceGoneException e) {
            throw e;
        } catch (Exception e) {
            logger.warn("Failed to record location for {}: {}", deviceNumber, e.getMessage());
        }
    }

    private static Integer parseCommandId(String commandId) {
        try {
            return Integer.valueOf(commandId);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The device's public IP as seen by the server: a forwarding header only from the install's own proxy (a loopback
     * or private peer), else the peer itself. {@link com.hmdm.util.ClientAddress} has the rule; a device reaching
     * Tomcat directly cannot forge the recorded publicIp.
     */
    static String clientIp(javax.servlet.http.HttpServletRequest request) {
        return com.hmdm.util.ClientAddress.of(request);
    }
}
