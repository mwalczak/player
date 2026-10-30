package com.mwalczak.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64

class XtreamTest {

    @Test
    fun `server url is normalized from whatever the user pastes`() {
        assertEquals("http://host.tv:8080", ServerUrls.normalizeServer("host.tv:8080"))
        assertEquals("http://host.tv:8080", ServerUrls.normalizeServer(" http://host.tv:8080/ "))
        assertEquals("https://host.tv", ServerUrls.normalizeServer("https://host.tv/player_api.php?username=a"))
        assertEquals("http://host.tv/panel", ServerUrls.normalizeServer("http://host.tv/panel/get.php?username=a&password=b"))
    }

    @Test
    fun `credentials are read from an m3u link`() {
        val creds = ServerUrls.parseM3uLink("http://host.tv:80/get.php?username=john&password=p%40ss&type=m3u_plus&output=ts")
        assertEquals(Credentials("http://host.tv:80", "john", "p@ss"), creds)
        assertNull(ServerUrls.parseM3uLink("http://host.tv/playlist.m3u"))
    }

    @Test
    fun `stream urls follow the xtream scheme`() {
        val creds = Credentials("http://host.tv:8080", "john", "secret")
        assertEquals("http://host.tv:8080/live/john/secret/42.ts", ServerUrls.liveStream(creds, 42, StreamFormat.TS))
        assertEquals("http://host.tv:8080/live/john/secret/42.m3u8", ServerUrls.liveStream(creds, 42, StreamFormat.HLS))
    }

    @Test
    fun `account parsing accepts string or numeric fields`() {
        val info = XtreamParser.parseAccount(
            """{"user_info":{"auth":1,"status":"Active","exp_date":"1767225600","max_connections":"2","active_cons":0,"is_trial":"0"},"server_info":{}}""",
        )
        assertEquals(1767225600L, info.expiresAtEpochSec)
        assertEquals(2, info.maxConnections)
        assertEquals(0, info.activeConnections)
    }

    @Test
    fun `bad credentials and expired accounts are reported`() {
        assertThrows(XtreamParser.AuthException::class.java) { XtreamParser.parseAccount("""{"user_info":{"auth":0}}""") }
        assertThrows(XtreamParser.AuthException::class.java) {
            XtreamParser.parseAccount("""{"user_info":{"auth":1,"status":"Expired"}}""")
        }
        assertThrows(XtreamParser.AuthException::class.java) { XtreamParser.parseAccount("<html>nginx</html>") }
    }

    @Test
    fun `live streams tolerate mixed types and nulls`() {
        val channels = XtreamParser.parseLiveStreams(
            """[
              {"num":1,"name":"US: CNN HD","stream_id":"101","stream_icon":"http://logo/cnn.png","category_id":"5","epg_channel_id":"cnn.us"},
              {"num":"2","name":" BBC One ","stream_id":102,"stream_icon":"","category_id":5,"epg_channel_id":null},
              {"name":"broken entry"}
            ]""",
        )
        assertEquals(2, channels.size)
        assertEquals(Channel(101, "US: CNN HD", "http://logo/cnn.png", "5", "cnn.us", 1), channels[0])
        assertEquals(Channel(102, "BBC One", null, "5", null, 2), channels[1])
    }

    @Test
    fun `short epg titles are base64 decoded`() {
        fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray())
        val epg = XtreamParser.parseShortEpg(
            """{"epg_listings":[{"title":"${b64("News at Ten")}","description":"${b64("Headlines")}","start_timestamp":"100","stop_timestamp":"200"}]}""",
        ) { String(Base64.getDecoder().decode(it)) }
        assertEquals(listOf(EpgEntry("News at Ten", "Headlines", 100, 200)), epg)
    }

    @Test
    fun `catalog search matches all words in any order`() {
        val catalog = Catalog(
            categories = emptyList(),
            allRows = listOf(Channel(1, "UK: BBC One HD"), Channel(2, "UK: BBC Two"), Channel(3, "One Sports")),
            loadedAtMillis = 0,
        )
        assertEquals(listOf(1), catalog.search("one bbc").map { it.streamId })
        assertEquals(listOf(1, 3), catalog.search("ONE").map { it.streamId })
    }
}
