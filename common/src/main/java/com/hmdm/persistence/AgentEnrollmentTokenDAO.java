/*
 * Copyright (C) 2026 the MDMesh contributors. Licensed under the Apache License, Version 2.0.
 */

package com.hmdm.persistence;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.hmdm.persistence.domain.AgentEnrollmentToken;
import com.hmdm.persistence.mapper.AgentEnrollmentTokenMapper;
import com.hmdm.util.CryptoUtil;

/**
 * <p>DAO for the agent v1 enrollment tokens.</p>
 */
@Singleton
public class AgentEnrollmentTokenDAO {

    private final AgentEnrollmentTokenMapper mapper;

    @Inject
    public AgentEnrollmentTokenDAO(AgentEnrollmentTokenMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Stores the token with only the SHA-256 hash of its value, as device secrets are stored: a database or backup
     * that leaks holds no code that enrolls. {@code token.getToken()} is the plaintext before and after the call
     * (the mint returns it once).
     */
    public void insert(AgentEnrollmentToken token) {
        String plaintext = token.getToken();
        token.setToken(hash(plaintext));
        try {
            mapper.insert(token);
        } finally {
            token.setToken(plaintext);
        }
    }

    /** The stored token whose value is {@code token} (looked up by its hash), or null. */
    public AgentEnrollmentToken findByToken(String token) {
        if (token == null) {
            return null;
        }
        return mapper.findByToken(hash(token));
    }

    /**
     * Stops every token of the customer that has not expired, used up or not: the claim refuses a token with
     * {@code revokedAt} set whatever its count.
     *
     * @return how many tokens were stopped.
     */
    public int revokeAll(int customerId) {
        return mapper.revokeAll(customerId, System.currentTimeMillis());
    }

    /** What the token column holds for a token value: uppercase hex SHA-256, as {@link CryptoUtil#getSHA256String}. */
    static String hash(String token) {
        return CryptoUtil.getSHA256String(token);
    }

}
