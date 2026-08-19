package com.anizium

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson

class Anizium : MainAPI() {
    override var mainUrl = "https://anizium.com"
    private val apiUrl = "https://api.anizium.co"
    override var name = "Anizium"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    override val mainPage = mainPageOf(
        "$apiUrl/page/home#featured" to "Öne Çıkanlar",
        "$apiUrl/page/home#middle" to "Popüler Animeler",
        "$apiUrl/page/last-added-episodes" to "Son Eklenen Bölümler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val headers = AniziumToken.getApiHeaders()
        val results = mutableListOf<SearchResponse>()

        if (request.data.contains("/page/home")) {
            val res = app.get(apiUrl + "/page/home", headers = headers).parsedSafe<HomeResponseWrapper>()
            if (request.data.endsWith("#featured")) {
                res?.settlementTop?.forEach { item ->
                    item.toSearchResponse()?.let { results.add(it) }
                }
            } else {
                res?.settlementMiddle?.forEach { item ->
                    item.toSearchResponse()?.let { results.add(it) }
                }
                res?.settlementLower?.forEach { item ->
                    item.toSearchResponse()?.let { results.add(it) }
                }
            }
            return newHomePageResponse(request.name, results, hasNext = false)
        } else if (request.data.contains("/page/last-added-episodes")) {
            val url = "$apiUrl/page/last-added-episodes?page=$page"
            val res = app.get(url, headers = headers).parsedSafe<LastAddedResponseWrapper>()
            res?.page?.data?.forEach { item ->
                val id = item.id ?: item.contentId ?: return@forEach
                val name = item.name ?: "Anime"
                val poster = item.poster ?: item.banner ?: item.logo
                val type = if (item.type == "movie") TvType.AnimeMovie else TvType.Anime

                results.add(newAnimeSearchResponse(name, "$apiUrl/anime/get?id=$id", type) {
                    this.posterUrl = poster
                })
            }
            val hasNext = (res?.page?.data?.size ?: 0) >= 10
            return newHomePageResponse(request.name, results, hasNext = hasNext)
        }

        return newHomePageResponse(request.name, results, hasNext = false)
    }

    private fun AnimeItemData.toSearchResponse(): SearchResponse? {
        val id = this.id ?: return null
        val title = this.name ?: return null
        val poster = this.poster ?: this.banner ?: this.logo
        val type = if (this.type == "movie") TvType.AnimeMovie else TvType.Anime

        return newAnimeSearchResponse(title, "$apiUrl/anime/get?id=$id", type) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val headers = AniziumToken.getApiHeaders()
        val url = "$apiUrl/page/search?value=${query.trim()}&page=$page"
        val res = app.get(url, headers = headers).parsedSafe<SearchResponseWrapper>()

        val items = res?.page?.data?.mapNotNull { it.toSearchResponse() } ?: emptyList()
        val hasNext = (res?.page?.page ?: 1) < (res?.page?.totalPages ?: 1)

        return newSearchResponseList(items, hasNext = hasNext)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        return search(query, 1)?.items
    }

    override suspend fun load(url: String): LoadResponse? {
        val headers = AniziumToken.getApiHeaders()
        val animeId = when {
            url.contains("id=") -> url.substringAfter("id=").substringBefore("&")
            url.contains("/anime/") -> url.substringAfterLast("/").substringBefore("?")
            else -> url
        }

        val fetchUrl = "$apiUrl/anime/get?id=$animeId"
        val res = app.get(fetchUrl, headers = headers).parsedSafe<AnimeDetailWrapper>()
        val data = res?.data ?: return null

        val title = data.name ?: "Anime"
        val poster = data.poster ?: data.banner ?: data.logo
        val plot = data.overview ?: data.overviewShort
        val year = data.releaseYear
        val tags = data.genre?.mapNotNull { it.name }
        val status = when (data.status) {
            "Final", "Bitti" -> ShowStatus.Completed
            "Devam Ediyor" -> ShowStatus.Ongoing
            else -> null
        }

        val isMovie = data.type == "movie" || (data.seasons.isNullOrEmpty() && (data.totalSeason ?: 0) == 0)

        if (isMovie) {
            val payload = EpisodeLinkPayload(id = animeId, isMovie = true).toJson()
            return newAnimeLoadResponse(title, url, TvType.AnimeMovie) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.showStatus = status
                data.imdbPoint?.let { this.score = Score.from10(it.toDouble()) }
                addEpisodes(DubStatus.Subbed, listOf(newEpisode(payload) {
                    this.name = title
                    this.episode = 1
                }))
            }
        }

        val episodes = mutableListOf<Episode>()
        data.seasons?.forEach { season ->
            val seasonNum = season.number ?: 1
            season.episodes?.forEach { ep ->
                val epNum = ep.number ?: 1
                val epName = ep.name ?: "$epNum. Bölüm"
                val epPoster = ep.bannerLink
                val epOverview = ep.overview
                val payload = EpisodeLinkPayload(
                    id = animeId,
                    isMovie = false,
                    season = seasonNum,
                    episode = epNum
                ).toJson()

                val episodeObj = newEpisode(payload) {
                    this.name = epName
                    this.season = seasonNum
                    this.episode = epNum
                    this.posterUrl = epPoster
                    this.description = epOverview
                }
                episodes.add(episodeObj)
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
            this.showStatus = status
            data.imdbPoint?.let { this.score = Score.from10(it.toDouble()) }
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val headers = AniziumToken.getApiHeaders()
        val payload = try {
            parseJson<EpisodeLinkPayload>(data)
        } catch (e: Exception) {
            EpisodeLinkPayload(id = data, isMovie = true)
        }

        val animeId = payload.id ?: return false
        val isMovie = payload.isMovie

        val servers = listOf("1", "2")
        for (server in servers) {
            val sourceUrl = if (isMovie) {
                "$apiUrl/anime/source?id=$animeId&site=main&plan=1&server=$server"
            } else {
                "$apiUrl/anime/source?id=$animeId&site=main&plan=1&server=$server&season=${payload.season ?: 1}&episode=${payload.episode ?: 1}"
            }

            val res = app.get(sourceUrl, headers = headers).parsedSafe<SourceWrapper>() ?: continue
            if (res.success != true) continue

            // Altyazıları yükle
            res.subtitles?.forEach { sub ->
                val subUrl = sub.link ?: return@forEach
                val langName = sub.name ?: if (sub.group == "tr") "Türkçe" else if (sub.group == "en") "İngilizce" else "Altyazı"
                subtitleCallback(SubtitleFile(langName, subUrl))
            }

            // Video akışlarını yükle
            res.groups?.forEach { group ->
                val groupKey = (group.group ?: "").lowercase()
                val groupName = (group.name ?: "").lowercase()
                val audioLabel = when {
                    groupKey == "trdub" || groupName.contains("türkçe") || groupName.contains("turkce") -> "🇹🇷 Türkçe Dublaj"
                    groupKey == "original" || groupName.contains("japonca") -> "🇯🇵 Japonca"
                    groupKey == "endub" || groupName.contains("ingilizce") -> "🇬🇧 İngilizce Dublaj"
                    else -> group.name ?: "Ses"
                }

                group.items?.forEach { item ->
                    val videoLink = item.link ?: return@forEach
                    val quality = item.quality ?: 1080
                    val qualityLabel = when (quality) {
                        2160 -> "4K"
                        1440 -> "2K"
                        else -> "${quality}p"
                    }
                    val displayName = "$name - $qualityLabel | $audioLabel"
                    val isHls = videoLink.contains(".m3u8")

                    callback(
                        newExtractorLink(
                            source = name,
                            name = displayName,
                            url = videoLink,
                            type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.quality = quality
                            this.headers = mapOf(
                                "Referer" to "https://anizium.com/",
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                            )
                        }
                    )
                }
            }

            if (!res.groups.isNullOrEmpty()) {
                break
            }
        }

        return true
    }

    // ── Veri Modelleri (JSON Schemas) ──────────────────────────────────

    data class EpisodeLinkPayload(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("isMovie") val isMovie: Boolean = false,
        @JsonProperty("season") val season: Int? = null,
        @JsonProperty("episode") val episode: Int? = null
    )

    data class HomeResponseWrapper(
        @JsonProperty("success") val success: Boolean? = null,
        @JsonProperty("settlement_top") val settlementTop: List<AnimeItemData>? = null,
        @JsonProperty("settlement_middle") val settlementMiddle: List<AnimeItemData>? = null,
        @JsonProperty("settlement_lower") val settlementLower: List<AnimeItemData>? = null
    )

    data class LastAddedResponseWrapper(
        @JsonProperty("success") val success: Boolean? = null,
        @JsonProperty("page") val page: LastAddedPageData? = null
    )

    data class LastAddedPageData(
        @JsonProperty("data") val data: List<AnimeItemData>? = null
    )

    data class SearchResponseWrapper(
        @JsonProperty("success") val success: Boolean? = null,
        @JsonProperty("page") val page: SearchPageData? = null
    )

    data class SearchPageData(
        @JsonProperty("page") val page: Int? = null,
        @JsonProperty("total_pages") val totalPages: Int? = null,
        @JsonProperty("data") val data: List<AnimeItemData>? = null
    )

    data class AnimeItemData(
        @JsonProperty("ID") val id: String? = null,
        @JsonProperty("content_id") val contentId: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("banner") val banner: String? = null,
        @JsonProperty("logo") val logo: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("release_year") val releaseYear: Int? = null
    )

    data class AnimeDetailWrapper(
        @JsonProperty("success") val success: Boolean? = null,
        @JsonProperty("data") val data: AnimeDetailData? = null
    )

    data class AnimeDetailData(
        @JsonProperty("ID") val id: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("banner") val banner: String? = null,
        @JsonProperty("logo") val logo: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("overview_short") val overviewShort: String? = null,
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("quality") val quality: String? = null,
        @JsonProperty("release_year") val releaseYear: Int? = null,
        @JsonProperty("imdb_point") val imdbPoint: Number? = null,
        @JsonProperty("total_season") val totalSeason: Int? = null,
        @JsonProperty("genre") val genre: List<GenreItem>? = null,
        @JsonProperty("seasons") val seasons: List<SeasonItem>? = null
    )

    data class GenreItem(
        @JsonProperty("ID") val id: String? = null,
        @JsonProperty("name") val name: String? = null
    )

    data class SeasonItem(
        @JsonProperty("ID") val id: String? = null,
        @JsonProperty("number") val number: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("episodes") val episodes: List<EpisodeItem>? = null
    )

    data class EpisodeItem(
        @JsonProperty("ID") val id: String? = null,
        @JsonProperty("number") val number: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("banner_link") val bannerLink: String? = null,
        @JsonProperty("quality") val quality: String? = null
    )

    data class SourceWrapper(
        @JsonProperty("success") val success: Boolean? = null,
        @JsonProperty("groups") val groups: List<SourceGroupItem>? = null,
        @JsonProperty("subtitles") val subtitles: List<SourceSubtitleItem>? = null
    )

    data class SourceGroupItem(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("group") val group: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("items") val items: List<SourceQualityItem>? = null
    )

    data class SourceQualityItem(
        @JsonProperty("quality") val quality: Int? = null,
        @JsonProperty("link") val link: String? = null,
        @JsonProperty("type") val type: String? = null
    )

    data class SourceSubtitleItem(
        @JsonProperty("group") val group: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("link") val link: String? = null
    )
}
