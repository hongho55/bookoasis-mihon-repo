package eu.kanade.presentation.reader.components

/** Keeps intermediate slider values local when a reader commits page changes only on release. */
internal class PageScrubCommitPolicy(
    private val deferPageChangesUntilFinished: Boolean,
    private val onPageIndexChange: (Int) -> Unit,
) {
    private var isActive = true

    fun onValueChange(pageIndex: Int) {
        if (isActive && !deferPageChangesUntilFinished) onPageIndexChange(pageIndex)
    }

    fun onValueChangeFinished(pageIndex: Int) {
        if (isActive && deferPageChangesUntilFinished) onPageIndexChange(pageIndex)
    }

    fun cancel() {
        isActive = false
    }
}
