package com.mdmesh.policies.rest;

import com.hmdm.persistence.domain.User;
import com.hmdm.rest.json.Response;
import com.hmdm.security.SecurityContext;
import com.mdmesh.policies.service.AutomationService;
import com.mdmesh.policies.service.JobService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import java.util.Optional;

/** The automation settings (automatic scans and their window) and the last night's sweep. */
@Singleton
@Path("/plugins/policies/private/automation")
public class AutomationResource {
    private static final Logger log = LoggerFactory.getLogger(AutomationResource.class);

    private final AutomationService automation;

    @Inject
    public AutomationResource(AutomationService automation) {
        this.automation = automation;
    }

    public static class Settings {
        public boolean automaticScans;
        public int windowStart = 1;
        public int windowEnd = 5;
        public String timeZone = "UTC";
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response get() {
        if (!SecurityContext.get().hasPermission("configurations")) return Response.PERMISSION_DENIED();
        Optional<User> user = SecurityContext.get().getCurrentUser();
        if (!user.isPresent()) return Response.PERMISSION_DENIED();
        try {
            return Response.OK(automation.status(user.get().getCustomerId()));
        } catch (RuntimeException e) {
            log.error("Policies plugin: automation settings not read", e);
            return Response.INTERNAL_ERROR();
        }
    }

    /** Saves the settings. Needs {@code edit_devices}: automatic scans send commands to the phones. */
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response save(Settings body) {
        if (!SecurityContext.get().hasPermission("edit_devices")) return Response.PERMISSION_DENIED();
        Optional<User> user = SecurityContext.get().getCurrentUser();
        if (!user.isPresent() || body == null) return Response.PERMISSION_DENIED();
        Response refused = Access.fleetWide(user.get());
        if (refused != null) return refused;
        int customerId = user.get().getCustomerId();
        try {
            automation.save(customerId, body.automaticScans, body.windowStart, body.windowEnd, body.timeZone);
            return Response.OK(automation.status(customerId));
        } catch (JobService.Refused e) {
            return Response.ERROR(e.getMessage());
        } catch (RuntimeException e) {
            log.error("Policies plugin: automation settings not saved", e);
            return Response.INTERNAL_ERROR();
        }
    }
}
