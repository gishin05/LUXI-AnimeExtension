package eu.kanade.tachiyomi.lib.playlistutils

import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

class PlaylistUtils(
    private val client: OkHttpClient,
    private val headers: Headers = Headers.headersOf(),
) {

    fun extractFromHls(
        playlistUrl: String,
        referer: String = "",
        videoHeadersGen: ((Headers, String, String) -> Headers)? = null,
        videoNameGen: (String) -> String = { quality -> quality },
        subtitleList: List<Track> = emptyList(),
        audioList: List<Track> = emptyList(),
        includeMasterOnSeparateAudio: Boolean = true,
    ): List<Video> {
        val masterHttpUrl = playlistUrl.toHttpUrlOrNull() ?: return emptyList()
        val requestHeaders = buildMergedHeaders(referer)

        val playlistBody = runCatching {
            client.newCall(GET(playlistUrl, requestHeaders)).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                response.body.string()
            }
        }.getOrNull() ?: return emptyList()

        if (!playlistBody.contains(STREAM_INF_PREFIX)) {
            val singleHeaders = videoHeadersGen?.invoke(requestHeaders, referer, playlistUrl) ?: requestHeaders
            return listOf(
                Video(
                    videoUrl = playlistUrl,
                    videoTitle = videoNameGen("Video"),
                    headers = singleHeaders,
                    subtitleTracks = subtitleList.distinctBy { it.url },
                    audioTracks = audioList.distinctBy { it.url },
                    initialized = true,
                ),
            )
        }

        val audioGroups = mutableMapOf<String, MutableList<Track>>()
        val parsedSubtitles = mutableListOf<Track>()
        val variants = mutableListOf<HlsVariant>()

        val lines = playlistBody.lineSequence().map { it.trim() }.toList()
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            when {
                line.startsWith(MEDIA_PREFIX) -> {
                    val attrs = parseAttributes(line.removePrefix(MEDIA_PREFIX))
                    val type = attrs["TYPE"]?.uppercase()
                    val uri = attrs["URI"]?.let { resolveUrl(masterHttpUrl, it) }
                    if (!uri.isNullOrBlank()) {
                        val langLabel = buildTrackLabel(attrs["NAME"], attrs["LANGUAGE"])
                        when (type) {
                            "AUDIO" -> {
                                val groupId = attrs["GROUP-ID"].orEmpty()
                                audioGroups.getOrPut(groupId) { mutableListOf() }.add(Track(uri, langLabel))
                            }
                            "SUBTITLES" -> {
                                parsedSubtitles.add(Track(uri, langLabel))
                            }
                        }
                    }
                }
                line.startsWith(STREAM_INF_PREFIX) -> {
                    val attrs = parseAttributes(line.removePrefix(STREAM_INF_PREFIX))
                    val nextLine = lines.drop(index + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                    if (nextLine != null) {
                        val variantUrl = resolveUrl(masterHttpUrl, nextLine)
                        if (!variantUrl.isNullOrBlank()) {
                            val height = attrs["RESOLUTION"]
                                ?.substringAfter('x', "")
                                ?.toIntOrNull()
                            val bandwidth = attrs["BANDWIDTH"]?.toIntOrNull()
                            val audioGroup = attrs["AUDIO"]
                            variants.add(
                                HlsVariant(
                                    url = variantUrl,
                                    height = height,
                                    bandwidth = bandwidth,
                                    audioGroup = audioGroup,
                                ),
                            )
                        }
                    }
                }
            }
            index++
        }

        if (variants.isEmpty()) {
            val fallbackHeaders = videoHeadersGen?.invoke(requestHeaders, referer, playlistUrl) ?: requestHeaders
            return listOf(
                Video(
                    videoUrl = playlistUrl,
                    videoTitle = videoNameGen("Auto"),
                    headers = fallbackHeaders,
                    subtitleTracks = (subtitleList + parsedSubtitles).distinctBy { it.url },
                    audioTracks = audioList.distinctBy { it.url },
                    initialized = true,
                ),
            )
        }

        val mergedSubtitles = (subtitleList + parsedSubtitles).distinctBy { it.url }
        val videos = mutableListOf<Video>()

        if (audioGroups.isNotEmpty() && includeMasterOnSeparateAudio) {
            val masterHeaders = videoHeadersGen?.invoke(requestHeaders, referer, playlistUrl) ?: requestHeaders
            videos.add(
                Video(
                    videoUrl = playlistUrl,
                    videoTitle = videoNameGen("Auto"),
                    resolution = Int.MAX_VALUE,
                    headers = masterHeaders,
                    preferred = true,
                    subtitleTracks = mergedSubtitles,
                    audioTracks = emptyList(),
                    initialized = true,
                ),
            )
        }

        val sortedVariants = variants
            .distinctBy { it.url }
            .sortedWith(
                compareByDescending<HlsVariant> { it.height ?: 0 }
                    .thenByDescending { it.bandwidth ?: 0 },
            )

        for (variant in sortedVariants) {
            val qualityLabel = when {
                variant.height != null && variant.height > 0 -> "${variant.height}p"
                variant.bandwidth != null && variant.bandwidth > 0 -> "${variant.bandwidth / 1000}kbps"
                else -> "Video"
            }
            val groupAudio = variant.audioGroup?.let { audioGroups[it] }.orEmpty()
            val mergedAudio = (audioList + groupAudio).distinctBy { it.url }
            val variantHeaders = videoHeadersGen?.invoke(requestHeaders, referer, variant.url) ?: requestHeaders

            videos.add(
                Video(
                    videoUrl = variant.url,
                    videoTitle = videoNameGen(qualityLabel),
                    resolution = variant.height,
                    bitrate = variant.bandwidth,
                    headers = variantHeaders,
                    subtitleTracks = mergedSubtitles,
                    audioTracks = mergedAudio,
                    initialized = true,
                ),
            )
        }

        return videos
    }

    private fun buildMergedHeaders(referer: String): Headers {
        if (referer.isBlank()) return headers
        val builder = headers.newBuilder()
        if (headers["Referer"] == null) {
            builder.set("Referer", referer)
        }
        val origin = referer.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" }
        if (origin != null && headers["Origin"] == null) {
            builder.set("Origin", origin)
        }
        return builder.build()
    }

    private fun resolveUrl(base: HttpUrl, raw: String): String? {
        val cleaned = raw.trim().removeSurrounding("\"")
        if (cleaned.isEmpty()) return null
        return base.resolve(cleaned)?.toString() ?: cleaned.takeIf { it.startsWith("http") }
    }

    private fun parseAttributes(input: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        ATTR_REGEX.findAll(input).forEach { match ->
            val key = match.groupValues[1].uppercase()
            val value = match.groupValues[2].trim().removeSurrounding("\"")
            result[key] = value
        }
        return result
    }

    private fun buildTrackLabel(name: String?, language: String?): String {
        val cleanName = name?.trim().orEmpty()
        val cleanLang = language?.trim().orEmpty()
        return when {
            cleanName.isNotEmpty() && cleanLang.isNotEmpty() && !cleanName.equals(cleanLang, ignoreCase = true) ->
                "$cleanName ($cleanLang)"
            cleanName.isNotEmpty() -> cleanName
            cleanLang.isNotEmpty() -> cleanLang
            else -> "Default"
        }
    }

    private data class HlsVariant(
        val url: String,
        val height: Int?,
        val bandwidth: Int?,
        val audioGroup: String?,
    )

    companion object {
        private const val STREAM_INF_PREFIX = "#EXT-X-STREAM-INF:"
        private const val MEDIA_PREFIX = "#EXT-X-MEDIA:"
        private val ATTR_REGEX = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")
    }
}
