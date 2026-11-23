// Derived from Headwind MDM (Apache-2.0); modified by the MDMesh contributors.
package com.hmdm.plugins.audit.rest.filter;

import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

public class ServletRequestAuditWrapper extends HttpServletRequestWrapper {
    /**
     * The most of a body the audit buffers: a longer one goes to the resource whole, streamed past what was buffered,
     * and the record says it was not stored (no audited route takes anything near this).
     */
    static final int MAX_BODY = 1024 * 1024;

    // The body exactly as the client sent it; the resource reads these same bytes, whatever the JVM default charset
    private final byte[] rawBody;
    // What is left of a body over MAX_BODY, unread, for the resource; null when the whole body is in rawBody
    private final InputStream rest;
    private final Charset charset;
    private final String body;

    public ServletRequestAuditWrapper(HttpServletRequest request) throws IOException {
        //So that other request method behave just like before
        super(request);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        InputStream inputStream = request.getInputStream();
        boolean whole = true;
        if (inputStream != null) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                bytes.write(buffer, 0, bytesRead);
                if (bytes.size() > MAX_BODY) {
                    whole = false;
                    break;
                }
            }
            if (whole) {
                inputStream.close();
            }
        }
        rawBody = bytes.toByteArray();
        rest = whole ? null : inputStream;
        charset = requestCharset(request);
        //Store request body content in 'body' variable, decoded only for the audit log
        body = new String(rawBody, charset);
    }

    private static Charset requestCharset(HttpServletRequest request) {
        String encoding = request.getCharacterEncoding();
        if (encoding != null) {
            try {
                return Charset.forName(encoding);
            } catch (IllegalArgumentException e) {
                // Unknown or malformed charset name, use the JSON default
            }
        }
        return StandardCharsets.UTF_8;
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
        final InputStream byteArrayInputStream = rest == null ? new ByteArrayInputStream(rawBody)
                : new SequenceInputStream(new ByteArrayInputStream(rawBody), rest);
        ServletInputStream servletInputStream = new ServletInputStream() {
            public int read() throws IOException {
                return byteArrayInputStream.read();
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                return byteArrayInputStream.read(b, off, len);
            }
        };
        return servletInputStream;
    }

    @Override
    public BufferedReader getReader() throws IOException {
        return new BufferedReader(new InputStreamReader(this.getInputStream(), charset));
    }

    // The body exactly as the client sent it, for parsing it the way the resource does
    byte[] getRawBody() {
        return this.rawBody;
    }

    /** Whether the body was over {@link #MAX_BODY}: then it is not stored, and the resource can read it once. */
    boolean isTooLarge() {
        return rest != null;
    }

    //Use this method to read the request body N times
    public String getBody() {
        return this.body;
    }
}
