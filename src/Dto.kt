package eu.kanade.tachiyomi.extension.all.bookoasis

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class SeriesListResponse(
    val success: Boolean = false,
    val series: List<SeriesDto> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
)

@Serializable
class SeriesDto(
    @SerialName("series_key") val seriesKey: String = "",
    @SerialName("series_name") val seriesName: String = "",
    @SerialName("series_alias") val seriesAlias: String = "",
    @SerialName("display_name") val displayName: String = "",
    @SerialName("representative_title") val representativeTitle: String = "",
    val author: String = "",
    @SerialName("book_count") val bookCount: Int = 0,
    @SerialName("cover_image") val coverImage: String = "",
    @SerialName("library_id") val libraryId: Int = 0,
    val genre: String = "",
    val tags: String = "",
    @SerialName("is_completed") val isCompleted: Int = 0,
)

@Serializable
class DetailResponse(
    val success: Boolean = false,
    val meta: SeriesMetaDto = SeriesMetaDto(),
    val books: List<BookDto> = emptyList(),
)

@Serializable
class SeriesMetaDto(
    @SerialName("series_name") val seriesName: String = "",
    @SerialName("series_alias") val seriesAlias: String = "",
    val author: String = "",
    val summary: String = "",
    @SerialName("cover_image") val coverImage: String = "",
    val genre: String = "",
    val tags: String = "",
    @SerialName("is_completed") val isCompleted: Int = 0,
    @SerialName("library_id") val libraryId: Int = 0,
    @SerialName("book_count") val bookCount: Int = 0,
)

@Serializable
class BookDto(
    val id: Int = 0,
    val title: String = "",
    @SerialName("file_format") val fileFormat: String = "",
    @SerialName("file_size") val fileSize: Long = 0L,
    @SerialName("cover_image") val coverImage: String = "",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("is_completed") val isCompleted: Int = 0,
)

@Serializable
class BookInfoResponse(
    val success: Boolean = false,
    @SerialName("total_pages") val totalPages: Int = 0,
)
