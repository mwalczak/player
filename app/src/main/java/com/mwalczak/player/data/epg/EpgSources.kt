package com.mwalczak.player.data.epg

import com.mwalczak.player.data.Category
import com.mwalczak.player.data.Channel

/**
 * One downloadable XMLTV guide from epgshare01 (updated daily).
 * [countries] are the channel-name country codes it covers; [categoryWords] match category names.
 */
data class EpgSource(
    val id: String,
    val label: String,
    val approxMb: Int,
    val countries: Set<String> = emptySet(),
    val categoryWords: List<String> = emptyList(),
) {
    val url: String get() = "https://epgshare01.online/epgshare01/epg_ripper_$id.xml.gz"
}

object EpgSources {

    /** Country and service guides. US local stations are left out on purpose (57 MB, few matches). */
    val all: List<EpgSource> = listOf(
        EpgSource("PL1", "Poland", 9, setOf("pl"), listOf("poland", "polska", "polish")),
        EpgSource("US2", "United States", 7, setOf("us"), listOf("united states", "usa", "us ")),
        EpgSource("US_SPORTS1", "US sports", 1, setOf("us"), listOf("us sports")),
        EpgSource("CA2", "Canada", 7, setOf("ca"), listOf("canada")),
        EpgSource("UK1", "United Kingdom", 3, setOf("uk"), listOf("united kingdom", "uk ", "british")),
        EpgSource("IE1", "Ireland", 1, setOf("ie"), listOf("ireland", "irish")),
        EpgSource("MX1", "Mexico", 1, setOf("mx"), listOf("mexico", "latino")),
        EpgSource("PT1", "Portugal", 2, setOf("pt"), listOf("portugal")),
        EpgSource("ES1", "Spain", 3, setOf("es"), listOf("spain", "españa", "espana")),
        EpgSource("AU1", "Australia", 4, setOf("au"), listOf("australia")),
        EpgSource("NZ1", "New Zealand", 1, setOf("nz"), listOf("new zealand")),
        EpgSource("DE1", "Germany", 4, setOf("de"), listOf("germany", "deutsch")),
        EpgSource("AT1", "Austria", 4, setOf("at"), listOf("austria")),
        EpgSource("CH1", "Switzerland", 2, setOf("ch"), listOf("switzerland", "swiss")),
        EpgSource("FR1", "France", 6, setOf("fr"), listOf("france", "french")),
        EpgSource("BE2", "Belgium", 2, setOf("be"), listOf("belgium")),
        EpgSource("NL1", "Netherlands", 2, setOf("nl"), listOf("netherlands", "dutch")),
        EpgSource("IT1", "Italy", 2, setOf("it"), listOf("italy", "italia")),
        EpgSource("IN1", "India", 4, setOf("in"), listOf("india")),
        EpgSource("RO1", "Romania", 2, setOf("ro"), listOf("romania")),
        EpgSource("TR1", "Turkey", 1, setOf("tr"), listOf("turkey", "türk")),
        EpgSource("GR1", "Greece", 1, setOf("gr"), listOf("greece", "greek")),
        EpgSource("BR1", "Brazil", 1, setOf("br"), listOf("brazil", "brasil")),
        EpgSource("AR1", "Argentina", 1, setOf("ar"), listOf("argentina")),
        EpgSource("CL1", "Chile", 1, setOf("cl"), listOf("chile")),
        EpgSource("CO1", "Colombia", 1, setOf("co"), listOf("colombia")),
        EpgSource("SE1", "Sweden", 2, setOf("se"), listOf("sweden")),
        EpgSource("NO1", "Norway", 1, setOf("no"), listOf("norway")),
        EpgSource("DK1", "Denmark", 1, setOf("dk"), listOf("denmark")),
        EpgSource("FI1", "Finland", 1, setOf("fi"), listOf("finland")),
        EpgSource("HU1", "Hungary", 1, setOf("hu"), listOf("hungary")),
        EpgSource("CZ1", "Czechia", 1, setOf("cz"), listOf("czech")),
        EpgSource("SK1", "Slovakia", 1, setOf("sk"), listOf("slovak")),
        EpgSource("HR1", "Croatia", 1, setOf("hr"), listOf("croatia")),
        EpgSource("RS1", "Serbia", 1, setOf("rs"), listOf("serbia")),
        EpgSource("BG1", "Bulgaria", 1, setOf("bg"), listOf("bulgaria")),
        EpgSource("UA1", "Ukraine", 1, setOf("ua"), listOf("ukraine")),
        EpgSource("ZA1", "South Africa", 1, setOf("za"), listOf("south africa")),
        EpgSource("PLEX1", "Plex", 5, categoryWords = listOf("plex")),
    )

    private const val MIN_CHANNELS = 10

    fun byId(id: String): EpgSource? = all.firstOrNull { it.id == id }

    /**
     * The guides worth downloading for this catalog: a country's file when at least a few channels
     * are marked with its code ("US:", "(PL)") or sit in a category named after it ("Canada").
     */
    fun autoSelect(channels: List<Channel>, categories: List<Category>): List<EpgSource> {
        val byCountry = channels.groupingBy { Xmltv.countryOf(it.name) }.eachCount()
        val categoryNames = categories.map { " ${it.name.lowercase()} " }
        val perCategory = channels.groupingBy { it.categoryId }.eachCount()
        return all.filter { source ->
            val coded = source.countries.sumOf { byCountry[it] ?: 0 }
            val inCategories = categories.withIndex().sumOf { (i, cat) ->
                if (source.categoryWords.any { word -> categoryNames[i].contains(" ${word.trim()}") }) perCategory[cat.id] ?: 0 else 0
            }
            coded >= MIN_CHANNELS || inCategories >= MIN_CHANNELS
        }
    }
}
