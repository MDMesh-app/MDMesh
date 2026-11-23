/*
 * Copyright (C) 2026 the MDMesh contributors. Licensed under the Apache License, Version 2.0.
 */

package com.hmdm.plugins.audit.persistence.domain;

/**
 * <p>An old audit record as the scrub reads it (AuditMapper.findForScrub). A payload over the scrub's size limit is not
 * read whole: only its start and end, where the method, URI and User-Agent are.</p>
 */
public class AuditScrubRow {

    private int id;
    private String action;
    /** The payload, or null when it is over the limit (then {@link #head} and {@link #tail} hold its ends). */
    private String payload;
    private String head;
    private String tail;

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public String getHead() {
        return head;
    }

    public void setHead(String head) {
        this.head = head;
    }

    public String getTail() {
        return tail;
    }

    public void setTail(String tail) {
        this.tail = tail;
    }
}
