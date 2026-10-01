package app.syncwake.alarm

import app.syncwake.data.daysToMask
import app.syncwake.data.maskToDays
import java.time.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Test

class MapperTest {
    @Test
    fun dayMaskRoundTrips() {
        val all = DayOfWeek.entries
        for (bits in 0 until (1 shl 7)) {
            val days = all.filter { bits and (1 shl (it.value - 1)) != 0 }.toSet()
            assertEquals(days, maskToDays(daysToMask(days)))
        }
        assertEquals(0b0011111, daysToMask(setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)))
    }
}
