package com.mdmesh.policies.rest;

import com.hmdm.persistence.domain.User;
import com.hmdm.rest.json.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Automation and groups act on every phone of a policy or of the customer, not only on the
 * phones a user may access. So changing them needs a user who may manage all phones (core's allDevicesAvailable) or a
 * super-admin, the same rule as core's SecurityContext.canReachAllDevices() (push uses it for scheduled tasks). Anyone
 * else is refused, with the reason.
 */
final class Access {
    private static final Logger log = LoggerFactory.getLogger(Access.class);

    private Access() {
    }

    /** Null when the user may change fleet-wide settings, else the refusal to return. */
    static Response fleetWide(User user) {
        if (user.isSuperAdmin() || user.isAllDevicesAvailable()) return null;
        log.warn("Policies plugin: fleet-wide change refused for user {} limited to some device groups", user.getLogin());
        return Response.ERROR("This acts on every phone of a policy, so only a user who may manage all phones can change it.");
    }
}
