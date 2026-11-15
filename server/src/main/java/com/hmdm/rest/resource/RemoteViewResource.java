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
import javax.inject.Named;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;

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
    private final String baseUrl;

    @Inject
    public RemoteViewResource(RemoteViewSessionService sessions, AgentCommandDAO commands, DeviceDAO devices,
                              UnsecureDAO unsecure, AgentWakeHub wakeHub, @Named("base.url") String baseUrl) {
        this.sessions = sessions; this.commands = commands; this.devices = devices;
        this.unsecure = unsecure; this.wakeHub = wakeHub; this.baseUrl = baseUrl;
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
            command.setPayload(agentPayload(created.session.getId(), created.session.getExpiresAt(),
                    signalingEndpoint(created.session.getId()), created.agentJoinSecret));
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

    private String signalingEndpoint(UUID id) {
        String origin = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        // OkHttp's WebSocket Request API takes an HTTP(S) URL and performs the Upgrade itself.
        // Supplying ws(s) here causes a protocol failure before the endpoint sees the handshake.
        return origin + "/agent/remote/" + id;
    }

    static String agentPayload(UUID id, long expiresAt, String endpoint, String credential) {
        // Values are server-minted UUID/hex credentials or the configured origin; quote the only
        // origin-derived field so a valid JSON command is still generated for a nonstandard URL.
        return "{\"sessionId\":\"" + id + "\",\"mode\":\"view\",\"expiresAt\":\""
                + Instant.ofEpochMilli(expiresAt) + "\",\"signaling\":{\"endpoint\":\""
                + endpoint.replace("\\", "\\\\").replace("\"", "\\\"") + "\",\"credential\":\"" + credential + "\"}}";
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
