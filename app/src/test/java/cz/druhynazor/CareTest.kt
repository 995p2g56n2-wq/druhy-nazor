package cz.druhynazor

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class CareTest {
    private val zone = ZoneId.of("Europe/Prague")
    private fun at(s: String) = LocalDateTime.parse(s).atZone(zone).toInstant().toEpochMilli()

    @Test fun laterToday() = assertEquals(at("2026-11-28T09:00"), Care.nextAt(at("2026-11-28T07:30"), 9, 0, zone))

    @Test fun alreadyPassedGoesTomorrow() = assertEquals(at("2026-11-29T09:00"), Care.nextAt(at("2026-11-28T09:00"), 9, 0, zone))

    @Test fun eveningMed() = assertEquals(at("2026-11-28T20:15"), Care.nextAt(at("2026-11-28T20:14"), 20, 15, zone))

    // Přechod na letní čas v noci na 28. 3. 2027 v noci se posouvají hodiny, ráno musí zůstat 8:00
    @Test fun summerTime() = assertEquals(at("2027-03-28T08:00"), Care.nextAt(at("2027-03-27T08:30"), 8, 0, zone))
}
