package eu.kanade.presentation.reader.components

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PageScrubCommitPolicyTest {

    @Test
    fun `text reader commits only the final selected page`() {
        val committedPages = mutableListOf<Int>()
        val policy = PageScrubCommitPolicy(deferPageChangesUntilFinished = true, committedPages::add)

        policy.onValueChange(18)
        policy.onValueChange(42)
        policy.onValueChange(73)
        assertEquals(emptyList<Int>(), committedPages)

        policy.onValueChangeFinished(73)
        assertEquals(listOf(73), committedPages)
    }

    @Test
    fun `continuous readers still commit every slider change`() {
        val committedPages = mutableListOf<Int>()
        val policy = PageScrubCommitPolicy(deferPageChangesUntilFinished = false, committedPages::add)

        policy.onValueChange(18)
        policy.onValueChange(42)
        policy.onValueChange(73)
        policy.onValueChangeFinished(73)

        assertEquals(listOf(18, 42, 73), committedPages)
    }

    @Test
    fun `cancelled scrub cannot commit a stale page`() {
        val committedPages = mutableListOf<Int>()
        val policy = PageScrubCommitPolicy(deferPageChangesUntilFinished = true, committedPages::add)

        policy.cancel()
        policy.onValueChange(42)
        policy.onValueChangeFinished(73)

        assertEquals(emptyList<Int>(), committedPages)
    }
}
