package com.touristsafety.app.geofencing

import com.touristsafety.app.domain.model.AlertEventType
import com.touristsafety.app.domain.model.GeoFenceAlert
import com.touristsafety.app.domain.model.GeoPoint
import com.touristsafety.app.domain.model.ZoneType
import com.touristsafety.app.motion.ActivityKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The approach score is a *published* weighted sum, and this test is what "published" has to mean:
 * every weight is pinned, so changing one is a deliberate act with a failing test attached rather
 * than an accident.
 */
class ApproachRiskTest {

    private val noon = 12
    private val anyZone = ZoneType.DANGEROUS

    private fun history(
        entries: Int = 0,
        exits: Int = 0,
        last7: Int = 0,
        last30: Int = 0,
        days: Int = 0,
        first: Long? = null,
        last: Long? = null,
    ) = ZoneHistory(entries, exits, last7, last30, days, first, last)

    private fun assess(
        zoneType: ZoneType = anyZone,
        history: ZoneHistory = history(),
        distance: Double = 500.0,
        inside: Boolean = false,
        hour: Int = noon,
        activity: ActivityKind? = null,
        fallProbability: Float? = null,
    ) = ApproachRiskModel.assess(
        zoneType = zoneType,
        history = history,
        input = ApproachRiskModel.Input(distance, inside, hour, activity, fallProbability),
    )

    private fun pointsFor(result: ApproachRisk, label: String): Int =
        result.factors.first { it.label == label }.points

    @Test
    fun `zone severity is the dominant term and is ordered as intended`() {
        val dangerous = assess(zoneType = ZoneType.DANGEROUS)
        val restricted = assess(zoneType = ZoneType.RESTRICTED)
        val safe = assess(zoneType = ZoneType.SAFE)

        assertEquals(40, pointsFor(dangerous, "Zone type"))
        assertEquals(28, pointsFor(restricted, "Zone type"))
        assertEquals(5, pointsFor(safe, "Zone type"))
        assertTrue(dangerous.score > restricted.score)
        assertTrue(restricted.score > safe.score)
    }

    @Test
    fun `a zone the tourist has never crossed is flagged as unknown ground`() {
        val result = assess(history = history())
        assertEquals(4, pointsFor(result, "Your history here"))
        assertTrue(result.factors.first { it.label == "Your history here" }.detail.contains("unknown ground"))
    }

    @Test
    fun `recent crossings count towards the score and are capped`() {
        val three = assess(history = history(entries = 3, last7 = 3, last30 = 3, days = 3))
        assertEquals(9, pointsFor(three, "Your history here"))
        assertTrue(three.factors.first { it.label == "Your history here" }.detail.contains("3 crossing(s) in the last 7 days"))

        val many = assess(history = history(entries = 40, last7 = 40, last30 = 40, days = 20))
        assertEquals("the history term must be capped", 18, pointsFor(many, "Your history here"))
    }

    @Test
    fun `proximity scales from nothing at the radius to full at the boundary`() {
        assertEquals(0, pointsFor(assess(distance = ApproachRiskModel.APPROACH_RADIUS_METERS), "Distance"))
        assertEquals(8, pointsFor(assess(distance = ApproachRiskModel.APPROACH_RADIUS_METERS / 2), "Distance"))
        assertEquals(16, pointsFor(assess(distance = 0.0), "Distance"))
        assertEquals("beyond the radius contributes nothing", 0, pointsFor(assess(distance = 10_000.0), "Distance"))
    }

    @Test
    fun `being inside the zone counts as full proximity`() {
        val result = assess(distance = 0.0, inside = true)
        assertEquals(16, pointsFor(result, "Distance"))
        assertEquals("Inside the zone now", result.factors.first { it.label == "Distance" }.detail)
    }

    @Test
    fun `darkness adds points, midday does not`() {
        assertEquals(12, pointsFor(assess(hour = 22), "Time of day"))
        assertEquals(12, pointsFor(assess(hour = 2), "Time of day"))
        assertEquals(5, pointsFor(assess(hour = 19), "Time of day"))
        assertEquals(0, pointsFor(assess(hour = 12), "Time of day"))
    }

    @Test
    fun `walking towards the boundary adds a little, standing still does not`() {
        assertEquals(4, pointsFor(assess(activity = ActivityKind.WALKING), "Movement"))
        assertEquals(0, pointsFor(assess(activity = ActivityKind.STANDING), "Movement"))
        assertEquals(0, pointsFor(assess(activity = null), "Movement"))
    }

    @Test
    fun `an unavailable movement model is neutral, not treated as safe`() {
        assertEquals(6, pointsFor(assess(fallProbability = null), "Impact/fall signature"))
        assertEquals(12, pointsFor(assess(fallProbability = 0.9f), "Impact/fall signature"))
        assertEquals(0, pointsFor(assess(fallProbability = 0.05f), "Impact/fall signature"))
    }

    @Test
    fun `the score never exceeds 100 and the factors account for every point`() {
        val worst = assess(
            zoneType = ZoneType.DANGEROUS,
            history = history(entries = 50, last7 = 50, last30 = 50, days = 30),
            distance = 0.0,
            inside = true,
            hour = 23,
            activity = ActivityKind.WALKING,
            fallProbability = 0.99f,
        )

        assertEquals(100, worst.score)
        assertTrue(worst.factors.sumOf { it.points } >= 100)
        assertTrue(worst.factors.all { it.points >= 0 })
    }

    @Test
    fun `bands change at 30 and 60`() {
        assertEquals(RiskBand.LOW, RiskBand.of(0))
        assertEquals(RiskBand.LOW, RiskBand.of(29))
        assertEquals(RiskBand.MODERATE, RiskBand.of(30))
        assertEquals(RiskBand.MODERATE, RiskBand.of(59))
        assertEquals(RiskBand.HIGH, RiskBand.of(60))
        assertEquals(RiskBand.HIGH, RiskBand.of(100))
    }
}

/** [ZoneHistory] is derived from the phone's own recorded crossings, so its arithmetic must add up. */
class ZoneHistoryTest {

    private val now = 1_700_000_000_000L
    private val hour = 60L * 60 * 1000
    private val day = 24 * hour

    private fun alert(
        zoneId: Long,
        eventType: AlertEventType,
        ageMillis: Long,
    ) = GeoFenceAlert(
        zoneId = zoneId,
        zoneName = "Zone $zoneId",
        zoneType = ZoneType.DANGEROUS,
        eventType = eventType,
        point = GeoPoint(12.9716, 77.5946),
        accuracyMeters = 8f,
        timestampMillis = now - ageMillis,
    )

    @Test
    fun `an unknown zone has an empty history`() {
        val history = ZoneHistory.from(
            listOf(alert(1, AlertEventType.ENTRY, hour)),
            zoneId = 99,
            nowMillis = now,
        )
        assertTrue(history.isEmpty)
        assertEquals("No crossings recorded yet", history.describe())
        assertEquals(null, history.firstEventAtMillis)
    }

    @Test
    fun `only the requested zone is summarised`() {
        val history = ZoneHistory.from(
            listOf(
                alert(1, AlertEventType.ENTRY, hour),
                alert(2, AlertEventType.ENTRY, hour),
                alert(1, AlertEventType.EXIT, 2 * hour),
            ),
            zoneId = 1,
            nowMillis = now,
        )

        assertEquals(1, history.entries)
        assertEquals(1, history.exits)
        assertEquals(2, history.eventsLast30Days)
    }

    @Test
    fun `the seven and thirty day windows are measured against the supplied clock`() {
        val history = ZoneHistory.from(
            listOf(
                alert(1, AlertEventType.ENTRY, 1 * hour),
                alert(1, AlertEventType.EXIT, 2 * day),
                alert(1, AlertEventType.ENTRY, 10 * day),
                alert(1, AlertEventType.EXIT, 40 * day),
            ),
            zoneId = 1,
            nowMillis = now,
        )

        assertEquals(2, history.eventsLast7Days)
        assertEquals(3, history.eventsLast30Days)
        assertEquals(4, history.entries + history.exits)
        assertEquals(4, history.distinctDaysWithEvents)
        assertEquals(now - 40 * day, history.firstEventAtMillis)
        assertEquals(now - 1 * hour, history.lastEventAtMillis)
    }

    @Test
    fun `crossings recorded on one day count as one day`() {
        val history = ZoneHistory.from(
            listOf(
                alert(1, AlertEventType.ENTRY, 1 * hour),
                alert(1, AlertEventType.EXIT, 2 * hour),
                alert(1, AlertEventType.ENTRY, 3 * hour),
            ),
            zoneId = 1,
            nowMillis = now,
        )

        assertEquals(1, history.distinctDaysWithEvents)
        assertEquals(2, history.entries)
        assertEquals(1, history.exits)
        assertEquals("2 recorded entries", history.describe())
    }

    @Test
    fun `a single entry is described in the singular`() {
        val history = ZoneHistory.from(
            listOf(alert(1, AlertEventType.ENTRY, hour)),
            zoneId = 1,
            nowMillis = now,
        )
        assertEquals("1 recorded entry", history.describe())
    }

    @Test
    fun `future timestamps are never counted in a past window`() {
        // Defensive: a clock change or a bad row must not make the "recent" counts grow.
        val history = ZoneHistory.from(
            listOf(alert(1, AlertEventType.ENTRY, -1 * day)),
            zoneId = 1,
            nowMillis = now,
        )
        assertEquals(0, history.eventsLast7Days)
        assertEquals(0, history.eventsLast30Days)
        assertEquals(1, history.entries)
    }
}
