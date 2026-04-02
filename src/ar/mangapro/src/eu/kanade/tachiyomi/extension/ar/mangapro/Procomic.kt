package eu.kanade.tachiyomi.extension.ar.mangapro

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.lib.cookieinterceptor.CookieInterceptor
import keiyoushi.utils.extractNextJs
import keiyoushi.utils.extractNextJsRsc
import keiyoushi.utils.firstInstance
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
import java.lang.UnsupportedOperationException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64

class Procomic : Madara("ProComic", "https://procomic.pro", "ar"), ConfigurableSource {

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override val baseUrl: String
        get() = preferences.getString(BASE_URL_PREF, "https://procomic.pro")!!

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/123.0.0.0")
                .header("Referer", baseUrl)
                .build()
            chain.proceed(request)
        }
        .build()

    override fun chapterListParse(response: okhttp3.Response): List<SChapter> {
        val chapters = super.chapterListParse(response)
        val showPaid = preferences.getBoolean(SHOW_PAID_PREF, true)

        return if (showPaid) {
            chapters
        } else {
            chapters.filter { chapter ->
                val name = chapter.name.lowercase()
                !name.contains("مدفوع") && 
                !name.contains("premium") && 
                !name.contains("vip")
            }
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = BASE_URL_PREF
            title = "Base URL"
            summary = baseUrl
            defaultValue = "https://procomic.pro"
            dialogTitle = "Edit Base URL"

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(BASE_URL_PREF, newValue as String).commit()
            }
        }.also(screen::addPreference)

        CheckBoxPreference(screen.context).apply {
            key = SHOW_PAID_PREF
            title = "Show Paid Chapters"
            summary = "Show or hide chapters marked as paid/premium"
            defaultValue = true

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(SHOW_PAID_PREF, newValue as Boolean).commit()
            }
        }.also(screen::addPreference)
    }

    override fun getFilterList(): FilterList = getFilterList()

    companion object {
        private const val BASE_URL_PREF = "base_url_v1"
        private const val SHOW_PAID_PREF = "show_paid_chapters_v1"
    }
