package eu.kanade.tachiyomi.lib.dailymotionextractor

import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

class DailymotionExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
) {

    private val json = Json {
        ignoreUnknownKeys = true
    }

    fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        val normalizedUrl = if (url.startsWith("//")) "https:$url" else url
        val videoId = extractVideoId(normalizedUrl) ?: return emptyList()

        val apiHeaders = headers.newBuilder()
            .set("Accept", "application/json")
            .set("Referer", "https://www.dailymotion.com/")
            .build()

        val body = runCatching {
            client.newCall(GET("https://www.dailymotion.com/player/metadata/video/$videoId", apiHeaders))
                .execute()
                .use { response ->
                    if (!response.isSuccessful) return emptyList()
                    response.body.string()
                }
        }.getOrNull() ?: return emptyList()

        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
        val autoArray = root["qualities"]?.jsonObject?.get("auto")?.jsonArray ?: return emptyList()
        val masterUrl = autoArray.firstOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?: return emptyList()

        val subtitleTracks = extractSubtitles(root["subtitles"]?.jsonObject)

        return PlaylistUtils(client, headers).extractFromHls(
            playlistUrl = masterUrl,
            referer = "https://www.dailymotion.com/",
            videoNameGen = { quality -> "${prefix}Dailymotion - $quality" },
            subtitleList = subtitleTracks,
        )
    }

    private fun extractVideoId(url: String): String? {
        val httpUrl = url.toHttpUrlOrNull()
        val queryId = httpUrl?.queryParameter("video")?.takeIf { it.isNotBlank() }
        if (queryId != null) return queryId
        return ID_REGEX.find(url)?.groupValues?.getOrNull(1)
    }

    private fun extractSubtitles(subtitlesObj: JsonObject?): List<Track> {
        val dataObj = subtitlesObj?.get("data") as? JsonObject ?: return emptyList()
        val tracks = mutableListOf<Track>()
        for ((langKey, value) in dataObj) {
            val entry = value as? JsonObject ?: continue
            val label = entry["label"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: langKey
            val urls = entry["urls"]?.jsonArray.orEmpty()
                .mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank) }
            val chosen = urls.firstOrNull { it.contains(".vtt", ignoreCase = true) || it.contains(".srt", ignoreCase = true) }
                ?: urls.firstOrNull()
            if (chosen != null) {
                tracks.add(Track(chosen, label))
            }
        }
        return tracks
    }

    companion object {
        private val ID_REGEX = Regex("""(?:dailymotion\.com/(?:embed/)?video/|dai\.ly/)([A-Za-z0-9]+)""")
    }
}
