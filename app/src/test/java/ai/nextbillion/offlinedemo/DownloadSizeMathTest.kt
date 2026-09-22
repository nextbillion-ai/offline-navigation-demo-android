package ai.nextbillion.offlinedemo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class DownloadSizeMathTest {
    @Test
    fun sumKnown_requiresBothComponentSizes() {
        assertNull(DownloadSizeMath.sumKnown(10L, null))
        assertNull(DownloadSizeMath.sumKnown(null, 20L))
        assertEquals(30L, DownloadSizeMath.sumKnown(10L, 20L))
    }

    @Test
    fun sumKnown_saturatesOnOverflow() {
        assertEquals(Long.MAX_VALUE, DownloadSizeMath.sumKnown(Long.MAX_VALUE, 1L))
    }

    @Test
    fun remaining_prefersLiveProgressAndNeverReturnsNegative() {
        assertEquals(70L, DownloadSizeMath.remaining(30L, 100L, 200L))
        assertEquals(0L, DownloadSizeMath.remaining(120L, 100L, 200L))
        assertEquals(200L, DownloadSizeMath.remaining(null, null, 200L))
        assertNull(DownloadSizeMath.remaining(null, null, null))
    }

    @Test
    fun requiredFreeBytes_usesMinimumOrTwentyPercentReserve() {
        assertEquals(
            101L * 1024L * 1024L,
            DownloadSizeMath.requiredFreeBytes(1024L * 1024L),
        )
        assertEquals(1_288_490_189L, DownloadSizeMath.requiredFreeBytes(1_073_741_824L))
    }

    @Test
    fun divideRoundUp_validatesArguments() {
        assertEquals(4L, DownloadSizeMath.divideRoundUp(10L, 3L))
        assertThrows(IllegalArgumentException::class.java) {
            DownloadSizeMath.divideRoundUp(1L, 0L)
        }
    }
}
