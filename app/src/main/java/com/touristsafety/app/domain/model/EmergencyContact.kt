package com.touristsafety.app.domain.model

/**
 * One person the authority should call about this tourist.
 *
 * The whole list is uploaded as a unit (the backend replaces the stored list in a single
 * transaction), so there is no notion of a partially-synced contact: either the phone's list is the
 * authority's list or the authority still has the previous one.
 *
 * [priority] is the order the authority should work down, not a severity.
 */
data class EmergencyContact(
    val id: Long = 0L,
    val name: String,
    val phone: String,
    val relationship: String? = null,
    val priority: Int = 0,
) {
    /** True when the row is complete enough to send; the backend rejects an empty name or phone. */
    val isSendable: Boolean get() = name.isNotBlank() && phone.isNotBlank()
}
