/*
 * MDMesh: where a queued app.install gets its APK SHA-256.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 */

package com.hmdm.rest.resource.support;

import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.AgentCommand;
import com.hmdm.util.InstallIntegrity;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * The SHA-256 of an APK a customer installs: a file this server hosts is hashed live; any other URL takes the hash
 * stored on the customer's Library version with that URL. Used when the console queues an {@code app.install} and
 * again when one is delivered (commands queued before hashes were required carry none).
 */
@Singleton
public class InstallHashes {

    private final ApkHashResolver hosted;
    private final UnsecureDAO unsecureDAO;

    @Inject
    public InstallHashes(ApkHashResolver hosted, UnsecureDAO unsecureDAO) {
        this.hosted = hosted;
        this.unsecureDAO = unsecureDAO;
    }

    public InstallIntegrity.Sha256Source forCustomer(int customerId) {
        return InstallIntegrity.hostedThenStored(hosted, url -> unsecureDAO.findApkHashByUrl(customerId, url));
    }

    /**
     * The payload to deliver for {@code command}: unchanged unless it is an {@code app.install}, which gets a sha256 on
     * every APK. @throws IllegalArgumentException with the operator-facing reason when no hash can be had.
     */
    public String forDelivery(AgentCommand command, int customerId) {
        return InstallIntegrity.forCommand(command.getType(), command.getPayload(), forCustomer(customerId));
    }
}
