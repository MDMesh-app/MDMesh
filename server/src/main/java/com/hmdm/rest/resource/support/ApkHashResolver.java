/*
 * MDMesh: computes the SHA-256 of an APK by URL, for install integrity and QR provisioning.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 */

package com.hmdm.rest.resource.support;

import com.hmdm.rest.json.InstallIntegrity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The hex SHA-256 of an APK named by URL. A file this server hosts ({@code <base.url>/files/...}) is read from the
 * files directory — the bytes uploaded here, with no network round trip (the public URL is often not reachable from
 * inside the container). Any other http(s) URL is downloaded once and hashed: the digest then pins those bytes, so a
 * later change at the source fails the agent's check instead of installing silently.
 */
@Singleton
public class ApkHashResolver implements InstallIntegrity.Sha256Source {

    private static final Logger logger = LoggerFactory.getLogger(ApkHashResolver.class);

    static final int CONNECT_TIMEOUT_MS = 15_000;
    static final int READ_TIMEOUT_MS = 60_000;
    /** Upper bound on a downloaded APK (Play's own limit for a base APK is far below this). */
    static final long MAX_DOWNLOAD_BYTES = 1024L * 1024 * 1024;

    private final File filesDirectory;
    private final String[] localPrefixes;

    @Inject
    public ApkHashResolver(@Named("files.directory") String filesDirectory, @Named("base.url") String baseUrl) {
        this.filesDirectory = new File(filesDirectory);
        String base = baseUrl == null ? "" : baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String origin = base;
        try {
            URL u = new URL(base);
            origin = u.getProtocol() + "://" + u.getHost() + (u.getPort() != -1 ? ":" + u.getPort() : "");
        } catch (Exception ignored) {
            // keep the base as given
        }
        // The files URL FilesResource builds, and the origin-only form QR provisioning has always accepted.
        this.localPrefixes = new String[]{base + "/files/", origin + "/files/"};
    }

    @Override
    public String sha256Hex(String url) throws IOException {
        if (url == null || url.trim().isEmpty()) {
            throw new IOException("no URL");
        }
        String u = url.trim();
        File local = localFile(u);
        if (local != null) {
            try (InputStream in = new FileInputStream(local)) {
                return digest(in, Long.MAX_VALUE);
            }
        }
        if (!(u.startsWith("https://") || u.startsWith("http://"))) {
            throw new IOException("not an http(s) URL");
        }
        logger.info("Hashing the APK at {}", u);
        HttpURLConnection conn = (HttpURLConnection) new URL(u).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setInstanceFollowRedirects(true);
        try {
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + code);
            }
            try (InputStream in = conn.getInputStream()) {
                return digest(in, MAX_DOWNLOAD_BYTES);
            }
        } finally {
            conn.disconnect();
        }
    }

    /** The hosted file a {@code <base.url>/files/...} URL names, or null (not ours, missing, or outside the dir). */
    File localFile(String url) {
        for (String prefix : localPrefixes) {
            if (!url.startsWith(prefix)) {
                continue;
            }
            String rest = url.substring(prefix.length());
            int cut = indexOfAny(rest, '?', '#');
            if (cut >= 0) {
                rest = rest.substring(0, cut);
            }
            for (String path : new String[]{rest, decode(rest)}) {
                File f = contained(path);
                if (f != null) {
                    return f;
                }
            }
        }
        return null;
    }

    private File contained(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        try {
            File root = filesDirectory.getCanonicalFile();
            File f = new File(root, path).getCanonicalFile();
            if (!f.toPath().startsWith(root.toPath()) || !f.isFile()) {
                return null;
            }
            return f;
        } catch (IOException e) {
            return null;
        }
    }

    /** Percent-decoded path ({@code %20} → space), or null when it isn't a valid URI path. */
    private static String decode(String path) {
        try {
            return URI.create("http://h/" + path).getPath().substring(1);
        } catch (Exception e) {
            return null;
        }
    }

    private static int indexOfAny(String s, char a, char b) {
        int i = s.indexOf(a);
        int j = s.indexOf(b);
        return i < 0 ? j : (j < 0 ? i : Math.min(i, j));
    }

    private static String digest(InputStream in, long limit) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        byte[] buf = new byte[64 * 1024];
        long total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > limit) {
                throw new IOException("APK larger than " + limit + " bytes");
            }
            md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder(64);
        for (byte b : md.digest()) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
