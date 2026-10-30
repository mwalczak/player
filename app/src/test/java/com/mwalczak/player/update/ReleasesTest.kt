package com.mwalczak.player.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleasesTest {

    @Test
    fun `build number comes from the release tag`() {
        assertEquals(12, Releases.buildFromTag("build-12"))
        assertEquals(0, Releases.buildFromTag("v1.2"))
        assertEquals(0, Releases.buildFromTag("build-x"))
    }

    @Test
    fun `latest release yields the apk asset`() {
        val body = """
            {"tag_name":"build-7","assets":[
              {"name":"notes.txt","browser_download_url":"https://x/notes.txt","size":3},
              {"name":"bunny-player.apk","browser_download_url":"https://x/bunny-player.apk","size":2600000}
            ]}
        """
        assertEquals(Release(7, "https://x/bunny-player.apk", 2600000), Releases.parseLatest(body))
    }

    @Test
    fun `releases without our apk or build tag are ignored`() {
        assertNull(Releases.parseLatest("""{"tag_name":"build-7","assets":[]}"""))
        assertNull(Releases.parseLatest("""{"tag_name":"v7","assets":[{"name":"bunny-player.apk","browser_download_url":"u"}]}"""))
        assertNull(Releases.parseLatest("""{"message":"Not Found"}"""))
    }

    @Test
    fun `local builds never offer updates`() {
        assertFalse(UpdateState(currentBuild = 1, latestBuild = 9).available)
        assertTrue(UpdateState(currentBuild = 5, latestBuild = 9).available)
        assertFalse(UpdateState(currentBuild = 9, latestBuild = 9).available)
    }
}
