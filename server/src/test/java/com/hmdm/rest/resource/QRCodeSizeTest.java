/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.rest.resource;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** The public QR endpoint renders within a fixed pixel budget whatever size is requested. */
public class QRCodeSizeTest {

    @Test
    public void defaultsWhenAbsent() {
        assertEquals(250, QRCodeResource.qrImageSize(null));
    }

    @Test
    public void keepsSizesInRange() {
        assertEquals(64, QRCodeResource.qrImageSize(64));
        assertEquals(300, QRCodeResource.qrImageSize(300));
        assertEquals(1024, QRCodeResource.qrImageSize(1024));
    }

    @Test
    public void clampsZeroNegativeAndHugeSizes() {
        assertEquals(64, QRCodeResource.qrImageSize(0));
        assertEquals(64, QRCodeResource.qrImageSize(-5));
        assertEquals(64, QRCodeResource.qrImageSize(Integer.MIN_VALUE));
        assertEquals(64, QRCodeResource.qrImageSize(63));
        assertEquals(1024, QRCodeResource.qrImageSize(1025));
        assertEquals(1024, QRCodeResource.qrImageSize(100_000));
        assertEquals(1024, QRCodeResource.qrImageSize(Integer.MAX_VALUE));
    }
}
