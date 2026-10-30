/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */

package com.hmdm.plugins.audit.rest.filter;

import org.junit.Test;

import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/**
 * An audited request hands the resource the exact bytes the client sent, and the audit log text is decoded with the
 * request's charset (UTF-8 when it has none), never with the JVM default charset.
 */
public class ServletRequestAuditWrapperTest {

    private static final String TEXT = "{\"type\":\"device.alert\",\"payload\":\"{\\\"text\\\":\\\"Zażółć gęślą jaźń\\\","
            + "\\\"password\\\":\\\"pässwort\\\"}\"}";

    @Test
    public void passesAUtf8BodyThroughByteForByte() throws IOException {
        byte[] sent = TEXT.getBytes(StandardCharsets.UTF_8);
        ServletRequestAuditWrapper wrapper = new ServletRequestAuditWrapper(request(sent, "UTF-8"));

        assertArrayEquals(sent, readAll(wrapper.getInputStream()));
        // The body can be read more than once: the audit log reads it after the resource did
        assertArrayEquals(sent, readAll(wrapper.getInputStream()));
        assertEquals(TEXT, wrapper.getBody());
        assertEquals(TEXT, wrapper.getReader().readLine());
    }

    @Test
    public void decodesABodyWithoutCharsetAsUtf8() throws IOException {
        byte[] sent = TEXT.getBytes(StandardCharsets.UTF_8);
        ServletRequestAuditWrapper wrapper = new ServletRequestAuditWrapper(request(sent, null));

        assertArrayEquals(sent, readAll(wrapper.getInputStream()));
        assertEquals(TEXT, wrapper.getBody());
        assertEquals(TEXT, wrapper.getReader().readLine());
    }

    @Test
    public void decodesABodyWithTheRequestCharset() throws IOException {
        String text = "{\"password\":\"pässwort\"}";
        byte[] sent = text.getBytes(StandardCharsets.ISO_8859_1);
        ServletRequestAuditWrapper wrapper = new ServletRequestAuditWrapper(request(sent, "ISO-8859-1"));

        assertArrayEquals(sent, readAll(wrapper.getInputStream()));
        assertEquals(text, wrapper.getBody());
        assertEquals(text, wrapper.getReader().readLine());
    }

    @Test
    public void fallsBackToUtf8ForAnUnknownCharset() throws IOException {
        byte[] sent = TEXT.getBytes(StandardCharsets.UTF_8);
        ServletRequestAuditWrapper wrapper = new ServletRequestAuditWrapper(request(sent, "no-such-charset"));

        assertArrayEquals(sent, readAll(wrapper.getInputStream()));
        assertEquals(TEXT, wrapper.getBody());
    }

    @Test
    public void keepsAnEmptyBody() throws IOException {
        ServletRequestAuditWrapper wrapper = new ServletRequestAuditWrapper(request(new byte[0], null));

        assertArrayEquals(new byte[0], readAll(wrapper.getInputStream()));
        assertEquals("", wrapper.getBody());
    }

    private static HttpServletRequest request(byte[] body, String characterEncoding) {
        ByteArrayInputStream in = new ByteArrayInputStream(body);
        ServletInputStream servletInputStream = new ServletInputStream() {
            @Override
            public int read() {
                return in.read();
            }
        };
        return (HttpServletRequest) Proxy.newProxyInstance(
                ServletRequestAuditWrapperTest.class.getClassLoader(), new Class<?>[]{HttpServletRequest.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getInputStream":
                            return servletInputStream;
                        case "getCharacterEncoding":
                            return characterEncoding;
                        default:
                            return null;
                    }
                });
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            out.write(b);
        }
        return out.toByteArray();
    }
}
