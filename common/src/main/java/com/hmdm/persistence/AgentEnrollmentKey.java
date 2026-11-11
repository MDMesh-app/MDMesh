/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */

package com.hmdm.persistence;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.hmdm.persistence.mapper.AgentEnrollmentKeyMapper;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;

import java.security.SecureRandom;

/**
 * <p>The server-only key that agent enrollment secrets are derived with ({@link AgentEnrollmentDAO}): 32 random bytes
 * made with {@link SecureRandom} on first use and kept in the database (table {@code agentEnrollmentKey}, one row), so
 * every server instance and every restart derive the same secret for one enrollment attempt. It is in no setting,
 * file, API answer or log; it is in the database and its backups.</p>
 *
 * <p>Read and created on a session of its own (auto-commit), outside any enrollment transaction: a key is never kept
 * in memory that a rolled-back transaction took out of the database. Several first uses at once keep exactly one key
 * (insert-if-absent, then read back).</p>
 *
 * <p>Rotating it (deleting the row, then restarting the server) only matters for enrollments still inside their retry
 * window: their next retry gets a secret derived with the new key, which the server then stores.</p>
 */
@Singleton
public class AgentEnrollmentKey {

    private static final int KEY_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SqlSessionFactory sessions;
    private volatile byte[] key;

    @Inject
    public AgentEnrollmentKey(SqlSessionFactory sessions) {
        this.sessions = sessions;
    }

    /** The key's bytes (a copy). Loads, or makes and stores, it on first use. */
    public byte[] bytes() {
        byte[] k = key;
        if (k == null) {
            synchronized (this) {
                if (key == null) {
                    key = load();
                }
                k = key;
            }
        }
        return k.clone();
    }

    private byte[] load() {
        try (SqlSession session = sessions.openSession(true)) {
            AgentEnrollmentKeyMapper mapper = session.getMapper(AgentEnrollmentKeyMapper.class);
            String hex = mapper.get();
            if (hex == null) {
                byte[] fresh = new byte[KEY_BYTES];
                RANDOM.nextBytes(fresh);
                mapper.insertIfAbsent(toHex(fresh), System.currentTimeMillis());
                hex = mapper.get(); // ours, or the one a concurrent first use stored first
            }
            if (hex == null || !hex.matches("[0-9a-f]{" + (KEY_BYTES * 2) + "}")) {
                throw new IllegalStateException("the enrollment key in the database is missing or malformed");
            }
            return fromHex(hex);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }

    private static byte[] fromHex(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }
}
