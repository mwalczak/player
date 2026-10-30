package com.mwalczak.player.data.epg

import com.mwalczak.player.data.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.StringReader

class XmltvTest {

    @Test
    fun `xmltv times are converted with their offset`() {
        assertEquals(1793347200L, Xmltv.parseTime("20261030080000 +0000"))
        assertEquals(1793347200L, Xmltv.parseTime("20261030090000 +0100"))
        assertEquals(1793347200L, Xmltv.parseTime("20261030080000"))
        assertNull(Xmltv.parseTime("garbage"))
    }

    @Test
    fun `provider names and guide ids reduce to the same key`() {
        val keys = listOf("PL: TVP 1 HD", "(PL) (MG) TVP1", "TVP.1.pl", "TVP 1 FHD PL", "PL| TVP 1").map(Xmltv::matchKey)
        assertEquals(setOf("tvp1"), keys.toSet())
        assertEquals("tvpsport", Xmltv.matchKey("TVP.Sport.pl"))
        assertEquals("polsat", Xmltv.matchKey("PL: POLSAT HD"))
    }

    @Test
    fun `guide channels map to every matching stream`() {
        val ours = listOf(Channel(1, "PL: TVN HD"), Channel(2, "(PL) (MG) TVN"), Channel(3, "PL: TVN 24"))
        val guide = mapOf("TVN.pl" to listOf("TVN"), "TVN24.pl" to listOf("TVN 24"), "Other.pl" to listOf("Other"))
        assertEquals(mapOf("TVN.pl" to listOf(1, 2), "TVN24.pl" to listOf(3)), Xmltv.match(guide, ours))
    }

    @Test
    fun `foreign feeds in brackets stay separate from the polish ones`() {
        val ours = listOf(Channel(1, "(PL) (MG) Discovery Channel HD"))
        val guide = linkedMapOf(
            "Discovery.Channel.(niem.).pl" to listOf("Discovery Channel (niem.)"),
            "Discovery.Channel.pl" to listOf("Discovery Channel"),
        )
        assertEquals(mapOf("Discovery.Channel.pl" to listOf(1)), Xmltv.match(guide, ours))
    }

    @Test
    fun `loose pass ignores filler words but exact matches win`() {
        val ours = listOf(Channel(1, "PL: PULS HD"), Channel(2, "(PL) (MG) Polsat Sport 1"), Channel(3, "PL: TVN FABULA HD"), Channel(4, "PL: TVP 1"))
        val guide = linkedMapOf(
            "TV.Puls.HD.pl" to listOf("TV Puls HD"),
            "Polsat.Sport.pl" to listOf("Polsat Sport"),
            "TVN.Fabuła.pl" to listOf("TVN Fabuła"),
            "TVP.pl" to listOf("TVP"),
            "TVP.1.pl" to listOf("TVP 1"),
        )
        assertEquals(
            mapOf("TV.Puls.HD.pl" to listOf(1), "Polsat.Sport.pl" to listOf(2), "TVN.Fabuła.pl" to listOf(3), "TVP.1.pl" to listOf(4)),
            Xmltv.match(guide, ours),
        )
    }

    @Test
    fun `channels marked with another country don't take the polish schedule`() {
        val ours = listOf(Channel(1, "US: Discovery"), Channel(2, "PL: Discovery"), Channel(3, "Al Jazeera"))
        val guide = mapOf("Discovery.pl" to listOf("Discovery"), "Al.Jazeera.pl" to listOf("Al Jazeera"))
        assertEquals(mapOf("Discovery.pl" to listOf(2), "Al.Jazeera.pl" to listOf(3)), Xmltv.match(guide, ours))
        assertEquals("pl", Xmltv.countryOf("PL (C+PL) FX"))
        assertEquals("us", Xmltv.countryOf("USA- CNN"))
        assertEquals("nz", Xmltv.countryOf("(NZ) SKY Sport 1"))
        assertNull(Xmltv.countryOf("NFL 02: Panthers"))
        assertNull(Xmltv.countryOf("TC+ Tennis"))
    }

    @Test
    fun `parser keeps matched programmes inside the window`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <channel id="TVP.Sport.pl"><display-name lang="pl">TVP Sport</display-name></channel>
              <channel id="Unknown.pl"><display-name>Nobody watches</display-name></channel>
              <programme start="20261030060000 +0000" stop="20261030070000 +0000" channel="TVP.Sport.pl"><title lang="pl">Too early</title></programme>
              <programme start="20261030080000 +0000" stop="20261030093000 +0000" channel="TVP.Sport.pl">
                <title lang="pl">Studio Sport &amp; Wiadomości</title><desc lang="pl">Opis</desc>
              </programme>
              <programme start="20261030093000 +0000" stop="20261030100000 +0000" channel="TVP.Sport.pl"><title>Mecz</title></programme>
              <programme start="20261030080000 +0000" stop="20261030090000 +0000" channel="Unknown.pl"><title>Skip</title></programme>
            </tv>"""
        val parser = KXmlParser().apply { setInput(StringReader(xml)) }
        val from = Xmltv.parseTime("20261030075000 +0000")!!
        val to = Xmltv.parseTime("20261031000000 +0000")!!
        val result = Xmltv.parse(parser, listOf(Channel(7, "PL: TVP SPORT HD")), from, to)
        assertEquals(listOf(7), result.keys.toList())
        assertEquals(listOf("Studio Sport & Wiadomości", "Mecz"), result[7]!!.map { it.title })
        assertEquals("Opis", result[7]!![0].desc)
    }

    @Test
    fun `on air and upcoming programmes`() {
        val list = listOf(Programme(0, 100, "A"), Programme(100, 200, "B"), Programme(200, 300, "C"))
        assertEquals("B", list.onAir(150)?.title)
        assertEquals(listOf("B", "C"), list.fromNow(150).map { it.title })
        assertNull(list.onAir(400))
    }
}
