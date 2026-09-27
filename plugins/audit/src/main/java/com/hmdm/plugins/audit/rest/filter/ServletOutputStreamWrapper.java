/*
 *
 * Headwind MDM: Open Source Android MDM Software
 * https://h-mdm.com
 *
 * Copyright (C) 2019 Headwind Solutions LLC (http://h-sms.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.hmdm.plugins.audit.rest.filter;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * <p>A wrapper around the servlet response stream used for capturing the content of the response.</p>
 *
 * @author isv
 */
public class ServletOutputStreamWrapper extends ServletOutputStream {

    /**
     * <p>An original servlet response stream wrapped by this wrapper.</p>
     */
    private final ServletOutputStream outputStream;

    /**
     * <p>A copy of the response content collected while client code writes to response stream.</p>
     */
    private final ByteArrayOutputStream copy;

    public ServletOutputStreamWrapper(ServletOutputStream outputStream) {
        this.outputStream = outputStream;
        this.copy = new ByteArrayOutputStream(1024);
    }

    @Override
    public void write(int b) throws IOException {
        outputStream.write(b);
        copy.write(b);
    }

    @Override
    public boolean isReady() {
        return outputStream.isReady();
    }

    @Override
    public void setWriteListener(WriteListener writeListener) {
        outputStream.setWriteListener(writeListener);
    }

    /**
     * <p>Gets the content of the response.</p>
     *
     * @return a content of the response.
     */
    public byte[] getContent() {
        return copy.toByteArray();
    }

}
