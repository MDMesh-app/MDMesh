package com.mdmesh.policies.rest;

import com.hmdm.persistence.domain.User;
import com.hmdm.rest.json.Response;
import com.hmdm.security.SecurityContext;
import com.mdmesh.policies.rest.json.JobRequest;
import com.mdmesh.policies.service.JobService;
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
import javax.ws.rs.core.MediaType;
import java.util.Optional;

/** Bulk jobs: one request for an action on many phones; the server paces the wakes. */
@Singleton
@Path("/plugins/policies/private/jobs")
public class JobResource {
    private static final Logger log = LoggerFactory.getLogger(JobResource.class);

    private final JobService jobs;

    @Inject
    public JobResource(JobService jobs) {
        this.jobs = jobs;
    }

    /** Start a job. Needs {@code edit_devices}, like every command core queues. */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response create(JobRequest request) {
        if (!SecurityContext.get().hasPermission("edit_devices")) return Response.PERMISSION_DENIED();
        Optional<User> user = SecurityContext.get().getCurrentUser();
        if (!user.isPresent()) return Response.PERMISSION_DENIED();
        User u = user.get();
        try {
            return Response.OK(jobs.create(u.getCustomerId(), u.getId(), u.isSuperAdmin(), request, System.currentTimeMillis()));
        } catch (JobService.Refused e) {
            return Response.ERROR(e.getMessage());
        } catch (RuntimeException e) {
            log.error("Policies plugin: job not created", e);
            return Response.INTERNAL_ERROR();
        }
    }

    /** The customer's jobs of the last 24 hours with their progress. */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response recent() {
        if (!SecurityContext.get().hasPermission("configurations")) return Response.PERMISSION_DENIED();
        Optional<User> user = SecurityContext.get().getCurrentUser();
        if (!user.isPresent()) return Response.PERMISSION_DENIED();
        try {
            return Response.OK(jobs.recent(user.get().getCustomerId(), System.currentTimeMillis()));
        } catch (RuntimeException e) {
            log.error("Policies plugin: jobs not listed", e);
            return Response.INTERNAL_ERROR();
        }
    }

    /** Stop a job: no more wakes, and its commands no phone has received yet expire. */
    @POST
    @Path("/{id}/cancel")
    @Produces(MediaType.APPLICATION_JSON)
    public Response cancel(@PathParam("id") int id) {
        if (!SecurityContext.get().hasPermission("edit_devices")) return Response.PERMISSION_DENIED();
        Optional<User> user = SecurityContext.get().getCurrentUser();
        if (!user.isPresent()) return Response.PERMISSION_DENIED();
        try {
            return Response.OK(jobs.cancel(user.get().getCustomerId(), id, System.currentTimeMillis()));
        } catch (JobService.Refused e) {
            return Response.ERROR(e.getMessage());
        } catch (RuntimeException e) {
            log.error("Policies plugin: job {} not cancelled", id, e);
            return Response.INTERNAL_ERROR();
        }
    }
}
