package eu.kanade.tachiyomi.extension.ar.prochan

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.lib.cookieinterceptor.CookieInterceptor
import keiyoushi.utils.extractNextJs
import keiyoushi.utils.firstInstance
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonString
import keiyoushi.utils.tryParse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.internal.closeQuietly
import okio.Buffer
import rx.Observable
import tachiyomi.decoder.ImageDecoder
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
class ProChan :
    HttpSource(),
    ConfigurableSource {
    override val name = "ProChan"
    override val lang = "ar"
    private val preferences: SharedPreferences by getPreferencesLazy()
    private val domain get() = baseUrl.substringAfter("//")
    override val baseUrl by lazy { preferences.getString(DOMAIN_PREF, DEFAULT_DOMAIN)!! }
    override val supportsLatest = true
    override val versionId = 8

    override val client = network.cloudflareClient.newBuilder()
        .addInterceptor(
            CookieInterceptor(
                domain,
                listOf(
                    "safe_browsing" to "off",
                    "language" to "ar",
                ),
            ),
        )
        .addInterceptor(::scrambledImageInterceptor)
        .rateLimit(1)
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("Origin", baseUrl)
        .set("Accept-Language", "ar-EG,ar;q=0.9,en-US;q=0.8,en;q=0.7")
        .set("Sec-CH-UA", "\"Not(A:Brand\";v=\"99\", \"Google Chrome\";v=\"133\", \"Chromium\";v=\"133\"")
        .set("Sec-CH-UA-Mobile", "?1")
        .set("Sec-CH-UA-Platform", "\"Android\"")
        .set("User-Agent", DEFAULT_USER_AGENT)
        .set("X-Requested-With", "XMLHttpRequest")

    private fun htmlHeaders() = headersBuilder()
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
        .set("Sec-Fetch-Dest", "document")
        .set("Sec-Fetch-Mode", "navigate")
        .set("Sec-Fetch-Site", "same-origin")
        .set("Sec-Fetch-User", "?1")
        .set("Upgrade-Insecure-Requests", "1")
        .build()

    private fun apiHeaders() = headersBuilder()
        .set("Accept", "application/json, text/plain, */*")
        .set("Sec-Fetch-Dest", "empty")
        .set("Sec-Fetch-Mode", "cors")
        .set("Sec-Fetch-Site", "same-origin")
        .build()

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/series?page=$page&sort=popular", htmlHeaders())

    override fun popularMangaParse(response: Response): MangasPage = parseMangaList(response)

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/series?page=$page&sort=latest_chapter", htmlHeaders())

    override fun latestUpdatesParse(response: Response): MangasPage = parseMangaList(response)

    private fun parseMangaList(response: Response): MangasPage {
        val document = response.asJsoup()
        val data = document.extractNextJs<MetaData<BrowseManga>>()
            ?: throw Exception("لم يتم العثور على البيانات. حاول حل الكابتشا في المتصفح.")

        val mangas = data.data.filter { it.type in SUPPORTED_TYPES }.map { manga ->
            SManga.create().apply {
                url = "/series/${manga.type}/${manga.id}/${manga.slug}"
                title = manga.title
                thumbnail_url = (manga.coverImageApp?.desktop ?: manga.coverImage)?.let {
                    if (it.startsWith("/")) {
                        manga.cdn?.let { cdn ->
                            "https://$cdn.$domain$it"
                        }
                    } else {
                        it
                    }
                }
            }
        }

        return MangasPage(mangas, data.meta?.hasNextPage() ?: false)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isNotBlank()) {
            return GET("$baseUrl/series?search=$query&page=$page", htmlHeaders())
        }

        val url = "$baseUrl/series".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
            filters.firstInstance<TypeFilter>().selected?.also { addQueryParameter("type", it) }
            addQueryParameter("sort", filters.firstInstance<SortFilter>().selected)
            filters.firstInstance<YearFilter>().selected?.also { addQueryParameter("year", it) }
        }.build()

        return GET(url, htmlHeaders())
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val statusFilter = lastFilters?.firstInstance<StatusFilter>()?.selected
        val genreFilter = lastFilters?.firstInstance<GenreFilter>()
        val tagFilter = lastFilters?.firstInstance<TagFilter>()

        val document = response.asJsoup()
        val data = document.extractNextJs<MetaData<BrowseManga>>()
            ?: throw Exception("لم يتم العثور على البيانات. حاول حل الكابتشا في المتصفح.")

        val mangas = data.data.asSequence()
            .filter { it.type in SUPPORTED_TYPES }
            .filter { statusFilter == null || it.progress == statusFilter }
            .filter {
                genreFilter == null || genreFilter.included.isEmpty() ||
                    it.metadata.genres.containsAll(genreFilter.included)
            }
            .filter {
                genreFilter == null || genreFilter.excluded.none { g -> g in it.metadata.genres }
            }
            .filter {
                tagFilter == null || tagFilter.included.isEmpty() ||
                    it.metadata.tags.containsAll(tagFilter.included)
            }
            .filter {
                tagFilter == null || tagFilter.excluded.none { t -> t in it.metadata.tags }
            }
            .map { manga ->
                SManga.create().apply {
                    url = "/series/${manga.type}/${manga.id}/${manga.slug}"
                    title = manga.title
                    thumbnail_url = (manga.coverImageApp?.desktop ?: manga.coverImage)?.let {
                        if (it.startsWith("/")) {
                            manga.cdn?.let { cdn ->
                                "https://$cdn.$domain$it"
                            }
                        } else {
                            it
                        }
                    }
                }
            }
            .toList()

        return MangasPage(mangas, data.meta?.hasNextPage() ?: false)
    }

    private var lastFilters: FilterList? = null

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        if (query.startsWith("https://")) {
            val url = query.toHttpUrl()
            val path = url.pathSegments
            if ((url.host == domain || url.host == "prochan.pro" || url.host == "prochan.net") && path.size >= 4 && path[0] == "series") {
                val type = path[1]
                val mangaId = path[2]
                val slug = path[3]

                val manga = SManga.create().apply {
                    this@apply.url = "/series/$type/$mangaId/$slug"
                }

                return fetchMangaDetails(manga).map {
                    MangasPage(listOf(it), false)
                }
            }
        }
        lastFilters = filters
        return super.fetchSearchManga(page, query, filters)
    }

    override fun getFilterList() = FilterList(
        TypeFilter(),
        SortFilter(),
        YearFilter(),
        StatusFilter(),
        GenreFilter(),
        TagFilter(),
    )

    override fun mangaDetailsRequest(manga: SManga): Request = GET(getMangaUrl(manga), htmlHeaders())

    override fun getMangaUrl(manga: SManga): String = "$baseUrl${manga.url}"

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        val seriesData = document.extractNextJs<Series>()
            ?: throw Exception("لم يتم العثور على بيانات العمل. حاول حل الكابتشا في المتصفح.")
        val manga = seriesData.series

        return SManga.create().apply {
            url = "/series/${manga.type}/${manga.id}/${manga.slug}"
            title = manga.title
            artist = manga.metadata.artist.joinToString()
            author = manga.metadata.author.joinToString()
            description = buildString {
                manga.description?.also { append(it.trim()).append("\n\n") }
                val altTitles = buildList {
                    addAll(manga.metadata.altTitles)
                    manga.metadata.originalTitle?.also { add(it) }
                }
                if (altTitles.isNotEmpty()) {
                    append("عناوين بديلة\n")
                    altTitles.forEach { title ->
                        append("- ").append(title).append("\n")
                    }
                    append("\n")
                }
            }.trim()
            genre = buildList<String> {
                add(manga.type.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() })
                manga.metadata.year?.also { add(it) }
                manga.metadata.origin?.also { origin ->
                    add(origin.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() })
                }
                when (manga.type) {
                    "manga" -> add("مانجا")
                    "manhwa" -> add("مانهوا")
                    "manhua" -> add("مانها")
                }
                if (manga.metadata.genres.isNotEmpty()) {
                    val genreMap = genres.associate { it.second to it.first }
                    manga.metadata.genres.forEach {
                        add(genreMap[it] ?: it)
                    }
                }
                if (manga.metadata.tags.isNotEmpty()) {
                    val tagsMap = tags.associate { it.second to it.first }
                    manga.metadata.tags.forEach {
                        add(tagsMap[it] ?: it)
                    }
                }
            }.joinToString()
            status = when (manga.progress?.trim()) {
                "مستمر" -> SManga.ONGOING
                "مكتمل" -> SManga.COMPLETED
                "متوقف" -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }
            thumbnail_url = (manga.coverImageApp?.desktop ?: manga.metadata.coverImage)?.let {
                if (it.startsWith("/")) {
                    manga.cdn?.let { cdn ->
                        "https://$cdn.$domain$it"
                    }
                } else {
                    it
                }
            }
            initialized = true
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val type = response.request.url.pathSegments[1]
        val id = response.request.url.pathSegments[2]
        val slug = response.request.url.pathSegments[3]

        // Try API first as it's more reliable for full lists
        val apiRequest = GET("$baseUrl/api/public/$type/$id/chapters?page=1&limit=500&order=desc", apiHeaders())
        val apiResponse = client.newCall(apiRequest).execute()
        if (apiResponse.isSuccessful && apiResponse.header("Content-Type")?.contains("application/json") == true) {
            val chapters = apiResponse.parseAs<Data<List<Chapter>>>().data
            if (!chapters.isNullOrEmpty()) {
                countViews(id)
                return chapters.filter { it.language == "AR" }.map { it.toSChapter(type, id, slug) }
            }
        }

        // Fallback to HTML extraction
        val document = response.asJsoup()
        val data = document.extractNextJs<InitialChapters>()
            ?: throw Exception("لم يتم العثور على قائمة الفصول. حاول حل الكابتشا في المتصفح.")

        countViews(id)

        return data.initialChapters
            .filter { it.language == "AR" }
            .map { it.toSChapter(type, id, slug) }
            .sortedByDescending { it.chapter_number }
    }

    override fun chapterListRequest(manga: SManga): Request = GET(getMangaUrl(manga), htmlHeaders())

    private fun Chapter.toSChapter(type: String, seriesId: String, slug: String): SChapter = SChapter.create().apply {
        url = "/series/$type/$seriesId/$slug/${this@toSChapter.id}/${this@toSChapter.number}"
        name = buildString {
            append("\u200F") // rtl marker
            if (coins != null && coins > 0) append("🔒 ")
            append("الفصل ")
            append(number.toFloat().toString().substringBefore(".0"))
            title?.trim()?.takeIf { it.isNotBlank() }?.let { trimmedTitle ->
                if (trimmedTitle != number.trim() && trimmedTitle != number) {
                    append(" \u200F- ")
                    append(trimmedTitle)
                }
            }
        }
        scanlator = uploader ?: "\u200B"
        chapter_number = number.toFloat()
        date_upload = dateFormat.tryParse(createdAt)
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)

    override fun pageListRequest(chapter: SChapter): Request = GET(getChapterUrl(chapter), htmlHeaders())

    override fun getChapterUrl(chapter: SChapter): String {
        val url = if (chapter.url.startsWith("{")) {
            chapter.url.parseAs<ChapterUrl>().url
        } else {
            chapter.url
        }

        return "$baseUrl$url"
    }

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val imageData = document.extractNextJs<Images>()
        if (imageData == null) {
            val coins = document.extractNextJs<Coins>()?.coins
            if (coins != null && coins > 0) {
                throw Exception("فصل مدفوع")
            } else {
                return emptyList()
            }
        }

        val seriesId = response.request.url.pathSegments[2]
        val chapterId = response.request.url.pathSegments[4]

        val images = imageData.images.toMutableList()
        val maps = mutableListOf<ScrambledData>()

        if (imageData.deferredMedia != null) {
            val deferredUrl = baseUrl.toHttpUrl().newBuilder()
                .addPathSegment("chapter-deferred-media")
                .addPathSegment(chapterId)
                .addQueryParameter("token", imageData.deferredMedia.token)
                .build()

            val deferredResponse = client.newCall(GET(deferredUrl, apiHeaders())).execute()
            if (deferredResponse.isSuccessful) {
                val deferredData = deferredResponse.parseAs<Data<DeferredImages>>()
                deferredData.data?.let {
                    images.addAll(it.images)
                    maps.addAll(it.maps)
                }
            }
        }

        countViews(seriesId, chapterId)

        val chapterUrl = response.request.url.toString()
        val pages = mutableListOf<Page>()

        images.forEachIndexed { index, imageUrl ->
            pages.add(Page(index, chapterUrl, imageUrl))
        }
        maps.forEachIndexed { index, scrambledData ->
            pages.add(Page(pages.size, chapterUrl, "http://$SCRAMBLED_IMAGE_HOST/#${scrambledData.toJsonString()}"))
        }

        return pages
    }

    override fun imageRequest(page: Page): Request {
        val headers = headersBuilder()
            .set("Referer", page.url)
            .build()

        return GET(page.imageUrl!!, headers)
    }

    private fun scrambledImageInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        if (url.host != SCRAMBLED_IMAGE_HOST) {
            return chain.proceed(request)
        }

        val chapterUrl = request.header("Referer")!!
        val cdn = when (chapterUrl.toHttpUrl().pathSegments[1]) {
            "manga" -> "cdn1"
            "manhua" -> "cdn2"
            else -> "cdn3"
        }

        val scrambledImage = when (val scrambledData = url.fragment!!.parseAs<ScrambledData>()) {
            is ScrambledImage -> scrambledData
            is ScrambledImageToken -> decodeScrambledImageToken(scrambledData)
        }

        val puzzleMode = scrambledImage.mode.substringBefore("_")
        val layout = scrambledImage.mode.substringAfter("_", "")

        require(scrambledImage.dim.size >= 2) { "Invalid dim: ${scrambledImage.dim}" }

        val width = scrambledImage.dim[0]
        val height = scrambledImage.dim[1]

        val orderedPieces = scrambledImage.order.map { scrambledImage.pieces[it] }
        val pieceBitmaps = runBlocking {
            orderedPieces.map { pieceUrl ->
                async(Dispatchers.IO.limitedParallelism(2)) {
                    var imgUrl = if (pieceUrl.startsWith("/")) {
                        "https://$cdn.$domain$pieceUrl"
                    } else {
                        pieceUrl
                    }.toHttpUrl()
                    if (imgUrl.host.startsWith("cdn")) {
                        val payload = Url(url = imgUrl.toString()).toJsonString().toRequestBody(JSON_MEDIA_TYPE)
                        val signHeaders = headersBuilder()
                            .set("Sec-Fetch-Site", "same-origin")
                            .set("Referer", chapterUrl)
                            .build()
                        val signRequest = POST("$baseUrl/api/cdn-image/sign", signHeaders, payload)
                        val response = client.newCall(signRequest).await()
                        if (response.isSuccessful) {
                            val token = response.parseAs<Token>()
                            imgUrl = imgUrl.newBuilder()
                                .setQueryParameter("token", token.token)
                                .setQueryParameter("expires", token.expires.toString())
                                .build()
                        } else {
                            response.close()
                        }
                    }
                    val pieceRequest = request.newBuilder().url(imgUrl).build()
                    val response = client.newCall(pieceRequest).await()
                    response.body.use { body ->
                        // use Tachiyomi ImageDecoder because android.graphics.BitmapFactory doesn't handle avif
                        val decoder = ImageDecoder.newInstance(body.byteStream())
                            ?: throw Exception("Failed to create decoder")
                        decoder.decode() ?: throw Exception("Failed to decode piece")
                    }
                }
            }.awaitAll()
        }

        val resultBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(resultBitmap)

        try {
            when (puzzleMode) {
                "vertical" -> {
                    var x = 0f
                    for (bitmap in pieceBitmaps) {
                        canvas.drawBitmap(bitmap, x, 0f, null)
                        x += bitmap.width
                    }
                }
                "grid" -> {
                    val (cols, rows) = layout.split('x', limit = 2).map { it.toInt() }
                    var y = 0f
                    for (r in 0 until rows) {
                        var x = 0f
                        var maxHeightInRow = 0f
                        for (c in 0 until cols) {
                            val index = r * cols + c
                            if (index < pieceBitmaps.size) {
                                val bitmap = pieceBitmaps[index]
                                canvas.drawBitmap(bitmap, x, y, null)
                                x += bitmap.width
                                maxHeightInRow = maxOf(maxHeightInRow, bitmap.height.toFloat())
                            }
                        }
                        y += maxHeightInRow
                    }
                }
                else -> throw IOException("Unknown puzzle mode: $puzzleMode")
            }

            val buffer = Buffer().apply {
                resultBitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream())
            }
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(buffer.asResponseBody("image/jpg".toMediaType(), buffer.size))
                .build()
        } finally {
            pieceBitmaps.forEach { it.recycle() }
            resultBitmap.recycle()
        }
    }

    private val sessionKey = ConcurrentHashMap<Int, Pair<String, Long>>()
    private val sessionKeyLock = Any()

    private fun decodeScrambledImageToken(data: ScrambledImageToken): ScrambledImage {
        val value = String(urlSafeBase64(data.token), Charsets.UTF_8)
            .parseAs<ScrambledImageTokenValue>()

        val iv = urlSafeBase64(value.iv)
        val tag = urlSafeBase64(value.tag)
        val encryptedData = urlSafeBase64(value.data)

        val key = when (value.m) {
            "browser" -> {
                if (value.v == 2) {
                    val hash = MessageDigest.getInstance("SHA-256")
                        .digest(
                            "prochan-browser-map:2e6f9a1c4d8b7e3f0a5c9d2b6e1f4a8c7d3b0e6a9f2c5d8b1e4a7c0d3f6b9e2:${value.cid}"
                                .toByteArray(Charsets.UTF_8),
                        )
                    SecretKeySpec(hash, "AES")
                } else {
                    throw Exception("Unknown version: ${value.v}")
                }
            }
            // Untested, couldn't find a chapter which uses this, possibly for paid chapters?
            "browser_session" -> {
                if (value.v == 3) {
                    synchronized(sessionKeyLock) {
                        val time = System.currentTimeMillis()
                        val key = sessionKey[value.cid]?.takeIf { it.second > time }?.first ?: run {
                            val request = GET("$baseUrl/chapter-map-session-key/${value.cid}", headers)
                            val response = client.newCall(request).execute().parseAs<Data<Key>>()

                            val keyStr = response.data?.key ?: throw Exception("Failed to get session key")
                            sessionKey[value.cid] = keyStr to (time + 120000)

                            keyStr
                        }

                        SecretKeySpec(urlSafeBase64(key), "AES")
                    }
                } else {
                    throw Exception("Unknown version: ${value.v}")
                }
            }
            else -> throw Exception("Unknown method: ${value.m}")
        }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            val spec = GCMParameterSpec(128, iv)

            init(Cipher.DECRYPT_MODE, key, spec)
        }

        val decryptedBytes = cipher.doFinal(encryptedData + tag)
        return String(decryptedBytes, Charsets.UTF_8).parseAs()
    }

    private fun urlSafeBase64(data: String) = Base64.UrlSafe
        .withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)
        .decode(data)

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val domainPref = ListPreference(screen.context).apply {
            key = DOMAIN_PREF
            title = "النطاق (Domain)"
            entries = arrayOf("prochan.net", "prochan.pro")
            entryValues = arrayOf("https://prochan.net", "https://prochan.pro")
            setDefaultValue(DEFAULT_DOMAIN)
            summary = "%s"

            setOnPreferenceChangeListener { _, _ ->
                Toast.makeText(screen.context, "يرجى إعادة تشغيل التطبيق لتطبيق التغييرات", Toast.LENGTH_LONG).show()
                true
            }
        }
        screen.addPreference(domainPref)
    }

    private fun countViews(seriesId: String, chapterId: String? = null) {
        val userAgent = DEFAULT_USER_AGENT
        val payload = ViewsDto(
            chapterId = chapterId?.toIntOrNull(),
            contentId = seriesId.toInt(),
            deviceType = when {
                MOBILE_REGEX.containsMatchIn(userAgent) -> "mobile"
                TABLES_REGEX.containsMatchIn(userAgent) -> "tablet"
                else -> "desktop"
            },
            surface = when {
                chapterId == null -> "series"
                else -> "chapter"
            },
        ).toJsonString().toRequestBody(JSON_MEDIA_TYPE)

        client.newCall(POST("$baseUrl/api/views", headers, payload))
            .enqueue(
                object : Callback {
                    override fun onResponse(call: Call, response: Response) {
                        if (!response.isSuccessful) {
                            Log.e(name, "Failed to count views, HTTP ${response.code}")
                        }
                        response.closeQuietly()
                    }
                    override fun onFailure(call: Call, e: okio.IOException) {
                        Log.e(name, "Failed to count views", e)
                    }
                },
            )
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()
}

private val SUPPORTED_TYPES = setOf("manga", "manhwa", "manhua")
private const val SCRAMBLED_IMAGE_HOST = "127.0.0.1"
private val JSON_MEDIA_TYPE = "application/json".toMediaType()
private val MOBILE_REGEX = Regex("mobile|android|iphone|ipad|ipod", RegexOption.IGNORE_CASE)
private val TABLES_REGEX = Regex("tablet", RegexOption.IGNORE_CASE)

private const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Mobile Safari/537.36"
private const val DOMAIN_PREF = "preferred_domain"
private const val DEFAULT_DOMAIN = "https://prochan.net"
