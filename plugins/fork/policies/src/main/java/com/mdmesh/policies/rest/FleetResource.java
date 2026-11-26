package com.mdmesh.policies.rest;

import com.hmdm.persistence.domain.User;
import com.hmdm.rest.json.Response;
import com.hmdm.security.SecurityContext;
import com.mdmesh.policies.service.FleetService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import java.util.Optional;

/** The Policies page's data in one request. Read-only. */
@Singleton
@Path("/plugins/policies/private")
public class FleetResource {
    private static final Logger log = LoggerFactory.getLogger(FleetResource.class);

    private final FleetService fleet;

    @Inject
    public FleetResource(FleetService fleet) {
        this.fleet = fleet;
    }

    /**
     * Every phone the user may access (as core decides it), with its brand, configuration status, latest app scan and
     * open scans and uninstalls. Needs the {@code configurations} permission, like the Policies page.
     */
    @GET
    @Path("/fleet")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getFleet() {
        if (!SecurityContext.get().hasPermission("configurations")) return Response.PERMISSION_DENIED();
        Optional<User> user = SecurityContext.get().getCurrentUser();
        if (!user.isPresent()) return Response.PERMISSION_DENIED();
        User u = user.get();
        try {
            return Response.OK(fleet.fleet(u.getCustomerId(), u.getId(), u.isSuperAdmin()));
        } catch (RuntimeException e) {
            log.error("Policies plugin: fleet request failed", e);
            return Response.INTERNAL_ERROR();
        }
    }
}
