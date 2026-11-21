package eu.kanade.tachiyomi.multisrc.animestream

import android.app.Application
import android.content.SharedPreferences
import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.dailymotionextractor.DailymotionExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

open class AnimeStream(
    override val lang: String,
    override val name: String,
    override val baseUrl: String,
) : AnimeHttpSource(), ConfigurableAnimeSource {

    override val supportsLatest = true

    @Suppress("DEPRECATION")
    override val client: OkHttpClient = network.client.newBuilder()
        .rateLimit(2)
        .build()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0)
    }

    private val okruExtractor by lazy { OkruExtractor(client) }
    private val dailymotionExtractor by lazy { DailymotionExtractor(client, headers) }
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    // ============================== Popular & Latest ==============================

    override fun popularAnimeRequest(page: Int): Request {
        return GET("$baseUrl/anime/?page=$page&order=popular", headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select("div.listupd article.bs").mapNotNull(::animeFromElement)
        val hasNextPage = document.selectFirst(NEXT_PAGE_SELECTOR) != null
        return AnimesPage(animes, hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/anime/?page=$page&order=update", headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // ============================== Search & Filters ==============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isNotEmpty()) {
            val encoded = URLEncoder.encode(trimmedQuery, "UTF-8")
            val searchUrl = if (page <= 1) "$baseUrl/?s=$encoded" else "$baseUrl/page/$page/?s=$encoded"
            return GET(searchUrl, headers)
        }

        val url = "$baseUrl/anime/".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())

        filters.forEach { filter ->
            when (filter) {
                is OrderFilter -> url.addQueryParameter("order", filter.toUriPart())
                is StatusFilter -> filter.toUriPart().takeIf(String::isNotEmpty)?.let {
                    url.addQueryParameter("status", it)
                }
                is TypeFilter -> filter.toUriPart().takeIf(String::isNotEmpty)?.let {
                    url.addQueryParameter("type", it)
                }
                else -> {}
            }
        }
        return GET(url.build(), headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        OrderFilter(),
        StatusFilter(),
        TypeFilter(),
    )

    // ============================== Details ==============================

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            title = document.selectFirst("h1.entry-title")?.text()?.trim().orEmpty()
            thumbnail_url = document.selectFirst("div.thumb img")?.let(::extractImageUrl)
            description = document.select("div.entry-content p, div.mindesc")
                .map { it.text().trim() }
                .filter(String::isNotEmpty)
                .distinct()
                .joinToString("\n\n")
                .ifEmpty { null }
            genre = document.select("div.genxed a")
                .joinToString(", ") { it.text().trim() }
                .ifEmpty { null }
            author = document.selectFirst("div.spe span:contains(Studio) a, div.spe span:contains(Network) a")
                ?.text()?.trim()
            val statusText = document.selectFirst("div.spe span:contains(Status)")?.text().orEmpty()
            status = when {
                statusText.contains("Ongoing", ignoreCase = true) -> SAnime.ONGOING
                statusText.contains("Completed", ignoreCase = true) -> SAnime.COMPLETED
                statusText.contains("Hiatus", ignoreCase = true) -> SAnime.ON_HIATUS
                else -> SAnime.UNKNOWN
            }
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val elements = document.select("div.eplister ul li a")
        return elements.mapIndexedNotNull { index, element ->
            val href = element.absUrl("href").takeIf(String::isNotBlank) ?: return@mapIndexedNotNull null
            val epNumStr = element.selectFirst(".epl-num")?.text()?.trim().orEmpty()
            val epTitle = element.selectFirst(".epl-title")?.text()?.trim().orEmpty()
            val epDate = element.selectFirst(".epl-date")?.text()?.trim()

            val parsedNum = EPISODE_NUM_REGEX.find(epNumStr)?.value?.toFloatOrNull()
                ?: EPISODE_NUM_REGEX.find(epTitle)?.value?.toFloatOrNull()
                ?: (elements.size - index).toFloat()

            val displayName = when {
                epNumStr.isNotBlank() && epTitle.isNotBlank() &&
                    epTitle.contains("Episode", ignoreCase = true) -> {
                    "Episode $epNumStr"
                }
                epNumStr.isNotBlank() && epTitle.isNotBlank() -> "Ep. $epNumStr - $epTitle"
                epTitle.isNotBlank() -> epTitle
                epNumStr.isNotBlank() -> "Episode $epNumStr"
                else -> "Episode ${elements.size - index}"
            }

            SEpisode.create().apply {
                setUrlWithoutDomain(href)
                name = displayName
                episode_number = parsedNum
                date_upload = parseDate(epDate)
            }
        }
    }

    override fun seasonListParse(response: Response): List<SAnime> {
        throw UnsupportedOperationException("Not used")
    }

    // ============================== Hosters & Videos ==============================

    override fun hosterListParse(response: Response): List<Hoster> {
        val document = response.asJsoup()
        val hosters = mutableListOf<Hoster>()
        val seenUrls = HashSet<String>()

        document.select("select.mirror option[value]").forEach { option ->
            val encoded = option.attr("value").trim()
            if (encoded.isEmpty()) return@forEach
            val decoded = runCatching {
                String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
            }.getOrNull() ?: return@forEach

            val rawSrc = extractEmbedUrl(decoded) ?: return@forEach
            val normalizedSrc = normalizeEmbedUrl(rawSrc)
            if (!isSupportedEmbed(normalizedSrc) || !seenUrls.add(normalizedSrc)) return@forEach

            val label = cleanServerLabel(option.text(), normalizedSrc)
            hosters.add(
                Hoster(
                    hosterUrl = normalizedSrc,
                    hosterName = label,
                    lazy = false,
                ),
            )
        }

        if (hosters.isEmpty()) {
            document.select("div.player-embed iframe[src], div#embed_holder iframe[src]").forEach { iframe ->
                val rawSrc = iframe.absUrl("src").ifBlank { iframe.attr("src") }
                val normalizedSrc = normalizeEmbedUrl(rawSrc)
                if (normalizedSrc.isNotBlank() && isSupportedEmbed(normalizedSrc) && seenUrls.add(normalizedSrc)) {
                    hosters.add(
                        Hoster(
                            hosterUrl = normalizedSrc,
                            hosterName = cleanServerLabel("", normalizedSrc),
                            lazy = false,
                        ),
                    )
                }
            }
        }

        return hosters
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> = withContext(Dispatchers.IO) {
        val url = hoster.hosterUrl
        val lower = url.lowercase(Locale.ROOT)
        val prefix = "${hoster.hosterName} - "
        when {
            "ok.ru" in lower || "odnoklassniki.ru" in lower -> {
                okruExtractor.videosFromUrl(url, prefix = prefix)
            }
            "dailymotion.com" in lower || "dai.ly" in lower -> {
                dailymotionExtractor.videosFromUrl(url, prefix = prefix)
            }
            ".m3u8" in lower -> {
                playlistUtils.extractFromHls(
                    playlistUrl = url,
                    referer = "$baseUrl/",
                    videoNameGen = { quality -> "$prefix$quality" },
                )
            }
            ".mp4" in lower -> {
                listOf(
                    Video(
                        videoUrl = url,
                        videoTitle = hoster.hosterName,
                        headers = headers.newBuilder().set("Referer", "$baseUrl/").build(),
                        initialized = true,
                    ),
                )
            }
            else -> emptyList()
        }
    }

    override fun List<Hoster>.sortHosters(): List<Hoster> {
        val prefHoster = preferences.getString(PREF_HOSTER_KEY, PREF_HOSTER_DEFAULT) ?: PREF_HOSTER_DEFAULT
        return sortedByDescending { it.hosterName.contains(prefHoster, ignoreCase = true) }
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val prefQuality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
        val prefHoster = preferences.getString(PREF_HOSTER_KEY, PREF_HOSTER_DEFAULT) ?: PREF_HOSTER_DEFAULT
        val sorted = sortedWith(
            compareByDescending<Video> {
                it.videoTitle.contains(prefHoster, ignoreCase = true) &&
                    it.videoTitle.contains(prefQuality, ignoreCase = true)
            }
                .thenByDescending { it.videoTitle.contains(prefQuality, ignoreCase = true) }
                .thenByDescending { it.videoTitle.contains(prefHoster, ignoreCase = true) }
                .thenByDescending { it.resolution ?: 0 },
        )
        return sorted.mapIndexed { index, video ->
            if (index == 0) video.copy(preferred = true) else video
        }
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_ENTRIES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_HOSTER_KEY
            title = "Preferred hoster"
            entries = PREF_HOSTER_ENTRIES
            entryValues = PREF_HOSTER_ENTRIES
            setDefaultValue(PREF_HOSTER_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)
    }

    // ============================== Helpers ==============================

    private fun animeFromElement(element: Element): SAnime? {
        val link = element.selectFirst("a[href]") ?: return null
        val href = link.absUrl("href").takeIf(String::isNotBlank) ?: return null
        val titleText = element.selectFirst("div.tt")?.ownText()?.trim()?.takeIf(String::isNotEmpty)
            ?: element.selectFirst("div.tt h2, h2")?.text()?.trim()?.takeIf(String::isNotEmpty)
            ?: link.attr("title").trim()
        if (titleText.isEmpty()) return null

        return SAnime.create().apply {
            setUrlWithoutDomain(href)
            title = titleText
            thumbnail_url = element.selectFirst("img")?.let(::extractImageUrl)
        }
    }

    private fun extractImageUrl(img: Element): String? {
        return img.absUrl("data-src").takeIf(String::isNotBlank)
            ?: img.absUrl("data-lazy-src").takeIf(String::isNotBlank)
            ?: img.absUrl("src").takeIf(String::isNotBlank)
    }

    private fun extractEmbedUrl(decoded: String): String? {
        val trimmed = decoded.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.startsWith("//")) {
            return trimmed
        }
        val doc = Jsoup.parse(trimmed)
        return doc.selectFirst("iframe[src]")?.attr("src")?.trim()?.takeIf(String::isNotBlank)
            ?: doc.selectFirst("video source[src]")?.attr("src")?.trim()?.takeIf(String::isNotBlank)
    }

    private fun normalizeEmbedUrl(raw: String): String {
        val trimmed = raw.trim()
        return when {
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("http://") -> trimmed.replaceFirst("http://", "https://")
            else -> trimmed
        }
    }

    private fun isSupportedEmbed(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        return "ok.ru" in lower ||
            "odnoklassniki.ru" in lower ||
            "dailymotion.com" in lower ||
            "dai.ly" in lower ||
            ".m3u8" in lower ||
            ".mp4" in lower
    }

    private fun cleanServerLabel(rawText: String, url: String): String {
        val trimmed = rawText.trim()
        val hostTag = when {
            "ok.ru" in url.lowercase(Locale.ROOT) || "odnoklassniki.ru" in url.lowercase(Locale.ROOT) -> "Okru"
            "dailymotion.com" in url.lowercase(Locale.ROOT) || "dai.ly" in url.lowercase(Locale.ROOT) -> "Dailymotion"
            else -> "Stream"
        }
        if (trimmed.isBlank()) return hostTag
        val cleaned = trimmed
            .replace("《Ads Free》", "", ignoreCase = true)
            .replace("All Sub Player", "", ignoreCase = true)
            .replace(Regex("""\s+"""), " ")
            .trim()
        return if (cleaned.contains(hostTag, ignoreCase = true)) cleaned else "$hostTag ($cleaned)"
    }

    private fun parseDate(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        return runCatching { DATE_FORMAT.parse(raw.trim())?.time ?: 0L }.getOrDefault(0L)
    }

    // ============================== Filters ==============================

    private open class UriPartFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart(): String = vals[state].second
    }

    private class OrderFilter : UriPartFilter(
        "Order by",
        arrayOf(
            "Popular" to "popular",
            "Latest Update" to "update",
            "Latest Added" to "latest",
            "A-Z" to "title",
            "Rating" to "rating",
        ),
    )

    private class StatusFilter : UriPartFilter(
        "Status",
        arrayOf(
            "All" to "",
            "Ongoing" to "ongoing",
            "Completed" to "completed",
            "Hiatus" to "hiatus",
        ),
    )

    private class TypeFilter : UriPartFilter(
        "Type",
        arrayOf(
            "All" to "",
            "ONA" to "ona",
            "TV" to "tv",
            "Movie" to "movie",
            "Special" to "special",
        ),
    )

    companion object {
        private const val NEXT_PAGE_SELECTOR = "div.hpage a.r, div.pagination a.next.page-numbers"
        private val EPISODE_NUM_REGEX = Regex("""\d+(?:\.\d+)?""")
        private val DATE_FORMAT = SimpleDateFormat("MMMM d, yyyy", Locale.US)

        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")

        private const val PREF_HOSTER_KEY = "pref_hoster"
        private const val PREF_HOSTER_DEFAULT = "Okru"
        private val PREF_HOSTER_ENTRIES = arrayOf("Okru", "Dailymotion")
    }
}
