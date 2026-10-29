/*
 * MDMesh: the client's address as the server's own proxy reports it.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 */

package com.hmdm.util;

import com.google.common.net.InetAddresses;

import javax.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.util.Enumeration;

/**
 * <p>The address a request came from. Forwarding headers are client-controllable, so they are honoured ONLY when the
 * direct peer is a loopback or private address, i.e. the install's own proxy (Caddy or cloudflared on Docker, the
 * operator's proxy on a native install). From such a peer, in order:</p>
 * <ol>
 *   <li>{@code CF-Connecting-IP}, which Cloudflare sets. The Docker edge drops a client-sent one; on a native install
 *       the operator's proxy must (docs/OPERATIONS.md).</li>
 *   <li>The LAST {@code X-Forwarded-For} entry: the one the nearest proxy added. The Docker edge sends exactly one, its
 *       own view of the client. Earlier entries are whatever the client sent.</li>
 * </ol>
 * <p>A header value that is not an IP address literal is ignored.</p>
 */
public final class ClientAddress {

    private ClientAddress() {
    }

    /** The client's address: a forwarding header from a local or private peer (see above), else the peer itself. */
    public static String of(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwarded = forwarded(request);
        return forwarded != null ? forwarded : request.getRemoteAddr();
    }

    /**
     * <p>The client's address for a per-address limit, where a forged address is worth something to an attacker, so
     * the rule is stricter than {@link #of}'s:</p>
     * <ul>
     *   <li>A header is believed only from a peer that is the install's proxy: a loopback address, one listed in
     *       {@code trustedProxies} ({@code proxy.addresses}), or, when {@code privatePeersAreProxies} (Docker, where
     *       only the edge can reach the server), any private address. Not from any LAN client that reaches a native
     *       install's Tomcat port directly.</li>
     *   <li>Only the LAST {@code X-Forwarded-For} entry counts, the one the proxy added (the Docker edge, Cloudflare
     *       and nginx's {@code $proxy_add_x_forwarded_for} all put the client there). {@code CF-Connecting-IP} is
     *       ignored: a proxy that doesn't strip it would let any client pick it.</li>
     *   <li>Any other peer is the client, public or private, whatever headers it sends: a LAN client that reaches a
     *       native install's Tomcat directly (a LAN-only install with no proxy) is limited by its own address, and
     *       can't opt out of that limit by sending a header. So a proxy on another host must be listed: unlisted,
     *       every client behind it shares the proxy's address, and its limit.</li>
     *   <li>Null only when a trusted proxy named no client (no usable {@code X-Forwarded-For}): the server can't tell
     *       the clients behind it apart, and keying them all by the proxy would let 10 failures lock everyone out.
     *       The caller then skips the per-address limit; per-account limits still apply.</li>
     * </ul>
     */
    public static String forLimiting(HttpServletRequest request, java.util.Collection<String> trustedProxies,
                                     boolean privatePeersAreProxies) {
        if (request == null) {
            return null;
        }
        String peer = request.getRemoteAddr();
        if (!isAddress(peer)) {
            return null;
        }
        boolean proxy = isLoopback(peer)
                || (trustedProxies != null && trustedProxies.contains(peer))
                || (privatePeersAreProxies && isLocalOrPrivate(peer));
        if (proxy) {
            return ipOrNull(lastForwardedFor(request.getHeaders("X-Forwarded-For")));
        }
        return peer;
    }

    private static boolean isLoopback(String addr) {
        try {
            return InetAddresses.forString(addr).isLoopbackAddress();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** The address a proxy named, when the peer is local or private and named one; else null. */
    private static String forwarded(HttpServletRequest request) {
        if (!isLocalOrPrivate(request.getRemoteAddr())) {
            return null;
        }
        String cf = ipOrNull(request.getHeader("CF-Connecting-IP"));
        if (cf != null) {
            return cf;
        }
        return ipOrNull(lastForwardedFor(request.getHeaders("X-Forwarded-For")));
    }

    /** The last entry of the X-Forwarded-For header lines, or null when there is none. */
    private static String lastForwardedFor(Enumeration<String> lines) {
        String last = null;
        while (lines != null && lines.hasMoreElements()) {
            String line = lines.nextElement();
            if (line != null && !line.trim().isEmpty()) {
                last = line;
            }
        }
        if (last == null) {
            return null;
        }
        return last.substring(last.lastIndexOf(',') + 1);
    }

    /** The trimmed value when it is an IPv4 or IPv6 address literal (no DNS lookup), else null. */
    private static String ipOrNull(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        return isAddress(v) ? v : null;
    }

    private static boolean isAddress(String value) {
        return value != null && InetAddresses.isInetAddress(value);
    }

    /** Loopback / RFC1918 / link-local / IPv6 ULA: the address space the install's own proxy lives in. */
    public static boolean isLocalOrPrivate(String addr) {
        if (addr == null) {
            return false;
        }
        try {
            InetAddress a = InetAddresses.forString(addr);
            return a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress()
                    || (a.getAddress().length == 16 && (a.getAddress()[0] & 0xfe) == 0xfc);
        } catch (Exception e) {
            return false;
        }
    }
}
