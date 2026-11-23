/*
 * Copyright (C) 2026 the MDMesh contributors. Licensed under the Apache License, Version 2.0.
 */

package com.hmdm.plugins.audit.rest.filter;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * <p>The scrub policy for audit records older releases stored, applied by {@code AuditScrubTask}: the one place that
 * decides what an old record keeps.</p>
 *
 * <p>Policy: mask in place. The record keeps its method, URI and User-Agent, and its body goes through the same
 * {@link AuditBody} the rule stores new bodies with, so an old record ends up as a new one would: secrets become masks
 * or markers, the rest of the body stays. A body that can't be parsed (old releases stored raw text), and the body of a
 * rule that now stores none, are replaced with {@link AuditBody#NOT_STORED}, and a body over 1 MB with
 * {@link AuditBody#TOO_LARGE}, unparsed, as for a new record. Scrubbing a scrubbed record changes nothing.</p>
 */
public final class StoredPayloads {

    private static final String BODY = "\nBody: ";
    private static final String USER_AGENT = "\nUser-Agent: ";

    /**
     * Payloads up to this many characters are read whole; a longer one's body is over 1 MB whatever its header (method,
     * URI, User-Agent: far less than the margin), so it is replaced without being read ({@link #scrubTooLarge}).
     */
    public static final int READ_WHOLE = ServletRequestAuditWrapper.MAX_BODY + 64 * 1024;

    /** The most payload bytes one scrub batch reads (at most about 32 MB of strings). */
    public static final long BATCH_BYTES = 16L * 1024 * 1024;

    private StoredPayloads() {
    }

    /** The most of a User-Agent, URI or method an old record too large to read keeps. */
    private static final int KEPT_LINE = 512;

    /**
     * <p>The payload an old record too large to read keeps ({@link #READ_WHOLE}): its method and URI from {@code head},
     * its User-Agent from {@code tail} (each cut to {@link #KEPT_LINE} characters, the URI masked), and
     * {@link AuditBody#TOO_LARGE} as its body. Never the record as it was: what made it large is not known to be the
     * body, so nothing else of it is kept.</p>
     *
     * @param head the payload's first characters.
     * @param tail the payload's last characters.
     * @return the scrubbed payload.
     */
    public static String scrubTooLarge(String head, String tail) {
        StringBuilder out = new StringBuilder();
        String method = line(head, "Method: ");
        String uri = line(head, "URI: ");
        if (method != null) {
            out.append("Method: ").append(cut(method));
        }
        if (uri != null) {
            out.append(out.length() > 0 ? "\n" : "").append("URI: ").append(cut(AuditBody.maskTextSecrets(uri)));
        }
        out.append(BODY).append(AuditBody.TOO_LARGE);
        int agent = tail == null ? -1 : tail.lastIndexOf(USER_AGENT);
        if (agent >= 0) {
            out.append(USER_AGENT).append(cut(tail.substring(agent + USER_AGENT.length())));
        }
        return out.toString();
    }

    /** The rest of the line of {@code text} that starts with {@code prefix} (its first line, or one after a break). */
    private static String line(String text, String prefix) {
        int start;
        if (text.startsWith(prefix)) {
            start = prefix.length();
        } else {
            int at = text.indexOf("\n" + prefix);
            if (at < 0) {
                return null;
            }
            start = at + 1 + prefix.length();
        }
        int end = text.indexOf('\n', start);
        return text.substring(start, end < 0 ? text.length() : end);
    }

    private static String cut(String text) {
        return text.length() > KEPT_LINE ? text.substring(0, KEPT_LINE) : text;
    }

    /**
     * <p>The payload an old record of {@code action} keeps.</p>
     *
     * @param action the record's action key.
     * @param payload the record's payload, as stored ("Method: ...\nURI: ...\nBody: ...\nUser-Agent: ...").
     * @return the scrubbed payload, the same text when there is nothing to scrub.
     */
    public static String scrub(String action, String payload) {
        if (payload == null) {
            return null;
        }
        int start = payload.indexOf(BODY);
        if (start < 0) {
            return payload;
        }
        // The body runs to the last User-Agent line: the header holds no line break, a raw body may
        int end = payload.lastIndexOf(USER_AGENT);
        if (end < start) {
            end = payload.length();
        }
        String body = payload.substring(start + BODY.length(), end);
        Optional<AuditBody> rule = bodyOf(action);
        String scrubbed;
        if (body.equals(AuditBody.TOO_LARGE) || body.equals(AuditBody.NOT_STORED)) {
            // A note this release wrote: kept as it is
            scrubbed = body;
        } else if (!rule.isPresent()) {
            scrubbed = AuditBody.NOT_STORED;
        } else if (body.length() > ServletRequestAuditWrapper.MAX_BODY) {
            // Never parsed, as for a new record: old releases stored bodies of any size
            scrubbed = AuditBody.TOO_LARGE;
        } else {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            scrubbed = bytes.length > ServletRequestAuditWrapper.MAX_BODY ? AuditBody.TOO_LARGE
                    : rule.get().stored(bytes);
        }
        return payload.substring(0, start) + BODY + scrubbed + payload.substring(end);
    }

    /**
     * How a body of {@code action} is stored now: its rule's way, {@link AuditBody#MASKED} for an action no rule
     * records any more, none for a rule that stores no body.
     */
    private static Optional<AuditBody> bodyOf(String action) {
        Optional<ResourceAuditInfo> rule = Stream.of(ResourceAuditInfo.values())
                .filter(r -> r.getAuditLogAction().equals(action))
                .findFirst();
        if (!rule.isPresent()) {
            return Optional.of(AuditBody.MASKED);
        }
        return rule.get().storesBody() ? Optional.of(rule.get().body()) : Optional.empty();
    }
}
