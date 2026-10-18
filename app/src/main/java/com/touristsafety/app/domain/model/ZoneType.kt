package com.touristsafety.app.domain.model

/**
 * How a zone behaves when the tourist is inside it.
 *
 * RESTRICTED and DANGEROUS zones raise a warning on entry; SAFE zones are the mirror image and are
 * treated as informational (the tourist *should* be here, so leaving is the interesting event).
 */
enum class ZoneType(
    val storageKey: String,
    val displayName: String,
) {
    RESTRICTED("RESTRICTED", "Restricted Area"),
    DANGEROUS("DANGEROUS", "Dangerous Area"),
    SAFE("SAFE", "Safe Zone"),
    ;

    /** Only these zone types justify an interruption-style warning notification. */
    val raisesWarningOnEntry: Boolean
        get() = this == RESTRICTED || this == DANGEROUS

    companion object {
        val default: ZoneType = RESTRICTED

        fun fromStorage(value: String?): ZoneType =
            entries.firstOrNull { it.storageKey == value } ?: default

        /** Used by the UI to cycle/list options in a stable order. */
        val orderedOptions: List<ZoneType> = listOf(RESTRICTED, DANGEROUS, SAFE)
    }
}
