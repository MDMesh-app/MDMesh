/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */

package com.hmdm.plugins.audit.rest.filter;

import org.junit.Test;

import java.util.Optional;

import static org.junit.Assert.assertEquals;

/**
 * The audit rules match the path the way Jersey routes it, not the raw spelling: a trailing slash (Jersey routes
 * {@code /rest/public/auth/login/} to the login method), path parameters and percent-escapes don't skip a rule.
 */
public class AuditRulePathTest {

    @Test
    public void aTrailingSlashDoesNotSkipAnExactRule() {
        assertEquals(Optional.of(ResourceAuditInfo.LOGIN),
                ResourceAuditInfo.findAuditInfo("POST", "/rest/public/auth/login/"));
        assertEquals(Optional.of(ResourceAuditInfo.PASSWORD_CHANGED),
                ResourceAuditInfo.findAuditInfo("PUT", "/rest/private/users/current/"));
        assertEquals(Optional.of(ResourceAuditInfo.UPDATE_DEVICE),
                ResourceAuditInfo.findAuditInfo("PUT", "/rest/private/devices/"));
    }

    @Test
    public void aPercentEscapeDoesNotSkipARule() {
        assertEquals(Optional.of(ResourceAuditInfo.DEVICE_COMMAND),
                ResourceAuditInfo.findAuditInfo("POST", "/rest/private/agent/v1/devices/X/command%73"));
        assertEquals(Optional.of(ResourceAuditInfo.LOGIN),
                ResourceAuditInfo.findAuditInfo("POST", "/rest/public/auth/logi%6e"));
        assertEquals(Optional.of(ResourceAuditInfo.REMOVE_DEVICE),
                ResourceAuditInfo.findAuditInfo("DELETE", "/rest/%70rivate/devices/1"));
    }

    @Test
    public void aPathParameterDoesNotSkipARule() {
        // AmbiguousPathFilter refuses these first; the rules don't rely on it
        assertEquals(Optional.of(ResourceAuditInfo.UPDATE_USER),
                ResourceAuditInfo.findAuditInfo("PUT", "/rest/private;x=y/users;z"));
    }

    @Test
    public void readsThePathAsTheResourceWould() {
        assertEquals("/rest/private/users", ResourceAuditInfo.routedPath("/rest/private/users/"));
        assertEquals("/", ResourceAuditInfo.routedPath("/"));
        assertEquals("/a/b c+d", ResourceAuditInfo.routedPath("/a/b%20c+d"));
        assertEquals("/a/%41", ResourceAuditInfo.routedPath("/a/%2541"));       // decoded once only
        assertEquals("/a/é", ResourceAuditInfo.routedPath("/a/%C3%A9"));   // UTF-8
        assertEquals("/a/%zz", ResourceAuditInfo.routedPath("/a/%zz"));         // not an escape: left alone
        assertEquals("/a/b", ResourceAuditInfo.routedPath("/a;x/b;y=z"));
        assertEquals("", ResourceAuditInfo.routedPath(null));
    }

    @Test
    public void makingAndStoppingEnrollmentCodesIsAudited() {
        assertEquals(Optional.of(ResourceAuditInfo.MINT_ENROLL_TOKEN),
                ResourceAuditInfo.findAuditInfo("POST", "/rest/private/agent/v1/token"));
        assertEquals(Optional.of(ResourceAuditInfo.MINT_ENROLL_TOKEN),
                ResourceAuditInfo.findAuditInfo("POST", "/rest/private/agent/v1/token/"));
        assertEquals(Optional.of(ResourceAuditInfo.REVOKE_ENROLL_TOKENS),
                ResourceAuditInfo.findAuditInfo("POST", "/rest/private/agent/v1/token/revoke-all"));
    }

    @Test
    public void otherRoutesStayUnaudited() {
        assertEquals(Optional.empty(), ResourceAuditInfo.findAuditInfo("POST", "/rest/public/auth/loginx"));
        assertEquals(Optional.empty(), ResourceAuditInfo.findAuditInfo("PUT", "/rest/private/users/current/x"));
        assertEquals(Optional.empty(),
                ResourceAuditInfo.findAuditInfo("POST", "/rest/private/agent/v1/devices/a/b/commands/"));
    }
}
