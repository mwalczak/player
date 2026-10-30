package com.mwalczak.player.data.sync

import com.mwalczak.player.data.Credentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SyncKeyTest {

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun `pbkdf2 matches the standard test vectors`() {
        assertEquals(
            "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc",
            hex(SyncKey.pbkdf2("passwd".toByteArray(), "salt".toByteArray(), 1)),
        )
        assertEquals(
            "4ddcd8f60b98be21830cee5ef22701f9641a4418d04c0414aeff08876b34ab56",
            hex(SyncKey.pbkdf2("Password".toByteArray(), "NaCl".toByteArray(), 80_000)),
        )
    }

    @Test
    fun `the same login finds the same setup however the server was typed`() {
        val key = SyncKey.forLogin(Credentials("http://mybunny.tv:8080", "john", "secret"))
        assertEquals(64, key.length)
        assertEquals(key, SyncKey.forLogin(Credentials("https://MyBunny.tv/", " john ", "secret")))
        assertNotEquals(key, SyncKey.forLogin(Credentials("http://mybunny.tv", "john", "other")))
        assertNotEquals(key, SyncKey.forLogin(Credentials("http://mybunny.tv", "jane", "secret")))
    }

    @Test
    fun `share codes read in two halves`() {
        assertEquals("ABCD-EFGH", formatShareCode("ABCDEFGH"))
    }
}
