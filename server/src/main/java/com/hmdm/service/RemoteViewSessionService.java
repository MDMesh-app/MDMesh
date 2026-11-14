package com.hmdm.service;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.hmdm.persistence.RemoteViewSessionDAO;
import com.hmdm.persistence.domain.RemoteViewSession;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import com.hmdm.util.ExecutorRegistry;
import com.hmdm.util.CryptoUtil;
import com.hmdm.remote.RemoteViewSignalingHub;

/** Creates/stops durable, consent-pending view sessions. Transport is intentionally out of scope. */
@Singleton
public class RemoteViewSessionService {
    public static volatile RemoteViewSessionService INSTANCE;
    public static final long DEFAULT_CONSENT_MS = 60_000L;
    public static final long DEFAULT_SESSION_MS = 15 * 60_000L;
    private final RemoteViewSessionDAO dao;
    private final SecureRandom random = new SecureRandom();
    private final ScheduledExecutorService cleanup = ExecutorRegistry.register(Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "remote-view-expiry"); t.setDaemon(true); return t;
    }));
    @Inject public RemoteViewSessionService(RemoteViewSessionDAO dao) {
        this.dao = dao; INSTANCE = this;
        dao.expire(System.currentTimeMillis(), "server restarted");
        cleanup.scheduleAtFixedRate(() -> expireStale(System.currentTimeMillis()), 30, 30, TimeUnit.SECONDS);
    }
    public Created create(String device, int user, long now) {
        byte[] secret = new byte[32]; random.nextBytes(secret);
        String agentJoinSecret = hex(secret);
        RemoteViewSession s = new RemoteViewSession(); s.setId(UUID.randomUUID()); s.setDeviceNumber(device);
        s.setRequesterUserId(user); s.setMode("view"); s.setState("requested");
        s.setAgentJoinSecretHash(hex(sha256(agentJoinSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        long consentMs = durationProperty("mdmesh.remoteView.consentSeconds", DEFAULT_CONSENT_MS, 10, 300);
        long sessionMs = durationProperty("mdmesh.remoteView.maxSessionSeconds", DEFAULT_SESSION_MS, 60, 3600);
        s.setCreatedAt(now); s.setConsentExpiresAt(now + consentMs); s.setExpiresAt(now + sessionMs); dao.insert(s, "requested");
        return new Created(s, agentJoinSecret);
    }
    public boolean stop(UUID id, long now, String reason) {
        boolean stopped = dao.end(id, "stopped", now, reason);
        if (stopped && RemoteViewSignalingHub.INSTANCE != null) RemoteViewSignalingHub.INSTANCE.close(id, reason);
        return stopped;
    }
    public boolean stopOwned(UUID id, int userId, long now) {
        RemoteViewSession session = dao.find(id);
        return session != null && session.getRequesterUserId() == userId && stop(id, now, "stopped by requester");
    }
    /** Atomically accepts the one-time agent join credential and enters the in-memory signaling phase. */
    public RemoteViewSession claimAgentJoin(UUID id, String deviceNumber, String secret, long now) {
        RemoteViewSession session = dao.find(id);
        if (secret == null || deviceNumber == null || session == null || session.getEndedAt() != null || !deviceNumber.equals(session.getDeviceNumber())
                || now > session.getConsentExpiresAt() || now > session.getExpiresAt()
                || !CryptoUtil.constantTimeEquals(hex(sha256(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8))), session.getAgentJoinSecretHash())) {
            return null;
        }
        // A modern agent reports that it has presented the consent UI before it joins.
        // Older/partially delivered commands can join directly from requested, so accept both
        // durable predecessor states without making the raw signaling credential reusable.
        if (!("requested".equals(session.getState()) || "awaiting_consent".equals(session.getState()))) return null;
        return dao.transition(id, session.getState(), "signaling", now, session.getRequesterUserId(), "agent joined") ? session : null;
    }
    public boolean browserMayJoin(UUID id, int userId, long now) {
        RemoteViewSession session = dao.find(id);
        return session != null && session.getEndedAt() == null && session.getRequesterUserId() == userId
                && now <= session.getExpiresAt();
    }
    public boolean isSignalingLive(UUID id, long now) {
        RemoteViewSession session = dao.find(id);
        return session != null && session.getEndedAt() == null && "signaling".equals(session.getState()) && now <= session.getExpiresAt();
    }
    public int expireStale(long now) { return dao.expire(now, "session expired or server restarted"); }
    public int stopForDevice(String device, long now, String reason) {
        // Any in-memory peer tied to this device is closed immediately; the DB mutation is the durable authority.
        if (RemoteViewSignalingHub.INSTANCE != null) RemoteViewSignalingHub.INSTANCE.closeDevice(device, reason);
        return dao.endForDevice(device, "stopped", now, reason);
    }

    /**
     * Records an agent lifecycle outcome from the normal authenticated check-in event buffer.
     * The event detail is intentionally reduced to the opaque session id and bounded reason;
     * signaling payloads, media, and credentials are never accepted here.
     */
    public void recordAgentEvent(String deviceNumber, String type, String detail, long now) {
        UUID id = sessionId(detail);
        if (id == null || deviceNumber == null || type == null) return;
        RemoteViewSession session = dao.find(id);
        if (session == null || session.getEndedAt() != null || !deviceNumber.equals(session.getDeviceNumber())) return;
        if ("remoteViewConsentRequested".equals(type)) {
            dao.transition(id, "requested", "awaiting_consent", now, session.getRequesterUserId(), "device requested capture consent");
        } else if ("remoteViewDenied".equals(type)) {
            end(id, "denied", now, "device denied capture consent");
        } else if ("remoteViewEnded".equals(type)) {
            String reason = detail == null ? "device ended capture" : detail.replaceAll("(?s).*reason=", "");
            end(id, terminalState(reason), now, "device ended capture: " + trim(reason, 96));
        }
    }

    private void end(UUID id, String state, long now, String reason) {
        if (dao.end(id, state, now, reason) && RemoteViewSignalingHub.INSTANCE != null) RemoteViewSignalingHub.INSTANCE.close(id, reason);
    }
    private static String terminalState(String reason) {
        String value = reason == null ? "" : reason.toLowerCase(java.util.Locale.ROOT);
        if (value.contains("signaling") || value.contains("peer") || value.contains("disconnect")) return "disconnected";
        if (value.contains("failed") || value.contains("unavailable")) return "failed";
        return "stopped";
    }
    private static UUID sessionId(String detail) {
        if (detail == null) return null;
        java.util.regex.Matcher match = java.util.regex.Pattern.compile("(?:^|[;\\s])session=([0-9a-fA-F-]{36})(?:;|\\s|$)").matcher(detail);
        if (!match.find()) return null;
        try { return UUID.fromString(match.group(1)); } catch (IllegalArgumentException ignored) { return null; }
    }
    private static String trim(String value, int max) { return value == null ? "" : value.length() <= max ? value : value.substring(0, max); }
    private static long durationProperty(String property, long fallback, long minSeconds, long maxSeconds) {
        String configured = System.getProperty(property);
        if (configured == null) return fallback;
        try { return Math.max(minSeconds, Math.min(maxSeconds, Long.parseLong(configured))) * 1000L; }
        catch (NumberFormatException ignored) { return fallback; }
    }
    private static byte[] sha256(byte[] value) { try { return MessageDigest.getInstance("SHA-256").digest(value); } catch (Exception e) { throw new IllegalStateException(e); } }
    private static String hex(byte[] value) { StringBuilder b=new StringBuilder(value.length*2); for(byte v:value)b.append(String.format("%02x",v)); return b.toString(); }
    public static class Created { public final RemoteViewSession session; public final String agentJoinSecret; Created(RemoteViewSession s,String k){session=s;agentJoinSecret=k;} }
}
