package com.mdmesh.policies.rest;

import com.hmdm.persistence.domain.User;
import com.hmdm.rest.json.Response;
import com.hmdm.security.SecurityContext;
import com.mdmesh.policies.service.JobService;
import com.mdmesh.policies.service.ScheduleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import java.util.Optional;

/**
 * A policy's app schedules (open an app at set times). Reading needs {@code configurations}; changing needs
 * {@code edit_devices} (they send commands to phones) and a user who may manage all phones (a schedule reaches every
 * phone of a policy or a group).
 */
@Singleton
@Path("/plugins/policies/private/schedules")
public class ScheduleResource {
    private static final Logger log = LoggerFactory.getLogger(ScheduleResource.class);

    private final ScheduleService schedules;

    @Inject
    public ScheduleResource(ScheduleService schedules) {
        this.schedules = schedules;
    }

    @GET
    @Path("/{configurationId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response list(@PathParam("configurationId") int configurationId) {
        if (!SecurityContext.get().hasPermission("configurations")) return Response.PERMISSION_DENIED();
        Optional<User> user = SecurityContext.get().getCurrentUser();
        if (!user.isPresent()) return Response.PERMISSION_DENIED();
        try {
            return Response.OK(schedules.list(user.get().getCustomerId(), configurationId, System.currentTimeMillis()));
        } catch (RuntimeException e) {
            log.error("Policies plugin: schedules not listed", e);
            return Response.INTERNAL_ERROR();
        }
    }

    @POST
    @Path("/{configurationId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response create(@PathParam("configurationId") int configurationId, ScheduleService.Input body) {
        return save(configurationId, null, body);
    }

    @PUT
    @Path("/{configurationId}/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response update(@PathParam("configurationId") int configurationId, @PathParam("id") int id, ScheduleService.Input body) {
        return save(configurationId, id, body);
    }

    @DELETE
    @Path("/{configurationId}/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response delete(@PathParam("configurationId") int configurationId, @PathParam("id") int id) {
        Response refused = writer();
        if (refused != null) return refused;
        try {
            schedules.delete(SecurityContext.get().getCurrentUser().get().getCustomerId(), id);
            return Response.OK();
        } catch (JobService.Refused e) {
            return Response.ERROR(e.getMessage());
        } catch (RuntimeException e) {
            log.error("Policies plugin: schedule {} not deleted", id, e);
            return Response.INTERNAL_ERROR();
        }
    }

    private Response save(int configurationId, Integer id, ScheduleService.Input body) {
        Response refused = writer();
        if (refused != null) return refused;
        try {
            int customerId = SecurityContext.get().getCurrentUser().get().getCustomerId();
            return Response.OK(schedules.save(customerId, configurationId, id, body, System.currentTimeMillis()));
        } catch (JobService.Refused e) {
            return Response.ERROR(e.getMessage());
        } catch (RuntimeException e) {
            log.error("Policies plugin: schedule not saved", e);
            return Response.INTERNAL_ERROR();
        }
    }

    /** Null when the current user may change schedules, else the refusal. */
    private static Response writer() {
        if (!SecurityContext.get().hasPermission("edit_devices")) return Response.PERMISSION_DENIED();
        Optional<User> user = SecurityContext.get().getCurrentUser();
        if (!user.isPresent()) return Response.PERMISSION_DENIED();
        return Access.fleetWide(user.get());
    }
}
