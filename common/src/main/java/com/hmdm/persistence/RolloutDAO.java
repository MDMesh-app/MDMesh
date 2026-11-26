/*
 * Copyright (C) 2026 the MDMesh contributors. Licensed under the Apache License, Version 2.0.
 */

package com.hmdm.persistence;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.hmdm.persistence.domain.AgentCommand;
import com.hmdm.persistence.domain.AgentRollout;
import com.hmdm.persistence.domain.RolloutDeviceRow;
import com.hmdm.persistence.mapper.RolloutMapper;
import com.hmdm.util.RolloutProgress;

import java.util.List;
import java.util.Set;

/** DAO for staged agent-APK rollouts. Thin wrapper over {@link RolloutMapper}. */
@Singleton
public class RolloutDAO {

    private final RolloutMapper mapper;

    @Inject
    public RolloutDAO(RolloutMapper mapper) {
        this.mapper = mapper;
    }

    public void insertRollout(AgentRollout rollout) {
        mapper.insertRollout(rollout);
    }

    /** @throws DeviceGoneException the device was deleted meanwhile (after the statement's EXISTS check) */
    public void insertCanary(int rolloutId, String deviceNumber) {
        DeviceGoneException.guard(deviceNumber, () -> mapper.insertCanary(rolloutId, deviceNumber));
    }

    public AgentRollout findActiveByCustomer(int customerId) {
        return mapper.findActiveByCustomer(customerId);
    }

    public AgentRollout findById(int id) {
        return mapper.findById(id);
    }

    /** @return false when the rollout was no longer in {@code from} (another request moved it first). */
    public boolean moveStage(int id, String from, String to) {
        return mapper.moveStage(id, from, to, System.currentTimeMillis()) > 0;
    }

    /** @return false when the rollout had already finished or been cancelled. */
    public boolean cancelActive(int id) {
        return mapper.cancelActive(id, System.currentTimeMillis()) > 0;
    }

    public List<String> listCanaryNumbers(int rolloutId) {
        return mapper.listCanaryNumbers(rolloutId);
    }

    public List<RolloutDeviceRow> listCustomerDevices(int customerId) {
        return mapper.listCustomerDevices(customerId);
    }

    /** Device numbers in this customer with an in-flight (pending/delivered) app.install of {@code packageName}. */
    public Set<String> listPendingInstallNumbers(int customerId, String packageName) {
        return RolloutProgress.inFlightFor(packageName, mapper.listInFlightInstalls(customerId));
    }

    public RolloutDeviceRow findDeviceRow(String deviceNumber) {
        return mapper.findDeviceRow(deviceNumber);
    }

    public boolean isCanary(int rolloutId, String deviceNumber) {
        return mapper.isCanary(rolloutId, deviceNumber);
    }

    /** Queues {@code cmd} unless the same command is already in flight for the device. @return true when queued.
     *  @throws DeviceGoneException the device was deleted meanwhile */
    public boolean insertIfNotInFlight(AgentCommand cmd) {
        return DeviceGoneException.guard(cmd.getDeviceNumber(), () -> mapper.insertIfNotInFlight(cmd)) > 0;
    }

    /** The device's app.install commands created at or after {@code since}, newest first. */
    public List<AgentCommand> listInstallsSince(String deviceNumber, long since) {
        return mapper.listInstallsSince(deviceNumber, since);
    }
}
