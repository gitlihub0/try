
import android.util.Log
import android.content.Context
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import okhttp3.Interceptor
import okhttp3.ResponseBody
import org.json.JSONArray
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.IvParameterSpec
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject
import okio.BufferedSource

class InatBox : MainAPI() {
    private val contentUrl  = "https://dizibox.rest"
    //private val categoryUrl = "https://dizilab.cfd"

    override var name                 = "InatBox"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries, TvType.Live)
    override var sequentialMainPage   = false

    private val urlToSearchResponse = mutableMapOf<String, SearchResponse>()
    private val aesKey = "ywevqtjrurkwtqgz" //Master secret and iv key (This is used for both secret key and iv. This is the embedded master key for loading categories like sport channels.)

    // InatBox v15 Firebase Remote Config
    private val firebaseProjectId = "inatbox-c60cd"
    private val firebaseApiKey = "AIzaSyBFB8TuBXgojHyshkS6GSlnlQvCtPSRmFs"
    private val firebaseAppId = "1:845468888832:android:3e5f3eac2658b8e21d0de5"
    private var inatRemoteConfig: JSONObject? = null
    private var firebaseApp: Any? = null

    override val mainPage = mainPageOf(
        "https://boxbc.sbs/CDN/001_STR/boxbc.sbs/spor_v2.php"  to "Spor Kanalları",
        "https://boxbc.sbs/CDN/001_STR/boxbc.sbs/derbiler.php" to "Derbiler",

        "${contentUrl}/tv/list1.php"                           to "Kanallar Liste 1 - TR",
        "${contentUrl}/tv/list2.php"                           to "Kanallar Liste 2 - GLB",
        "${contentUrl}/tv/list3.php"                           to "Kanallar Liste 3 - TR",
        "${contentUrl}/tv/sinema.php"                          to "Sinema Kanalları",
        "${contentUrl}/tv/belgesel.php"                        to "Belgesel Kanalları",
        "${contentUrl}/tv/ulusal.php"                          to "Ulusal Kanallar",
        "${contentUrl}/tv/haber.php"                           to "Haber Kanalları",
        "${contentUrl}/tv/eba.php"                             to "Eba Kanalları",
        "${contentUrl}/tv/cocuk.php"                           to "Çocuk Kanalları",
        "${contentUrl}/tv/dini.php"                            to "Dini Kanallar",

        "${contentUrl}/ex/index.php"                           to "EXXEN",
        "${contentUrl}/ga/index.php"                           to "Gain",
        "${contentUrl}/blu/index.php"                          to "BluTV",
        "${contentUrl}/nf/index.php"                           to "Netflix", // Burası şu an çalışmıyor.
        "${contentUrl}/dsny/index.php"                         to "Disney+",
        "${contentUrl}/amz/index.php"                          to "Amazon Prime",
        "${contentUrl}/hb/index.php"                           to "HBO Max",
        "${contentUrl}/tbi/index.php"                          to "Tabii",
        "${contentUrl}/film/mubi.php"                          to "Mubi",
        "${contentUrl}/ccc/index.php"                          to "TOD",

        "${contentUrl}/yabanci-dizi/index.php"                 to "Yabancı Diziler",
        "${contentUrl}/yerli-dizi/index.php"                   to "Yerli Diziler",
        "${contentUrl}/film/yerli-filmler.php"                 to "Yerli Filmler",
        "${contentUrl}/film/4k-film-exo.php"                   to "4K Film İzle | Exo",
        "${contentUrl}/film/4k-film-web.php"                   to "4K Film İzle | Web"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val jsonResponse =
            makeInatRequest(request.data) ?: return newHomePageResponse(request.name, emptyList())

        val searchResults = getSearchResponseList(jsonResponse)

        for (searchResponse in searchResults) {
            val url = searchResponse.url
            if (!urlToSearchResponse.containsKey(url)) {
                urlToSearchResponse[url] = searchResponse
            }
        }

        // Return a HomePageResponse with the parsed results
        return newHomePageResponse(request.name, searchResults)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (urlToSearchResponse.isEmpty()) {
            for (pageData in mainPage) {
                val url = pageData.data
                val jsonResponse = makeInatRequest(url) ?: continue

                val searchResults = getSearchResponseList(jsonResponse)

                for (searchResponse in searchResults) {
                    val contentUrl = searchResponse.url
                    if (!urlToSearchResponse.containsKey(contentUrl)) {
                        urlToSearchResponse[contentUrl] = searchResponse
                    }
                }
            }
        }

        val matchingResults = mutableListOf<SearchResponse>()

        val regex = try {
            Regex(query, RegexOption.IGNORE_CASE)
        } catch (e: Exception) {
            Regex(Regex.escape(query), RegexOption.IGNORE_CASE)
        }

        for ((_, searchResponse) in urlToSearchResponse) {
            if (regex.containsMatchIn(searchResponse.name)) {
                matchingResults.add(searchResponse)
            }
        }

        return matchingResults.distinctBy { it.name }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        return search(query)
    }

    override suspend fun load(url: String): LoadResponse? {
        val item = JSONObject(url)

        if (!inatContentAllowed(item)) {
            return null
        }

        if (item.has("diziType")) {
            item.getString("diziName")
            val type = item.getString("diziType")

            return when (type) {
                "dizi" -> parseTvSeriesResponse(item)
                "film" -> parseMovieResponse(item)
                else -> null
            }

        } else if (item.has("chName") && item.has("chUrl") && item.has("chImg")) {
            item.getString("chName")
            val chType = item.getString("chType")

            val loadResponse = when (chType) {
                "live_url", "cable_sh" -> parseLiveStreamLoadResponse(item)
                "tekli_regex_lb_sh_3" -> parseLiveSportsStreamLoadResponse(item)
                else -> parseMovieResponse(item)
            }
            return loadResponse
        } else {
            return null
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("InatBox", "data: $data")
        return try {
            if (data.startsWith("[")) {
                val chContentJsonArray = JSONArray(data)
                for (i in 0 until chContentJsonArray.length()) {
                    val chContentJsonObject = chContentJsonArray.getJSONObject(i)
                    val chContent = parseToChContent(chContentJsonObject)
                    loadChContentLinks(chContent, subtitleCallback, callback)
                }
            } else {
                val chContentJsonArray = JSONObject(data)
                val chContent = parseToChContent(chContentJsonArray)
                loadChContentLinks(chContent, subtitleCallback, callback)
            }
            true
        } catch (e: Exception) {
            Log.e("InatBox", "Error on loadLinks:${e::class.simpleName} - ${e.message}")
            false
        }
    }

    private suspend fun parseTvSeriesResponse(item: JSONObject, tvType: TvType = TvType.TvSeries): LoadResponse? {
        val episodes = mutableMapOf<DubStatus, MutableList<Episode>>()
        val seasonDataList = mutableListOf<SeasonData>()

        val name = item.getString("diziName")
        val url = item.getString("diziUrl")
        val plot = item.getString("diziDetay")

        val jsonResponse = makeInatRequest(url) ?: return null
        val jsonArray = JSONArray(jsonResponse)

        try {
            for (i in 0 until jsonArray.length()) {
                val seasonItem = jsonArray.getJSONObject(i)
                val seasonName = seasonItem.getString("diziName")
                val seasonData = SeasonData(season = (i + 1), name = seasonName)
                seasonDataList.add(seasonData)

                val seasonUrl = seasonItem.getString("diziUrl")

                // Fetch the episode data for this season
                val episodeResponse = makeInatRequest(seasonUrl) ?: continue
                val episodeArray = try {
                    JSONArray(episodeResponse)
                } catch (e: Exception) {
                    Log.e("InatBox", "Failed to parse episode JSON for season: $seasonName", e)
                    continue
                }

                for (j in 0 until episodeArray.length()) {
                    try {
                        val episodeItem = episodeArray.getJSONObject(j)
                        val episodeName = episodeItem.getString("chName")
                        val episodePoster = episodeItem.getString("chImg")
                        episodes.getOrPut(DubStatus.None) { mutableListOf() }.add(
                            newEpisode(episodeItem.toString()) {
                                this.name = episodeName
                                this.posterUrl = episodePoster
                                this.season = i + 1
                                this.episode = j + 1
                            }
                        )
                    } catch (e: JSONException) {
                        continue
                    }
                }
            }

            // Get the poster URL from the first season
            val firstSeason = jsonArray.getJSONObject(0)
            val posterUrl = firstSeason.getString("diziImg")

            return newAnimeLoadResponse(
                name = name,
                url = item.toString(),
                type = tvType,
                comingSoonIfNone = false
            ) {
                this.episodes = episodes.mapValues { it.value.toList() }.toMutableMap()
                this.posterUrl = posterUrl
                this.plot = plot
                this.seasonNames = seasonDataList
            }
        } catch (e: Exception) {
            Log.e(
                "InatBox",
                "Failed to parse TV series response: ${e.message}\nStacktrace:${
                    e.stackTrace.joinToString("\n")
                }"
            )
            return null
        }
    }

    private suspend fun parseMovieResponse(item: JSONObject): LoadResponse? {
        try {
            if (item.has("diziType")) {
                val name = item.getString("diziName")
                val url = item.getString("diziUrl")
                val posterUrl = item.getString("diziImg")
                val plot = item.getString("diziDetay")

                val jsonResponse = makeInatRequest(url) ?: return null
                val jsonArray = JSONArray(jsonResponse)

                return newMovieLoadResponse(name = name,url = item.toString(), type = TvType.Movie, dataUrl = jsonArray.toString()){
                    this.posterUrl = posterUrl
                    this.plot = plot
                }
            } else {
                val name = item.getString("chName")
                item.getString("chUrl")
                val posterUrl = item.getString("chImg")
                return newMovieLoadResponse(name, item.toString(), TvType.Movie, item.toString()) {
                    this.posterUrl = posterUrl
                }
            }
        } catch (e: Exception) {
            Log.e("InatBox", "Failed to parse movie response: ${e.message}")
            return null
        }
    }

    private suspend fun parseLiveSportsStreamLoadResponse(item: JSONObject): LiveStreamLoadResponse? {
        try {
            val chContent = parseToChContent(item)
            val posterUrl = chContent.chImg

            return newLiveStreamLoadResponse(name, item.toString(), item.toString()) {
                this.posterUrl = posterUrl
            }
        } catch (e: Exception) {
            Log.e("InatBox", "Failed to parse sports live stream response: ${e.message}")
            return null
        }
    }

    private suspend fun parseLiveStreamLoadResponse(item: JSONObject): LiveStreamLoadResponse? {
        try {
            val chContent = parseToChContent(item)
            val name = chContent.chName
            val posterUrl = chContent.chImg

            return newLiveStreamLoadResponse(name, item.toString(), item.toString()) {
                this.posterUrl = posterUrl
            }
        } catch (e: Exception) {
            Log.e("InatBox", "Failed to parse movie response: ${e.message}")
            return null
        }
    }

    private fun inatContentAllowed(item: JSONObject): Boolean {
        val type: String = if (item.has("diziType")) {
            item.getString("diziType")
        } else {
            item.getString("chType")
        }

        return when (type) {
            "link", "web" -> false
            else -> true
        }
    }

    private fun String.vkSourceFix(): String {
        if (this.startsWith("act")) {
            return "https://vk.com/al_video.php?${this}"
        }
        return this
    }

    private fun parseToChContent(item: JSONObject): ChContent {
        return ChContent(
            chName = item.getString("chName"),
            chUrl = item.getString("chUrl").vkSourceFix(),
            chImg = item.getString("chImg"),
            chHeaders = item.getString("chHeaders"),
            chReg = item.getString("chReg"),
            chType = item.getString("chType")
        )
    }

    private suspend fun loadChContentLinks(chContent: ChContent, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit){
        val chType = chContent.chType
        val contentToProcess : ChContent

        if(chType == "tekli_regex_lb_sh_3"){
            val name = chContent.chName
            val url = chContent.chUrl
            val posterUrl = chContent.chImg
            val headers = chContent.chHeaders
            val reg = chContent.chReg
            val type = chContent.chType

            val jsonResponse = runCatching { makeInatRequest(url) }.getOrNull() ?: getJsonFromEncryptedInatResponse(app.get(url).text) ?: return
            val firstItem = JSONObject(jsonResponse)
            firstItem.put("chHeaders", headers)
            firstItem.put("chReg", reg)
            firstItem.put("chName",name)
            firstItem.put("chImg",posterUrl)
            firstItem.put("chType",type)
            contentToProcess = parseToChContent(firstItem)
        } else{
            contentToProcess = chContent
        }

        val sourceUrl = resolveInatUrl(contentToProcess.chUrl, playHost = true)

        val headers: MutableMap<String, String> = mutableMapOf()
        try {
            val chHeaders = contentToProcess.chHeaders
            val chReg = contentToProcess.chReg
            if (chHeaders != "null") {
                val jsonHeaders = JSONArray(chHeaders).getJSONObject(0)
                for (entry in jsonHeaders.keys()) {
                    headers[entry] = jsonHeaders[entry].toString()
                }
            }
            if (chReg != "null") {
                val jsonReg = JSONArray(chReg).getJSONObject(0)
                val cookie = jsonReg.getString("playSH2")
                headers["Cookie"] = cookie
            }
        } catch (_: Exception) {

        }

        val extractorFound =
            loadExtractor(sourceUrl, headers["Referer"], subtitleCallback){
                callback.invoke(
                    ExtractorLink(source = it.source,name = contentToProcess.chName, url = it.url, referer = it.referer, quality = it.quality, headers = it.headers, type = it.type)
                )
            }

        //When no extractor found, try to load as generic
        if (!extractorFound) {
            callback.invoke(
                ExtractorLink(
                    source = this.name,
                    name = contentToProcess.chName,
                    url = sourceUrl,
                    referer = "",
                    quality = Qualities.Unknown.value,
                    headers = headers,
                    type = if(sourceUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else if(sourceUrl.contains(".mpd")) ExtractorLinkType.DASH else ExtractorLinkType.VIDEO
                )
            )
        }
    }

    private suspend fun makeInatRequest(url: String): String? {
        val resolvedUrl = resolveInatUrl(url, playHost = false)

        val hostName = try {
            URI(resolvedUrl).host ?: throw IllegalArgumentException("Invalid URL: $resolvedUrl")
        } catch (e: Exception) {
            Log.e("InatBox", "Failed to extract hostname from URL: $resolvedUrl", e)
            return null
        }

        val config = getInatRemoteConfig()
        val isDiziHost = url.contains("dizibox.rest", ignoreCase = true)

        // v15 keeps separate request settings for the normal content host and
        // the disk/sports host. Use the normal inat* keys for dizibox-style
        // endpoints and inat_disk_* for the box/disk endpoints.
        val referer = if (isDiziHost) {
            config?.optString("inatRF").orEmpty()
        } else {
            config?.optString("inat_disk_ref").orEmpty()
        }.ifBlank { "https://speedrestapi.com/" }

        val userAgent = if (isDiziHost) {
            config?.optString("inatUA").orEmpty()
        } else {
            config?.optString("inat_disk_ua").orEmpty()
        }.ifBlank { "speedrestapi" }

        val xRequestedWith = if (isDiziHost) {
            config?.optString("inatXRW").orEmpty()
        } else {
            config?.optString("inat_disk_xrw").orEmpty()
        }.ifBlank { "com.bp.box" }

        val headers = mutableMapOf(
            "Cache-Control" to "no-cache",
            "Content-Length" to "37",
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
            "Host" to hostName,
            "Referer" to referer,
            "X-Requested-With" to xRequestedWith
        )

        val requestBody = "1=${aesKey}&0=${aesKey}"

        val interceptor = Interceptor { chain ->
            val request = chain.request()
            val newRequest = request.newBuilder().header("User-Agent", userAgent).build()
            chain.proceed(newRequest)
        }

        return try {
            Log.d("InatBox", "Inat request: $resolvedUrl")
            val response = app.post(
                url = resolvedUrl,
                headers = headers,
                requestBody = requestBody.toRequestBody(
                    contentType = "application/x-www-form-urlencoded; charset=UTF-8".toMediaType()
                ),
                interceptor = interceptor
            )

            if (response.isSuccessful) {
                val source = response.body?.source()
                val encryptedResponse = source?.readByteArray()
                val encryptedResponseString = encryptedResponse?.let { String(it, Charsets.UTF_8) }
                encryptedResponseString?.let { getJsonFromEncryptedInatResponse(it) }
            } else {
                Log.e("InatBox", "Request failed: HTTP ${response.code} URL=$resolvedUrl")
                null
            }
        } catch (e: Exception) {
            Log.e("InatBox", "Request error: ${e.message} URL=$resolvedUrl", e)
            null
        }
    }

    private suspend fun resolveInatUrl(originalUrl: String, playHost: Boolean): String {
        val config = getInatRemoteConfig() ?: return originalUrl

        val isBoxHost = originalUrl.contains("boxbc.sbs", ignoreCase = true)
        val isDiziHost = originalUrl.contains("dizibox.rest", ignoreCase = true)
        if (!isBoxHost && !isDiziHost) return originalUrl

        val keys = when {
            isBoxHost && playHost -> listOf("inat_disk_play_host", "inat_disk_host")
            isBoxHost -> listOf("inat_disk_host")
            else -> listOf("inatHost", "inat2Host")
        }

        val configuredHost = keys.asSequence()
            .map { config.optString(it).trim() }
            .firstOrNull { it.isNotBlank() }

        if (configuredHost.isNullOrBlank()) {
            Log.w("InatBox", "Remote Config did not contain ${keys.joinToString("/")}; using old URL")
            return originalUrl
        }

        val normalizedHost = configuredHost
            .removePrefix("https://")
            .removePrefix("http://")
            .trimEnd('/')

        val resolved = originalUrl.replace(
            Regex("^https?://[^/]+", RegexOption.IGNORE_CASE),
            "https://$normalizedHost"
        )

        Log.d("InatBox", "Resolved Inat URL: $originalUrl -> $resolved")
        return resolved
    }

    private suspend fun getInatRemoteConfig(): JSONObject? {
        inatRemoteConfig?.let { return it }

        return try {
            val context = CloudStreamApp.context
            if (context == null) {
                Log.e("InatBox", "CloudStream application context is unavailable")
                return null
            }

            // v15 uses the Firebase Android SDK itself. Reusing the SDK is important:
            // it creates the Installation ID/token and sends the exact Remote Config
            // request format expected by Firebase. The previous hand-written REST
            // request was rejected with HTTP 400/429.
            val firebaseAppClass = Class.forName("com.google.firebase.FirebaseApp")
            val firebaseOptionsBuilderClass = Class.forName("com.google.firebase.FirebaseOptions\$Builder")
            val optionsBuilder = firebaseOptionsBuilderClass.getDeclaredConstructor().newInstance()

            firebaseOptionsBuilderClass.getMethod("setApplicationId", String::class.java)
                .invoke(optionsBuilder, firebaseAppId)
            firebaseOptionsBuilderClass.getMethod("setApiKey", String::class.java)
                .invoke(optionsBuilder, firebaseApiKey)
            firebaseOptionsBuilderClass.getMethod("setProjectId", String::class.java)
                .invoke(optionsBuilder, firebaseProjectId)
            firebaseOptionsBuilderClass.getMethod("setGcmSenderId", String::class.java)
                .invoke(optionsBuilder, "845468888832")

            val options = firebaseOptionsBuilderClass.getMethod("build").invoke(optionsBuilder)

            val appName = "inatbox-v15"
            val getApps = firebaseAppClass.getMethod("getApps", Context::class.java)
            val apps = getApps.invoke(null, context) as? List<*>
            var appInstance = apps?.firstOrNull {
                runCatching {
                    firebaseAppClass.getMethod("getName").invoke(it) == appName
                }.getOrDefault(false)
            }

            if (appInstance == null) {
                appInstance = firebaseAppClass.getMethod(
                    "initializeApp",
                    Context::class.java,
                    Class.forName("com.google.firebase.FirebaseOptions"),
                    String::class.java
                ).invoke(null, context, options, appName)
            }
            firebaseApp = appInstance

            val remoteConfigClass = Class.forName("com.google.firebase.remoteconfig.FirebaseRemoteConfig")
            val getInstance = remoteConfigClass.getMethod(
                "getInstance",
                firebaseAppClass
            )
            val remoteConfig = getInstance.invoke(null, appInstance)

            // Fetch once and let Firebase's own throttling/cache rules apply.
            val fetchAndActivateTask = remoteConfigClass.getMethod("fetchAndActivate").invoke(remoteConfig)
            val tasksClass = Class.forName("com.google.android.gms.tasks.Tasks")
            tasksClass.getMethod("await", Class.forName("com.google.android.gms.tasks.Task"))
                .invoke(null, fetchAndActivateTask)

            val allValues = remoteConfigClass.getMethod("getAll").invoke(remoteConfig) as? Map<*, *>
            val result = JSONObject()

            allValues?.forEach { (key, value) ->
                if (key == null || value == null) return@forEach
                val stringValue = runCatching {
                    value.javaClass.getMethod("asString").invoke(value)?.toString()
                }.getOrNull()
                if (!stringValue.isNullOrBlank()) {
                    result.put(key.toString(), stringValue)
                }
            }

            if (result.length() == 0) {
                Log.e("InatBox", "Firebase SDK returned no Remote Config values")
                return null
            }

            inatRemoteConfig = result
            Log.d("InatBox", "Remote Config loaded via Firebase SDK: ${result.keys().asSequence().toList()}")
            Log.d("InatBox", "inatHost=${result.optString("inatHost")} inat2Host=${result.optString("inat2Host")}")
            Log.d("InatBox", "inat_disk_host=${result.optString("inat_disk_host")} inat_disk_play_host=${result.optString("inat_disk_play_host")}")
            result
        } catch (e: Throwable) {
            val cause = e.cause ?: e
            Log.e("InatBox", "Firebase SDK Remote Config failed: ${cause.message}", cause)
            null
        }
    }

    private fun getJsonFromEncryptedInatResponse(response: String): String? {
        try {
            val algorithm = "AES/CBC/PKCS5Padding"
            val keySpec = SecretKeySpec(aesKey.toByteArray(), "AES")

            // First decryption iteration
            val cipher1 = Cipher.getInstance(algorithm)
            cipher1.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(aesKey.toByteArray()))
            val firstIterationData =
                cipher1.doFinal(Base64.decode(response.split(":")[0], Base64.DEFAULT))

            // Second decryption iteration
            val cipher2 = Cipher.getInstance(algorithm)
            cipher2.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(aesKey.toByteArray()))
            val secondIterationData = cipher2.doFinal(
                Base64.decode(
                    String(firstIterationData).split(":")[0],
                    Base64.DEFAULT
                )
            )

            // Parse JSON
            val jsonString = String(secondIterationData)
            return jsonString
        } catch (e: Exception) {
            Log.e("InatBox", "Decryption failed: ${e.message}")
            return null
        }
    }

    private fun getSearchResponseList(jsonResponse: String): List<SearchResponse> {
        val searchResults = mutableListOf<SearchResponse>()
        try {
            val jsonArray = JSONArray(jsonResponse)

            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(i)

                if (!inatContentAllowed(item)) {
                    continue
                }

                //Let's pass item directly to the next step
                if (item.has("diziType")) {
                    val name = item.getString("diziName")
                    val type = item.getString("diziType")
                    val posterUrl = item.getString("diziImg")

                    val searchResponse = when (type) {
                        "dizi" -> newTvSeriesSearchResponse(name, item.toString()) {
                            this.posterUrl = posterUrl
                        }

                        "film" -> newMovieSearchResponse(name, item.toString()) {
                            this.posterUrl = posterUrl
                        }

                        else -> null // Ignore unsupported types
                    }
                    searchResponse?.let { searchResults.add(it) }
                } else if (item.has("chName") && item.has("chUrl") && item.has("chImg")) {
                    // Handle the case where diziType is missing but chName, chUrl, and chImg are present
                    val name = item.getString("chName")
                    val posterUrl = item.getString("chImg")
                    val chType = item.getString("chType")

                    val searchResponse = when (chType) {
                        "live_url", "tekli_regex_lb_sh_3" -> newLiveSearchResponse(name, item.toString(), TvType.Live) {
                            this.posterUrl = posterUrl
                        }

                        else -> newMovieSearchResponse(name, item.toString()) {
                            this.posterUrl = posterUrl
                        }
                    }
                    searchResults.add(searchResponse)
                }
            }
        } catch (e: Exception) {
            Log.e("InatBox", "Failed to parse JSON response: ${e.message}")
        }

        return searchResults
    }
}
