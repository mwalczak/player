package com.mwalczak.player.cast

import com.mwalczak.player.data.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class RemoteTest {

    @Test
    fun `reads a post request with a json body`() {
        val body = """{"type":"next"}"""
        val raw = "POST /command?x=1 HTTP/1.1\r\nHost: tv\r\ncontent-length: ${body.length}\r\n\r\n$body"
        val request = MiniHttp.readRequest(ByteArrayInputStream(raw.toByteArray()))
        assertEquals(MiniHttp.Request("POST", "/command", body), request)
    }

    @Test
    fun `reads a get request without body`() {
        val request = MiniHttp.readRequest(ByteArrayInputStream("GET /status HTTP/1.1\r\n\r\n".toByteArray()))
        assertEquals(MiniHttp.Request("GET", "/status", ""), request)
    }

    @Test
    fun `rejects truncated or oversized bodies`() {
        assertThrows(IOException::class.java) {
            MiniHttp.readRequest(ByteArrayInputStream("POST /command HTTP/1.1\r\nContent-Length: 10\r\n\r\n{}".toByteArray()))
        }
        assertThrows(IOException::class.java) {
            MiniHttp.readRequest(ByteArrayInputStream("POST / HTTP/1.1\r\nContent-Length: 999999999\r\n\r\n".toByteArray()))
        }
    }

    @Test
    fun `writes a json response with correct length`() {
        val out = ByteArrayOutputStream()
        MiniHttp.writeJson(out, 409, """{"error":"żółw"}""")
        val text = out.toString(Charsets.UTF_8.name())
        assertTrue(text.startsWith("HTTP/1.1 409 Conflict\r\n"))
        assertTrue(text.contains("Content-Length: ${"""{"error":"żółw"}""".toByteArray().size}\r\n"))
        assertTrue(text.endsWith("""{"error":"żółw"}"""))
    }

    @Test
    fun `play request round-trips through json`() {
        val request = RemoteRequest("play", "Favorites", listOf(Channel(1, "TVN24", categoryId = "5")), 0)
        val json = remoteJson.encodeToString(RemoteRequest.serializer(), request)
        assertEquals(request, remoteJson.decodeFromString(RemoteRequest.serializer(), json))
    }

    @Test
    fun `ipv6 hosts are bracketed in urls`() {
        assertEquals("http://192.168.1.20:4000", RemoteDevice("TV", "192.168.1.20", 4000).baseUrl)
        assertEquals("http://[fe80::1]:4000", RemoteDevice("TV", "fe80::1", 4000).baseUrl)
    }

    @Test
    fun `copy-to-tv payload keeps favorites order, lists and settings`() {
        val library = com.mwalczak.player.data.Library(
            favorites = listOf(Channel(2, "TVN24"), Channel(1, "TVP1")),
            lists = listOf(com.mwalczak.player.data.UserList("l1", "Sport", listOf(Channel(3, "Polsat Sport")))),
            pinnedCategories = listOf("5"),
        )
        val request = RemoteRequest(
            "sync", server = "http://s", username = "u", password = "p",
            sync = SyncPayload(library, "HLS", tryAlternates = false, nextInList = true, stallTimeoutSec = 8, epgEnabled = true, epgUrl = "https://g/x.xml.gz"),
        )
        val decoded = remoteJson.decodeFromString(RemoteRequest.serializer(), remoteJson.encodeToString(RemoteRequest.serializer(), request))
        assertEquals(request, decoded)
        assertEquals(listOf(2, 1), decoded.sync!!.library.favorites.map { it.streamId })
    }
}
