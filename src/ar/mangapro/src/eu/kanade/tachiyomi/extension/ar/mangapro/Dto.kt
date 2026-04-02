package eu.kanade.tachiyomi.extension.ar.procomic

import kotlinx.serialization.Serializable

@Serializable
data class ProComicMangaDto(
    val title: String? = null,
    val url: String? = null,
    val image: String? = null
)

@Serializable
data class ProComicSearchResultDto(
    val data: List<ProComicMangaDto>? = emptyList(),
    val success: Boolean? = false,
    val message: String? = null
)

@Serializable
data class ProComicChapterDto(
    val name: String? = null,
    val url: String? = null,
    val date: String? = null
)
