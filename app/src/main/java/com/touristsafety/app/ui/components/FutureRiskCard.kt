package com.touristsafety.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.touristsafety.app.motion.FutureRiskState
import com.touristsafety.app.ui.theme.StatusDanger
import com.touristsafety.app.ui.theme.StatusSafe
import com.touristsafety.app.ui.theme.StatusWarning

/**
 * The **trained** future-risk prediction.
 *
 * This card exists to keep three different things apart, because conflating them is the failure mode
 * the whole feature was specified to avoid:
 *
 * | heading | what it is |
 * |---|---|
 * | "Geofence status" (elsewhere on this screen) | a *configured rule*: the tourist is inside or outside a polygon |
 * | "Approaching a restricted area" | a *published weighted checklist* over crossing history - not learned, not a probability |
 * | **this card** | a *trained random forest* forecasting a real recorded physical event |
 *
 * The wording lives in [FutureRiskCardModel] and is unit-tested there; this file only draws it.
 */
@Composable
fun FutureRiskCard(
    state: FutureRiskState,
    nowMillis: Long,
    modifier: Modifier = Modifier,
) {
    val content = FutureRiskCardModel.build(state, nowMillis)

    InfoCard(title = content.title, modifier = modifier) {
        Text(
            text = content.headline,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = content.headlineTone.color(),
        )
        Text(
            text = content.subheadline,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

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

        if (content.factorHeading != null) {
            CardSpacer()
            SectionHeader(content.factorHeading)
            content.factors.forEach { factor ->
                LabelValueRow(factor.label, factor.value)
            }
            Text(
                text = content.factorCaveat,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        content.notices.forEach { notice ->
            CardSpacer()
            NoticeRow(text = notice)
        }

        CardSpacer()
        Column {
            Text(
                text = content.footer,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Tone to colour. `MUTED`/`NEUTRAL` defer to the theme, which needs a composable context. */
@Composable
private fun RiskCardTone.color(): Color = when (this) {
    RiskCardTone.SAFE -> StatusSafe
    RiskCardTone.WARNING -> StatusWarning
    RiskCardTone.DANGER -> StatusDanger
    RiskCardTone.MUTED -> MaterialTheme.colorScheme.onSurfaceVariant
    RiskCardTone.NEUTRAL -> MaterialTheme.colorScheme.onSurface
}
