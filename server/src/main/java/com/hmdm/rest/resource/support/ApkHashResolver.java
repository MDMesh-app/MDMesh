/*
 * MDMesh: computes the SHA-256 of an APK this server hosts, for install integrity.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 */

package com.hmdm.rest.resource.support;

import com.hmdm.rest.json.InstallIntegrity;
import com.hmdm.util.HostedFiles;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
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
    static final String NOT_HOSTED = "it is not hosted by this server and no SHA-256 is known for it — set it on "
            + "the Library app (SHA-256…), enter it with the URL under Custom APK, or upload the APK";

    private final HostedFiles hostedFiles;

    @Inject
    public ApkHashResolver(@Named("files.directory") String filesDirectory, @Named("base.url") String baseUrl) {
        this.hostedFiles = new HostedFiles(filesDirectory, baseUrl);
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
        return hostedFiles.localFile(url);
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
