package com.mdmesh.fixture;

import com.hmdm.rest.json.Response;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

/** The fixture plugin's public REST, as web/EXTENDING.md lays a fork's out. HMDMApplicationTest finds it. */
@Path("/plugins/fixture/public")
public class FixturePublicResource {

    @GET
    @Path("/ping")
    @Produces(MediaType.APPLICATION_JSON)
    public Response ping() {
        return Response.OK("pong");
    }
}
