package com.touristsafety.app.ui.screens.authority

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.touristsafety.app.ui.components.formatRelativeFrom
import com.touristsafety.app.ui.theme.StatusDanger
import com.touristsafety.app.ui.theme.StatusSafe
import com.touristsafety.app.ui.theme.StatusWarning
import com.touristsafety.app.ui.theme.SimulationAccent

/**
 * Shared presentation rules for the officer console.
 *
 * These are the *honesty* rules of the dashboard, kept in one file so they cannot drift between
 * screens: a position that has not been refreshed recently must look stale everywhere, and a risk
 * band the app does not recognise must never be coloured as though it were low.
 */

/** A position older than this is shown as stale rather than as "where they are now". */
const val POSITION_STALE_AFTER_MILLIS: Long = 5 * 60 * 1000L

/**
 * Colour for a risk band, using the model's own vocabulary.
 *
 * Anything unrecognised gets the neutral surface colour and keeps its raw text: guessing "low" for a
 * band a newer server invented is the one mistake that would understate a real danger.
 */
@Composable
fun riskBandColor(band: String?): Color = when (band?.uppercase()) {
    "LOW" -> StatusSafe
    "MODERATE" -> StatusWarning
    "HIGH" -> StatusDanger
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** Colour for an SOS status: an open incident is the loudest thing on any screen. */
@Composable
fun sosStatusColor(status: String?): Color = when (status?.uppercase()) {
    "OPEN" -> StatusDanger
    "ACKNOWLEDGED" -> StatusSafe
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** Colour for a tourist's roll-up state as `/authority/overview` reports it. */
@Composable
fun touristStateColor(state: String?): Color = when (state?.uppercase()) {
    "SOS" -> StatusDanger
    "ZONE_ALERT" -> StatusWarning
    "OK" -> StatusSafe
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** Simulated data is marked, never blended in with real data. */
val simulatedMarkerColor: Color get() = SimulationAccent

/**
 * How fresh a reported position is, and whether it should be labelled stale.
 *
 * A null timestamp is "never reported", which is a different statement from "reported a long time
 * ago" and is kept different here.
 */
fun positionFreshness(recordedAtMillis: Long?, nowMillis: Long): PositionFreshness {
    if (recordedAtMillis == null || recordedAtMillis <= 0L) {
        return PositionFreshness(label = "never reported", isStale = true)
    }
    val age = nowMillis - recordedAtMillis
    return PositionFreshness(
        label = recordedAtMillis.formatRelativeFrom(nowMillis),
        isStale = age > POSITION_STALE_AFTER_MILLIS,
    )
}

data class PositionFreshness(val label: String, val isStale: Boolean)
