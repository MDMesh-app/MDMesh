package com.hmdm.util;

/**
 * The server's {@code base.url} as kiosk background image URLs use it: without trailing slashes, so a configured
 * {@code https://host/} gives {@code https://host/files/...}, not {@code https://host//files/...}.
 */
public final class BaseUrl {
    private BaseUrl() {}

    /** {@code baseUrl} without trailing slashes; null stays null. */
    public static String trim(String baseUrl) {
        if (baseUrl == null) return null;
        int end = baseUrl.length();
        while (end > 0 && baseUrl.charAt(end - 1) == '/') end--;
        return baseUrl.substring(0, end);
    }

    /**
     * The same address, slashes repeated in the path aside: what an install with a trailing-slash base URL stored
     * ({@code https://host//files/x}) and the same URL built from the trimmed base URL ({@code https://host/files/x}).
     */
    public static boolean sameAddress(String a, String b) {
        if (a == null || b == null) return a == b;
        return collapse(a).equals(collapse(b));
    }

    private static String collapse(String url) {
        int scheme = url.indexOf("://");
        int pathStart = scheme < 0 ? 0 : url.indexOf('/', scheme + 3);
        if (pathStart < 0) return url;
        return url.substring(0, pathStart) + url.substring(pathStart).replaceAll("/{2,}", "/");
    }
}
