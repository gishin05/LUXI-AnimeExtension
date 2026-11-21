package eu.kanade.tachiyomi.animeextension.en.animeheaven

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Hoster.Companion.toHosterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.CacheControl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class AnimeHeaven : AnimeHttpSource() {

    override val name = "AnimeHeaven"
    override val baseUrl = "https://animeheaven.me"
    override val lang = "en"
    override val supportsLatest = true

    @Suppress("DEPRECATION")
    override val client: OkHttpClient = network.client.newBuilder()
        .rateLimit(2)
        .build()

    // ============================== Popular, Latest & Search ==============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/popular.php", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        return parseAnimeCards(response.asJsoup(), deduplicate = false)
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/new.php", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        return parseAnimeCards(response.asJsoup(), deduplicate = true)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val trimmed = query.trim()
        if (trimmed.isNotEmpty()) {
            return GET("$baseUrl/search.php?s=${URLEncoder.encode(trimmed, "UTF-8")}", headers)
        }
        val tagFilter = filters.filterIsInstance<TagFilter>().firstOrNull()
        val selectedTag = tagFilter?.toUriPart()?.takeIf(String::isNotBlank)
        return if (selectedTag != null) {
            GET("$baseUrl/tags.php?tag=${URLEncoder.encode(selectedTag, "UTF-8")}", headers)
        } else {
            popularAnimeRequest(page)
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        return parseAnimeCards(response.asJsoup(), deduplicate = true)
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        TagFilter(),
    )

    private fun parseAnimeCards(document: Document, deduplicate: Boolean): AnimesPage {
        val seenUrls = HashSet<String>()
        val animes = document.select("div.chart, div.similarimg").mapNotNull { card ->
            val link = card.selectFirst("div.charttitle a[href^=anime.php], div.similarname a[href^=anime.php], a[href^=anime.php]")
                ?: return@mapNotNull null
            val href = link.absUrl("href").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val titleText = card.selectFirst("div.charttitle a, div.similarname a")?.text()?.trim()
                ?.takeIf(String::isNotEmpty)
                ?: card.selectFirst("img.coverimg")?.attr("alt")?.trim()
                ?: return@mapNotNull null

            val anime = SAnime.create().apply {
                setUrlWithoutDomain(href)
                title = titleText
                thumbnail_url = card.selectFirst("img.coverimg")?.absUrl("src")?.takeIf(String::isNotBlank)
            }
            if (deduplicate && !seenUrls.add(anime.url)) {
                null
            } else {
                anime
            }
        }
        return AnimesPage(animes, false)
    }

    // ============================== Details & Episodes ==============================

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val descText = document.selectFirst("div.infodes")?.text()?.trim().orEmpty()
        val yearInfo = document.selectFirst("div.infoyear")?.text()?.trim().orEmpty()
        val combinedDesc = listOf(descText, yearInfo)
            .filter(String::isNotEmpty)
            .joinToString("\n\n")
            .ifEmpty { null }

        return SAnime.create().apply {
            title = document.selectFirst("div.infotitle")?.text()?.trim().orEmpty()
            thumbnail_url = document.selectFirst("img.posterimg")?.absUrl("src")?.takeIf(String::isNotBlank)
            description = combinedDesc
            genre = document.select("div.infotags a .boxitem, div.infotags a")
                .map { it.text().trim() }
                .filter(String::isNotEmpty)
                .distinct()
                .joinToString(", ")
                .ifEmpty { null }
            status = SAnime.UNKNOWN
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val animeUrl = response.request.url.encodedPath +
            (response.request.url.encodedQuery?.let { "?$it" } ?: "")
        val encodedRef = URLEncoder.encode(animeUrl, "UTF-8")

        val elements = document.select("div.linetitle2 a[href*=gate.php], a[href*=gate.php][id]")
        return elements.mapIndexedNotNull { index, element ->
            val key = element.attr("id").trim().takeIf(String::isNotEmpty)
                ?: GATE_KEY_REGEX.find(element.attr("onclick"))?.groupValues?.getOrNull(1)
                ?: return@mapIndexedNotNull null

            val epNumText = element.selectFirst("div.watch2")?.text()?.trim().orEmpty()
            val epNum = epNumText.toFloatOrNull()
                ?: EP_NUM_REGEX.find(epNumText)?.value?.toFloatOrNull()
                ?: (elements.size - index).toFloat()

            val relativeTime = element.select("div.watch1").lastOrNull()?.text()?.trim()

            SEpisode.create().apply {
                url = "/gate.php?key=$key&ref=$encodedRef"
                name = if (epNumText.isNotBlank()) "Episode $epNumText" else "Episode ${elements.size - index}"
                episode_number = epNum
                date_upload = parseRelativeDate(relativeTime)
            }
        }
    }

    override fun seasonListParse(response: Response): List<SAnime> {
        throw UnsupportedOperationException("Not used")
    }

    // ============================== Hosters & Videos ==============================

    override fun hosterListRequest(episode: SEpisode): Request {
        val parsed = "$baseUrl${episode.url}".toHttpUrlOrNull()
        val key = parsed?.queryParameter("key").orEmpty()
        val ref = parsed?.queryParameter("ref").takeUnless { it.isNullOrBlank() } ?: "/"
        val referer = if (ref.startsWith("http")) ref else "$baseUrl$ref"

        val gateHeaders = headers.newBuilder()
            .set("Cookie", "key=$key")
            .set("Referer", referer)
            .build()
        return GET("$baseUrl/gate.php", gateHeaders, CacheControl.FORCE_NETWORK)
    }

    override fun hosterListParse(response: Response): List<Hoster> {
        val html = response.body.string()
        val document = response.asJsoup(html)

        val rawUrls = buildList {
            document.select("video source[src], source[src]").forEach { sourceEl: Element ->
                val src = sourceEl.absUrl("src").ifBlank { sourceEl.attr("src") }.trim()
                if (src.isNotBlank()) add(src)
            }
            VIDEO_URL_REGEX.findAll(html).forEach { match ->
                add(match.value)
            }
        }.distinct()

        if (rawUrls.isEmpty()) return emptyList()

        val orderedUrls = rawUrls.sortedBy { url ->
            when {
                url.contains("&error2") -> 2
                url.contains("&error") -> 1
                else -> 0
            }
        }

        val streamHeaders = headers.newBuilder()
            .set("Referer", "$baseUrl/")
            .build()

        val videos = orderedUrls.mapIndexed { index, url ->
            val label = if (url.contains("&error")) {
                "AnimeHeaven - Backup ${index + 1}"
            } else {
                "AnimeHeaven - Mirror ${index + 1}"
            }
            Video(
                videoUrl = url,
                videoTitle = label,
                headers = streamHeaders,
                preferred = index == 0,
                initialized = true,
            )
        }
        return videos.toHosterList()
    }

    // ============================== Helpers ==============================

    private fun parseRelativeDate(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        val match = RELATIVE_TIME_REGEX.find(raw.lowercase()) ?: return 0L
        val amount = match.groupValues[1].toLongOrNull() ?: return 0L
        val unit = match.groupValues[2]
        val millis = when {
            unit.startsWith("min") || unit == "m" -> amount * 60_000L
            unit.startsWith("h") -> amount * 3_600_000L
            unit.startsWith("d") -> amount * 86_400_000L
            unit.startsWith("w") -> amount * 604_800_000L
            unit.startsWith("mo") -> amount * 2_592_000_000L
            unit.startsWith("y") -> amount * 31_536_000_000L
            else -> return 0L
        }
        return System.currentTimeMillis() - millis
    }

    // ============================== Filters ==============================

    private class TagFilter : AnimeFilter.Select<String>(
        "Genre / Tag",
        TAG_OPTIONS.map { it.first }.toTypedArray(),
    ) {
        fun toUriPart(): String = TAG_OPTIONS[state].second
    }

    companion object {
        private val GATE_KEY_REGEX = Regex("""gate[a-z]?\(\s*["']([^"']+)["']""")
        private val EP_NUM_REGEX = Regex("""\d+(?:\.\d+)?""")
        private val VIDEO_URL_REGEX = Regex("""https?://[^\s'"<>]+/video\.mp4[^\s'"<>]*""")
        private val RELATIVE_TIME_REGEX = Regex("""(\d+)\s*(min|m|h|hr|hrs|d|day|days|w|mo|y)""")

        private val TAG_OPTIONS = arrayOf(
            "All (Popular)" to "",
            "Action" to "Action",
            "Adventure" to "Adventure",
            "Comedy" to "Comedy",
            "Drama" to "Drama",
            "Ecchi" to "Ecchi",
            "Fantasy" to "Fantasy",
            "Horror" to "Horror",
            "Isekai" to "Isekai",
            "Magic" to "Magic",
            "Mecha" to "Mecha",
            "Mystery" to "Mystery",
            "Psychological" to "Psychological",
            "Romance" to "Romance",
            "School" to "School",
            "Sci-Fi" to "Sci-Fi",
            "Seinen" to "Seinen",
            "Shoujo" to "Shoujo",
            "Shounen" to "Shounen",
            "Slice of Life" to "Slice of Life",
            "Sports" to "Sports",
            "Supernatural" to "Supernatural",
            "Thriller" to "Thriller",
        )
    }
}
