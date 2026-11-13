package com.hmdm.rest.resource;

import com.hmdm.notification.AgentWakeHub;
import com.hmdm.persistence.AgentCommandDAO;
import com.hmdm.persistence.DeviceDAO;
import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.AgentCommand;
import com.hmdm.persistence.domain.Device;
import com.hmdm.rest.json.Response;
import com.hmdm.security.SecurityContext;
import com.hmdm.service.RemoteViewSessionService;
import com.hmdm.util.AgentCapabilityTokens;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** POC-only console API. It authorizes and persists a consent-pending view request; it never returns its join secret. */
@Singleton
@Path("/private/remote-view")
@Api(tags = {"Remote view POC"})
public class RemoteViewResource {
    private final RemoteViewSessionService sessions;
    private final AgentCommandDAO commands;
    private final DeviceDAO devices;
    private final UnsecureDAO unsecure;
    private final AgentWakeHub wakeHub;

    @Inject
    public RemoteViewResource(RemoteViewSessionService sessions, AgentCommandDAO commands, DeviceDAO devices,
                              UnsecureDAO unsecure, AgentWakeHub wakeHub) {
        this.sessions = sessions; this.commands = commands; this.devices = devices;
        this.unsecure = unsecure; this.wakeHub = wakeHub;
    }

    @POST @Path("/devices/{deviceId}/sessions") @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Request a remote screen-view session", notes = "POC: view-only, requires device consent.")
    public Response request(@PathParam("deviceId") String deviceId) {
        if (!SecurityContext.get().hasPermission("remote_view")) return Response.PERMISSION_DENIED();
        Optional<com.hmdm.persistence.domain.User> user = SecurityContext.get().getCurrentUser();
        Device device = unsecure.getDeviceByNumber(deviceId);
        if (!user.isPresent() || device == null || !devices.isAccessibleToUser(device)) return Response.PERMISSION_DENIED();
        if (!AgentCapabilityTokens.flatten(commands.getDeviceCapabilities(deviceId)).contains("remote.view")) {
            return Response.ERROR("The enrolled agent does not support remote viewing");
        }
        long now = System.currentTimeMillis();
        try {
            RemoteViewSessionService.Created created = sessions.create(deviceId, user.get().getId(), now);
            AgentCommand command = new AgentCommand();
            command.setDeviceNumber(deviceId); command.setType("remote.startSession");
            command.setPayload("{\"sessionId\":\"" + created.session.getId() + "\",\"mode\":\"view\",\"expiresAt\":"
                    + created.session.getExpiresAt() + ",\"joinSecret\":\"" + created.agentJoinSecret + "\"}");
            command.setRequiresCapability("remote.view"); command.setStatus("pending"); command.setCreatedAt(now);
            commands.insert(command); wakeHub.wake(deviceId, "interactive");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", created.session.getId()); result.put("state", created.session.getState());
            result.put("consentExpiresAt", created.session.getConsentExpiresAt()); result.put("expiresAt", created.session.getExpiresAt());
            return Response.OK(result);
        } catch (Exception e) {
            // The partial unique index is the authoritative concurrency guard.
            return Response.ERROR("A remote-view session is already active for this device");
        }
    }

    @POST @Path("/sessions/{id}/stop") @Produces(MediaType.APPLICATION_JSON)
    public Response stop(@PathParam("id") String rawId) {
        if (!SecurityContext.get().hasPermission("remote_view")) return Response.PERMISSION_DENIED();
        try {
            UUID id = UUID.fromString(rawId);
            long now = System.currentTimeMillis();
            if (!sessions.stopOwned(id, SecurityContext.get().getCurrentUser().map(u -> u.getId()).orElse(-1), now)) {
                return Response.PERMISSION_DENIED();
            }
            return Response.OK();
        } catch (IllegalArgumentException badId) { return Response.ERROR("Invalid remote-view session id"); }
    }

}
