package com.touristsafety.app.outbox

import com.touristsafety.app.domain.model.EmergencyContact
import com.touristsafety.app.domain.model.GeoFenceAlert
import com.touristsafety.app.domain.model.GeoReading

/**
 * Builds the JSON payloads for queued events.
 *
 * Hand-rolled rather than using `org.json` so the payloads can be unit-tested on the plain JVM (no
 * Robolectric, no Android stub), which keeps the offline-sync tests fast and dependency-free.
 */
object OutboxPayloads {

    /** Stable per-crossing key: the same crossing enqueued twice collapses to one row. */
    fun zoneAlertDedupeKey(alert: GeoFenceAlert): String =
        "alert:${alert.zoneId}:${alert.eventType.storageKey}:${alert.timestampMillis}"

    /** One SOS per millisecond is unique; a double-tap within the same millisecond is deduplicated. */
    fun sosDedupeKey(timestampMillis: Long): String = "sos:$timestampMillis"

    fun zoneAlert(alert: GeoFenceAlert): String = buildString {
        append('{')
        append("\"type\":\"ZONE_ALERT\",")
        append("\"zoneId\":").append(alert.zoneId).append(',')
        append("\"zoneName\":\"").append(escape(alert.zoneName)).append("\",")
        append("\"zoneType\":\"").append(alert.zoneType.storageKey).append("\",")
        append("\"eventType\":\"").append(alert.eventType.storageKey).append("\",")
        append("\"latitude\":").append(alert.point.latitude).append(',')
        append("\"longitude\":").append(alert.point.longitude).append(',')
        append("\"accuracyMeters\":").append(alert.accuracyMeters).append(',')
        append("\"timestampMillis\":").append(alert.timestampMillis).append(',')
        append("\"locationSource\":\"").append(alert.source.storageKey).append("\",")
        append("\"simulated\":").append(alert.source.isSimulated)
        append('}')
    }

    fun sos(reading: GeoReading?, timestampMillis: Long): String = buildString {
        append('{')
        append("\"type\":\"SOS\",")
        append("\"simulated\":true,")
        append("\"timestampMillis\":").append(timestampMillis).append(',')
        append("\"latitude\":").append(reading?.point?.latitude?.toString() ?: "null").append(',')
        append("\"longitude\":").append(reading?.point?.longitude?.toString() ?: "null").append(',')
        append("\"accuracyMeters\":").append(reading?.accuracyMeters?.toString() ?: "null").append(',')
        append("\"locationSource\":\"").append(reading?.source?.storageKey ?: "UNKNOWN").append('"')
        append('}')
    }

    /**
     * One position report per millisecond is unique. Unlike a crossing there is no natural
     * "same event" key, so the fix timestamp is the key: the throttling policy decides how often a
     * report is *built*, and this makes queueing the same fix twice a no-op regardless.
     */
    fun positionDedupeKey(timestampMillis: Long): String = "position:$timestampMillis"

    fun position(reading: GeoReading, timestampMillis: Long): String = buildString {
        append('{')
        append("\"type\":\"POSITION\",")
        append("\"simulated\":").append(reading.isSimulated).append(',')
        append("\"timestampMillis\":").append(timestampMillis).append(',')
        append("\"latitude\":").append(reading.point.latitude).append(',')
        append("\"longitude\":").append(reading.point.longitude).append(',')
        // accuracyMeters is emitted verbatim; a non-finite value would be invalid JSON, so it is
        // guarded here rather than trusted (a platform can report NaN on a cold start).
        append("\"accuracyMeters\":")
        append(if (reading.accuracyMeters.isFinite()) reading.accuracyMeters.toString() else "null")
        append(',')
        append("\"locationSource\":\"").append(reading.source.storageKey).append('"')
        append('}')
    }

    /**
     * One revision of the emergency-contact list.
     *
     * Keyed by the instant the list was saved rather than by a hash of its contents, because the
     * authority's endpoint is a whole-list replace: two saves one second apart are two legitimate
     * revisions, and the second must not be deduplicated away by looking identical to the first.
     */
    fun contactsDedupeKey(timestampMillis: Long): String = "contacts:$timestampMillis"

    fun contacts(contacts: List<EmergencyContact>, timestampMillis: Long): String = buildString {
        append('{')
        append("\"type\":\"CONTACTS\",")
        append("\"timestampMillis\":").append(timestampMillis).append(',')
        append("\"contacts\":[")
        contacts.forEachIndexed { index, contact ->
            if (index > 0) append(',')
            append('{')
            append("\"name\":\"").append(escape(contact.name)).append("\",")
            append("\"phone\":\"").append(escape(contact.phone)).append("\",")
            append("\"relationship\":").append(
                contact.relationship?.let { "\"${escape(it)}\"" } ?: "null",
            ).append(',')
            append("\"priority\":").append(contact.priority)
            append('}')
        }
        append(']')
        append('}')
    }

    // ------------------------------------------------------------------ future-risk summaries

    /**
     * The subset of a future-risk prediction that is shared with the authority.
     *
     * A summary rather than the raw state: the dashboard needs to know *that* the model expected a
     * fall, how confident it was and which model produced it - it has no business receiving a
     * continuous accelerometer record.
     *
     * `probability` is nullable on purpose. If a build ever shipped a model without calibration, the
     * honest payload carries `null` rather than a raw forest vote dressed up as a probability.
     */
    data class FutureRiskSummary(
        val modelVersion: String,
        val horizonSeconds: Float,
        /** [com.touristsafety.app.motion.FutureRiskBand.storageKey] — never `UNAVAILABLE`. */
        val riskBand: String,
        val probability: Float?,
        val windowSamples: Int,
        val observedSamples: Int,
        val dataQualityOk: Boolean,
        val dataQualityReason: String?,
        val factors: List<FutureRiskSummaryFactor>,
        val timestampMillis: Long,
    )

    /** One "the model is reacting to this" row, with its non-causal framing carried in the label. */
    data class FutureRiskSummaryFactor(val label: String, val detail: String)

    /**
     * The dedupe key for a risk summary.
     *
     * The prediction instant is the key, exactly as it is for a position report: the policy decides
     * how often a summary is *built*, and this guarantees that queueing the same instant twice is a
     * no-op rather than a duplicate row the authority would show twice.
     */
    fun futureRiskDedupeKey(timestampMillis: Long): String = "risk:$timestampMillis"

    fun futureRisk(summary: FutureRiskSummary): String = buildString {
        append('{')
        append("\"type\":\"RISK\",")
        append("\"timestampMillis\":").append(summary.timestampMillis).append(',')
        append("\"modelVersion\":\"").append(escape(summary.modelVersion)).append("\",")
        // A non-finite horizon would be invalid JSON, and a NaN probability would be rejected by the
        // server; both are guarded here rather than trusted, as the position payload does.
        append("\"horizonSeconds\":")
        append(if (summary.horizonSeconds.isFinite()) summary.horizonSeconds.toString() else "null")
        append(',')
        append("\"riskBand\":\"").append(summary.riskBand).append("\",")
        append("\"probability\":")
        append(
            summary.probability
                ?.takeIf { it.isFinite() }
                ?.let { it.toString() }
                ?: "null",
        )
        append(',')
        append("\"windowSamples\":").append(summary.windowSamples).append(',')
        append("\"observedSamples\":").append(summary.observedSamples).append(',')
        append("\"dataQuality\":{")
        append("\"ok\":").append(summary.dataQualityOk).append(',')
        append("\"samples\":").append(summary.observedSamples).append(',')
        append("\"reason\":")
        append(summary.dataQualityReason?.let { "\"${escape(it)}\"" } ?: "null")
        append("},")
        append("\"factors\":[")
        summary.factors.forEachIndexed { index, factor ->
            if (index > 0) append(',')
            append('{')
            append("\"label\":\"").append(escape(factor.label)).append("\",")
            append("\"detail\":\"").append(escape(factor.detail)).append('"')
            append('}')
        }
        append(']')
        append('}')
    }

    private fun escape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
}
