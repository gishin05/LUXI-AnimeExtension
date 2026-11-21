package eu.kanade.tachiyomi.lib.okruextractor

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.OkHttpClient

class OkruExtractor(private val client: OkHttpClient) {

    private val json = Json {
        ignoreUnknownKeys = true
    }

    fun videosFromUrl(
        url: String,
        prefix: String = "",
        fixQualities: Boolean = true,
    ): List<Video> {
        val normalizedUrl = when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("http://") -> url.replaceFirst("http://", "https://")
            else -> url
        }

        val embedHeaders = Headers.Builder()
            .add("Referer", OKRU_BASE)
            .add("User-Agent", DESKTOP_USER_AGENT)
            .build()

        val document = runCatching {
            client.newCall(GET(normalizedUrl, embedHeaders)).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                response.asJsoup()
            }
        }.getOrNull() ?: return emptyList()

        val optionsRaw = document.selectFirst("div[data-options]")
            ?.attr("data-options")
            ?.takeIf { it.isNotBlank() }
            ?: return emptyList()

        val metadataObj = runCatching {
            val root = json.parseToJsonElement(optionsRaw).jsonObject
            val flashvars = root["flashvars"]?.jsonObject ?: return emptyList()
            when (val metaElement = flashvars["metadata"]) {
                is JsonPrimitive -> json.parseToJsonElement(metaElement.content).jsonObject
                is JsonObject -> metaElement
                else -> return emptyList()
            }
        }.getOrNull() ?: return emptyList()

        val hlsUrl = metadataObj["hlsManifestUrl"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?: metadataObj["ondemandHls"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }

        val playlistUtils = PlaylistUtils(client, embedHeaders)
        val hlsVideos = if (hlsUrl != null) {
            playlistUtils.extractFromHls(
                playlistUrl = hlsUrl,
                referer = OKRU_BASE,
                videoNameGen = { quality -> "${prefix}Okru - $quality" },
            )
        } else {
            emptyList()
        }

        val mp4Videos = metadataObj["videos"]?.jsonArray
            ?.mapNotNull { entry ->
                val obj = entry.jsonObject
                val videoUrl = obj["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val rawName = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty().lowercase()
                val (qualityLabel, resolution) = mapOkruQuality(rawName, fixQualities)
                Video(
                    videoUrl = videoUrl,
                    videoTitle = "${prefix}Okru - $qualityLabel (MP4)",
                    resolution = resolution,
                    headers = embedHeaders,
                    initialized = true,
                )
            }
            ?.sortedByDescending { it.resolution ?: 0 }
            .orEmpty()

        return (hlsVideos + mp4Videos).distinctBy { it.videoUrl }
    }

    private fun mapOkruQuality(rawName: String, fixQualities: Boolean): Pair<String, Int?> {
        if (!fixQualities) return rawName to null
        return when (rawName) {
            "full" -> "1080p" to 1080
            "hd" -> "720p" to 720
            "sd" -> "480p" to 480
            "low" -> "360p" to 360
            "lowest" -> "240p" to 240
            "mobile" -> "144p" to 144
            else -> rawName.ifBlank { "MP4" } to null
        }
    }

    companion object {
        private const val OKRU_BASE = "https://ok.ru/"
        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}
