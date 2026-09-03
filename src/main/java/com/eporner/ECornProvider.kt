package com.eporner

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.Interceptor
import org.jsoup.nodes.Element

class ECornProvider : MainAPI() {
    override var name = "E Corn"
    override var mainUrl = "https://www.eporner.com"
    override var lang = "en"
    override val supportedTypes = setOf(TvType.NSFW, TvType.Movie, TvType.Others)
    override val vpnStatus = VPNStatus.None
    override val providerType = ProviderType.DirectProvider
    override val hasMainPage = true
    override val hasQuickSearch = false

    override val mainPage = listOf(
        MainPageData("Most Recent", ""),
        MainPageData("Top Rated", "top-rated"),
        MainPageData("Most Viewed", "most-viewed"),
        MainPageData("Best Videos", "best-videos"),
        MainPageData("4K Ultra HD", "cat/4k-porn"),
        MainPageData("60 FPS", "cat/60fps"),
        MainPageData("VR Porn", "cat/vr-porn"),
        MainPageData("Amateur", "cat/amateur"),
        MainPageData("Anal", "cat/anal"),
        MainPageData("Blowjob", "cat/blowjob"),
        MainPageData("MILF", "cat/milf"),
        MainPageData("Japanese", "cat/japanese"),
        MainPageData("Hentai", "cat/hentai")
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val path = request.data.trim()
        val url = if (path.isEmpty()) {
            if (page <= 1) "$mainUrl/" else "$mainUrl/$page/"
        } else {
            if (page <= 1) "$mainUrl/$path/" else "$mainUrl/$path/$page/"
        }

        val res = app.get(
            url,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "$mainUrl/"
            )
        )
        val document = res.document
        val items = document.select("div.mb").mapNotNull { it.toSearchResponse() }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = items,
                isHorizontalImages = true
            ),
            hasNext = items.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return search(query, 1)?.items
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val cleanQuery = query.trim().replace("\\s+".toRegex(), "-")
        val url = if (page <= 1) {
            "$mainUrl/search/$cleanQuery/"
        } else {
            "$mainUrl/search/$cleanQuery/$page/"
        }

        val res = app.get(
            url,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "$mainUrl/"
            )
        )
        val document = res.document
        val items = document.select("div.mb").mapNotNull { it.toSearchResponse() }
        val hasNext = document.selectFirst("link[rel=\"next\"]") != null ||
                document.selectFirst("a[rel=\"next\"]") != null ||
                document.select("a[href*=\"/search/$cleanQuery/${page + 1}/\"]").isNotEmpty()

        return newSearchResponseList(items, hasNext)
    }

    override suspend fun load(url: String): LoadResponse? {
        val res = app.get(
            url,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "$mainUrl/"
            )
        )
        val document = res.document

        val title = document.selectFirst("h1")?.text()
            ?: document.selectFirst("meta[property=\"og:title\"]")?.attr("content")
            ?: document.title().replace(" - EPORNER", "").trim()

        if (title.isBlank()) return null

        val posterUrl = document.selectFirst("meta[property=\"og:image\"]")?.attr("content")
            ?: document.selectFirst("link[rel=\"image_src\"]")?.attr("href")

        val plot = document.selectFirst("meta[name=\"description\"]")?.attr("content")
            ?.replace("Watch $title", "")
            ?.trim()

        val tags = document.select("div.mbtags a, p.mbtags a, div.tags-container a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }

        val actors = document.select("p.mbpornstars a, div.star-info a, a[href^=\"/pornstar/\"]")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val duration = document.selectFirst("span.mbtim")?.text()

        val ratingText = document.selectFirst("span.mbrate")?.text()
            ?: document.selectFirst("div.likeup i")?.text()

        val recommendations = document.select("div#relateddiv div.mb")
            .mapNotNull { it.toSearchResponse() }

        return newMovieLoadResponse(
            name = title,
            url = url,
            type = TvType.NSFW,
            dataUrl = url
        ) {
            this.posterUrl = posterUrl
            this.backgroundPosterUrl = posterUrl
            this.plot = plot
            this.tags = tags
            if (actors.isNotEmpty()) {
                addActors(actors)
            }
            if (!duration.isNullOrBlank()) {
                addDuration(duration)
            }
            if (!ratingText.isNullOrBlank()) {
                val cleanRate = ratingText.replace("%", "").trim().toDoubleOrNull()
                if (cleanRate != null) {
                    this.score = Score.from10(cleanRate / 10.0)
                }
            }
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = app.get(
            data,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "$mainUrl/"
            )
        )
        val html = res.text

        val vidRegex = Regex("""player\.vid\s*=\s*['"]([a-zA-Z0-9]+)['"]""")
        val hashRegex = Regex("""player\.hash\s*=\s*['"]([a-f0-9]{32})['"]""")

        val vid = vidRegex.find(html)?.groupValues?.get(1)
            ?: Regex("""/video-([a-zA-Z0-9]+)""").find(data)?.groupValues?.get(1)
        val hash = hashRegex.find(html)?.groupValues?.get(1)

        var foundLinks = false

        if (!vid.isNullOrBlank() && !hash.isNullOrBlank()) {
            val calcToken = calcHash(hash)
            val apiUrl = "$mainUrl/xhr/video/$vid?hash=$calcToken&device=generic&domain=www.eporner.com&fallback=false"

            try {
                val apiRes = app.get(
                    apiUrl,
                    headers = mapOf(
                        "Referer" to data,
                        "User-Agent" to USER_AGENT,
                        "Accept" to "application/json, text/javascript, */*; q=0.01",
                        "X-Requested-With" to "XMLHttpRequest"
                    )
                )

                val videoData = tryParseJson<EpornerVideoResponse>(apiRes.text)
                val mp4Sources = videoData?.sources?.mp4

                if (!mp4Sources.isNullOrEmpty()) {
                    mp4Sources.forEach { (formatKey, sourceItem) ->
                        val streamUrl = sourceItem.src ?: return@forEach
                        if (!streamUrl.startsWith("http")) return@forEach

                        val qualityLabel = sourceItem.labelShort ?: formatKey
                        val quality = getQualityFromName(qualityLabel)

                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = "${this.name} - $qualityLabel",
                                url = streamUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = "$mainUrl/"
                                this.quality = quality
                            }
                        )
                        foundLinks = true
                    }
                }
            } catch (e: Throwable) {
                logError(e)
            }
        }

        // Fallback: Parse direct download links if XHR was blocked or returned empty
        if (!foundLinks) {
            val downloadElements = res.document.select("div#downloaddiv span.download-h264 a[href]")
            downloadElements.forEach { a ->
                val href = a.attr("href")
                if (href.isNotBlank()) {
                    val qualityMatch = Regex("""(\d+p)""").find(a.text())?.value ?: "HD"
                    val quality = getQualityFromName(qualityMatch)
                    val streamUrl = fixUrl(href)

                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = "${this.name} - $qualityMatch (Direct)",
                            url = streamUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "$mainUrl/"
                            this.quality = quality
                        }
                    )
                    foundLinks = true
                }
            }
        }

        return foundLinks
    }

    /**
     * Interceptor to ensure all outgoing video chunk / segment requests have clean, canonical headers.
     * Prevents Error 2004 (ERROR_CODE_IO_BAD_HTTP_STATUS 403) and eliminates the 10s anti-hotlink warning teaser.
     */
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        return Interceptor { chain ->
            val original = chain.request()
            val cleanRequest = original.newBuilder()
                .removeHeader("Referer")
                .removeHeader("referer")
                .removeHeader("User-Agent")
                .removeHeader("user-agent")
                .removeHeader("Origin")
                .removeHeader("origin")
                .header("Referer", "$mainUrl/")
                .header("Origin", mainUrl)
                .header("User-Agent", USER_AGENT)
                .header("Sec-Fetch-Dest", "video")
                .header("Sec-Fetch-Mode", "no-cors")
                .header("Sec-Fetch-Site", "cross-site")
                .build()
            chain.proceed(cleanRequest)
        }
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val linkEl = this.selectFirst("a[href^=\"/video-\"]")
            ?: this.selectFirst("a[href*=\"/video-\"]")
            ?: return null

        val href = linkEl.attr("href")
        val title = this.selectFirst("p.mbtit a")?.text()
            ?: linkEl.attr("title").ifBlank { null }
            ?: this.selectFirst("img")?.attr("alt")
            ?: return null

        val imgEl = this.selectFirst("img")
        var posterUrl = imgEl?.attr("data-src")
        if (posterUrl.isNullOrBlank() || posterUrl.startsWith("data:")) {
            posterUrl = imgEl?.attr("src")
        }
        if (posterUrl != null && posterUrl.startsWith("data:")) {
            posterUrl = null
        }

        val qualityText = this.selectFirst("div.mvhdico span")?.text()
        val durationText = this.selectFirst("span.mbtim")?.text()
        val ratingText = this.selectFirst("span.mbrate")?.text()

        return newMovieSearchResponse(title, fixUrl(href), TvType.NSFW) {
            this.posterUrl = posterUrl
            if (!qualityText.isNullOrBlank()) {
                addQuality(qualityText)
            }
            if (!ratingText.isNullOrBlank()) {
                val scoreVal = ratingText.replace("%", "").trim().toDoubleOrNull()
                if (scoreVal != null) {
                    this.score = Score.from10(scoreVal / 10.0)
                }
            }
        }
    }

    private fun calcHash(hash: String): String {
        val clean = hash.trim()
        if (clean.length < 32) return clean
        return (0 until 32 step 8).joinToString("") { i ->
            val chunk = clean.substring(i, minOf(i + 8, clean.length))
            try {
                val num = chunk.toLong(16)
                java.lang.Long.toString(num, 36)
            } catch (_: Exception) {
                chunk
            }
        }
    }

    @Serializable
    data class EpornerVideoResponse(
        @JsonProperty("vid") @SerialName("vid") val vid: String? = null,
        @JsonProperty("available") @SerialName("available") val available: Boolean? = null,
        @JsonProperty("sources") @SerialName("sources") val sources: EpornerSources? = null,
    )

    @Serializable
    data class EpornerSources(
        @JsonProperty("mp4") @SerialName("mp4") val mp4: Map<String, EpornerSourceItem>? = null,
    )

    @Serializable
    data class EpornerSourceItem(
        @JsonProperty("labelShort") @SerialName("labelShort") val labelShort: String? = null,
        @JsonProperty("src") @SerialName("src") val src: String? = null,
        @JsonProperty("type") @SerialName("type") val type: String? = null,
    )
}
