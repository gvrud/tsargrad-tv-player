package ru.tsargrad.tv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

data class ResolvedStream(
    val url: String,
    val referer: String,
    val userAgent: String
)

class StreamResolveException(
    val reason: Reason,
    cause: Throwable? = null
) : Exception(reason.name, cause) {

    enum class Reason {
        NETWORK,
        NO_STREAM,
        AUTH_REQUIRED
    }
}

class StreamResolver {

    suspend fun resolve(source: StreamSource): ResolvedStream = withContext(Dispatchers.IO) {
        when (source) {
            StreamSource.RUTUBE -> resolveRutube()
            StreamSource.VK -> resolveVk()
        }
    }

    private fun resolveRutube(): ResolvedStream {
        val headers = baseHeaders(Streams.RUTUBE_REFERER)
        val candidates = mutableListOf<String>()
        var reached = false
        var lastError: Throwable? = null

        val apiUrl = "https://rutube.ru/api/play/options/${Streams.RUTUBE_LIVE_ID}/?format=json"
        try {
            val body = httpGet(apiUrl, headers)
            reached = true
            candidates += parseRutubeLive(body)
            if (candidates.isEmpty()) {
                candidates += HlsExtractor.extract(body)
            }
        } catch (error: IOException) {
            lastError = error
        }

        if (candidates.isEmpty()) {
            val pages = listOf(
                "https://rutube.ru/live/video/${Streams.RUTUBE_LIVE_ID}",
                "https://rutube.ru/play/embed/${Streams.RUTUBE_LIVE_ID}"
            )
            for (page in pages) {
                try {
                    val body = httpGet(page, baseHeaders(Streams.RUTUBE_REFERER, html = true))
                    reached = true
                    candidates += HlsExtractor.extract(body)
                    if (candidates.isNotEmpty()) break
                } catch (error: IOException) {
                    lastError = error
                }
            }
        }

        val url = HlsExtractor.pick(candidates)
            ?: throw StreamResolveException(
                if (reached) StreamResolveException.Reason.NO_STREAM
                else StreamResolveException.Reason.NETWORK,
                lastError
            )

        return ResolvedStream(url, Streams.RUTUBE_REFERER, Streams.USER_AGENT)
    }

    private fun resolveVk(): ResolvedStream {
        val headers = baseHeaders(Streams.VK_REFERER, html = true)
        val hash = Streams.VK_HASH.trim()
        val hashParam = if (hash.isEmpty()) "" else "&hash=$hash"
        val endpoints = listOf(
            "https://vk.com/video_ext.php?oid=${Streams.VK_OWNER_ID}&id=${Streams.VK_VIDEO_ID}$hashParam&hd=2",
            "https://m.vk.com/video_ext.php?oid=${Streams.VK_OWNER_ID}&id=${Streams.VK_VIDEO_ID}$hashParam&hd=2",
            "https://vkvideo.ru/video_ext.php?oid=${Streams.VK_OWNER_ID}&id=${Streams.VK_VIDEO_ID}$hashParam&hd=2"
        )

        var reached = false
        var loginWall = false
        var lastError: Throwable? = null

        for (endpoint in endpoints) {
            val body = try {
                httpGet(endpoint, headers)
            } catch (error: IOException) {
                lastError = error
                continue
            }
            reached = true
            val url = HlsExtractor.pick(HlsExtractor.extract(body))
            if (url != null) {
                return ResolvedStream(url, Streams.VK_REFERER, Streams.USER_AGENT)
            }
            if (looksLikeLogin(body)) {
                loginWall = true
            }
        }

        val reason = when {
            loginWall -> StreamResolveException.Reason.AUTH_REQUIRED
            reached -> StreamResolveException.Reason.NO_STREAM
            else -> StreamResolveException.Reason.NETWORK
        }
        throw StreamResolveException(reason, lastError)
    }

    private fun parseRutubeLive(body: String): List<String> {
        val result = mutableListOf<String>()
        try {
            val hlsNode = JSONObject(body)
                .optJSONObject("live_streams")
                ?.opt("hls")
            when (hlsNode) {
                is JSONArray -> {
                    for (index in 0 until hlsNode.length()) {
                        val item = hlsNode.optJSONObject(index) ?: continue
                        val url = item.optString("url")
                        if (url.startsWith("http")) result += url
                    }
                }

                is JSONObject -> {
                    val url = hlsNode.optString("url")
                    if (url.startsWith("http")) result += url
                }

                is String -> {
                    if (hlsNode.startsWith("http")) result += hlsNode
                }
            }
        } catch (_: Exception) {
        }
        return result
    }

    private fun looksLikeLogin(body: String): Boolean {
        val lower = body.lowercase()
        return lower.contains("id=\"login\"") ||
            lower.contains("name=\"login\"") ||
            lower.contains("action=\"/login")
    }

    private fun baseHeaders(referer: String, html: Boolean = false): Map<String, String> = mapOf(
        "User-Agent" to Streams.USER_AGENT,
        "Accept" to if (html) {
            "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        } else {
            "application/json, text/plain, */*"
        },
        "Accept-Language" to "ru-RU,ru;q=0.9,en;q=0.7",
        "Referer" to referer
    )

    private fun httpGet(url: String, headers: Map<String, String>): String {
        var current = URL(url)
        var redirects = 0
        while (true) {
            val connection = (current.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                instanceFollowRedirects = false
                useCaches = false
                requestMethod = "GET"
                for ((name, value) in headers) {
                    setRequestProperty(name, value)
                }
            }
            try {
                val status = connection.responseCode
                if (status in 300..399) {
                    val location = connection.getHeaderField("Location")
                    if (location.isNullOrBlank() || redirects >= 5) {
                        throw IOException("Bad redirect from $url")
                    }
                    redirects++
                    current = URL(current, location)
                    continue
                }

                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
                if (status !in 200..299) {
                    throw IOException("HTTP $status for $url")
                }

                val encoding = connection.contentEncoding
                val text = if (!encoding.isNullOrBlank() && encoding.contains("gzip", ignoreCase = true)) {
                    GZIPInputStream(ByteArrayInputStream(bytes))
                        .bufferedReader(Charsets.UTF_8)
                        .use { it.readText() }
                } else {
                    String(bytes, Charsets.UTF_8)
                }
                return text
            } finally {
                connection.disconnect()
            }
        }
    }
}

private object HlsExtractor {

    private val unicodeEscape = Regex("""\\u([0-9a-fA-F]{4})""")
    private val hlsUrl = Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*""")

    fun extract(raw: String): List<String> {
        var text = raw
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("\\/", "/")
        text = unicodeEscape.replace(text) { match ->
            match.groupValues[1].toInt(16).toChar().toString()
        }
        return hlsUrl.findAll(text)
            .map { it.value }
            .distinct()
            .toList()
    }

    fun pick(urls: List<String>): String? {
        if (urls.isEmpty()) return null
        return urls.firstOrNull { it.contains("master", ignoreCase = true) }
            ?: urls.firstOrNull { it.contains("index.m3u8", ignoreCase = true) }
            ?: urls.first()
    }
}
