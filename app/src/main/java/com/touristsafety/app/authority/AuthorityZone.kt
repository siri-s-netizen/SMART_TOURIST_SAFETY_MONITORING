package com.touristsafety.app.authority

import com.touristsafety.app.domain.model.GeoPoint

/**
 * A restricted/dangerous/safe area **as the authority backend holds it**.
 *
 * Deliberately a different type from the app's local `GeoFenceZone`. The local type is what this phone
 * evaluates; this is what the authority published. Keeping them apart is what makes it possible to
 * say, honestly, which zones are authority-owned — and it is why [revision] exists: a phone that has
 * cached revision 3 of a zone can tell that the authority has since changed it to revision 4 and must
 * re-read the polygon before it can be trusted again.
 *
 * The vertex order is the authority's ring in the order it was published. The closing edge is
 * implicit; the first vertex is never repeated.
 */
data class AuthorityZone(
    val zoneId: Int,
    val name: String,
    val description: String,
    val zoneType: String,
    val status: String,
    val revision: Int,
    val vertices: List<GeoPoint>,
    val updatedAtMillis: Long,
) {
    val isActive: Boolean get() = status == ZoneStatus.ACTIVE

    /** Whether a phone should raise a warning when the tourist is inside this zone. */
    val warnsWhenInside: Boolean get() = zoneType == ZoneType.RESTRICTED || zoneType == ZoneType.DANGEROUS
}

/**
 * The wire values for a zone, mirroring `backend/tsm/api.py`.
 *
 * String constants rather than an enum because the field is a *contract*: an unknown value from a
 * newer server must degrade to "not active", never throw and never be guessed at.
 */
object ZoneType {
    const val RESTRICTED: String = "RESTRICTED"
    const val DANGEROUS: String = "DANGEROUS"
    const val SAFE: String = "SAFE"
}

object ZoneStatus {
    const val ACTIVE: String = "ACTIVE"
    const val INACTIVE: String = "INACTIVE"
    const val ARCHIVED: String = "ARCHIVED"
}
