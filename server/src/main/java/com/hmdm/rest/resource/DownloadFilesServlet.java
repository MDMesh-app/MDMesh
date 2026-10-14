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

package com.hmdm.rest.resource;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import javax.inject.Named;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.hmdm.notification.rest.NotificationResource;
import com.hmdm.persistence.ApplicationDAO;
import com.hmdm.rest.filter.PublicIPFilter;
import com.hmdm.rest.json.Response;
import com.hmdm.util.CryptoUtil;
import com.hmdm.util.HostedFiles;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class DownloadFilesServlet extends HttpServlet {
    private final ApplicationDAO applicationDAO;
    private final File baseDirectory;
    private final PublicIPFilter publicIPFilter;

    private boolean secureEnrollment;
    private String hashSecret;

    private static final String HEADER_ENROLLMENT_SIGNATURE = "X-Request-Signature";
    private static final String CONTENT_TYPE_APK = "application/vnd.android.package-archive";

    private static final Logger log = LoggerFactory.getLogger(DownloadFilesServlet.class);

    @Inject
    public DownloadFilesServlet(ApplicationDAO applicationDAO,
                                PublicIPFilter publicIPFilter,
                                @Named("files.directory") String filesDirectory,
                                @Named("secure.enrollment") boolean secureEnrollment,
                                @Named("hash.secret") String hashSecret) {
        this.applicationDAO = applicationDAO;
        this.baseDirectory = new File(filesDirectory);
        this.publicIPFilter = publicIPFilter;
        this.secureEnrollment = secureEnrollment;
        this.hashSecret = hashSecret;
        if (!this.baseDirectory.exists()) {
            this.baseDirectory.mkdirs();
        }

    }

    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String path = URLDecoder.decode(req.getRequestURI(), "UTF8");
        int index = path.indexOf("/files/", 0) + "/files/".length();
        path = path.substring(index);

        if (!publicIPFilter.match(req)) {
            log.warn("Request blocked by IP: " + req.getRemoteAddr());
            resp.sendError(403);
            return;
        }

        if (secureEnrollment && !applicationDAO.isMainApp("%" + path)) {
            String signature = req.getHeader(HEADER_ENROLLMENT_SIGNATURE);
            if (signature == null) {
                log.warn("No signature for file request " + req.getRequestURL().toString());
                resp.sendError(403);
                return;
            }
            try {
                String goodSignature = CryptoUtil.getSHA1String(hashSecret + path);
                if (!signature.equalsIgnoreCase(goodSignature)) {
                    log.warn("Wrong signature for file request " + path + ": " + signature + " Should be: " + goodSignature);
                    resp.sendError(403);
                    return;
                }
            } catch (Exception e) {
            }
        }

        File file = resolveRequestedFile(this.baseDirectory, req.getRequestURI());
        if (file != null) {

            long modifiedSince = req.getDateHeader("If-Modified-Since");
            if (modifiedSince != -1 && modifiedSince > file.lastModified()) {
                // Client can use cached images
                resp.setStatus(304);
                return;
            }

            // XSS: never let a hosted file open as a page on the server's domain (full and partial responses)
            resp.addHeader("Content-Disposition", "attachment; filename=\"" + file.getName() + "\"");
            String range = singleRange(req.getHeader("Range"));
            if (range != null) {
                sendPartialContent(range, file, resp);
                return;
            }

            try (InputStream input = new FileInputStream(file);
                 ServletOutputStream outputStream = resp.getOutputStream()) {
                long length = file.length();
                if (length <= 2147483647L) {
                    resp.setContentLength((int)length);
                } else {
                    resp.addHeader("Content-Length", Long.toString(length));
                }
                if (file.getAbsolutePath().endsWith(".apk")) {
                    resp.setContentType(CONTENT_TYPE_APK);
                }

                IOUtils.copyLarge(input, outputStream);
                outputStream.flush();
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            log.debug("Not found: {}", path);
            resp.sendError(404);
        }

    }

    /**
     * <p>Maps a {@code /files/...} request URI to the file it names: decoded exactly once, and only when the
     * result is an existing regular file whose real path (symlinks resolved) lies inside {@code baseDirectory}.</p>
     *
     * @return the file to serve, or null when there is nothing servable at that path.
     */
    static File resolveRequestedFile(File baseDirectory, String requestUri) {
        if (requestUri == null) {
            return null;
        }
        int index = requestUri.indexOf("/files/");
        if (index < 0) {
            return null;
        }
        try {
            return HostedFiles.containedFile(baseDirectory,
                    URLDecoder.decode(requestUri.substring(index + "/files/".length()), "UTF8"));
        } catch (IllegalArgumentException | IOException e) {
            return null;
        }
    }

    /**
     * @return the spec of a single {@code bytes=} range, or null when there is none. A multi-range request is
     *         served as the whole file (allowed by RFC 9110), so it gets null too.
     */
    static String singleRange(String header) {
        return header != null && header.startsWith("bytes=") && !header.contains(",") ? header.substring(6) : null;
    }

    /**
     * <p>Parses a single {@code bytes=} range spec ({@code first-last}, {@code first-} or the suffix {@code -n}).</p>
     *
     * @return the first and last byte (both inclusive) clamped to the file, or null when it is malformed or
     *         unsatisfiable.
     */
    static long[] byteRange(String rangeStr, long length) {
        try {
            String[] parts = rangeStr.trim().split("-", 2);
            if (parts.length < 2) return null;
            String a = parts[0].trim();
            String b = parts[1].trim();
            long first;
            long last;
            if (a.isEmpty()) {
                first = Math.max(0, length - Long.parseLong(b)); // the last n bytes
                last = length - 1;
            } else {
                first = Long.parseLong(a);
                last = b.isEmpty() ? length - 1 : Math.min(Long.parseLong(b), length - 1);
            }
            return first >= 0 && first <= last ? new long[]{first, last} : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void sendPartialContent(String rangeStr, File file, HttpServletResponse resp) {
        long length = file.length();
        long[] range = byteRange(rangeStr, length);
        if (range == null) {
            resp.setStatus(416);
            resp.setHeader("Content-Range", "bytes */" + length);
            return;
        }
        long contentLength = range[1] - range[0] + 1;
        resp.setStatus(206);
        resp.setHeader("Content-Range", "bytes " + range[0] + "-" + range[1] + "/" + length);
        if (contentLength <= 2147483647L) {
            resp.setContentLength((int) contentLength);
        } else {
            resp.addHeader("Content-Length", Long.toString(contentLength));
        }
        if (file.getAbsolutePath().endsWith(".apk")) {
            resp.setContentType(CONTENT_TYPE_APK);
        }
        try (InputStream input = new FileInputStream(file);
             ServletOutputStream outputStream = resp.getOutputStream()) {
            IOUtils.copyLarge(input, outputStream, range[0], contentLength);
            outputStream.flush();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
