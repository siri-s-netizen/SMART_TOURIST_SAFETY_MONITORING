package com.touristsafety.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.touristsafety.app.tracking.TrackingSnapshot
import com.touristsafety.app.ui.theme.StatusDanger
import com.touristsafety.app.ui.theme.StatusDangerContainer
import com.touristsafety.app.ui.theme.StatusSafe
import com.touristsafety.app.ui.theme.StatusWarning

/**
 * CARD 1 — Geographical safety.
 *
 * The answer to "am I inside an area the authority has closed off?" and *nothing else*. It is built
 * from the tracking pipeline's own snapshot, and no model state is passed in, so a Low fall-risk
 * prediction structurally cannot soften the inside-zone warning.
 *
 * When the tourist is inside a restricted or dangerous zone the card turns red, carries the
 * required unmissable sentence, and names the zone and the distance to its boundary.
 */
@Composable
fun GeoSafetyCard(
    snapshot: TrackingSnapshot,
    maxUsableAccuracyMeters: Double,
    nowMillis: Long,
    modifier: Modifier = Modifier,
) {
    // Cheap, pure and allocation-light: rebuild on every recomposition rather than caching, so the
    // card can never show a stale verdict while the map has already moved on.
    val content = GeoSafetyCardModel.build(snapshot, maxUsableAccuracyMeters, nowMillis)

    InfoCard(
        title = content.title,
        modifier = modifier,
        containerColor = if (content.isInsideRestricted) {
            StatusDangerContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
    ) {
        Text(
            text = content.headline,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = content.headlineTone.color(),
        )

        if (content.warning != null) {
            Column {
                Text(
                    text = content.warning,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = StatusDanger,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        CardSpacer()
        Text(
            text = content.distinction,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (content.rows.isNotEmpty()) {
            CardSpacer()
            content.rows.forEach { row ->
                LabelValueRow(row.label, row.value, valueColor = row.tone.color())
            }
        }

        content.notices.forEach { notice ->
            CardSpacer()
            NoticeRow(text = notice)
        }

        CardSpacer()
        Text(
            text = content.footer,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Tone to colour. `MUTED`/`NEUTRAL` defer to the theme, which needs a composable context. */
@Composable
private fun GeoSafetyTone.color() = when (this) {
    GeoSafetyTone.SAFE -> StatusSafe
    GeoSafetyTone.WARNING -> StatusWarning
    GeoSafetyTone.DANGER -> StatusDanger
    GeoSafetyTone.MUTED -> MaterialTheme.colorScheme.onSurfaceVariant
    GeoSafetyTone.NEUTRAL -> MaterialTheme.colorScheme.onSurface
}
