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
import com.hmdm.persistence.AgentEnrollmentTokenDAO;
import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.AgentCommand;
import com.hmdm.persistence.domain.AgentEnrollmentToken;
import com.hmdm.persistence.domain.Configuration;
import com.hmdm.persistence.domain.Device;
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
import com.hmdm.util.AgentCapabilityTokens;
import com.hmdm.util.CryptoUtil;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
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

/**
 * <p>Public-facing resource for the from-scratch Android agent v1 protocol. Mirrors
 * {@code proto/endpoints.md}. Command {@code type}/{@code payload} are entirely opaque: this
 * resource never switches on them. Delivery is gated only by capability-token set membership
 * (see {@link AgentCapabilityTokens}).</p>
 */
@Singleton
@Path("/public/agent/v1")
@Api(tags = {"Agent v1"})
public class AgentResource {

    private static final Logger logger = LoggerFactory.getLogger(AgentResource.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Accepted command-result statuses (mirrors proto CommandStatus). Anything else is ignored. */
    private static final Set<String> ALLOWED_RESULT_STATUS = new HashSet<>(Arrays.asList(
            "accepted", "done", "failed", "unsupported", "expired"));

    private UnsecureDAO unsecureDAO;
    private AgentEnrollmentTokenDAO tokenDAO;
    private AgentCommandDAO commandDAO;

    /**
     * <p>A constructor required by Swagger.</p>
     */
    public AgentResource() {
    }

    @Inject
    public AgentResource(UnsecureDAO unsecureDAO,
                         AgentEnrollmentTokenDAO tokenDAO,
                         AgentCommandDAO commandDAO) {
        this.unsecureDAO = unsecureDAO;
        this.tokenDAO = tokenDAO;
        this.commandDAO = commandDAO;
    }

    // =================================================================================================================
    @ApiOperation(value = "Enroll an agent", notes = "Validates a single-use token and registers the device.")
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
            return Response.ERROR("error.agent.token.invalid");
        }
        if (token.isUsed()) {
            return Response.ERROR("error.agent.token.used");
        }
        if (token.getExpiresAt() != null && token.getExpiresAt() < System.currentTimeMillis()) {
            return Response.ERROR("error.agent.token.expired");
        }

        String deviceId = UUID.randomUUID().toString();
        Device device = unsecureDAO.createNewDeviceOnDemand(deviceId);
        if (device == null) {
            // The only null path is the "create new devices" settings flag being off — name it,
            // so the failure is diagnosable from the agent/proxy side (a generic internal error
            // here cost a debugging session: valid token, reachable server, no device row).
            logger.warn("Agent enroll: on-demand device creation is disabled by settings (token {})", token.getId());
            return Response.ERROR("error.agent.enrollment.disabled");
        }

        // Mint a per-device secret; persist only its SHA-256 hash. The plaintext is
        // returned exactly once (below) and is required as a bearer token on /checkin.
        String deviceSecret = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
        commandDAO.updateDeviceSecretHash(deviceId, CryptoUtil.getSHA256String(deviceSecret));
        commandDAO.updateDeviceCapabilities(deviceId, capabilitiesJson(request.getCapabilities()));
        // Record the agent's stable hardware id so duplicate enrollments of the same physical
        // device can be detected/flagged in the admin UI (we still create a fresh row per enroll).
        if (request.getHardwareId() != null && !request.getHardwareId().trim().isEmpty()) {
            commandDAO.updateHardwareId(deviceId, request.getHardwareId().trim());
        }
        commandDAO.touchLastUpdate(deviceId);
        tokenDAO.markUsed(token.getId());

        String configurationName = null;
        if (device.getConfigurationId() != null) {
            Configuration configuration = unsecureDAO.getConfigurationById(device.getConfigurationId());
            if (configuration != null) {
                configurationName = configuration.getName();
            }
        }

        logger.info("Agent enrolled device {}", deviceId);
        return Response.OK(new AgentEnrollResponse(deviceId, configurationName, deviceSecret));
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
        if (device == null) {
            return Response.ERROR("error.agent.device.unknown");
        }

        // Authenticate the device by its per-device secret BEFORE any state change.
        // The body-supplied deviceId is untrusted until this passes (prevents IDOR/spoofing).
        if (!authenticate(authorization, deviceNumber)) {
            return Response.ERROR("error.agent.unauthorized");
        }

        // Stamp last-seen so the admin UI shows the device online.
        commandDAO.touchLastUpdate(deviceNumber);

        // Backfill the stable hardware id for already-enrolled devices (set once / when it changes).
        if (request.getHardwareId() != null && !request.getHardwareId().trim().isEmpty()
                && !request.getHardwareId().trim().equals(device.getHardwareId())) {
            commandDAO.updateHardwareId(deviceNumber, request.getHardwareId().trim());
        }

        // Refresh the stored capability matrix (kept fresh every check-in).
        if (request.getCapabilities() != null) {
            commandDAO.updateDeviceCapabilities(deviceNumber, capabilitiesJson(request.getCapabilities()));
        }

        // Persist the latest device-state snapshot (powers the admin console).
        if (request.getState() != null) {
            AgentDeviceState s = request.getState();
            DeviceState row = new DeviceState();
            row.setDeviceNumber(deviceNumber);
            row.setBattery(s.getBattery());
            row.setCharging(s.getCharging());
            row.setLocked(s.getLocked());
            row.setKioskActive(s.getKioskActive());
            row.setAndroidRelease(s.getAndroidRelease());
            row.setLastBootAt(s.getLastBootAt());
            row.setAgentVersion(s.getAgentVersion());
            row.setPowerMode(s.getPowerMode());
            // The server (not the device) knows the public IP — inject it into the census JSON.
            JsonNode tel = request.getTelemetry();
            if (tel != null && tel.isObject()) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) tel).put("publicIp", clientIp(httpRequest));
            }
            row.setTelemetry(tel == null ? null : tel.toString());
            row.setUpdatedAt(System.currentTimeMillis());
            commandDAO.upsertState(row);
            // Mirror the Android version into the device row (infojson) so the device LIST — which
            // reads infojson->>'androidVersion', not the state table — shows it.
            String androidRelease = s.getAndroidRelease();
            if (androidRelease != null && !androidRelease.trim().isEmpty()) {
                commandDAO.updateAndroidVersion(deviceNumber, androidRelease.trim());
            }
            // Append the reported location (dynamic.location) to the device's breadcrumb trail.
            recordLocation(deviceNumber, tel);
        }

        // Ingest buffered lifecycle events into the timeline.
        if (request.getEvents() != null) {
            for (com.hmdm.rest.json.agent.AgentTelemetryEvent e : request.getEvents()) {
                if (e == null || e.getType() == null) {
                    continue;
                }
                long ts = e.getTs() == null ? System.currentTimeMillis() : e.getTs();
                commandDAO.insertEvent(deviceNumber, e.getType(), ts, e.getDetail());
            }
        }

        // Ack results from previously-delivered commands.
        if (request.getResults() != null) {
            for (AgentCommandResult result : request.getResults()) {
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
                AgentCommand stored = commandDAO.findByDeviceAndId(deviceNumber, commandId);
                if (stored != null) {
                    // Store the device-reported detail + completion time, so remote actions are
                    // observable from the server.
                    commandDAO.markResultWithTime(commandId, result.getStatus(), result.getDetail(),
                            System.currentTimeMillis());
                }
            }
        }

        // Lazily expire commands that were never acted on before delivering more. TTL = 60 min:
        // long enough that the doze-proof heartbeat (~10 min) delivers to a parked device first.
        commandDAO.expireStale(deviceNumber, 60L * 60L * 1000L);

        // Load pending commands and gate them by capability-token set membership.
        Set<String> deviceTokens = AgentCapabilityTokens.flatten(commandDAO.getDeviceCapabilities(deviceNumber));
        List<AgentCommand> pending = commandDAO.listPending(deviceNumber);
        List<com.hmdm.rest.json.agent.AgentCommand> commands = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (AgentCommand stored : pending) {
            if (!AgentCapabilityTokens.isAllowed(stored.getRequiresCapability(), deviceTokens)) {
                continue;
            }
            // Atomically claim it so a concurrent check-in can't deliver the same command twice.
            if (commandDAO.claimForDelivery(stored.getId(), now)) {
                commands.add(toWire(stored));
            }
        }

        return Response.OK(new AgentCheckInResponse(commands));
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

    private static String capabilitiesJson(AgentCapabilities capabilities) {
        if (capabilities == null || capabilities.getTree() == null) {
            return null;
        }
        return capabilities.getTree().toString();
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
                row.setProvider(loc.get("provider").asText());
            }
            row.setCapturedAt(loc.hasNonNull("capturedAt") ? loc.get("capturedAt").asLong() : System.currentTimeMillis());
            commandDAO.recordLocation(row);
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

    /** The device's public IP as seen by the server, honoring the tunnel/proxy forwarding headers. */
    private static String clientIp(javax.servlet.http.HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String cf = request.getHeader("CF-Connecting-IP");
        if (cf != null && !cf.trim().isEmpty()) {
            return cf.trim();
        }
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.trim().isEmpty()) {
            // First hop is the original client.
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
