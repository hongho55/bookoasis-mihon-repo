package eu.kanade.tachiyomi.source.bookoasis

/**
 * Fixed IDs exported by BookOasis extension v1.4.34's BookOasisFactory.
 * Keep these stable so existing manga/chapter/download rows resolve to the same source.
 */
internal object BookOasisLegacyIdentity {
    val sourceIds: List<Long> = listOf(
        8_820_010_100_100_100L,
        8_820_010_100_100_101L,
        8_820_010_100_100_102L,
    )

    internal fun isBookOasisId(sourceId: Long): Boolean = sourceId in sourceIds

    internal fun <T> withExtensionPrecedence(
        seeded: Map<Long, T>,
        extensionSources: Iterable<Pair<Long, T>>,
    ): Map<Long, T> = buildMap {
        putAll(seeded)
        extensionSources.forEach { (id, source) -> put(id, source) }
    }

    fun preferencesName(sourceId: Long): String? =
        sourceId.takeIf(::isBookOasisId)?.let { "source_$it" }
}
