package eu.kanade.tachiyomi.extension.ar.mangapro

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.CheckBoxPreference
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import okhttp3.OkHttpClient
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

class ProComic : Madara("ProComic", "https://procomic.pro", "ar"), ConfigurableSource {

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // تحميل الرابط من الإعدادات
    override val baseUrl: String
        get() = preferences.getString(BASE_URL_PREF, "https://procomic.pro") ?: "https://procomic.pro"

    // إعدادات العميل مع Cloudflare و Headers مخصصة
    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val originalRequest = chain.request()
            val newRequest = originalRequest.newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "ar-AR,ar;q=0.9")
                .build()
            chain.proceed(newRequest)
        }
        .build()

    // تصفية الفصول بناءً على الإعدادات
    override fun chapterListParse(response: okhttp3.Response): List<eu.kanade.tachiyomi.source.model.SChapter> {
        val chapters = super.chapterListParse(response)
        val filterMode = preferences.getString(FILTER_MODE_PREF, "show_all") ?: "show_all"

        return when (filterMode) {
            "show_all" -> chapters
            
            "hide_paid" -> chapters.filter { chapter ->
                !chapter.name.contains(Regex("مدفوع|premium|restricted", RegexOption.IGNORE_CASE))
            }
            
            "hide_adult" -> chapters.filter { chapter ->
                !chapter.name.contains(Regex("للبالغين|\\+18|adult", RegexOption.IGNORE_CASE))
            }
            
            "show_paid_only" -> chapters.filter { chapter ->
                chapter.name.contains(Regex("مدفوع|premium", RegexOption.IGNORE_CASE))
            }
            
            "show_adult_only" -> chapters.filter { chapter ->
                chapter.name.contains(Regex("للبالغين|\\+18|adult", RegexOption.IGNORE_CASE))
            }
            
            "custom_filter" -> {
                val customKeywords = preferences.getString(CUSTOM_KEYWORDS_PREF, "") ?: ""
                if (customKeywords.isEmpty()) {
                    chapters
                } else {
                    val keywords = customKeywords.split("|").map { it.trim() }
                    val hideMode = preferences.getString(CUSTOM_MODE_PREF, "hide") ?: "hide"
                    
                    chapters.filter { chapter ->
                        val matches = keywords.any { keyword ->
                            chapter.name.contains(keyword, ignoreCase = true)
                        }
                        if (hideMode == "hide") !matches else matches
                    }
                }
            }
            
            else -> chapters
        }
    }

    // إعداد واجهة المستخدم للتفضيلات
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        
        // === قسم تغيير الرابط ===
        EditTextPreference(screen.context).apply {
            key = BASE_URL_PREF
            title = "🔗 رابط الموقع"
            summary = "غير الرابط إذا تم حجب الرابط الأساسي"
            setDefaultValue("https://procomic.pro")
            dialogTitle = "أدخل رابط الموقع الجديد"

            setOnPreferenceChangeListener { _, newValue ->
                val url = newValue.toString().trim()
                val validUrl = if (!url.startsWith("http")) "https://$url" else url
                preferences.edit().putString(BASE_URL_PREF, validUrl).apply()
                true
            }
        }.also(screen::addPreference)

        // === قسم إخفاء/إظهار الفصول ===
        ListPreference(screen.context).apply {
            key = FILTER_MODE_PREF
            title = "⚙️ إدارة الفصول"
            summary = "اختر كيفية عرض الفصول"
            setDefaultValue("show_all")
            
            entries = arrayOf(
                "عرض جميع الفصول",
                "إخفاء الفصول المدفوعة",
                "إخفاء محتوى البالغين",
                "عرض الفصول المدفوعة فقط",
                "عرض محتوى البالغين فقط",
                "تصفية مخصصة"
            )
            
            entryValues = arrayOf(
                "show_all",
                "hide_paid",
                "hide_adult",
                "show_paid_only",
                "show_adult_only",
                "custom_filter"
            )
        }.also(screen::addPreference)

        // === قسم التصفية المخصصة ===
        EditTextPreference(screen.context).apply {
            key = CUSTOM_KEYWORDS_PREF
            title = "🔍 كلمات مخصصة للتصفية"
            summary = "أدخل الكلمات المراد تصفيتها (مفصولة بـ |)"
            dialogTitle = "أمثلة: مدفوع | restricted | special"
            
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(CUSTOM_KEYWORDS_PREF, newValue.toString()).apply()
                true
            }
        }.also(screen::addPreference)

        // وضع التصفية المخصصة
        ListPreference(screen.context).apply {
            key = CUSTOM_MODE_PREF
            title = "وضع التصفية المخصصة"
            summary = "إخفاء أم إظهار الكلمات المخصصة فقط"
            setDefaultValue("hide")
            
            entries = arrayOf(
                "إخفاء الفصول التي تحتوي على الكلمات",
                "إظهار الفصول التي تحتوي على الكلمات فقط"
            )
            
            entryValues = arrayOf(
                "hide",
                "show"
            )
        }.also(screen::addPreference)

        // === خيارات إضافية ===
        CheckBoxPreference(screen.context).apply {
            key = REMOVE_BRACKETS_PREF
            title = "🧹 إزالة الأقواس من أسماء الفصول"
            summary = "إزالة [الكلمات] من اسم الفصل"
            setDefaultValue(false)

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(REMOVE_BRACKETS_PREF, newValue as Boolean).apply()
                true
            }
        }.also(screen::addPreference)

        CheckBoxPreference(screen.context).apply {
            key = CASE_SENSITIVE_PREF
            title = "🔤 حساس لحالة الأحرف"
            summary = "جعل التصفية حساسة لحالة الأحرف"
            setDefaultValue(false)

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(CASE_SENSITIVE_PREF, newValue as Boolean).apply()
                true
            }
        }.also(screen::addPreference)
    }

    // قائمة التصفيات الافتراضية
    override fun getFilterList(): FilterList = super.getFilterList()

    companion object {
        private const val BASE_URL_PREF = "base_url_v1"
        private const val FILTER_MODE_PREF = "filter_mode_v1"
        private const val CUSTOM_KEYWORDS_PREF = "custom_keywords_v1"
        private const val CUSTOM_MODE_PREF = "custom_mode_v1"
        private const val REMOVE_BRACKETS_PREF = "remove_brackets_v1"
        private const val CASE_SENSITIVE_PREF = "case_sensitive_v1"
    }
}
