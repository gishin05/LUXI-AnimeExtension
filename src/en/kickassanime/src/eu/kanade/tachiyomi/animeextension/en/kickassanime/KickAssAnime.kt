package eu.kanade.tachiyomi.animeextension.en.kickassanime

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Hoster.Companion.toHosterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class KickAssAnime : AnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "KickAssAnime"
    override val baseUrl = "https://kaa.lt"
    override val lang = "en"
    override val supportsLatest = true

    @Suppress("DEPRECATION")
    override val client: OkHttpClient = network.client.newBuilder()
        .rateLimit(2)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
    }

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0)
    }

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    private val activeBaseUrl: String
        get() = preferences.getString(PREF_DOMAIN_KEY, baseUrl)?.trimEnd('/').takeUnless { it.isNullOrBlank() } ?: baseUrl

    private fun apiHeaders(): Headers = headers.newBuilder()
        .set("Referer", "$activeBaseUrl/")
        .set("Origin", activeBaseUrl)
        .set("Accept", "application/json")
        .build()

    private fun jsonPostHeaders(): Headers = apiHeaders().newBuilder()
        .set("Content-Type", "application/json")
        .build()

    // ============================== Popular, Latest & Search ==============================

    override fun popularAnimeRequest(page: Int): Request {
        return GET("$activeBaseUrl/api/show/popular?page=$page", apiHeaders())
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        return parseCatalogResponse(response, deduplicate = false)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$activeBaseUrl/api/show/recent?type=all&page=$page", apiHeaders())
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        return parseCatalogResponse(response, deduplicate = true)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val trimmed = query.trim()
        if (trimmed.isNotEmpty()) {
            val bodyJson = buildJsonObject {
                put("page", page)
                put("query", trimmed)
                put("filters", "{}")
            }.toString()
            return POST(
                url = "$activeBaseUrl/api/fsearch",
                headers = jsonPostHeaders(),
                body = bodyJson.toRequestBody(JSON_MEDIA_TYPE),
            ).newBuilder()
                .tag(Int::class.javaObjectType, page)
                .build()
        }

        val browse = filters.filterIsInstance<BrowseFilter>().firstOrNull()?.toUriPart() ?: "popular"
        val endpoint = when (browse) {
            "recent_all" -> "$activeBaseUrl/api/show/recent?type=all&page=$page"
            "recent_sub" -> "$activeBaseUrl/api/show/recent?type=sub&page=$page"
            "recent_dub" -> "$activeBaseUrl/api/show/recent?type=dub&page=$page"
            "all" -> "$activeBaseUrl/api/anime?page=$page"
            else -> "$activeBaseUrl/api/show/popular?page=$page"
        }
        return GET(endpoint, apiHeaders()).newBuilder()
            .tag(Int::class.javaObjectType, page)
            .build()
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val isRecent = response.request.url.encodedPath.contains("/api/show/recent")
        return parseCatalogResponse(response, deduplicate = isRecent)
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        BrowseFilter(),
    )

    private fun parseCatalogResponse(response: Response, deduplicate: Boolean): AnimesPage {
        val rawBody = response.body.string()
        val parsed = runCatching { json.parseToJsonElement(rawBody) }.getOrNull() ?: return AnimesPage(emptyList(), false)
        val currentPage = response.request.tag(Int::class.javaObjectType)
            ?: response.request.url.queryParameter("page")?.toIntOrNull()
            ?: 1

        if (parsed is JsonArray) {
            val animes = parsed.mapNotNull(::showObjectToSAnime)
            return AnimesPage(animes, animes.size >= 24)
        }

        val root = parsed as? JsonObject ?: return AnimesPage(emptyList(), false)
        val rawItems = root["result"]?.jsonArray.orEmpty()
        val seenSlugs = HashSet<String>()
        val animes = rawItems.mapNotNull { element ->
            val anime = showObjectToSAnime(element) ?: return@mapNotNull null
            if (deduplicate && !seenSlugs.add(anime.url)) return@mapNotNull null
            anime
        }

        val hasNext = when {
            root["maxPage"]?.jsonPrimitive?.intOrNull != null ->
                currentPage < root["maxPage"]!!.jsonPrimitive.int
            root["page_count"]?.jsonPrimitive?.intOrNull != null ->
                currentPage < root["page_count"]!!.jsonPrimitive.int
            root["hadNext"]?.jsonPrimitive?.booleanOrNull != null ->
                root["hadNext"]!!.jsonPrimitive.boolean
            else -> animes.size >= 24
        }

        return AnimesPage(animes, hasNext)
    }

    private fun showObjectToSAnime(element: JsonElement): SAnime? {
        val obj = element as? JsonObject ?: return null
        val slug = obj["slug"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val displayTitle = pickPreferredTitle(obj) ?: return null

        return SAnime.create().apply {
            url = "/$slug"
            title = displayTitle
            thumbnail_url = resolvePosterUrl(obj["poster"] as? JsonObject)
            description = obj["synopsis"]?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null }
            genre = (obj["genres"] as? JsonArray)
                ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
                ?.filter(String::isNotEmpty)
                ?.joinToString(", ")
                ?.ifEmpty { null }
            status = parseStatus(obj["status"]?.jsonPrimitive?.contentOrNull)
        }
    }

    // ============================== Details & Episodes ==============================

    override fun getAnimeUrl(anime: SAnime): String {
        val slug = extractShowSlug(anime.url)
        return "$activeBaseUrl/$slug"
    }

    override fun animeDetailsRequest(anime: SAnime): Request {
        val slug = extractShowSlug(anime.url)
        return GET("$activeBaseUrl/api/show/$slug", apiHeaders())
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val rawBody = response.body.string()
        val obj = runCatching { json.parseToJsonElement(rawBody).jsonObject }.getOrNull()
            ?: return SAnime.create()

        val displayTitle = pickPreferredTitle(obj).orEmpty()
        val synopsis = obj["synopsis"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val infoParts = buildList {
            obj["type"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)?.let {
                add("Type: ${it.uppercase()}")
            }
            obj["year"]?.jsonPrimitive?.intOrNull?.let { add("Year: $it") }
            obj["rating"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)?.let {
                add("Rating: $it")
            }
            val locales = (obj["locales"] as? JsonArray)
                ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
                ?.filter(String::isNotEmpty)
                .orEmpty()
            if (locales.isNotEmpty()) {
                add("Audio: ${locales.joinToString(", ")}")
            }
        }
        val fullDescription = buildString {
            if (synopsis.isNotEmpty()) append(synopsis)
            if (infoParts.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append(infoParts.joinToString(" • "))
            }
        }.ifEmpty { null }

        return SAnime.create().apply {
            title = displayTitle
            thumbnail_url = resolvePosterUrl(obj["poster"] as? JsonObject)
            description = fullDescription
            genre = (obj["genres"] as? JsonArray)
                ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
                ?.filter(String::isNotEmpty)
                ?.joinToString(", ")
                ?.ifEmpty { null }
            status = parseStatus(obj["status"]?.jsonPrimitive?.contentOrNull)
        }
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = withContext(Dispatchers.IO) {
        val slug = extractShowSlug(anime.url)
        val availableLocales = fetchAvailableLocales(slug)
        val locale = selectAudioLocale(availableLocales)

        val firstPageJson = fetchJsonObject("$activeBaseUrl/api/show/$slug/episodes?lang=$locale&page=1")
            ?: return@withContext emptyList()

        val allItems = mutableListOf<JsonObject>()
        collectEpisodeObjects(firstPageJson, allItems)

        val maxPage = (firstPageJson["pages"] as? JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.get("number")?.jsonPrimitive?.intOrNull }
            ?.maxOrNull()
            ?.coerceAtMost(50)
            ?: 1

        for (pageNum in 2..maxPage) {
            val pageJson = fetchJsonObject("$activeBaseUrl/api/show/$slug/episodes?lang=$locale&page=$pageNum") ?: break
            collectEpisodeObjects(pageJson, allItems)
        }

        val audioBadge = formatAudioBadge(locale)
        allItems.mapIndexedNotNull { idx, epObj ->
            episodeObjectToSEpisode(slug, epObj, idx, audioBadge)
        }.distinctBy { it.url }.reversed()
    }

    override fun episodeListRequest(anime: SAnime): Request {
        val slug = extractShowSlug(anime.url)
        val locale = preferences.getString(PREF_AUDIO_LANG_KEY, PREF_AUDIO_LANG_DEFAULT) ?: PREF_AUDIO_LANG_DEFAULT
        return GET("$activeBaseUrl/api/show/$slug/episodes?lang=$locale&page=1", apiHeaders())
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val rawBody = response.body.string()
        val obj = runCatching { json.parseToJsonElement(rawBody).jsonObject }.getOrNull() ?: return emptyList()
        val slug = response.request.url.pathSegments.getOrNull(2).orEmpty()
        val locale = response.request.url.queryParameter("lang") ?: PREF_AUDIO_LANG_DEFAULT
        val audioBadge = formatAudioBadge(locale)
        val items = mutableListOf<JsonObject>()
        collectEpisodeObjects(obj, items)
        return items.mapIndexedNotNull { idx, epObj ->
            episodeObjectToSEpisode(slug, epObj, idx, audioBadge)
        }.distinctBy { it.url }.reversed()
    }

    override fun seasonListParse(response: Response): List<SAnime> {
        throw UnsupportedOperationException("Not used")
    }

    // ============================== Hosters & Videos ==============================

    override fun hosterListRequest(episode: SEpisode): Request {
        val path = episode.url
        val fullUrl = if (path.startsWith("http")) path else "$activeBaseUrl$path"
        return GET(fullUrl, apiHeaders())
    }

    override fun hosterListParse(response: Response): List<Hoster> {
        val rawBody = response.body.string()
        val epJson = runCatching { json.parseToJsonElement(rawBody).jsonObject }.getOrNull() ?: return emptyList()
        val servers = epJson["servers"] as? JsonArray ?: return emptyList()

        val allVideos = mutableListOf<Video>()
        for (serverEl in servers) {
            val serverObj = serverEl as? JsonObject ?: continue
            val serverName = serverObj["name"]?.jsonPrimitive?.contentOrNull?.trim()
                ?.takeIf(String::isNotEmpty) ?: "Server"
            val embedUrl = normalizeUrl(serverObj["src"]?.jsonPrimitive?.contentOrNull, activeBaseUrl)
                ?: continue

            val videos = extractFromCatPlayer(serverName, embedUrl)
            allVideos.addAll(videos)
        }

        return allVideos.distinctBy { it.videoUrl }.toHosterList()
    }

    private fun extractFromCatPlayer(serverName: String, embedUrl: String): List<Video> {
        val embedReqHeaders = headers.newBuilder()
            .set("Referer", "$activeBaseUrl/")
            .build()

        val embedHtml = runCatching {
            client.newCall(GET(embedUrl, embedReqHeaders)).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                resp.body.string()
            }
        }.getOrNull() ?: return emptyList()

        val propsAttr = Jsoup.parse(embedHtml)
            .selectFirst("astro-island[props]")
            ?.attr("props")
            ?.takeIf(String::isNotBlank)
            ?: return emptyList()

        val props = runCatching { json.parseToJsonElement(propsAttr).jsonObject }.getOrNull() ?: return emptyList()
        val embedHttpUrl = embedUrl.toHttpUrlOrNull()
        val embedOrigin = embedHttpUrl?.let { "${it.scheme}://${it.host}" } ?: "https://krussdomi.com"
        val embedReferer = "$embedOrigin/"

        val manifestRaw = props["manifest"]?.jsonArray?.getOrNull(1)?.jsonPrimitive?.contentOrNull
        val manifestUrl = normalizeUrl(manifestRaw, embedOrigin) ?: return emptyList()
        val streamType = props["type"]?.jsonArray?.getOrNull(1)?.jsonPrimitive?.contentOrNull

        val subtitleTracks = parseAstroSubtitles(props["subtitles"], embedOrigin)

        if (manifestUrl.contains(".m3u8", ignoreCase = true) || streamType != "dash") {
            return playlistUtils.extractFromHls(
                playlistUrl = manifestUrl,
                referer = embedReferer,
                videoNameGen = { quality -> "$serverName - $quality" },
                subtitleList = subtitleTracks,
                includeMasterOnSeparateAudio = true,
            )
        }

        val dashHeaders = headers.newBuilder()
            .set("Referer", embedReferer)
            .set("Origin", embedOrigin)
            .build()

        val reachable = runCatching {
            client.newCall(GET(manifestUrl, dashHeaders)).execute().use { it.isSuccessful }
        }.getOrDefault(false)
        if (!reachable) return emptyList()

        return listOf(
            Video(
                videoUrl = manifestUrl,
                videoTitle = "$serverName - Auto (DASH)",
                headers = dashHeaders,
                subtitleTracks = subtitleTracks,
                initialized = true,
            ),
        )
    }

    private fun parseAstroSubtitles(element: JsonElement?, fallbackOrigin: String): List<Track> {
        val outer = (element as? JsonArray)?.getOrNull(1) as? JsonArray ?: return emptyList()
        return outer.mapNotNull { entry ->
            val subObj = (entry as? JsonArray)?.getOrNull(1) as? JsonObject ?: return@mapNotNull null
            val rawSrc = subObj["src"]?.jsonArray?.getOrNull(1)?.jsonPrimitive?.contentOrNull
            val subUrl = normalizeUrl(rawSrc, fallbackOrigin) ?: return@mapNotNull null
            val lang = subObj["language"]?.jsonArray?.getOrNull(1)?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val name = subObj["name"]?.jsonArray?.getOrNull(1)?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val label = name.ifEmpty { lang.ifEmpty { "Unknown" } }
            val isEnglish = lang.equals("en", ignoreCase = true) ||
                lang.equals("eng", ignoreCase = true) ||
                name.contains("English", ignoreCase = true)
            Triple(isEnglish, subUrl, label)
        }
            .sortedByDescending { it.first }
            .distinctBy { it.second }
            .map { Track(it.second, it.third) }
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val prefQuality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
        val sorted = sortedWith(
            compareByDescending<Video> { it.videoTitle.contains(prefQuality, ignoreCase = true) }
                .thenByDescending { !it.videoTitle.contains("(DASH)", ignoreCase = true) }
                .thenByDescending { it.resolution ?: 0 },
        )
        return sorted.mapIndexed { index, video ->
            video.copy(preferred = index == 0)
        }
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_DOMAIN_KEY
            title = "Preferred domain"
            entries = PREF_DOMAIN_ENTRIES
            entryValues = PREF_DOMAIN_VALUES
            setDefaultValue(baseUrl)
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_AUDIO_LANG_KEY
            title = "Preferred audio language"
            entries = PREF_AUDIO_ENTRIES
            entryValues = PREF_AUDIO_VALUES
            setDefaultValue(PREF_AUDIO_LANG_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_TITLE_LANG_KEY
            title = "Preferred title language"
            entries = PREF_TITLE_ENTRIES
            entryValues = PREF_TITLE_VALUES
            setDefaultValue(PREF_TITLE_LANG_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_ENTRIES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)
    }

    // ============================== Helpers ==============================

    private suspend fun fetchAvailableLocales(slug: String): List<String> {
        val langJson = fetchJsonObject("$activeBaseUrl/api/show/$slug/language") ?: return emptyList()
        return (langJson["result"] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
            ?.filter(String::isNotEmpty)
            .orEmpty()
    }

    private fun selectAudioLocale(available: List<String>): String {
        val preferred = preferences.getString(PREF_AUDIO_LANG_KEY, PREF_AUDIO_LANG_DEFAULT) ?: PREF_AUDIO_LANG_DEFAULT
        if (available.isEmpty()) return preferred
        return available.firstOrNull { it.equals(preferred, ignoreCase = true) }
            ?: available.firstOrNull { it.equals("ja-JP", ignoreCase = true) }
            ?: available.firstOrNull { it.equals("en-US", ignoreCase = true) }
            ?: available.first()
    }

    private suspend fun fetchJsonObject(url: String): JsonObject? {
        return runCatching {
            client.newCall(GET(url, apiHeaders())).awaitSuccess().use { resp ->
                json.parseToJsonElement(resp.body.string()).jsonObject
            }
        }.getOrNull()
    }

    private fun collectEpisodeObjects(pageJson: JsonObject, out: MutableList<JsonObject>) {
        val arr = pageJson["result"] as? JsonArray ?: return
        for (el in arr) {
            (el as? JsonObject)?.let(out::add)
        }
    }

    private fun episodeObjectToSEpisode(
        showSlug: String,
        epObj: JsonObject,
        fallbackIndex: Int,
        audioBadge: String,
    ): SEpisode? {
        val epSlug = epObj["slug"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val epNumStr = epObj["episode_string"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
            ?: epObj["episode_number"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: (fallbackIndex + 1).toString()
        val epNum = epObj["episode_number"]?.jsonPrimitive?.floatOrNull
            ?: epNumStr.toFloatOrNull()
            ?: (fallbackIndex + 1).toFloat()
        val epTitle = epObj["title"]?.jsonPrimitive?.contentOrNull?.trim()

        return SEpisode.create().apply {
            url = "/api/show/$showSlug/episode/ep-$epNumStr-$epSlug"
            name = if (!epTitle.isNullOrBlank()) "Episode $epNumStr: $epTitle" else "Episode $epNumStr"
            episode_number = epNum
            scanlator = audioBadge
        }
    }

    private fun extractShowSlug(rawUrl: String): String {
        return rawUrl.trim('/')
            .removePrefix("api/show/")
            .removePrefix("anime/")
            .substringBefore('/')
    }

    private fun pickPreferredTitle(obj: JsonObject): String? {
        val pref = preferences.getString(PREF_TITLE_LANG_KEY, PREF_TITLE_LANG_DEFAULT) ?: PREF_TITLE_LANG_DEFAULT
        val en = obj["title_en"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
        val romaji = obj["title"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
        return if (pref == "romaji") (romaji ?: en) else (en ?: romaji)
    }

    private fun resolvePosterUrl(posterObj: JsonObject?): String? {
        if (posterObj == null) return null
        val slug = posterObj["hq"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
            ?: posterObj["sm"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
            ?: return null
        val formats = (posterObj["formats"] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull?.lowercase() }
            .orEmpty()
        val ext = when {
            "jpeg" in formats || "jpg" in formats -> "jpg"
            "webp" in formats -> "webp"
            else -> "jpg"
        }
        return "$activeBaseUrl/image/poster/$slug.$ext"
    }

    private fun parseStatus(raw: String?): Int = when (raw?.trim()?.lowercase()) {
        "currently_airing", "airing" -> SAnime.ONGOING
        "finished_airing", "completed", "finished" -> SAnime.COMPLETED
        else -> SAnime.UNKNOWN
    }

    private fun formatAudioBadge(locale: String): String = when {
        locale.startsWith("en", ignoreCase = true) -> "Dub"
        locale.startsWith("ja", ignoreCase = true) -> "Sub"
        else -> locale
    }

    private fun normalizeUrl(raw: String?, fallbackOrigin: String? = null): String? {
        val trimmed = raw?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val fixed = when {
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("/") && !fallbackOrigin.isNullOrBlank() -> "$fallbackOrigin$trimmed"
            PROTOCOL_SLASH_REGEX.containsMatchIn(trimmed) -> {
                val scheme = if (trimmed.startsWith("http:", ignoreCase = true)) "http" else "https"
                "$scheme://${trimmed.substringAfter(':').trimStart('/')}"
            }
            else -> trimmed
        }
        return fixed.toHttpUrlOrNull()?.toString()
    }

    // ============================== Filters ==============================

    private class BrowseFilter : AnimeFilter.Select<String>(
        "Browse",
        arrayOf("Popular", "Recent (All)", "Recent (Sub)", "Recent (Dub)", "All Anime"),
    ) {
        fun toUriPart(): String = when (state) {
            1 -> "recent_all"
            2 -> "recent_sub"
            3 -> "recent_dub"
            4 -> "all"
            else -> "popular"
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val PROTOCOL_SLASH_REGEX = Regex("^(https?):/+", RegexOption.IGNORE_CASE)

        private const val PREF_DOMAIN_KEY = "pref_domain"
        private val PREF_DOMAIN_ENTRIES = arrayOf("kaa.lt", "kaa.to")
        private val PREF_DOMAIN_VALUES = arrayOf("https://kaa.lt", "https://kaa.to")

        private const val PREF_AUDIO_LANG_KEY = "pref_audio_lang"
        private const val PREF_AUDIO_LANG_DEFAULT = "ja-JP"
        private val PREF_AUDIO_ENTRIES = arrayOf("Sub (Japanese)", "Dub (English)")
        private val PREF_AUDIO_VALUES = arrayOf("ja-JP", "en-US")

        private const val PREF_TITLE_LANG_KEY = "pref_title_lang"
        private const val PREF_TITLE_LANG_DEFAULT = "en"
        private val PREF_TITLE_ENTRIES = arrayOf("English", "Romaji")
        private val PREF_TITLE_VALUES = arrayOf("en", "romaji")

        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "Auto"
        private val PREF_QUALITY_ENTRIES = arrayOf("Auto", "1080p", "720p", "480p", "360p")
    }
}
