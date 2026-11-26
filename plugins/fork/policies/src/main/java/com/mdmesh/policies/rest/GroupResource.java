package com.mdmesh.policies.rest;

import com.hmdm.persistence.domain.User;
import com.hmdm.rest.json.Response;
import com.hmdm.security.SecurityContext;
import com.mdmesh.policies.service.GroupService;
import com.mdmesh.policies.service.JobService;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Device groups and brands. Reading needs {@code configurations}; changing needs it too (groups decide which policy
 * phones should be on, as the policies themselves do), and a user who may manage all phones: a group covers whole
 * brands, so it reaches beyond any device group a user is limited to. Every change answers with the whole new state.
 * Nothing here sends anything to a phone.
 */
@Singleton
@Path("/plugins/policies/private/groups")
public class GroupResource {
    private static final Logger log = LoggerFactory.getLogger(GroupResource.class);

    private final GroupService groups;

    @Inject
    public GroupResource(GroupService groups) {
        this.groups = groups;
    }

    public static class Members {
        public List<Integer> deviceIds = new ArrayList<>();
        /** The group to pick them into; null takes them out of the group they were picked into. */
        public Integer groupId;
    }

    public static class DeviceBrands {
        public List<Integer> deviceIds = new ArrayList<>();
        /** The brand to set on them; null lets them show the brand they report again. */
        public String brand;
    }

    public static class NewBrand {
        public String label;
    }

    public static class PolicyGroups {
        /** The policy; null takes the groups' policy away. */
        public Integer configurationId;
        public List<Integer> groupIds = new ArrayList<>();
    }

    /** What a change runs once its checks pass. */
    private interface Change {
        void run(int customerId);
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response list() {
        if (!SecurityContext.get().hasPermission("configurations")) return Response.PERMISSION_DENIED();
        Optional<User> user = SecurityContext.get().getCurrentUser();
        if (!user.isPresent()) return Response.PERMISSION_DENIED();
        try {
            return Response.OK(groups.view(user.get().getCustomerId()));
        } catch (RuntimeException e) {
            log.error("Policies plugin: groups not read", e);
            return Response.INTERNAL_ERROR();
        }
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response create(GroupService.Input body) {
        return change("group not created", c -> groups.save(c, null, body, System.currentTimeMillis()));
    }

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response update(@PathParam("id") int id, GroupService.Input body) {
        return change("group not saved", c -> groups.save(c, id, body, System.currentTimeMillis()));
    }

    @DELETE
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response delete(@PathParam("id") int id) {
        return change("group not deleted", c -> groups.delete(c, id));
    }

    @PUT
    @Path("/members")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response members(Members body) {
        if (body == null) return Response.ERROR("Nothing to save.");
        return change("phones not moved between groups", c -> groups.setMembers(c, body.deviceIds, body.groupId));
    }

    @PUT
    @Path("/device-brands")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response deviceBrands(DeviceBrands body) {
        if (body == null) return Response.ERROR("Nothing to save.");
        return change("brand not set on phones", c -> groups.setDeviceBrands(c, body.deviceIds, body.brand));
    }

    @POST
    @Path("/brands")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response createBrand(NewBrand body) {
        if (body == null) return Response.ERROR("Give the brand a name.");
        return change("brand not created", c -> groups.createBrand(c, body.label));
    }

    @DELETE
    @Path("/brands/{brand}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response deleteBrand(@PathParam("brand") String brand) {
        return change("brand not deleted", c -> groups.deleteBrand(c, brand));
    }

    @PUT
    @Path("/policy")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response policy(PolicyGroups body) {
        if (body == null) return Response.ERROR("Nothing to save.");
        return change("groups not given to the policy", c -> groups.setPolicy(c, body.configurationId, body.groupIds));
    }

    private Response change(String what, Change change) {
        if (!SecurityContext.get().hasPermission("configurations")) return Response.PERMISSION_DENIED();
        Optional<User> user = SecurityContext.get().getCurrentUser();
        if (!user.isPresent()) return Response.PERMISSION_DENIED();
        Response refused = Access.fleetWide(user.get());
        if (refused != null) return refused;
        int customerId = user.get().getCustomerId();
        try {
            change.run(customerId);
            return Response.OK(groups.view(customerId));
        } catch (JobService.Refused e) {
            return Response.ERROR(e.getMessage());
        } catch (RuntimeException e) {
            log.error("Policies plugin: {}", what, e);
            return Response.INTERNAL_ERROR();
        }
    }
}
