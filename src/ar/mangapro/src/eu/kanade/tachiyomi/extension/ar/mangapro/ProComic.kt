package eu.kanade.tachiyomi.extension.ar.mangapro

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.CheckBoxPreference
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SChapter
import okhttp3.OkHttpClient
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

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
}
