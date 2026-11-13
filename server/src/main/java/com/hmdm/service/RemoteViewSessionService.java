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
    public static final long DEFAULT_CONSENT_MS = 60_000L;
    public static final long DEFAULT_SESSION_MS = 15 * 60_000L;
    private final RemoteViewSessionDAO dao;
    private final SecureRandom random = new SecureRandom();
    private final ScheduledExecutorService cleanup = ExecutorRegistry.register(Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "remote-view-expiry"); t.setDaemon(true); return t;
    }));
    @Inject public RemoteViewSessionService(RemoteViewSessionDAO dao) {
        this.dao = dao;
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
        return dao.transition(id, "requested", "signaling", now, session.getRequesterUserId(), "agent joined") ? session : null;
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
