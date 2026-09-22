package ai.nextbillion.offlinedemo

/**
 * Pure size arithmetic shared by the single-region and batch-download screens.
 *
 * SDK catalog sizes are nullable because a package can be available before its metadata has a
 * byte estimate. Unknown values deliberately remain unknown: treating them as zero would let the
 * UI claim that a download fits on disk when its real size is not yet known.
 */
object DownloadSizeMath {
    /** Absolute headroom retained even for very small packages. */
    const val MINIMUM_STORAGE_RESERVE = 100L * 1024L * 1024L

    /** Returns a combined estimate only when both independently downloaded packages are known. */
    fun sumKnown(first: Long?, second: Long?): Long? {
        if (first == null || second == null) return null
        return saturatedAdd(first.coerceAtLeast(0L), second.coerceAtLeast(0L))
    }

    fun remaining(downloaded: Long?, total: Long?, fallbackTotal: Long?): Long? {
        if (downloaded != null && total != null) {
            return (total - downloaded).coerceAtLeast(0L)
        }
        return fallbackTotal?.coerceAtLeast(0L)
    }

    fun requiredFreeBytes(downloadBytes: Long): Long {
        val normalized = downloadBytes.coerceAtLeast(0L)
        // Keep the larger of 100 MiB and 20% as temporary-file/unpacking headroom. This is an app
        // policy rather than an SDK requirement and should be tuned for the host product.
        val reserve = maxOf(MINIMUM_STORAGE_RESERVE, divideRoundUp(normalized, 5L))
        return saturatedAdd(normalized, reserve)
    }

    /** Saturation prevents a corrupt or future very-large catalog value wrapping negative. */
    fun saturatedAdd(first: Long, second: Long): Long =
        if (first > Long.MAX_VALUE - second) Long.MAX_VALUE else first + second

    fun divideRoundUp(value: Long, divisor: Long): Long {
        require(value >= 0L) { "value must be non-negative" }
        require(divisor > 0L) { "divisor must be positive" }
        return value / divisor + if (value % divisor == 0L) 0L else 1L
    }
}
