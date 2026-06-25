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

import com.hmdm.persistence.AgentCommandDAO;
import com.hmdm.persistence.AgentEnrollmentTokenDAO;
import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.AgentCommand;
import com.hmdm.persistence.domain.AgentEnrollmentToken;
import com.hmdm.persistence.domain.Device;
import com.hmdm.notification.AgentWakeHub;
import com.hmdm.rest.json.Response;
import com.hmdm.security.SecurityContext;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import java.util.Optional;
import java.util.UUID;

/**
 * <p>Authenticated admin resource for the from-scratch Android agent v1 protocol: mints enrollment
 * tokens and queues opaque commands. As with {@link AgentResource}, command {@code type}/{@code
 * payload} are never interpreted by the server.</p>
 */
@Singleton
@Path("/private/agent/v1")
@Api(tags = {"Agent v1 admin"})
public class AgentAdminResource {

    private static final Logger logger = LoggerFactory.getLogger(AgentAdminResource.class);

    /** Default lifetime of a freshly-minted enrollment token (24h). */
    private static final long DEFAULT_TOKEN_TTL_MILLIS = 24L * 60L * 60L * 1000L;

    private AgentEnrollmentTokenDAO tokenDAO;
    private AgentCommandDAO commandDAO;
    private UnsecureDAO unsecureDAO;
    private AgentWakeHub wakeHub;

    /**
     * <p>A constructor required by Swagger.</p>
     */
    public AgentAdminResource() {
    }

    @Inject
    public AgentAdminResource(AgentEnrollmentTokenDAO tokenDAO,
                              AgentCommandDAO commandDAO,
                              UnsecureDAO unsecureDAO,
                              AgentWakeHub wakeHub) {
        this.tokenDAO = tokenDAO;
        this.commandDAO = commandDAO;
        this.unsecureDAO = unsecureDAO;
        this.wakeHub = wakeHub;
    }

    // =================================================================================================================
    @ApiOperation(value = "Mint enrollment token", notes = "Creates a single-use enrollment token for the current customer.")
    @POST
    @Path("/token")
    @Produces(MediaType.APPLICATION_JSON)
    public Response mintToken() {
        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) {
            return Response.PERMISSION_DENIED();
        }

        long now = System.currentTimeMillis();
        AgentEnrollmentToken token = new AgentEnrollmentToken();
        token.setToken(UUID.randomUUID().toString());
        token.setCustomerId(customerId.get());
        token.setUsed(false);
        token.setCreatedAt(now);
        token.setExpiresAt(now + DEFAULT_TOKEN_TTL_MILLIS);
        tokenDAO.insert(token);

        logger.info("Agent enrollment token {} minted for customer {}", token.getId(), customerId.get());
        return Response.OK(token);
    }

    // =================================================================================================================
    @ApiOperation(value = "Queue agent command", notes = "Queues an opaque command for a device.")
    @POST
    @Path("/devices/{deviceId}/commands")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response queueCommand(@PathParam("deviceId") String deviceId, AgentCommand body) {
        if (body == null || body.getType() == null || body.getType().trim().isEmpty()) {
            return Response.ERROR("error.agent.command.invalid");
        }

        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) {
            return Response.PERMISSION_DENIED();
        }

        Device device = unsecureDAO.getDeviceByNumber(deviceId);
        if (device == null) {
            return Response.ERROR("error.agent.device.unknown");
        }
        if (device.getCustomerId() != customerId.get()) {
            return Response.PERMISSION_DENIED();
        }

        AgentCommand command = new AgentCommand();
        command.setDeviceNumber(deviceId);
        command.setType(body.getType());
        command.setPayload(body.getPayload());
        command.setRequiresCapability(body.getRequiresCapability());
        command.setStatus("pending");
        command.setCreatedAt(System.currentTimeMillis());
        commandDAO.insert(command);

        // Wake the device so it pulls the command immediately (best-effort; floor reconcile backs it up).
        wakeHub.wake(deviceId, "commands");

        logger.info("Agent command {} queued for device {}", command.getId(), deviceId);
        return Response.OK(command);
    }

    // =================================================================================================================
    @ApiOperation(value = "Device state", notes = "Latest agent-reported device-state snapshot.")
    @GET
    @Path("/devices/{deviceId}/state")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getState(@PathParam("deviceId") String deviceId) {
        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) {
            return Response.PERMISSION_DENIED();
        }
        Device device = unsecureDAO.getDeviceByNumber(deviceId);
        if (device == null) {
            return Response.ERROR("error.agent.device.unknown");
        }
        if (device.getCustomerId() != customerId.get()) {
            return Response.PERMISSION_DENIED();
        }
        return Response.OK(commandDAO.getState(deviceId));
    }

    // =================================================================================================================
    @ApiOperation(value = "Device telemetry", notes = "Latest full census snapshot (JSON) reported by the agent.")
    @GET
    @Path("/devices/{deviceId}/telemetry")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getTelemetry(@PathParam("deviceId") String deviceId) {
        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) {
            return Response.PERMISSION_DENIED();
        }
        Device device = unsecureDAO.getDeviceByNumber(deviceId);
        if (device == null) {
            return Response.ERROR("error.agent.device.unknown");
        }
        if (device.getCustomerId() != customerId.get()) {
            return Response.PERMISSION_DENIED();
        }
        com.hmdm.persistence.domain.DeviceState s = commandDAO.getState(deviceId);
        String json = s == null ? null : s.getTelemetry();
        try {
            return Response.OK(json == null ? null
                    : new com.fasterxml.jackson.databind.ObjectMapper().readTree(json));
        } catch (Exception e) {
            return Response.OK(null);
        }
    }

    // =================================================================================================================
    @ApiOperation(value = "Device events", notes = "Agent lifecycle event timeline, newest first.")
    @GET
    @Path("/devices/{deviceId}/events")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listEvents(@PathParam("deviceId") String deviceId,
                               @QueryParam("since") Long since,
                               @QueryParam("limit") Integer limit) {
        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) {
            return Response.PERMISSION_DENIED();
        }
        Device device = unsecureDAO.getDeviceByNumber(deviceId);
        if (device == null) {
            return Response.ERROR("error.agent.device.unknown");
        }
        if (device.getCustomerId() != customerId.get()) {
            return Response.PERMISSION_DENIED();
        }
        long sinceMillis = since == null ? 0L : since;
        int cap = limit == null ? 200 : Math.min(limit, 500);
        return Response.OK(commandDAO.listEvents(deviceId, sinceMillis, cap));
    }

    // =================================================================================================================
    @ApiOperation(value = "Command history", notes = "Command lifecycle history for a device, newest first.")
    @GET
    @Path("/devices/{deviceId}/commands")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listCommands(@PathParam("deviceId") String deviceId,
                                 @QueryParam("since") Long since) {
        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) {
            return Response.PERMISSION_DENIED();
        }
        Device device = unsecureDAO.getDeviceByNumber(deviceId);
        if (device == null) {
            return Response.ERROR("error.agent.device.unknown");
        }
        if (device.getCustomerId() != customerId.get()) {
            return Response.PERMISSION_DENIED();
        }
        long sinceMillis = since == null ? 0L : since;
        return Response.OK(commandDAO.listHistory(deviceId, sinceMillis, 200));
    }

    // =================================================================================================================
    @ApiOperation(value = "Location history", notes = "Recent location breadcrumb trail for a device, newest first.")
    @GET
    @Path("/devices/{deviceId}/locations")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listLocations(@PathParam("deviceId") String deviceId,
                                  @QueryParam("since") Long since) {
        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) {
            return Response.PERMISSION_DENIED();
        }
        Device device = unsecureDAO.getDeviceByNumber(deviceId);
        if (device == null) {
            return Response.ERROR("error.agent.device.unknown");
        }
        if (device.getCustomerId() != customerId.get()) {
            return Response.PERMISSION_DENIED();
        }
        long sinceMillis = since == null ? 0L : since;
        return Response.OK(commandDAO.listLocations(deviceId, sinceMillis, 500));
    }

    // =================================================================================================================
    @ApiOperation(value = "Force sync", notes = "Wake the device now so it pulls pending commands + reports state.")
    @POST
    @Path("/devices/{deviceId}/sync")
    @Produces(MediaType.APPLICATION_JSON)
    public Response forceSync(@PathParam("deviceId") String deviceId) {
        Optional<Integer> customerId = SecurityContext.get().getCurrentCustomerId();
        if (!customerId.isPresent()) {
            return Response.PERMISSION_DENIED();
        }
        Device device = unsecureDAO.getDeviceByNumber(deviceId);
        if (device == null) {
            return Response.ERROR("error.agent.device.unknown");
        }
        if (device.getCustomerId() != customerId.get()) {
            return Response.PERMISSION_DENIED();
        }
        wakeHub.wake(deviceId, "commands");
        return Response.OK();
    }
}
