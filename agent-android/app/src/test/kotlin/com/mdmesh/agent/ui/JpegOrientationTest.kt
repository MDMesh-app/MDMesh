package com.mdmesh.agent.ui

import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** [JpegOrientation] on hand-made EXIF segments: offsets that point anywhere never stop the scan. */
class JpegOrientationTest {
    @get:Rule val temp = TemporaryFolder()

    /** A big-endian EXIF APP1 payload whose first IFD is at [ifdOffset] (from the TIFF header), with [ifd] bytes. */
    private fun exif(ifdOffset: Long, ifd: ByteArray = ByteArray(0)): ByteArray =
        ByteArrayOutputStream().apply {
            write("Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1))
            write(byteArrayOf('M'.code.toByte(), 'M'.code.toByte(), 0, 0x2A))
            for (shift in intArrayOf(24, 16, 8, 0)) write(((ifdOffset shr shift) and 0xFF).toInt())
            write(ifd)
        }.toByteArray()

    /** One IFD entry: orientation (0x0112), SHORT, count 1, [value]; then no next IFD. */
    private fun orientationIfd(value: Int): ByteArray = byteArrayOf(
        0, 1, 0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, value.toByte(), 0, 0, 0, 0, 0, 0,
    )

    private fun jpeg(vararg app1: ByteArray): File {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        for (payload in app1) {
            val length = payload.size + 2
            out.write(byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), length.toByte()))
            out.write(payload)
        }
        out.write(byteArrayOf(0xFF.toByte(), 0xDA.toByte()))
        return temp.newFile().apply { writeBytes(out.toByteArray()) }
    }

    @Test fun `an EXIF orientation is read`() {
        assertEquals(6, JpegOrientation.read(jpeg(exif(8, orientationIfd(6)))))
    }

    /**
     * PR #192 N12: an IFD offset near the top of the 32-bit range (where `ifd + 2` overflows an Int) or past it is no
     * orientation in that segment, and the scan goes on to the next EXIF segment, as for any other offset out of it.
     */
    @Test fun `an IFD offset out of the segment, overflowing or not, moves on to the next segment`() {
        for (offset in listOf(0x7FFF_FFF9L, 0x7FFF_FFFFL, 0xFFFF_FFFFL, 1000L)) {
            assertEquals("offset $offset", 6, JpegOrientation.read(jpeg(exif(offset), exif(8, orientationIfd(6)))))
        }
    }
}
