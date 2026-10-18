package com.touristsafety.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dangerous
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.touristsafety.app.domain.model.SafetyStatus
import com.touristsafety.app.domain.model.ZoneType
import com.touristsafety.app.ui.theme.DangerousFill
import com.touristsafety.app.ui.theme.DangerousStroke
import com.touristsafety.app.ui.theme.RestrictedFill
import com.touristsafety.app.ui.theme.RestrictedStroke
import com.touristsafety.app.ui.theme.SafeFill
import com.touristsafety.app.ui.theme.SafeStroke
import com.touristsafety.app.ui.theme.StatusDanger
import com.touristsafety.app.ui.theme.StatusDangerContainer
import com.touristsafety.app.ui.theme.StatusSafe
import com.touristsafety.app.ui.theme.StatusSafeContainer
import com.touristsafety.app.ui.theme.StatusWarning
import com.touristsafety.app.ui.theme.StatusWarningContainer

/** How a status is presented: colour, icon, and what the user should do about it. */
data class StatusVisuals(
    val label: String,
    val color: Color,
    val containerColor: Color,
    val icon: ImageVector,
    val guidance: String,
)

fun statusVisualsFor(status: SafetyStatus): StatusVisuals = when (status) {
    SafetyStatus.SAFE -> StatusVisuals(
        label = "Safe",
        color = StatusSafe,
        containerColor = StatusSafeContainer,
        icon = Icons.Filled.CheckCircle,
        guidance = "You are outside every monitored zone.",
    )

    SafetyStatus.INSIDE_SAFE_ZONE -> StatusVisuals(
        label = "Inside a safe zone",
        color = StatusSafe,
        containerColor = StatusSafeContainer,
        icon = Icons.Filled.CheckCircle,
        guidance = "You are inside a designated safe zone.",
    )

    SafetyStatus.NEAR_BOUNDARY -> StatusVisuals(
        label = "Near a boundary",
        color = StatusWarning,
        containerColor = StatusWarningContainer,
        icon = Icons.Filled.Warning,
        guidance = "You are close to a zone edge. The app will confirm once you are clearly inside or outside.",
    )

    SafetyStatus.UNCERTAIN -> StatusVisuals(
        label = "Location uncertain",
        color = StatusWarning,
        containerColor = StatusWarningContainer,
        icon = Icons.Filled.HelpOutline,
        guidance = "GPS accuracy is too poor to judge the boundary, so no alert has been raised. Move to open sky.",
    )

    SafetyStatus.INSIDE_RESTRICTED -> StatusVisuals(
        label = "Inside a RESTRICTED area",
        color = StatusDanger,
        containerColor = StatusDangerContainer,
        icon = Icons.Filled.Block,
        guidance = "Leave the restricted area immediately.",
    )

    SafetyStatus.INSIDE_DANGEROUS -> StatusVisuals(
        label = "Inside a DANGEROUS area",
        color = StatusDanger,
        containerColor = StatusDangerContainer,
        icon = Icons.Filled.Dangerous,
        guidance = "Leave the dangerous area immediately and alert your group.",
    )
}

/**
 * Banner visuals derived from the *geographical* state, so the banner at the top of the screen can
 * never show a green "Safe" while the tourist is inside a restricted zone — or while there is simply
 * no position to decide from. This is what stops a green banner from contradicting CARD 1.
 */
fun geoSafetyVisualsFor(state: GeoSafetyState): StatusVisuals = when (state) {
    GeoSafetyState.OUTSIDE_ZONES -> StatusVisuals(
        label = "Outside restricted zones",
        color = StatusSafe,
        containerColor = StatusSafeContainer,
        icon = Icons.Filled.CheckCircle,
        guidance = "No active restricted or dangerous area contains your current position.",
    )

    GeoSafetyState.CHECKING_LOCATION -> StatusVisuals(
        label = "Checking your location",
        color = StatusWarning,
        containerColor = StatusWarningContainer,
        icon = Icons.Filled.HelpOutline,
        guidance = "Waiting for the first GPS fix; zone containment is not decided yet.",
    )

    GeoSafetyState.LOCATION_UNAVAILABLE -> StatusVisuals(
        label = "Location unavailable",
        color = StatusWarning,
        containerColor = StatusWarningContainer,
        icon = Icons.Filled.HelpOutline,
        guidance = "There is no current position, so no zone can be checked right now.",
    )

    GeoSafetyState.NEAR_BOUNDARY_UNCERTAIN -> StatusVisuals(
        label = "Location uncertain",
        color = StatusWarning,
        containerColor = StatusWarningContainer,
        icon = Icons.Filled.Warning,
        guidance = "You are close to a zone edge, or the fix is too coarse to decide which side " +
            "you are on. Move to open sky.",
    )

    GeoSafetyState.INSIDE_RESTRICTED_ZONE -> StatusVisuals(
        label = "Inside a restricted area",
        color = StatusDanger,
        containerColor = StatusDangerContainer,
        icon = Icons.Filled.Block,
        guidance = GeoSafetyCardModel.INSIDE_WARNING,
    )
}

/** Map styling for a zone type: shaded fill plus a clearly visible boundary. */
data class ZoneVisuals(
    val fill: Color,
    val stroke: Color,
    val displayName: String,
) {
    companion object {
        val Restricted = ZoneVisuals(RestrictedFill, RestrictedStroke, ZoneType.RESTRICTED.displayName)
        val Dangerous = ZoneVisuals(DangerousFill, DangerousStroke, ZoneType.DANGEROUS.displayName)
        val Safe = ZoneVisuals(SafeFill, SafeStroke, ZoneType.SAFE.displayName)
    }
}

fun zoneVisualsFor(type: ZoneType): ZoneVisuals = when (type) {
    ZoneType.RESTRICTED -> ZoneVisuals.Restricted
    ZoneType.DANGEROUS -> ZoneVisuals.Dangerous
    ZoneType.SAFE -> ZoneVisuals.Safe
}

/** Icon used on zone list rows. */
fun zoneIconFor(type: ZoneType): ImageVector = when (type) {
    ZoneType.RESTRICTED -> Icons.Filled.Block
    ZoneType.DANGEROUS -> Icons.Filled.Dangerous
    ZoneType.SAFE -> Icons.Filled.CheckCircle
}

/** Generic error/info visuals reused by the banners. */
val ErrorVisuals: StatusVisuals = StatusVisuals(
    label = "Attention",
    color = StatusDanger,
    containerColor = StatusDangerContainer,
    icon = Icons.Filled.Error,
    guidance = "",
)
