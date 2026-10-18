package com.touristsafety.app.ui.components

import com.touristsafety.app.domain.model.SafetyStatus
import com.touristsafety.app.domain.model.ZoneContainment
import com.touristsafety.app.domain.model.ZoneType
import com.touristsafety.app.geofencing.ZoneStatus
import com.touristsafety.app.tracking.TrackingSnapshot
import java.util.Locale

/** Semantic tone for one rendered row, mapped to a colour by the composable. */
enum class GeoSafetyTone { SAFE, WARNING, DANGER, MUTED, NEUTRAL }

/**
 * The five geographical states the tourist can be in.
 *
 * These are deliberately **not** a fall-risk band and **not** an activity. This is the answer to
 * "am I inside an area the authority has closed off, or not?", and it is the only thing on the screen
 * that is allowed to answer that question. A model score must never move it: the future-risk card
 * says whether a *hard fall* is likely, which is a completely different claim from whether the
 * tourist is standing in a restricted polygon.
 */
enum class GeoSafetyState(val displayName: String) {
    /** No fix yet, but tracking is running: the answer is genuinely "we are still working it out". */
    CHECKING_LOCATION("Checking your location"),

    /** There is no usable, current fix at all (tracking stopped, GPS off, or the last fix is stale). */
    LOCATION_UNAVAILABLE("Location unavailable"),

    /** A decided fix, and it sits outside every restricted/dangerous zone. */
    OUTSIDE_ZONES("Outside restricted zones"),

    /** The fix is inside the tolerance band, or too coarse to decide which side of a boundary. */
    NEAR_BOUNDARY_UNCERTAIN("Near a boundary — location uncertain"),

    /** A decided fix that sits inside an active restricted or dangerous zone. Red. */
    INSIDE_RESTRICTED_ZONE("Inside a restricted zone"),
}

/** One label/value row of the geographical card. */
data class GeoSafetyRow(
    val label: String,
    val value: String,
    val tone: GeoSafetyTone = GeoSafetyTone.NEUTRAL,
)

/**
 * Everything the geographical-safety card says, as plain data.
 *
 * The card itself is a thin renderer over this. That split matters for the same reason it does for
 * the future-risk card: the questions that actually matter here - *does a low model score suppress
 * the inside-zone warning? does the card claim "outside" when the fix is stale? does it name the
 * zone and the distance to its boundary?* - are questions about text, and they can be answered by a
 * plain JVM test with no emulator.
 */
data class GeoSafetyCardContent(
    val title: String,
    val state: GeoSafetyState,
    val headline: String,
    val headlineTone: GeoSafetyTone,
    /** Present only when the tourist is inside a restricted/dangerous zone. */
    val warning: String?,
    val distinction: String,
    val rows: List<GeoSafetyRow>,
    val notices: List<String>,
    val footer: String,
) {
    val isInsideRestricted: Boolean get() = state == GeoSafetyState.INSIDE_RESTRICTED_ZONE
}

object GeoSafetyCardModel {

    const val TITLE = "Geographical safety · your position"

    /**
     * The unmissable wording required whenever a decided fix is inside a restricted polygon, worded
     * to work for both a "restricted" and a "dangerous" zone.
     */
    const val INSIDE_WARNING =
        "YOU ARE INSIDE A RESTRICTED AREA. Move to a safer location if you can do so safely."

    /**
     * The sentence that keeps this card apart from the two model cards. Without it a reader will
     * assume the three cards on the screen are three votes on one question.
     */
    const val DISTINCTION =
        "This is decided from your GPS position and the zones the authority has set. It is not a " +
            "fall-risk score, and the fall-risk model cannot change it."

    const val FOOTER =
        "The authority owns these zones; this phone only reports and warns. Signs that the authority " +
            "has published a new zone arrive the next time the app can reach it."

    /** A fix older than this is not treated as "where you are" any more. */
    const val STALE_AFTER_MILLIS: Long = 60_000L

    /**
     * Builds the card's content for one instant.
     *
     * The input is the tracking pipeline's own snapshot - the same object the status banner reads -
     * so this card and the banner can never disagree about inside/outside. No model state is passed
     * in, which is what makes "a Low prediction cannot suppress this warning" structural rather than
     * a promise.
     */
    fun build(
        snapshot: TrackingSnapshot,
        maxUsableAccuracyMeters: Double,
        nowMillis: Long,
    ): GeoSafetyCardContent {
        val reading = snapshot.lastReading

        // 1. No fix at all. "Still working it out" and "we have nothing" are different sentences.
        if (reading == null) {
            return unavailable(
                state = if (snapshot.isTracking) {
                    GeoSafetyState.CHECKING_LOCATION
                } else {
                    GeoSafetyState.LOCATION_UNAVAILABLE
                },
                headline = if (snapshot.isTracking) {
                    "Checking your location"
                } else {
                    "Location unavailable"
                },
                reason = if (snapshot.isTracking) {
                    "Waiting for the first GPS fix. Zone containment is not evaluated until a " +
                        "position arrives."
                } else {
                    "Tracking is stopped, so no position is being read and no zone can be checked."
                },
                snapshot = snapshot,
                reading = null,
            )
        }

        // 2. The fix is too old to describe where the tourist is *now*.
        val ageMillis = nowMillis - reading.timestampMillis
        if (reading.timestampMillis > 0L && ageMillis > STALE_AFTER_MILLIS) {
            return unavailable(
                state = GeoSafetyState.LOCATION_UNAVAILABLE,
                headline = "Location unavailable",
                reason = "The last position is ${formatAge(ageMillis)} old, so it is not being " +
                    "treated as where you are now.",
                snapshot = snapshot,
                reading = reading,
            )
        }

        // 3. The fix is current, but too coarse to decide which side of a boundary it is on.
        val accuracy = reading.accuracyMeters
        val accuracyTooPoor = !accuracy.isFinite() || accuracy.toDouble() > maxUsableAccuracyMeters
        val worstInside = worstRestrictedInside(snapshot.zoneStatuses)

        // A decided inside verdict wins even over a coarse accuracy: if the GPS is poor but the
        // engine still decided "inside", we do not hide that behind an uncertainty message.
        if (worstInside != null && !accuracyTooPoor) {
            return insideRestricted(snapshot, worstInside, accuracy, reading.timestampMillis)
        }

        if (accuracyTooPoor) {
            return uncertain(
                snapshot = snapshot,
                reason = "GPS accuracy is ${accuracyLabel(accuracy)}, worse than the " +
                    "${maxUsableAccuracyMeters.toInt()} m the app will trust. Move to open sky; " +
                    "the app will not guess which side of a boundary you are on.",
                rowTone = GeoSafetyTone.WARNING,
            )
        }

        // 4. Undecided near a boundary (tolerance band or uncertainty circle straddling an edge).
        val undecided = snapshot.zoneStatuses.firstOrNull {
            it.containment == ZoneContainment.BOUNDARY || it.containment == ZoneContainment.UNCERTAIN
        }
        if (undecided != null) {
            return uncertain(
                snapshot = snapshot,
                reason = "The current fix is close enough to ${undecided.zoneName} that the app " +
                    "cannot yet say whether you are inside it. It will confirm as you move.",
                rowTone = GeoSafetyTone.WARNING,
            )
        }

        // 5. Inside a safe zone is still "outside restricted zones", but it is worth naming.
        val insideSafe = snapshot.zoneStatuses.firstOrNull {
            it.containment == ZoneContainment.INSIDE && it.zoneType == ZoneType.SAFE
        }

        return outside(snapshot, insideSafe, accuracy, reading.timestampMillis)
    }

    private fun insideRestricted(
        snapshot: TrackingSnapshot,
        zone: ZoneStatus,
        accuracy: Float,
        timestampMillis: Long,
    ): GeoSafetyCardContent = GeoSafetyCardContent(
        title = TITLE,
        state = GeoSafetyState.INSIDE_RESTRICTED_ZONE,
        headline = if (zone.zoneType == ZoneType.DANGEROUS) {
            "You are inside a DANGEROUS area: ${zone.zoneName}"
        } else {
            "You are inside a RESTRICTED area: ${zone.zoneName}"
        },
        headlineTone = GeoSafetyTone.DANGER,
        warning = INSIDE_WARNING,
        distinction = DISTINCTION,
        rows = buildList {
            add(GeoSafetyRow("Zone", zone.zoneName, GeoSafetyTone.DANGER))
            add(GeoSafetyRow("Zone type", zone.zoneType.displayName, GeoSafetyTone.DANGER))
            add(
                GeoSafetyRow(
                    "Distance to boundary",
                    zone.distanceToBoundaryMeters.formatDistanceFromBoundary(),
                ),
            )
            add(GeoSafetyRow("GPS accuracy", accuracyLabel(accuracy)))
            add(GeoSafetyRow("Position time", formatPositionTime(timestampMillis)))
        },
        notices = emptyList(),
        footer = FOOTER,
    )

    private fun uncertain(
        snapshot: TrackingSnapshot,
        reason: String,
        rowTone: GeoSafetyTone,
    ): GeoSafetyCardContent = GeoSafetyCardContent(
        title = TITLE,
        state = GeoSafetyState.NEAR_BOUNDARY_UNCERTAIN,
        headline = GeoSafetyState.NEAR_BOUNDARY_UNCERTAIN.displayName,
        headlineTone = rowTone,
        warning = null,
        distinction = DISTINCTION,
        rows = positionRows(snapshot, tone = rowTone),
        notices = listOf(reason),
        footer = FOOTER,
    )

    private fun outside(
        snapshot: TrackingSnapshot,
        insideSafe: ZoneStatus?,
        accuracy: Float,
        timestampMillis: Long,
    ): GeoSafetyCardContent = GeoSafetyCardContent(
        title = TITLE,
        state = GeoSafetyState.OUTSIDE_ZONES,
        headline = GeoSafetyState.OUTSIDE_ZONES.displayName,
        headlineTone = GeoSafetyTone.SAFE,
        warning = null,
        distinction = DISTINCTION,
        rows = buildList {
            addAll(positionRows(snapshot, tone = GeoSafetyTone.SAFE))
            if (insideSafe != null) {
                add(GeoSafetyRow("Note", "You are inside a designated safe zone", GeoSafetyTone.SAFE))
            }
        },
        notices = emptyList(),
        footer = FOOTER,
    )

    private fun unavailable(
        state: GeoSafetyState,
        headline: String,
        reason: String,
        snapshot: TrackingSnapshot,
        reading: com.touristsafety.app.domain.model.GeoReading?,
    ): GeoSafetyCardContent = GeoSafetyCardContent(
        title = TITLE,
        state = state,
        headline = headline,
        headlineTone = GeoSafetyTone.MUTED,
        warning = null,
        distinction = DISTINCTION,
        rows = if (reading == null) {
            listOf(GeoSafetyRow("Monitored zones", snapshot.activeZoneCount.toString()))
        } else {
            positionRows(snapshot, tone = GeoSafetyTone.MUTED)
        },
        notices = listOf(reason),
        footer = FOOTER,
    )

    private fun positionRows(snapshot: TrackingSnapshot, tone: GeoSafetyTone): List<GeoSafetyRow> {
        val reading = snapshot.lastReading ?: return emptyList()
        return listOf(
            GeoSafetyRow("GPS accuracy", accuracyLabel(reading.accuracyMeters), tone),
            GeoSafetyRow("Position time", formatPositionTime(reading.timestampMillis)),
            GeoSafetyRow("Monitored zones", snapshot.activeZoneCount.toString()),
        )
    }

    /** The restricted/dangerous zone the engine decided the tourist is inside, if any. */
    private fun worstRestrictedInside(statuses: List<ZoneStatus>): ZoneStatus? =
        statuses
            .filter {
                it.containment == ZoneContainment.INSIDE &&
                    (it.zoneType == ZoneType.RESTRICTED || it.zoneType == ZoneType.DANGEROUS)
            }
            .maxByOrNull { if (it.zoneType == ZoneType.DANGEROUS) 1 else 0 }

    private fun accuracyLabel(accuracyMeters: Float): String =
        if (!accuracyMeters.isFinite() || accuracyMeters <= 0f) {
            "unknown"
        } else {
            "±${accuracyMeters.toInt()} m"
        }

    private fun formatPositionTime(timestampMillis: Long): String =
        if (timestampMillis <= 0L) "not recorded" else timestampMillis.formatClockTime()

    private fun formatAge(millis: Long): String = String.format(
        Locale.US,
        if (millis < 60_000L) "%ds" else "%dm",
        if (millis < 60_000L) millis / 1000L else millis / 60_000L,
    )

    /** True when the snapshot's own reduction already flagged an elevated geographical state. */
    fun isElevated(status: SafetyStatus): Boolean = status.isElevated
}
