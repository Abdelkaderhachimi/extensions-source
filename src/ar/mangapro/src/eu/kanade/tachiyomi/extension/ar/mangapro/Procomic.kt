package eu.kanade.tachiyomi.extension.ar.mangapro

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.CheckBoxPreference
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

class Procomic : Madara("ProComic", "https://procomic.pro", "ar"), ConfigurableSource {

    // الوصول للإعدادات المخزنة
    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // جلب الرابط من الإعدادات أو استخدام الرابط الافتراضي
    override val baseUrl: String
        get() = preferences.getString(BASE_URL_PREF, super.baseUrl)!!

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/123.0.0.0")
                .header("Referer", baseUrl)
                .build()
            chain.proceed(request)
        }
        .build()

    // --- منطق إخفاء/إظهار الفصول المدفوعة ---
    override fun chapterListParse(response: okhttp3.Response): List<eu.kanade.tachiyomi.source.model.SChapter> {
        val chapters = super.chapterListParse(response)
        val showPaid = preferences.getBoolean(SHOW_PAID_PREF, true)

        return if (showPaid) {
            chapters
        } else {
            // تصفية الفصول التي تحتوي على وسوم "مدفوع" أو "قريباً" أو أي علامة تميزها
            chapters.filter { !it.name.contains("مدفوع", ignoreCase = true) && !it.name.contains("Premium", ignoreCase = true) }
        }
    }

    // --- واجهة الإعدادات داخل التطبيق ---
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        // خيار تغيير الرابط
        EditTextPreference(screen.context).apply {
            key = BASE_URL_PREF
            title = "رابط الموقع"
            summary = "قم بتغيير الرابط إذا تغير رابط الموقع الرسمي"
            defaultValue = "https://procomic.pro"
            dialogTitle = "أدخل الرابط الجديد"

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(BASE_URL_PREF, newValue as String).commit()
            }
        }.also(screen::addPreference)

        // خيار إخفاء الفصول المدفوعة
        CheckBoxPreference(screen.context).apply {
            key = SHOW_PAID_PREF
            title = "إظهار الفصول المدفوعة"
            summary = "قم بإلغاء التحديد لإخفاء الفصول التي تتطلب دفعاً أو نقاطاً"
            defaultValue = true

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(SHOW_PAID_PREF, newValue as Boolean).commit()
            }
        }.also(screen::addPreference)
    }

    companion object {
        private const val BASE_URL_PREF = "base_url_v1"
        private const val SHOW_PAID_PREF = "show_paid_chapters"
    }

    override fun getFilterList(): FilterList = getFilterList()
}
