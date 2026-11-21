package eu.kanade.tachiyomi.animeextension.all.anizone

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
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.CacheControl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Document
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class AniZone : AnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "AniZone"
    override val baseUrl = "https://anizone.to"
    override val lang = "all"
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

    private val catalogStates = ConcurrentHashMap<String, LivewirePageState>()

    // ============================== Popular, Latest & Search ==============================

    override suspend fun getPopularAnime(page: Int): AnimesPage = withContext(Dispatchers.IO) {
        fetchCatalogPage(
            stateKey = "popular",
            page = page,
            pageUrl = "$baseUrl/anime",
            sort = "release-desc",
            typeIndex = null,
        )
    }

    override suspend fun getLatestUpdates(page: Int): AnimesPage = withContext(Dispatchers.IO) {
        fetchCatalogPage(
            stateKey = "latest",
            page = page,
            pageUrl = "$baseUrl/anime",
            sort = "added-desc",
            typeIndex = null,
        )
    }

    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage = withContext(Dispatchers.IO) {
        val trimmedQuery = query.trim()
        val sortFilter = filters.filterIsInstance<SortFilter>().firstOrNull()
        val typeFilter = filters.filterIsInstance<TypeFilter>().firstOrNull()
        val sortValue = sortFilter?.toUriPart()
        val typeValue = typeFilter?.state?.takeIf { it > 0 }?.toString()

        val searchParam = if (trimmedQuery.isNotEmpty()) {
            "?search=${URLEncoder.encode(trimmedQuery, "UTF-8")}"
        } else {
            ""
        }
        val pageUrl = "$baseUrl/anime$searchParam"
        val stateKey = "search:$trimmedQuery:${sortValue.orEmpty()}:${typeValue.orEmpty()}"

        fetchCatalogPage(
            stateKey = stateKey,
            page = page,
            pageUrl = pageUrl,
            sort = sortValue,
            typeIndex = typeValue,
        )
    }

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/anime", headers)
    override fun popularAnimeParse(response: Response): AnimesPage = parseCatalogDocument(response.asJsoup())
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/anime", headers)
    override fun latestUpdatesParse(response: Response): AnimesPage = parseCatalogDocument(response.asJsoup())
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val q = query.trim()
        return if (q.isNotEmpty()) {
            GET("$baseUrl/anime?search=${URLEncoder.encode(q, "UTF-8")}", headers)
        } else {
            GET("$baseUrl/anime", headers)
        }
    }
    override fun searchAnimeParse(response: Response): AnimesPage = parseCatalogDocument(response.asJsoup())

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        SortFilter(),
        TypeFilter(),
    )

    private suspend fun fetchCatalogPage(
        stateKey: String,
        page: Int,
        pageUrl: String,
        sort: String?,
        typeIndex: String?,
    ): AnimesPage {
        if (page <= 1) {
            val response = client.newCall(GET(pageUrl, headers, CacheControl.FORCE_NETWORK)).awaitSuccess()
            val document = response.asJsoup()
            val html = document.outerHtml()

            val csrf = document.selectFirst("meta[name=csrf-token]")?.attr("content")?.trim()
            val snapshot = findComponentSnapshot(document, "pages.anime-index")

            val initialUpdates = buildMap {
                if (!sort.isNullOrBlank() && sort != "title-asc") {
                    put("sort", JsonPrimitive(sort))
                }
                if (!typeIndex.isNullOrBlank() && typeIndex != "0") {
                    put("type", JsonPrimitive(typeIndex))
                }
            }

            if (initialUpdates.isNotEmpty() && !csrf.isNullOrBlank() && !snapshot.isNullOrBlank()) {
                val updateResult = callLivewire(
                    referer = pageUrl,
                    csrf = csrf,
                    snapshot = snapshot,
                    updates = JsonObject(initialUpdates),
                    calls = JsonArray(emptyList()),
                )
                if (updateResult != null) {
                    catalogStates[stateKey] = LivewirePageState(
                        pageUrl = pageUrl,
                        csrf = csrf,
                        snapshot = updateResult.snapshot,
                        nextCursor = updateResult.nextCursor,
                        hasMore = updateResult.hasMore,
                    )
                    val animes = updateResult.items.mapNotNull(::catalogItemToSAnime)
                    return AnimesPage(animes, updateResult.hasMore && !updateResult.nextCursor.isNullOrBlank())
                }
            }

            val itemsRaw = extractJsJsonLiteral(html, "items: JSON.parse('")
            val items = if (itemsRaw != null) {
                runCatching { json.parseToJsonElement(itemsRaw).jsonArray }.getOrNull().orEmpty()
            } else {
                emptyList()
            }
            val nextCursor = NEXT_CURSOR_REGEX.find(html)?.groupValues?.getOrNull(1)
            val hasMore = HAS_MORE_TRUE_REGEX.containsMatchIn(html)

            if (!csrf.isNullOrBlank() && !snapshot.isNullOrBlank()) {
                catalogStates[stateKey] = LivewirePageState(
                    pageUrl = pageUrl,
                    csrf = csrf,
                    snapshot = snapshot,
                    nextCursor = nextCursor,
                    hasMore = hasMore,
                )
            }
            return AnimesPage(
                animes = items.mapNotNull(::catalogItemToSAnime),
                hasNextPage = hasMore && !nextCursor.isNullOrBlank(),
            )
        }

        val state = catalogStates[stateKey] ?: return AnimesPage(emptyList(), false)
        val cursor = state.nextCursor?.takeIf { state.hasMore && it.isNotBlank() }
            ?: return AnimesPage(emptyList(), false)

        val loadCalls = buildJsonArray {
            add(
                buildJsonObject {
                    put("path", "")
                    put("method", "loadPage")
                    put("params", buildJsonArray { add(JsonPrimitive(cursor)) })
                },
            )
        }
        val result = callLivewire(
            referer = state.pageUrl,
            csrf = state.csrf,
            snapshot = state.snapshot,
            updates = JsonObject(emptyMap()),
            calls = loadCalls,
        ) ?: return AnimesPage(emptyList(), false)

        catalogStates[stateKey] = state.copy(
            snapshot = result.snapshot,
            nextCursor = result.nextCursor,
            hasMore = result.hasMore,
        )
        return AnimesPage(
            animes = result.items.mapNotNull(::catalogItemToSAnime),
            hasNextPage = result.hasMore && !result.nextCursor.isNullOrBlank(),
        )
    }

    private fun parseCatalogDocument(document: Document): AnimesPage {
        val html = document.outerHtml()
        val itemsRaw = extractJsJsonLiteral(html, "items: JSON.parse('") ?: return AnimesPage(emptyList(), false)
        val items = runCatching { json.parseToJsonElement(itemsRaw).jsonArray }.getOrNull().orEmpty()
        val hasMore = HAS_MORE_TRUE_REGEX.containsMatchIn(html)
        return AnimesPage(items.mapNotNull(::catalogItemToSAnime), hasMore)
    }

    private fun catalogItemToSAnime(element: JsonElement): SAnime? {
        val obj = element as? JsonObject ?: return null
        val slug = obj["slug"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: return null
        val titleText = obj["main_title"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: pickPreferredTitle(obj["title_list"] as? JsonObject)
            ?: return null

        return SAnime.create().apply {
            url = "/anime/$slug"
            title = titleText
            thumbnail_url = obj["cover"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
            genre = (obj["tags"] as? JsonArray)
                ?.mapNotNull { (it as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull?.trim() }
                ?.filter(String::isNotEmpty)
                ?.joinToString(", ")
                ?.ifEmpty { null }
            status = when (obj["is_ongoing"]?.jsonPrimitive?.booleanOrNull) {
                true -> SAnime.ONGOING
                false -> SAnime.COMPLETED
                null -> SAnime.UNKNOWN
            }
        }
    }

    // ============================== Details & Episodes ==============================

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val html = document.outerHtml()

        val titlesJson = extractJsJsonLiteral(html, "anmTitles: JSON.parse('")
        val titlesObj = titlesJson?.let {
            runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull()
        }
        val resolvedTitle = pickPreferredTitle(titlesObj)
            ?: document.selectFirst("h1")?.text()?.trim().orEmpty()

        val coverUrl = document.select("img[src*=/images/anime/]")
            .map { it.absUrl("src").ifBlank { it.attr("src") } }
            .firstOrNull { it.isNotBlank() && !it.contains("object-cover") }
            ?: document.selectFirst("div.mx-auto img[src*=/images/anime/]")?.absUrl("src")
            ?: document.select("img[src*=/images/anime/]").lastOrNull()?.absUrl("src")

        val synopsis = document.selectFirst("h3:contains(Synopsis) + div")?.text()?.trim()
            ?: document.select("div.text-slate-100 p")
                .joinToString("\n\n") { it.text().trim() }
                .ifEmpty { null }

        val genres = document.select("a[href*=/tag/]")
            .map { it.text().trim() }
            .filter(String::isNotEmpty)
            .distinct()
            .joinToString(", ")
            .ifEmpty { null }

        val metaText = document.select("div.flex.flex-wrap span.inline-block")
            .joinToString(" ") { it.text().trim() }

        val parsedStatus = when {
            metaText.contains("Ongoing", ignoreCase = true) ||
                metaText.contains("Airing", ignoreCase = true) -> SAnime.ONGOING
            metaText.contains("Completed", ignoreCase = true) -> SAnime.COMPLETED
            metaText.contains("Upcoming", ignoreCase = true) -> SAnime.UNKNOWN
            else -> SAnime.UNKNOWN
        }

        return SAnime.create().apply {
            setUrlWithoutDomain(response.request.url.toString())
            title = resolvedTitle
            thumbnail_url = coverUrl
            description = synopsis
            genre = genres
            status = parsedStatus
        }
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = withContext(Dispatchers.IO) {
        val requestUrl = baseUrl + anime.url
        val response = client.newCall(GET(requestUrl, headers, CacheControl.FORCE_NETWORK)).awaitSuccess()
        val document = response.asJsoup()
        val html = document.outerHtml()

        val animeSlug = anime.url.trimEnd('/').substringAfterLast('/')
        val allItems = mutableListOf<JsonElement>()

        val initialRaw = extractJsJsonLiteral(html, "items: JSON.parse('")
        if (initialRaw != null) {
            runCatching { json.parseToJsonElement(initialRaw).jsonArray }.getOrNull()?.let {
                allItems.addAll(it)
            }
        }

        val csrf = document.selectFirst("meta[name=csrf-token]")?.attr("content")?.trim()
        var snapshot = findComponentSnapshot(document, "pages.anime-show")
            ?: document.select("div[wire:snapshot]")
                .map { it.attr("wire:snapshot") }
                .lastOrNull { it.isNotBlank() }

        var nextCursor = NEXT_CURSOR_REGEX.find(html)?.groupValues?.getOrNull(1)
        var hasMore = HAS_MORE_TRUE_REGEX.containsMatchIn(html)
        var pageCount = 0

        while (hasMore && !nextCursor.isNullOrBlank() && !csrf.isNullOrBlank() && !snapshot.isNullOrBlank() && pageCount < 60) {
            pageCount++
            val currentCursor = nextCursor
            val calls = buildJsonArray {
                add(
                    buildJsonObject {
                        put("path", "")
                        put("method", "loadPage")
                        put("params", buildJsonArray { add(JsonPrimitive(currentCursor)) })
                    },
                )
            }
            val result = callLivewire(
                referer = requestUrl,
                csrf = csrf,
                snapshot = snapshot,
                updates = JsonObject(emptyMap()),
                calls = calls,
            ) ?: break

            if (result.items.isEmpty()) break
            allItems.addAll(result.items)
            snapshot = result.snapshot
            nextCursor = result.nextCursor
            hasMore = result.hasMore
        }

        val seenSlugs = HashSet<String>()
        allItems.mapIndexedNotNull { idx, element ->
            val obj = element as? JsonObject ?: return@mapIndexedNotNull null
            val epSlug = obj["slug"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
                ?: return@mapIndexedNotNull null
            if (!seenSlugs.add(epSlug)) return@mapIndexedNotNull null

            val epTitle = pickPreferredTitle(obj["title_list"] as? JsonObject)
            val airDate = obj["air_date"]?.jsonPrimitive?.contentOrNull
            val epNum = epSlug.toFloatOrNull()
                ?: EP_NUM_REGEX.find(epSlug)?.value?.toFloatOrNull()
                ?: (idx + 1).toFloat()

            SEpisode.create().apply {
                url = "/anime/$animeSlug/$epSlug"
                name = if (!epTitle.isNullOrBlank()) "Episode $epSlug: $epTitle" else "Episode $epSlug"
                episode_number = epNum
                date_upload = parseAirDate(airDate)
            }
        }.reversed()
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val html = document.outerHtml()
        val animeSlug = response.request.url.encodedPath.trimEnd('/').substringAfterLast('/')
        val initialRaw = extractJsJsonLiteral(html, "items: JSON.parse('") ?: return emptyList()
        val items = runCatching { json.parseToJsonElement(initialRaw).jsonArray }.getOrNull().orEmpty()
        return items.mapIndexedNotNull { idx, element ->
            val obj = element as? JsonObject ?: return@mapIndexedNotNull null
            val epSlug = obj["slug"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
                ?: return@mapIndexedNotNull null
            val epTitle = pickPreferredTitle(obj["title_list"] as? JsonObject)
            SEpisode.create().apply {
                url = "/anime/$animeSlug/$epSlug"
                name = if (!epTitle.isNullOrBlank()) "Episode $epSlug: $epTitle" else "Episode $epSlug"
                episode_number = epSlug.toFloatOrNull() ?: (idx + 1).toFloat()
                date_upload = parseAirDate(obj["air_date"]?.jsonPrimitive?.contentOrNull)
            }
        }.reversed()
    }

    override fun seasonListParse(response: Response): List<SAnime> {
        throw UnsupportedOperationException("Not used")
    }

    // ============================== Hosters & Videos ==============================

    override fun hosterListParse(response: Response): List<Hoster> {
        val html = response.body.string()
        val playerJsons = extractAllJsJsonLiterals(html, "vidstackPlayer(JSON.parse('")
        if (playerJsons.isEmpty()) return emptyList()

        val allVideos = mutableListOf<Video>()
        playerJsons.forEachIndexed { idx, rawJson ->
            val obj = runCatching { json.parseToJsonElement(rawJson).jsonObject }.getOrNull() ?: return@forEachIndexed
            val masterUrl = obj["src"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
                ?: return@forEachIndexed

            val subtitles = (obj["subtitles"] as? JsonArray)
                ?.mapNotNull { subEl ->
                    val subObj = subEl as? JsonObject ?: return@mapNotNull null
                    val fileUrl = subObj["file"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
                        ?: return@mapNotNull null
                    val langTag = subObj["language"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    val title = subObj["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    val isDefault = subObj["default"]?.jsonPrimitive?.booleanOrNull == true
                    val label = langTag.ifEmpty { title.ifEmpty { "en" } }
                    Triple(isDefault || langTag.equals("en", ignoreCase = true), fileUrl, label)
                }
                ?.sortedByDescending { it.first }
                ?.map { Track(it.second, it.third) }
                .orEmpty()

            val prefix = if (playerJsons.size > 1) "Source ${idx + 1} - " else "AniZone - "
            val extracted = playlistUtils.extractFromHls(
                playlistUrl = masterUrl,
                referer = "$baseUrl/",
                videoNameGen = { quality -> "$prefix$quality" },
                subtitleList = subtitles,
                includeMasterOnSeparateAudio = true,
            )
            allVideos.addAll(extracted)
        }

        return allVideos.distinctBy { it.videoUrl }.toHosterList()
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val prefQuality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
        val sorted = sortedWith(
            compareByDescending<Video> { it.videoTitle.contains(prefQuality, ignoreCase = true) }
                .thenByDescending { it.videoTitle.contains("Auto", ignoreCase = true) }
                .thenByDescending { it.resolution ?: 0 },
        )
        return sorted.mapIndexed { index, video ->
            video.copy(preferred = index == 0)
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
    }

    // ============================== Livewire & Helpers ==============================

    private suspend fun callLivewire(
        referer: String,
        csrf: String,
        snapshot: String,
        updates: JsonObject,
        calls: JsonArray,
    ): LivewireResult? {
        val payload = buildJsonObject {
            put("_token", csrf)
            put(
                "components",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("snapshot", snapshot)
                            put("updates", updates)
                            put("calls", calls)
                        },
                    )
                },
            )
        }.toString()

        val lwHeaders = headers.newBuilder()
            .set("Content-Type", "application/json")
            .set("X-Livewire", "true")
            .set("X-CSRF-TOKEN", csrf)
            .set("Referer", referer)
            .build()

        val response = runCatching {
            client.newCall(
                POST(
                    url = "$baseUrl/livewire/update",
                    headers = lwHeaders,
                    body = payload.toRequestBody(JSON_MEDIA_TYPE),
                    cache = CacheControl.FORCE_NETWORK,
                ),
            ).awaitSuccess()
        }.getOrNull() ?: return null

        val responseBody = response.use { it.body.string() }
        val root = runCatching { json.parseToJsonElement(responseBody).jsonObject }.getOrNull() ?: return null
        val component = root["components"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
        val newSnapshot = component["snapshot"]?.jsonPrimitive?.contentOrNull ?: snapshot
        val dispatches = component["effects"]?.jsonObject?.get("dispatches")?.jsonArray.orEmpty()
        val loadedDispatch = dispatches
            .mapNotNull { it as? JsonObject }
            .firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull == "items-loaded" }
            ?: return LivewireResult(newSnapshot, emptyList(), null, false)

        val params = loadedDispatch["params"]?.jsonObject ?: return LivewireResult(newSnapshot, emptyList(), null, false)
        val items = params["items"]?.jsonArray?.toList().orEmpty()
        val nextCursor = params["nextCursor"]?.jsonPrimitive?.contentOrNull
        val hasMore = params["hasMore"]?.jsonPrimitive?.booleanOrNull == true
        return LivewireResult(newSnapshot, items, nextCursor, hasMore)
    }

    private fun findComponentSnapshot(document: Document, componentName: String): String? {
        return document.select("div[wire:snapshot]")
            .map { it.attr("wire:snapshot") }
            .firstOrNull { snap ->
                val memoName = runCatching {
                    json.parseToJsonElement(snap).jsonObject["memo"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
                }.getOrNull()
                memoName == componentName
            }
    }

    private fun extractJsJsonLiteral(source: String, marker: String): String? {
        val start = source.indexOf(marker)
        if (start < 0) return null
        return readSingleQuotedJsLiteral(source, start + marker.length)
    }

    private fun extractAllJsJsonLiterals(source: String, marker: String): List<String> {
        val results = mutableListOf<String>()
        var searchFrom = 0
        while (true) {
            val idx = source.indexOf(marker, searchFrom)
            if (idx < 0) break
            val contentStart = idx + marker.length
            val decoded = readSingleQuotedJsLiteral(source, contentStart)
            if (decoded != null) {
                results.add(decoded)
            }
            searchFrom = contentStart + 1
        }
        return results
    }

    private fun readSingleQuotedJsLiteral(source: String, startIndex: Int): String? {
        val raw = StringBuilder()
        var i = startIndex
        var escaped = false
        while (i < source.length) {
            val c = source[i]
            if (escaped) {
                raw.append('\\').append(c)
                escaped = false
            } else if (c == '\\') {
                escaped = true
            } else if (c == '\'') {
                return decodeJsEscapes(raw.toString())
            } else {
                raw.append(c)
            }
            i++
        }
        return null
    }

    private fun decodeJsEscapes(raw: String): String {
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\' && i + 1 < raw.length) {
                when (val next = raw[i + 1]) {
                    '\'' -> {
                        out.append('\'')
                        i += 2
                    }
                    '"' -> {
                        out.append('"')
                        i += 2
                    }
                    '/' -> {
                        out.append('/')
                        i += 2
                    }
                    '\\' -> {
                        out.append('\\')
                        i += 2
                    }
                    'n' -> {
                        out.append('\n')
                        i += 2
                    }
                    'r' -> {
                        out.append('\r')
                        i += 2
                    }
                    't' -> {
                        out.append('\t')
                        i += 2
                    }
                    'u' -> {
                        if (i + 5 < raw.length) {
                            val codePoint = raw.substring(i + 2, i + 6).toIntOrNull(16)
                            if (codePoint != null) {
                                out.append(codePoint.toChar())
                                i += 6
                                continue
                            }
                        }
                        out.append(c)
                        i++
                    }
                    else -> {
                        out.append(next)
                        i += 2
                    }
                }
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    private fun pickPreferredTitle(titleList: JsonObject?): String? {
        if (titleList == null || titleList.isEmpty()) return null
        return titleList["1"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
            ?: titleList["5"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
            ?: titleList.values.firstOrNull()?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
    }

    private fun parseAirDate(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        return runCatching { AIR_DATE_FORMAT.parse(raw.trim())?.time ?: 0L }.getOrDefault(0L)
    }

    private data class LivewirePageState(
        val pageUrl: String,
        val csrf: String,
        val snapshot: String,
        val nextCursor: String?,
        val hasMore: Boolean,
    )

    private data class LivewireResult(
        val snapshot: String,
        val items: List<JsonElement>,
        val nextCursor: String?,
        val hasMore: Boolean,
    )

    // ============================== Filters ==============================

    private open class UriPartFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart(): String = vals[state].second
    }

    private class SortFilter : UriPartFilter(
        "Sort by",
        arrayOf(
            "Latest Release" to "release-desc",
            "Last Added" to "added-desc",
            "A-Z" to "title-asc",
            "Z-A" to "title-desc",
            "Earliest Release" to "release-asc",
            "First Added" to "added-asc",
        ),
    )

    private class TypeFilter : AnimeFilter.Select<String>(
        "Type",
        arrayOf("All", "Unknown", "TV Series", "OVA", "Movie", "Other", "Web", "TV Special", "Music Video"),
    )

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val NEXT_CURSOR_REGEX = Regex("""nextCursor:\s*'([^']+)'""")
        private val HAS_MORE_TRUE_REGEX = Regex("""hasMore:\s*true""")
        private val EP_NUM_REGEX = Regex("""\d+(?:\.\d+)?""")
        private val AIR_DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)

        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "Auto"
        private val PREF_QUALITY_ENTRIES = arrayOf("Auto", "1080p", "720p", "480p", "360p")
    }
}
