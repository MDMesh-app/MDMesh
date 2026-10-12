/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.rest.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.Provider;
import javax.ws.rs.ext.ReaderInterceptor;
import javax.ws.rs.ext.ReaderInterceptorContext;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * <p>Caps the request body of the agent API (resources annotated {@link AgentBodyLimit}). A body that declares a
 * larger {@code Content-Length} is refused with 413 before it is read; an undeclared (chunked) or understated one
 * fails with 413 as soon as more than the limit has been read, so the JSON reader never sees more than that.</p>
 *
 * <p>The limit is a backstop against abuse only, far above anything a real agent sends: telemetry is capped at
 * 256 KB server-side, events at 100 x 4 KB (the agent buffers at most 500 short ones), the capability tree at 64 KB,
 * and current agents send results in batches of at most 100 / 4 MiB. It must stay well clear of legitimate traffic:
 * an agent whose check-in is refused keeps its results and resends the same body, so a too-tight limit would stop
 * that device from ever checking in again. Per-result size is enforced in AgentResource instead.</p>
 */
@Provider
@AgentBodyLimit
public class AgentBodyLimitInterceptor implements ReaderInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(AgentBodyLimitInterceptor.class);

    /** Maximum agent request body, in bytes. */
    public static final long MAX_BODY_BYTES = 32L * 1024 * 1024;

    private final long maxBytes;

    public AgentBodyLimitInterceptor() {
        this(MAX_BODY_BYTES);
    }

    AgentBodyLimitInterceptor(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    @Override
    public Object aroundReadFrom(ReaderInterceptorContext context) throws IOException, WebApplicationException {
        long declared = declaredLength(context.getHeaders().getFirst("Content-Length"));
        if (declared > maxBytes) {
            logger.warn("Agent request refused: declared body of {} bytes exceeds {}", declared, maxBytes);
            throw tooLarge();
        }
        context.setInputStream(new LimitedInputStream(context.getInputStream(), maxBytes));
        return context.proceed();
    }

    private static long declaredLength(String header) {
        if (header == null) {
            return -1;
        }
        try {
            return Long.parseLong(header.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static WebApplicationException tooLarge() {
        return new WebApplicationException(Response.Status.REQUEST_ENTITY_TOO_LARGE);
    }

    /** Counts bytes read and fails once the count passes the limit, reading at most one byte beyond it. */
    private static final class LimitedInputStream extends FilterInputStream {
        private final long maxBytes;
        private long read;

        LimitedInputStream(InputStream in, long maxBytes) {
            super(in);
            this.maxBytes = maxBytes;
        }

        private void count(long n) {
            if (n > 0) {
                read += n;
                if (read > maxBytes) {
                    logger.warn("Agent request refused: body exceeds {} bytes", maxBytes);
                    throw tooLarge();
                }
            }
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                count(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            long allowed = maxBytes - read + 1;
            int n = super.read(buf, off, (int) Math.min(len, Math.max(1, allowed)));
            count(n);
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            long skipped = super.skip(Math.min(n, Math.max(1, maxBytes - read + 1)));
            count(skipped);
            return skipped;
        }

        @Override
        public boolean markSupported() {
            return false;
        }
    }
}
