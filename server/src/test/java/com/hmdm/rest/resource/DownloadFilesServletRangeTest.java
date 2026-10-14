/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.rest.resource;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** A {@code Range: bytes=} spec maps to inclusive first/last bytes, as RFC 9110 defines them. */
public class DownloadFilesServletRangeTest {

    private static long[] range(String spec, long length) {
        return DownloadFilesServlet.byteRange(spec, length);
    }

    @Test
    public void the_end_is_inclusive() {
        assertArrayEquals("bytes=0-999 is 1000 bytes", new long[]{0, 999}, range("0-999", 5000));
    }

    @Test
    public void an_open_end_runs_to_the_last_byte() {
        assertArrayEquals(new long[]{100, 4999}, range("100-", 5000));
    }

    @Test
    public void an_end_past_the_file_is_clamped() {
        assertArrayEquals(new long[]{4000, 4999}, range("4000-9999", 5000));
    }

    @Test
    public void a_suffix_range_is_the_last_n_bytes() {
        assertArrayEquals(new long[]{4500, 4999}, range("-500", 5000));
        assertArrayEquals(new long[]{0, 4999}, range("-9000", 5000));
    }

    @Test
    public void a_multi_range_request_gets_the_whole_file() {
        assertNull(DownloadFilesServlet.singleRange("bytes=0-1,5-9"));
        assertNull(DownloadFilesServlet.singleRange(null));
        assertEquals("0-999", DownloadFilesServlet.singleRange("bytes=0-999"));
    }

    @Test
    public void unsatisfiable_or_malformed_single_ranges_are_rejected() {
        assertNull(range("5000-", 5000));
        assertNull(range("10-5", 5000));
        assertNull(range("abc", 5000));
        assertNull(range("0-", 0));
    }
}
