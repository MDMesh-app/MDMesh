package com.mdmesh.core.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnrollAttemptsTest {

    @Test
    fun `a new id is 128 random bits the server accepts`() {
        val id = EnrollAttempts.newId()
        assertTrue(id, Regex("[A-Za-z0-9_-]{16,128}").matches(id))
        assertEquals(32, id.length)
        assertNotEquals(id, EnrollAttempts.newId())
    }

    @Test
    fun `the first call for a token makes an id to store`() {
        val (id, isNew) = EnrollAttempts.resolve(null, null, "tok-1") { "fresh" }
        assertEquals("fresh", id)
        assertTrue(isNew)
    }

    @Test
    fun `later calls for the same token keep the stored id`() {
        val binding = EnrollAttempts.bindingOf("tok-1")
        val (id, isNew) = EnrollAttempts.resolve(binding, "kept", "tok-1") { "fresh" }
        assertEquals("kept", id)
        assertFalse(isNew)
    }

    @Test
    fun `another token starts a new attempt`() {
        val binding = EnrollAttempts.bindingOf("tok-1")
        val (id, isNew) = EnrollAttempts.resolve(binding, "kept", "tok-2") { "fresh" }
        assertEquals("fresh", id)
        assertTrue(isNew)
    }

    @Test
    fun `the binding does not contain the token`() {
        val binding = EnrollAttempts.bindingOf("secret-token-value")
        assertFalse(binding.contains("secret-token-value"))
        assertEquals(64, binding.length)
    }
}
