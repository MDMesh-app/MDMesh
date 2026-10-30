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

import com.hmdm.util.CryptoUtil;
import com.hmdm.util.HostedFiles;
import com.hmdm.util.InstallIntegrity;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The hex SHA-256 of an APK this server hosts ({@code <base.url>/files/...}), read from the files directory — the bytes
 * uploaded here. The server never downloads a URL to hash it: an APK hosted elsewhere carries a SHA-256 known from its
 * source (the F-Droid index, a rollout, or the admin entering it with the URL), and without one it is not installed.
 *
 * <p>A file's hash is kept while its size and modification time stay the same (the last {@link #CACHE_SIZE} files),
 * so queuing one APK for a whole fleet reads it once, and a file replaced at the same URL is hashed again.</p>
 */
@Singleton
public class ApkHashResolver implements InstallIntegrity.Sha256Source {

    /** Why an APK hosted elsewhere has no hash (shown after "no sha256 for URL"). */
    static final String NOT_HOSTED = "not hosted by this server";

    /** Hosted files whose hash is kept. */
    static final int CACHE_SIZE = 256;

    private final HostedFiles hostedFiles;
    private final Map<String, Hashed> cache = new LinkedHashMap<String, Hashed>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Hashed> eldest) {
            return size() > CACHE_SIZE;
        }
    };

    /** A file's hash, and the size and modification time it was read at. */
    private static final class Hashed {
        final long length;
        final long lastModified;
        final String hex;

        Hashed(long length, long lastModified, String hex) {
            this.length = length;
            this.lastModified = lastModified;
            this.hex = hex;
        }
    }

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
        String key = local.getPath();
        long length = local.length();
        long lastModified = local.lastModified();
        synchronized (cache) {
            Hashed known = cache.get(key);
            if (known != null && known.length == length && known.lastModified == lastModified) {
                return known.hex;
            }
        }
        String hex;
        try (InputStream in = new FileInputStream(local)) {
            hex = CryptoUtil.getLowerHexString(CryptoUtil.sha256(in));
        }
        synchronized (cache) {
            cache.put(key, new Hashed(length, lastModified, hex));
        }
        return hex;
    }

    /** The hosted file a {@code <base.url>/files/...} URL names, or null (not ours, missing, or outside the dir). */
    File localFile(String url) {
        return hostedFiles.localFile(url);
    }
}
