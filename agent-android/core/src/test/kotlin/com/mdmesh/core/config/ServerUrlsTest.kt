package com.mdmesh.core.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerUrlsTest {

    @Test
    fun `a clean https origin is kept`() {
        assertEquals("https://mdm.example.org", ServerUrls.normalize("https://mdm.example.org"))
    }

    @Test
    fun `spaces and a trailing slash are dropped`() {
        assertEquals("https://mdm.example.org", ServerUrls.normalize("  https://mdm.example.org/ \n"))
    }

    @Test
    fun `no scheme means https`() {
        assertEquals("https://mdm.example.org", ServerUrls.normalize("mdm.example.org"))
    }

    @Test
    fun `a port is kept, the default one is not`() {
        assertEquals("https://mdm.example.org:8443", ServerUrls.normalize("https://mdm.example.org:8443/"))
        assertEquals("https://mdm.example.org", ServerUrls.normalize("https://mdm.example.org:443"))
        assertEquals("http://10.0.0.5:8080", ServerUrls.normalize("http://10.0.0.5:8080"))
    }

    @Test
    fun `a path, query or fragment is reduced to the origin both channels use`() {
        assertEquals("https://mdm.example.org", ServerUrls.normalize("https://mdm.example.org/rest/?x=1#y"))
    }

    @Test
    fun `the host is lower-cased and an IPv6 literal keeps its brackets`() {
        assertEquals("https://mdm.example.org", ServerUrls.normalize("https://MDM.Example.org"))
        assertEquals("https://[fd00::1]:8443", ServerUrls.normalize("https://[fd00::1]:8443"))
    }

    @Test
    fun `anything that is not an http address is refused`() {
        assertNull(ServerUrls.normalize(null))
        assertNull(ServerUrls.normalize("   "))
        assertNull(ServerUrls.normalize("ftp://mdm.example.org"))
        assertNull(ServerUrls.normalize("https://"))
        assertNull(ServerUrls.normalize("https://mdm example.org"))
        assertNull(ServerUrls.normalize("not a url"))
    }

    @Test
    fun `provisioning stores a valid address normalised`() {
        assertEquals("https://mdm.example.org", ServerUrls.toStore(" https://mdm.example.org/ ", null))
        assertEquals("https://new.example.org", ServerUrls.toStore("https://new.example.org", "https://old.example.org"))
    }

    @Test
    fun `nothing provided keeps what is stored`() {
        assertNull(ServerUrls.toStore(null, "https://mdm.example.org"))
        assertNull(ServerUrls.toStore("  ", null))
    }

    @Test
    fun `a broken address never replaces a valid one`() {
        assertNull(ServerUrls.toStore("mdm example", "https://mdm.example.org"))
    }

    @Test
    fun `a broken address is kept as given when nothing valid is stored, so it fails naming it`() {
        assertEquals("mdm example", ServerUrls.toStore(" mdm example ", null))
        assertEquals("mdm example", ServerUrls.toStore("mdm example", "also broken"))
    }
}
