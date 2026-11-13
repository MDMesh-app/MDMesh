package com.mdmesh.agent.ui

import java.io.DataInputStream
import java.io.EOFException
import java.io.File

/**
 * A JPEG's EXIF orientation (1..8; 1 when it has none, isn't a JPEG, or can't be read), read straight from its APP1
 * segment. The platform's ExifInterface reads the same files differently across Android 7 to 15 (and the AndroidX
 * one would be a new dependency); this reads only the markers before the image data, at most 256 KB.
 */
object JpegOrientation {

    fun read(file: File): Int = try {
        DataInputStream(file.inputStream().buffered()).use(::scan) ?: NORMAL
    } catch (_: Exception) {
        NORMAL
    }

    /** The orientation from the first EXIF segment that has one; null when there is none before the image data. */
    private fun scan(d: DataInputStream): Int? {
        if (d.readUnsignedShort() != SOI) throw NotJpeg
        var scanned = 0
        var found: Int? = null
        while (found == null && scanned < LIMIT) {
            val (type, length) = segment(d)
            if (type == SOS || type == EOI) break
            if (type == APP1) {
                found = orientationIn(Segment(ByteArray(length).also(d::readFully)))
            } else {
                skip(d, length)
            }
            scanned += length + MARKER_BYTES
        }
        return found
    }

    /** The next marker's type and payload length. */
    private fun segment(d: DataInputStream): Pair<Int, Int> {
        if (d.readUnsignedByte() != MARKER) throw NotJpeg
        var type = d.readUnsignedByte()
        while (type == MARKER) type = d.readUnsignedByte() // fill bytes
        if (type == SOS || type == EOI) return type to 0
        val length = d.readUnsignedShort() - LENGTH_BYTES
        if (length < 0) throw NotJpeg
        return type to length
    }

    private fun skip(d: DataInputStream, n: Int) {
        var left = n
        while (left > 0) {
            val skipped = d.skipBytes(left)
            if (skipped <= 0) throw EOFException()
            left -= skipped
        }
    }

    /** The orientation in an APP1 segment, if it is EXIF and has one. */
    private fun orientationIn(s: Segment): Int? {
        // The offset is unsigned 32-bit: checked as a Long, so one near or past the Int range can't wrap into range.
        val at = if (s.isExif()) TIFF + s.u32(TIFF + IFD_OFFSET) else Long.MAX_VALUE
        if (at > s.size - LENGTH_BYTES) return null
        val ifd = at.toInt()
        return (0 until s.u16(ifd)).asSequence()
            .map { ifd + LENGTH_BYTES + it * ENTRY }
            .takeWhile { it + ENTRY <= s.size }
            .firstOrNull { s.u16(it) == ORIENTATION_TAG && s.u16(it + TYPE_OFFSET) == SHORT_TYPE }
            ?.let { s.u16(it + VALUE_OFFSET) }
            ?.takeIf { it in NORMAL..MAX_ORIENTATION }
    }

    /** An APP1 payload: "Exif\0\0", then a TIFF header and its first IFD. */
    private class Segment(private val b: ByteArray) {
        val size: Int get() = b.size
        private var little = false

        fun isExif(): Boolean {
            if (b.size < TIFF + TIFF_HEADER || String(b, 0, EXIF.length, Charsets.ISO_8859_1) != EXIF) return false
            little = String(b, TIFF, 2, Charsets.ISO_8859_1) == "II"
            val order = little || String(b, TIFF, 2, Charsets.ISO_8859_1) == "MM"
            return order && u16(TIFF + 2) == TIFF_MAGIC
        }

        private fun u8(i: Int) = b[i].toInt() and BYTE

        fun u16(i: Int): Int = if (little) u8(i) or (u8(i + 1) shl BITS) else (u8(i) shl BITS) or u8(i + 1)

        /** Unsigned, so never negative. */
        fun u32(i: Int): Long {
            val (low, high) = if (little) u16(i) to u16(i + 2) else u16(i + 2) to u16(i)
            return (high.toLong() shl (2 * BITS)) or low.toLong()
        }
    }

    private object NotJpeg : Exception()

    const val NORMAL = 1
    private const val MAX_ORIENTATION = 8
    private const val SOI = 0xFFD8
    private const val MARKER = 0xFF
    private const val SOS = 0xDA
    private const val EOI = 0xD9
    private const val APP1 = 0xE1
    private const val LIMIT = 256 * 1024
    private const val MARKER_BYTES = 4
    private const val LENGTH_BYTES = 2
    private const val EXIF = "Exif\u0000\u0000"
    private const val TIFF = 6
    private const val TIFF_HEADER = 8
    private const val IFD_OFFSET = 4
    private const val TIFF_MAGIC = 42
    private const val ENTRY = 12
    private const val TYPE_OFFSET = 2
    private const val VALUE_OFFSET = 8
    private const val ORIENTATION_TAG = 0x0112
    private const val SHORT_TYPE = 3
    private const val BYTE = 0xFF
    private const val BITS = 8
}
