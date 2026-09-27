package com.hmdm.plugins.audit.rest.filter;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ReadListener;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Proxy;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ServletOutputStreamWrapperTest {

    @Test
    public void writesToTheResponseAndCapturesTheSameBytes() throws IOException {
        RecordingServletOutputStream response = new RecordingServletOutputStream();
        ServletOutputStreamWrapper wrapper = new ServletOutputStreamWrapper(response);

        wrapper.write('M');
        wrapper.write('D');

        assertArrayEquals(new byte[] {'M', 'D'}, response.content.toByteArray());
        assertArrayEquals(new byte[] {'M', 'D'}, wrapper.getContent());
    }

    @Test
    public void delegatesNonBlockingServletMethods() {
        RecordingServletOutputStream response = new RecordingServletOutputStream();
        ServletOutputStreamWrapper wrapper = new ServletOutputStreamWrapper(response);
        WriteListener listener = new WriteListener() {
            @Override
            public void onWritePossible() {
            }

            @Override
            public void onError(Throwable throwable) {
            }
        };

        assertTrue(wrapper.isReady());
        wrapper.setWriteListener(listener);

        assertSame(listener, response.listener);
    }

    @Test(expected = IllegalStateException.class)
    public void requestWrapperReplaysTheBodyButRejectsAsyncReads() throws IOException {
        HttpServletRequest request = (HttpServletRequest) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {HttpServletRequest.class},
                (proxy, method, arguments) -> {
                    if ("getInputStream".equals(method.getName())) {
                        return new ByteArrayServletInputStream("MDMesh".getBytes(StandardCharsets.UTF_8));
                    }
                    if ("getCharacterEncoding".equals(method.getName())) {
                        return StandardCharsets.UTF_8.name();
                    }
                    return null;
                });
        ServletRequestAuditWrapper wrapper = new ServletRequestAuditWrapper(request);

        assertEquals("MDMesh", wrapper.getBody());
        ServletInputStream replayed = wrapper.getInputStream();
        assertEquals('M', replayed.read());
        replayed.setReadListener(null);
    }

    private static class RecordingServletOutputStream extends ServletOutputStream {
        private final ByteArrayOutputStream content = new ByteArrayOutputStream();
        private WriteListener listener;

        @Override
        public void write(int value) {
            content.write(value);
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setWriteListener(WriteListener listener) {
            this.listener = listener;
        }
    }

    private static class ByteArrayServletInputStream extends ServletInputStream {
        private final java.io.ByteArrayInputStream input;

        private ByteArrayServletInputStream(byte[] content) {
            input = new java.io.ByteArrayInputStream(content);
        }

        @Override
        public int read() {
            return input.read();
        }

        @Override
        public boolean isFinished() {
            return input.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {
        }
    }
}
