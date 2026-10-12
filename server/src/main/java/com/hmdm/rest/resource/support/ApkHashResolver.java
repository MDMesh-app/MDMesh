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
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The hex SHA-256 of an APK this server hosts ({@code <base.url>/files/...}), read from the files directory — the bytes
 * uploaded here. The server never downloads a URL to hash it: an APK hosted elsewhere carries a SHA-256 known from its
 * source (the F-Droid index, a rollout, or the admin entering it with the URL), and without one it is not installed.
 */
@Singleton
public class ApkHashResolver implements InstallIntegrity.Sha256Source {

    /** Why an APK hosted elsewhere has no hash; follows "no sha256 for URL: ". */
    static final String NOT_HOSTED = "it is not hosted by this server, so its SHA-256 must be given — "
            + "add it in the Library (or upload the APK)";

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
        if (local == null) {
            throw new IOException(NOT_HOSTED);
        }
        try (InputStream in = new FileInputStream(local)) {
            return digest(in);
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

    private static String digest(InputStream in) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder(64);
        for (byte b : md.digest()) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
