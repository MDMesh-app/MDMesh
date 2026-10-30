package com.hmdm.plugins.audit.rest.filter;

import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

public class ServletRequestAuditWrapper extends HttpServletRequestWrapper {
    // The body exactly as the client sent it; the resource reads these same bytes, whatever the JVM default charset
    private final byte[] rawBody;
    private final Charset charset;
    private final String body;

    public ServletRequestAuditWrapper(HttpServletRequest request) throws IOException {
        //So that other request method behave just like before
        super(request);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        InputStream inputStream = request.getInputStream();
        if (inputStream != null) {
            try {
                byte[] buffer = new byte[1024];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    bytes.write(buffer, 0, bytesRead);
                }
            } finally {
                inputStream.close();
            }
        }
        rawBody = bytes.toByteArray();
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
        final ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(rawBody);
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

    //Use this method to read the request body N times
    public String getBody() {
        return this.body;
    }
}
