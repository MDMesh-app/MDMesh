/*
 * Copyright (C) 2026 the MDMesh contributors. Licensed under the Apache License, Version 2.0.
 */

package com.hmdm.persistence;

import com.hmdm.persistence.domain.Device;
import com.hmdm.rest.json.Response;

/**
 * <p>The device a request of the Headwind launcher's device API names, as that API may see it: served, or refused and
 * why. {@link UnsecureDAO} resolves and creates these under the API's one rule (see
 * {@link UnsecureDAO#findLegacyDevice}); a route acts on {@link #getDevice()} only when {@link #isServed()}, and
 * otherwise answers {@link #refusal()} and writes nothing.</p>
 */
public final class LegacyDevice {

    /** How a route resolves the identifier it is given, widest last: each step runs only when the ones before it found nothing. */
    public enum Lookup {
        /** The device's number. */
        NUMBER,
        /** Then the number it had before a migration. */
        OLD_NUMBER,
        /** Then its IMEI or serial number. */
        IMEI_OR_SERIAL
    }

    public enum Status {
        /** A launcher device the API may read and write. */
        SERVED,
        /** No device: a route that creates devices on demand may create one with this number. */
        ABSENT,
        /** An agent-enrolled device: answered exactly as {@link #ABSENT}, but never created over. */
        AGENT_DEVICE,
        /**
         * An agent-managed configuration (one an agent-enrolled device uses or a live enrollment token enrolls into):
         * the device in it, or one that would be created into it.
         */
        AGENT_CONFIGURATION
    }

    /** The launcher's answer for a device in, or a device it asked to create into, an agent-managed configuration. */
    public static final String AGENT_CONFIGURATION_ERROR = "error.configuration.agent.managed";

    private static final LegacyDevice ABSENT = new LegacyDevice(Status.ABSENT, null, null);
    private static final LegacyDevice AGENT_DEVICE = new LegacyDevice(Status.AGENT_DEVICE, null, null);
    private static final LegacyDevice AGENT_CONFIGURATION = new LegacyDevice(Status.AGENT_CONFIGURATION, null, null);

    private final Status status;
    private final Device device;
    private final Lookup match;

    private LegacyDevice(Status status, Device device, Lookup match) {
        this.status = status;
        this.device = device;
        this.match = match;
    }

    static LegacyDevice served(Device device, Lookup match) {
        return new LegacyDevice(Status.SERVED, device, match);
    }

    static LegacyDevice absent() {
        return ABSENT;
    }

    static LegacyDevice agentDevice() {
        return AGENT_DEVICE;
    }

    static LegacyDevice agentConfiguration() {
        return AGENT_CONFIGURATION;
    }

    public Status getStatus() {
        return status;
    }

    public boolean isServed() {
        return status == Status.SERVED;
    }

    /** No device has this identifier, so a route may create one on demand. */
    public boolean isAbsent() {
        return status == Status.ABSENT;
    }

    /** The device, when {@link #isServed() served}; else null. */
    public Device getDevice() {
        return device;
    }

    /** Which lookup found the device, when served; else null. */
    public Lookup getMatch() {
        return match;
    }

    /**
     * The launcher's answer when the device is not served: "device not found" when there is none and for an agent
     * device (which the API never reveals), {@link #AGENT_CONFIGURATION_ERROR} for an agent-managed configuration.
     */
    public Response refusal() {
        return status == Status.AGENT_CONFIGURATION
                ? Response.ERROR(AGENT_CONFIGURATION_ERROR)
                : Response.DEVICE_NOT_FOUND_ERROR();
    }
}
