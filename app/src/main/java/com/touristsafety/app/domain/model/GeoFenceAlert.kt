package com.touristsafety.app.domain.model

/** ENTRY = outside -> inside, EXIT = inside -> outside. */
enum class AlertEventType(
    val storageKey: String,
    val displayName: String,
) {
    ENTRY("ENTRY", "Entry"),
    EXIT("EXIT", "Exit"),
    ;

    companion object {
        fun fromStorage(value: String?): AlertEventType =
            entries.firstOrNull { it.storageKey == value } ?: ENTRY
    }
}

/** A recorded geo-fence crossing. Persisted to Room and shown in Alert History. */
data class GeoFenceAlert(
    val id: Long = 0L,
    val zoneId: Long,
    val zoneName: String,
    val zoneType: ZoneType,
    val eventType: AlertEventType,
    val point: GeoPoint,
    val accuracyMeters: Float,
    val timestampMillis: Long,
    val acknowledged: Boolean = false,
    val source: LocationSource = LocationSource.GPS,
) {
    /**
     * Only entering a restricted/dangerous zone is a warning worth a heads-up notification.
     * Entering a safe zone and leaving any zone are recorded but are informational.
     */
    val isWarning: Boolean
        get() = zoneType.raisesWarningOnEntry && eventType == AlertEventType.ENTRY
}
