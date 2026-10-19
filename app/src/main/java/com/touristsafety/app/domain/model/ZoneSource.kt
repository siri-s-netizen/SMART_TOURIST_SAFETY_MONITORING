package com.touristsafety.app.domain.model

/**
 * Where a saved zone came from — and therefore who owns it.
 *
 * This distinction is the whole point of publishing zones from the authority: before it existed,
 * every zone in `geo_fences` was created on this phone and belonged to whoever drew it, so "the
 * authority published a zone" was not expressible. Now it is:
 *
 * - [LOCAL] — drawn on this phone. It is nobody else's business. The authority sync never rewrites,
 *   disables or removes one, even if it happens to describe the same ground.
 * - [AUTHORITY] — downloaded from `GET /zones`. It is a **mirror** of what the authority publishes:
 *   when the authority changes it, this copy is replaced; when the authority stops publishing it, the
 *   copy is removed. Keeping a withdrawn zone would leave the tourist being warned by an area that
 *   is no longer closed.
 *
 * The database column is `NOT NULL DEFAULT 'LOCAL'`, so every pre-existing row survives the upgrade
 * as a local zone, which is exactly what it was.
 */
enum class ZoneSource(val storageKey: String, val displayName: String) {
    LOCAL("LOCAL", "This phone"),
    AUTHORITY("AUTHORITY", "Authority"),
    ;

    companion object {
        /**
         * An unrecognised value reads as [LOCAL] on purpose.
         *
         * [LOCAL] is the *protective* default: the authority sync will not touch, replace or delete a
         * local zone. Guessing [AUTHORITY] for a value this build does not understand could let a
         * sync erase a zone a human drew.
         */
        fun fromStorage(value: String?): ZoneSource =
            entries.firstOrNull { it.storageKey == value } ?: LOCAL
    }
}
