/*
 * Copyright (C) 2026 the MDMesh contributors. Licensed under the Apache License, Version 2.0.
 */

package com.hmdm.rest.json.agent;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

/**
 * Per-configuration desired-state sync counts across a customer's devices, as of the current
 * configuration revision. {@link #inSync}, {@link #outOfSync}, {@link #unsupported} and {@link #neverSeen} are disjoint
 * and add up to {@link #total}.
 */
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ConfigSyncSummary {
    private int configurationId;
    private int total;
    private int inSync;
    private int outOfSync;
    private int unsupported;
    private int neverSeen;
    /** Of {@link #inSync}: devices whose latest config.apply deferred some settings. */
    private int deferred;
    /** Wiped devices still on this configuration: outside {@link #total}, but they do block deleting it. */
    private int wiped;
}
