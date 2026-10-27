package eu.kanade.tachiyomi.source.bookoasis

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BookOasisLegacyIdentityTest {

    @Test
    fun `matches all three source ids exported by extension factory`() {
        assertEquals(
            listOf(8_820_010_100_100_100L, 8_820_010_100_100_101L, 8_820_010_100_100_102L),
            BookOasisLegacyIdentity.sourceIds,
        )
    }

    @Test
    fun `extension sources take precedence only for matching ids`() {
        val builtIns = mapOf(1L to "local", 2L to "bookoasis", 3L to "other")
        val extensions = listOf(2L to "extension-bookoasis", 4L to "extension-other")

        assertEquals(
            mapOf(1L to "local", 2L to "extension-bookoasis", 3L to "other", 4L to "extension-other"),
            BookOasisLegacyIdentity.withExtensionPrecedence(builtIns, extensions),
        )
    }

    @Test
    fun `retains the legacy source preference namespace per profile`() {
        assertEquals(
            listOf(
                "source_8820010100100100",
                "source_8820010100100101",
                "source_8820010100100102",
            ),
            BookOasisLegacyIdentity.sourceIds.map(BookOasisLegacyIdentity::preferencesName),
        )
        assertNull(BookOasisLegacyIdentity.preferencesName(123L))
    }
}
