/*
 * MDMesh: maps a URL of a file this server hosts to the file on disk.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 */

package com.hmdm.util;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * The file a {@code <base.url>/files/...} URL names, read from the files directory, so the server can hash what it
 * hosts without ever fetching a URL. Anything else (another host, a path that leaves the files directory, a missing
 * file) has no local file.
 */
public class HostedFiles {

    private final File filesDirectory;
    private final String[] localPrefixes;

    public HostedFiles(String filesDirectory, String baseUrl) {
        this.filesDirectory = new File(filesDirectory == null ? "" : filesDirectory);
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

    /** True for an {@code http://} or {@code https://} URL (any case); the only kind a new external file may have. */
    public static boolean isHttpUrl(String url) {
        if (url == null) {
            return false;
        }
        String u = url.trim().toLowerCase(Locale.ROOT);
        return (u.startsWith("http://") && u.length() > 7) || (u.startsWith("https://") && u.length() > 8);
    }

    /** The hosted file a {@code <base.url>/files/...} URL names, or null (not ours, missing, or outside the dir). */
    public File localFile(String url) {
        if (url == null) {
            return null;
        }
        String u = url.trim();
        for (String prefix : localPrefixes) {
            if (!u.startsWith(prefix)) {
                continue;
            }
            String rest = u.substring(prefix.length());
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
        return containedFile(filesDirectory, path);
    }

    /**
     * The file {@code relative} (already decoded) names below {@code baseDirectory}: only an existing regular file
     * whose real path (symlinks resolved) lies inside the directory, else null.
     */
    public static File containedFile(File baseDirectory, String relative) {
        if (relative == null) {
            return null;
        }
        while (relative.startsWith("/")) {
            relative = relative.substring(1); // "/files//a" names "a"
        }
        if (relative.isEmpty() || relative.indexOf('\0') >= 0) {
            return null;
        }
        try {
            Path base = baseDirectory.toPath().toRealPath();
            Path requested = base.resolve(relative).normalize();
            if (!requested.startsWith(base) || !Files.exists(requested)) {
                return null;
            }
            Path real = requested.toRealPath();
            if (!real.startsWith(base) || real.equals(base) || !Files.isRegularFile(real)) {
                return null;
            }
            return real.toFile();
        } catch (IllegalArgumentException | IOException e) {
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
}
