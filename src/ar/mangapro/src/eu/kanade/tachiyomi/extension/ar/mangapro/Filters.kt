package eu.kanade.tachiyomi.extension.ar.procomic

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

fun getFilterList() = FilterList(
    Filter.Header("استخدم الفلاتر لتضييق نطاق البحث"),
    GenreFilter(),
    StatusFilter(),
    TypeFilter(),
    OrderByFilter()
)

class Genre(name: String, val id: String) : Filter.CheckBox(name)
class GenreFilter : Filter.Group<Genre>("الأنواع", listOf(
    Genre("أكشن", "action"),
    Genre("مغامرة", "adventure"),
    Genre("كوميدي", "comedy"),
    Genre("دراما", "drama"),
    Genre("خيال", "fantasy"),
    Genre("إيسيكاي", "isekai"),
    Genre("مانهوا", "manhua"),
    Genre("مانجا", "manga"),
    Genre("رومانسية", "romance"),
    Genre("قوى خارقة", "supernatural")
))

class StatusFilter : Filter.Select<String>("الحالة", arrayOf("الكل", "مستمر", "مكتمل", "متوقف"))

class TypeFilter : Filter.Select<String>("النوع", arrayOf("الكل", "مانهوا (صينية)", "مانهوا (كورية)", "مانجا"))

class OrderByFilter : Filter.Select<String>("ترتيب حسب", arrayOf("الأحدث", "الأكثر مشاهدة", "الاسم", "التقييم"))
