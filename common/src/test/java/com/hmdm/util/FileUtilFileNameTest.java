package com.hmdm.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * An uploaded file's name becomes part of its {@code /files/...} URL as it is, so it must not hold a character the
 * server refuses in a path (AmbiguousPathFilter: {@code ;} and backslash) or one a URL can't carry plainly.
 */
public class FileUtilFileNameTest {

    @Test
    public void charactersThatBreakTheDownloadUrlAreReplaced() {
        assertEquals("my_app_v2.apk", FileUtil.adjustFileName("my app;v2.apk"));
        assertEquals("a_b.apk", FileUtil.adjustFileName("a\\b.apk"));
        assertEquals("a_b_c_.apk", FileUtil.adjustFileName("a+b%c .apk"));
        assertEquals("appcopy.apk", FileUtil.adjustFileName("app(copy).apk"));
    }

    @Test
    public void ordinaryNamesAreKept() {
        assertEquals("app-1.2.3_release.apk", FileUtil.adjustFileName("app-1.2.3_release.apk"));
        assertEquals("été.apk", FileUtil.adjustFileName("été.apk"));
    }
}
