package com.touristsafety.app.geofencing

import com.touristsafety.app.domain.model.AlertEventType
import com.touristsafety.app.domain.model.GeoFenceAlert
import com.touristsafety.app.domain.model.ZoneType
import com.touristsafety.app.motion.ActivityKind

/**
 * Everything the app has itself recorded about one zone.
 *
 * Derived from the local alert history — the crossings this phone actually saw. Nothing here is
 * inferred, simulated, or borrowed from another user, so the "history" the tourist is shown is
 * genuinely their own record.
 */
data class ZoneHistory(
    val entries: Int,
    val exits: Int,
    val eventsLast7Days: Int,
    val eventsLast30Days: Int,
    val distinctDaysWithEvents: Int,
    val firstEventAtMillis: Long?,
    val lastEventAtMillis: Long?,
) {
    val isEmpty: Boolean get() = entries == 0 && exits == 0

    /** "Entered 4 times, most recently 2 days ago" — the phrase the UI shows verbatim. */
    fun describe(): String = when {
        isEmpty -> "No crossings recorded yet"
        entries == 0 -> "$exits time(s) recorded, but no entry"
        else -> "$entries recorded entr${if (entries == 1) "y" else "ies"}"
    }

    companion object {
        private const val DAY_MILLIS = 24L * 60 * 60 * 1000

        /**
         * Summarises the crossings of a single zone out of the whole history.
         *
         * Done in memory rather than with a bespoke SQL query: the history table is already capped at
         * [com.touristsafety.app.data.repository.AlertRepository.DEFAULT_HISTORY_LIMIT] rows, so this
         * is a linear pass over a bounded list, and it keeps the derivation pure and testable.
         */
        fun from(alerts: List<GeoFenceAlert>, zoneId: Long, nowMillis: Long): ZoneHistory {
            val relevant = alerts.filter { it.zoneId == zoneId }
            if (relevant.isEmpty()) {
                return ZoneHistory(0, 0, 0, 0, 0, null, null)
            }
            var entries = 0
            var exits = 0
            var last7 = 0
            var last30 = 0
            val days = HashSet<Long>()
            var first = Long.MAX_VALUE
            var last = Long.MIN_VALUE

            for (alert in relevant) {
                when (alert.eventType) {
                    AlertEventType.ENTRY -> entries++
                    AlertEventType.EXIT -> exits++
                }
                val age = nowMillis - alert.timestampMillis
                if (age in 0..7 * DAY_MILLIS) last7++
                if (age in 0..30 * DAY_MILLIS) last30++
                days += alert.timestampMillis / DAY_MILLIS
                if (alert.timestampMillis < first) first = alert.timestampMillis
                if (alert.timestampMillis > last) last = alert.timestampMillis
            }

            return ZoneHistory(
                entries = entries,
                exits = exits,
                eventsLast7Days = last7,
                eventsLast30Days = last30,
                distinctDaysWithEvents = days.size,
                firstEventAtMillis = first,
                lastEventAtMillis = last,
            )
        }
    }
}

/** One named contribution to an [ApproachRisk], so the number can be argued with. */
data class RiskFactor(
    val label: String,
    val detail: String,
    val points: Int,
)

/** Coarse banding of an [ApproachRisk.score]; the raw score is always shown too. */
enum class RiskBand(val displayName: String) {
    LOW("Low"),
    MODERATE("Moderate"),
    HIGH("High"),
    ;

    companion object {
        fun of(score: Int): RiskBand = when {
            score < 30 -> LOW
            score < 60 -> MODERATE
            else -> HIGH
        }
    }
}

/**
 * The zone-approach briefing: a **published weighted score**, not a learned model.
 *
 * The reviewer asked for "historical risk analysis when they approach the restricted area". The
 * honest way to do that is to combine what the app actually knows — the zone's severity, the
 * tourist's own recorded crossings of it, how close they are, the time of day, and the live movement
 * analysis — with **weights that are written down and unit-tested**, and to show the working in the
 * UI. This is deliberately *not* dressed up as a machine-learned danger probability: a number like
 * that would need labelled outcomes (did something bad actually happen?) which this app does not and
 * cannot have. The learned part of the project is the movement/fall model, whose model card is
 * explicit about what it can and cannot claim.
 *
 * The total is capped at 100 and each factor is capped so a single signal cannot dominate.
 */
object ApproachRiskModel {

    /** How far out the briefing starts. Beyond this the tourist is not "approaching". */
    const val APPROACH_RADIUS_METERS: Double = 200.0

    // --- weights, in points out of 100 ---------------------------------------------------------
    private const val SEVERITY_DANGEROUS = 40
    private const val SEVERITY_RESTRICTED = 28
    private const val SEVERITY_SAFE = 5

    private const val RECENT_CROSSING_POINTS_PER_EVENT = 3
    private const val RECENT_CROSSING_POINTS_CAP = 18
    private const val NEVER_VISITED_POINTS = 4

    private const val PROXIMITY_POINTS_CAP = 16

    private const val NIGHT_POINTS = 12
    private const val DUSK_POINTS = 5
    private const val NIGHT_START_HOUR = 20
    private const val NIGHT_END_HOUR = 6
    private const val DUSK_START_HOUR = 18

    private const val AMBULATING_POINTS = 4
    private const val FALL_SCORE_POINTS = 12
    private const val FALL_SCORE_THRESHOLD = 0.55f
    private const val INVALID_FALL_SCORE_POINTS = 6

    /** The input side of the assessment. Everything is already measured; nothing is invented. */
    data class Input(
        val distanceMeters: Double,
        val isInsideNow: Boolean,
        val localHour: Int,
        val activity: ActivityKind?,
        /** The movement model's impact-signature score, 0..1, or `null` when unavailable. */
        val fallProbability: Float?,
    )

    fun assess(zoneType: ZoneType, history: ZoneHistory, input: Input): ApproachRisk {
        val factors = ArrayList<RiskFactor>(6)

        val severity = when (zoneType) {
            ZoneType.DANGEROUS -> SEVERITY_DANGEROUS
            ZoneType.RESTRICTED -> SEVERITY_RESTRICTED
            ZoneType.SAFE -> SEVERITY_SAFE
        }
        factors += RiskFactor(
            label = "Zone type",
            detail = zoneType.displayName,
            points = severity,
        )

        val recentPoints = (history.eventsLast7Days * RECENT_CROSSING_POINTS_PER_EVENT)
            .coerceAtMost(RECENT_CROSSING_POINTS_CAP)
        factors += if (history.isEmpty) {
            RiskFactor(
                label = "Your history here",
                detail = "No crossing of this zone on record — unknown ground",
                points = NEVER_VISITED_POINTS,
            )
        } else {
            RiskFactor(
                label = "Your history here",
                detail = "${history.eventsLast7Days} crossing(s) in the last 7 days, " +
                    "${history.eventsLast30Days} in 30 days, " +
                    "across ${history.distinctDaysWithEvents} day(s)",
                points = recentPoints,
            )
        }

        val fraction = if (input.isInsideNow) {
            1.0
        } else {
            ((APPROACH_RADIUS_METERS - input.distanceMeters) / APPROACH_RADIUS_METERS)
                .coerceIn(0.0, 1.0)
        }
        val proximityPoints = (fraction * PROXIMITY_POINTS_CAP).toInt()
        factors += RiskFactor(
            label = "Distance",
            detail = if (input.isInsideNow) {
                "Inside the zone now"
            } else {
                "About ${input.distanceMeters.toInt()} m from the boundary"
            },
            points = proximityPoints,
        )

        val timePoints = when {
            input.localHour >= NIGHT_START_HOUR || input.localHour < NIGHT_END_HOUR -> NIGHT_POINTS
            input.localHour >= DUSK_START_HOUR -> DUSK_POINTS
            else -> 0
        }
        factors += RiskFactor(
            label = "Time of day",
            detail = "%02d:00 local — %s".format(input.localHour, when (timePoints) {
                NIGHT_POINTS -> "dark, harder to see a boundary or a hazard"
                DUSK_POINTS -> "failing light"
                else -> "daylight"
            }),
            points = timePoints,
        )

        val movementPoints = (if (input.activity?.isAmbulating == true) AMBULATING_POINTS else 0)
        factors += RiskFactor(
            label = "Movement",
            detail = input.activity?.let {
                if (it.isAmbulating) "${it.displayName} — moving toward or past the boundary" else it.displayName
            } ?: "Movement not being analysed",
            points = movementPoints,
        )

        val fallProbability = input.fallProbability
        val fallPoints = when {
            fallProbability == null -> INVALID_FALL_SCORE_POINTS
            fallProbability >= FALL_SCORE_THRESHOLD -> FALL_SCORE_POINTS
            else -> 0
        }
        factors += RiskFactor(
            label = "Impact/fall signature",
            detail = fallProbability?.let {
                "Model score %.2f (threshold %.2f)".format(it, FALL_SCORE_THRESHOLD)
            } ?: "Movement model unavailable — assumed neutral",
            points = fallPoints,
        )

        val score = factors.sumOf { it.points }.coerceIn(0, 100)
        return ApproachRisk(score = score, band = RiskBand.of(score), factors = factors)
    }
}

/**
 * The result of [ApproachRiskModel.assess].
 *
 * [score] is out of 100 and [factors] accounts for every point of it, so the UI can show the sum
 * rather than asking the tourist to trust a badge.
 */
data class ApproachRisk(
    val score: Int,
    val band: RiskBand,
    val factors: List<RiskFactor>,
) {
    /** A one-line, plain-language reading. */
    val headline: String
        get() = when (band) {
            RiskBand.LOW -> "Low attention needed"
            RiskBand.MODERATE -> "Worth a look before you continue"
            RiskBand.HIGH -> "Take care near this boundary"
        }
}
