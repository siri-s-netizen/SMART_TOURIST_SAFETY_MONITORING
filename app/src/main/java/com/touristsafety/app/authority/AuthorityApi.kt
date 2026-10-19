package com.touristsafety.app.authority

import com.touristsafety.app.domain.model.EmergencyContact
import com.touristsafety.app.domain.model.GeoPoint
import com.touristsafety.app.domain.model.OutboxEvent

/**
 * Pure encoding and decoding of the authority protocol.
 *
 * No sockets and no Android, so every rule here — the exact JSON a batch is made of, how a
 * `REJECTED` verdict is read, what happens when a field is missing — is unit-testable on the plain
 * JVM. The transport only moves bytes; this decides what the bytes mean.
 */
object AuthorityApi {

    // ---------------------------------------------------------------- requests

    fun loginBody(role: String, id: String, password: String, deviceId: String?): String =
        Json.write(
            Json.obj(
                "role" to Json.str(role),
                "id" to Json.str(id),
                "password" to Json.str(password),
                "deviceId" to Json.str(deviceId),
            ),
        )

    /**
     * The same sign-in with a numeric PIN instead of a password.
     *
     * `password` is simply omitted: the server accepts a request carrying either credential and
     * rejects one carrying neither, so there is no need to send an empty password alongside the PIN.
     */
    fun pinLoginBody(role: String, id: String, pin: String, deviceId: String?): String =
        Json.write(
            Json.obj(
                "role" to Json.str(role),
                "id" to Json.str(id),
                "pin" to Json.str(pin),
                "deviceId" to Json.str(deviceId),
            ),
        )

    /** Body for `PUT /me/pin`. */
    fun pinBody(pin: String): String =
        Json.write(Json.obj("pin" to Json.str(pin)))

    /**
     * Body for `POST /auth/register`.
     *
     * Three things this body deliberately does *not* contain: a password (the tourist chooses a PIN
     * and nothing else), the PIN confirmation (it has already done its job on the phone), and a role
     * other than `tourist`. The server reads no role anyway — it can only create a tourist — but
     * sending the explicit tourist role means a future client cannot accidentally imply otherwise.
     */
    fun registerBody(draft: RegistrationDraft, deviceId: String?): String =
        Json.write(
            Json.obj(
                "role" to Json.str(AuthorityRoles.TOURIST),
                "displayName" to Json.str(draft.displayName),
                "phone" to Json.str(draft.phone),
                "pin" to Json.str(draft.pin),
                "contacts" to Json.arr(draft.contacts.map { it.toJson() }),
                "deviceId" to Json.str(deviceId),
            ),
        )

    /**
     * One sync batch.
     *
     * The stored payload is already JSON text (see `OutboxPayloads`), so it is embedded as a real
     * object rather than a quoted string: the backend accepts both (`_require_object`), but an object
     * is what the dashboard's own fixtures look like and it makes a captured request readable.
     *
     * If the stored payload is not valid JSON the raw string is still sent, on purpose. The server's
     * validator then answers `REJECTED` with a reason, which moves the row to a terminal state and
     * tells the operator *why* — silently dropping a corrupt row at the client would hide it.
     */
    fun syncBody(events: List<OutboxEvent>): String =
        Json.write(
            Json.obj(
                "events" to Json.arr(events.map { event -> eventEntry(event) }),
            ),
        )

    private fun eventEntry(event: OutboxEvent): JsonValue.Obj = Json.obj(
        "dedupeKey" to Json.str(event.dedupeKey),
        "kind" to Json.str(event.kind.storageKey),
        "origin" to Json.str(event.origin.storageKey),
        "payload" to (Json.parseOrNull(event.payload) ?: Json.str(event.payload)),
        "createdAtMillis" to Json.int(event.createdAtMillis),
        "attemptCount" to Json.int(event.attemptCount),
    )

    fun contactsBody(contacts: List<EmergencyContact>, timestampMillis: Long): String =
        Json.write(
            Json.obj(
                "contacts" to Json.arr(contacts.map { it.toJson() }),
                "timestampMillis" to Json.int(timestampMillis),
            ),
        )

    private fun EmergencyContact.toJson(): JsonValue.Obj = Json.obj(
        "name" to Json.str(name),
        "phone" to Json.str(phone),
        "relationship" to Json.str(relationship),
        "priority" to Json.int(priority),
    )

    // ---------------------------------------------------------------- responses

    fun parseLogin(text: String): AuthorityLoginResult {
        val root = Json.parse(text)
        return AuthorityLoginResult(
            token = root.string("token") ?: throw AuthorityProtocolException("login response has no token"),
            role = root.string("role") ?: AuthorityRoles.TOURIST,
            subjectId = root.string("subjectId")
                ?: throw AuthorityProtocolException("login response has no subjectId"),
            displayName = root.string("displayName") ?: "",
            expiresAtMillis = root.long("expiresAtMillis") ?: 0L,
            serverTimeMillis = root.long("serverTimeMillis") ?: 0L,
        )
    }

    /**
     * The Tourist ID the backend minted for a successful registration.
     *
     * A body with no `tourist` object, or one with no `touristId`, is an [AuthorityProtocolException]
     * rather than an empty success: reporting "registered" for a response that carried no id would
     * leave the tourist holding an account they can never sign in to.
     */
    fun parseRegistration(text: String): AuthorityRegistration {
        val root = Json.parse(text)
        val tourist = root.objectOrNull("tourist")
            ?: throw AuthorityProtocolException("registration response has no tourist object")
        return AuthorityRegistration(
            touristId = tourist.string("touristId")
                ?: throw AuthorityProtocolException("registration response has no touristId"),
            displayName = tourist.string("displayName") ?: "",
            phone = tourist.string("phone"),
            registeredAtMillis = tourist.long("registeredAtMillis") ?: 0L,
        )
    }

    fun parseSyncOutcomes(text: String): List<AuthoritySyncOutcome> {
        val root = Json.parse(text)
        return root.array("results").mapNotNull { entry ->
            val key = entry.string("dedupeKey")
            val status = AuthoritySyncStatus.fromWire(entry.string("status"))
            if (status == AuthoritySyncStatus.UNKNOWN && key == null) {
                null
            } else {
                AuthoritySyncOutcome(
                    dedupeKey = key,
                    status = status,
                    serverEventId = entry.string("serverEventId"),
                    acknowledgementId = entry.string("acknowledgementId"),
                    reason = entry.string("reason"),
                )
            }
        }
    }

    fun parseContacts(text: String): List<EmergencyContact> {
        val root = Json.parse(text)
        // `PUT /me/contacts` answers `{"contacts": [...], "count": n}`; a bare list is accepted too so
        // the same parser works against either shape.
        val items = if (root is JsonValue.Arr) root.items else root.array("contacts")
        return items.mapNotNull { it.toContactOrNull() }
    }

    fun parseProfile(text: String): AuthorityProfile {
        val root = Json.parse(text)
        val tourist = root.objectOrNull("tourist")
            ?: throw AuthorityProtocolException("profile response has no tourist object")
        return AuthorityProfile(
            touristId = tourist.string("touristId")
                ?: throw AuthorityProtocolException("profile response has no touristId"),
            displayName = tourist.string("displayName") ?: "",
            phone = tourist.string("phone"),
            contacts = root.array("contacts").mapNotNull { it.toContactOrNull() },
            deliveredEventCount = root.int("deliveredEventCount") ?: 0,
            lastDeliveredAtMillis = root.long("lastDeliveredAtMillis"),
            latestSos = root.objectOrNull("latestSos")?.let { sos ->
                AuthorityLatestSos(
                    sosId = sos.long("sosId") ?: 0L,
                    status = sos.string("status") ?: "UNKNOWN",
                    raisedAtMillis = sos.long("raisedAtMillis") ?: 0L,
                    acknowledgedAtMillis = sos.long("acknowledgedAtMillis"),
                    acknowledgedBy = sos.string("acknowledgedBy"),
                    simulated = sos.boolean("simulated") ?: false,
                )
            },
            latestPosition = root.objectOrNull("latestPosition")?.let { position ->
                AuthorityLatestPosition(
                    latitude = position.double("latitude")
                        ?: throw AuthorityProtocolException("latestPosition has no latitude"),
                    longitude = position.double("longitude")
                        ?: throw AuthorityProtocolException("latestPosition has no longitude"),
                    accuracyMeters = position.double("accuracyMeters"),
                    recordedAtMillis = position.long("recordedAtMillis") ?: 0L,
                    receivedAtMillis = position.long("receivedAtMillis") ?: 0L,
                    simulated = position.boolean("simulated") ?: false,
                )
            },
            serverTimeMillis = root.long("serverTimeMillis") ?: 0L,
        )
    }

    /**
     * The officer's register of accounts (`GET /authority/tourists`).
     *
     * A row without a `touristId` is dropped rather than rendered blank: an anonymous row in a list of
     * people the duty officer may have to act on is worse than a shorter list. Every other field
     * degrades to a safe default, because the three claims on the row — registered, tracked, in
     * danger — are already distinct server-side and must not be invented here.
     */
    fun parseTourists(text: String): List<AuthorityTouristRow> {
        val root = Json.parse(text)
        return root.array("tourists").mapNotNull { entry -> entry.toTouristRowOrNull() }
    }

    private fun JsonValue.toTouristRowOrNull(): AuthorityTouristRow? {
        val id = string("touristId") ?: return null
        val position = objectOrNull("latestPosition")
        return AuthorityTouristRow(
            touristId = id,
            displayName = string("displayName") ?: "",
            phone = string("phone"),
            registeredAtMillis = long("registeredAtMillis") ?: long("createdAtMillis") ?: 0L,
            registrationSource = string("registrationSource"),
            registrationStatus = string("registrationStatus") ?: "REGISTERED",
            trackingStatus = string("trackingStatus") ?: "AWAITING_FIRST_REPORT",
            lastSeenMillis = long("lastSeenMillis"),
            hasPosition = boolean("hasPosition") ?: (position != null),
            latitude = position?.double("latitude"),
            longitude = position?.double("longitude"),
            positionRecordedAtMillis = position?.long("recordedAtMillis"),
            positionSimulated = position?.boolean("simulated") ?: false,
            emergencyContactCount = int("emergencyContactCount") ?: 0,
            openSosCount = int("openSosCount") ?: 0,
            latestRiskBand = string("latestRiskBand"),
            state = string("state") ?: "OK",
        )
    }

    /**
     * The published zones (`GET /zones`, `GET /authority/zones`).
     *
     * A zone without an id, or with fewer than three usable vertices, is dropped rather than
     * rendered: a polygon that cannot be evaluated is not a zone, and an empty-name row in the
     * officer's list of closed-off areas is worse than a shorter list. An unrecognised `zoneType` is
     * treated as RESTRICTED — the safe direction, because the failure mode of guessing "safe" is a
     * tourist who is not warned.
     */
    fun parseZones(text: String): List<AuthorityZone> {
        val root = Json.parse(text)
        return root.array("zones").mapNotNull { entry -> entry.toZoneOrNull() }
    }

    /** A single zone, as the mutating routes answer with `{"zone": {...}}`. */
    fun parseZone(text: String): AuthorityZone? =
        Json.parse(text).objectOrNull("zone")?.toZoneOrNull()

    private fun JsonValue.toZoneOrNull(): AuthorityZone? {
        val zoneId = int("zoneId") ?: return null
        val vertices = array("vertices").mapNotNull { vertex ->
            val latitude = vertex.double("latitude") ?: return@mapNotNull null
            val longitude = vertex.double("longitude") ?: return@mapNotNull null
            GeoPoint(latitude, longitude)
        }
        if (vertices.size < 3) return null
        val type = string("zoneType")
        return AuthorityZone(
            zoneId = zoneId,
            name = string("name") ?: "",
            description = string("description") ?: "",
            zoneType = if (type == ZoneType.SAFE || type == ZoneType.DANGEROUS) {
                type
            } else {
                ZoneType.RESTRICTED
            },
            status = string("status") ?: ZoneStatus.INACTIVE,
            revision = int("revision") ?: 1,
            vertices = vertices,
            updatedAtMillis = long("updatedAtMillis") ?: 0L,
        )
    }

    /** The body for `POST /authority/zones` (create) and `PUT /authority/zones/<id>` (edit). */
    fun zoneBody(
        name: String,
        description: String,
        zoneType: String,
        vertices: List<GeoPoint>,
        status: String? = null,
    ): String {
        val ring = vertices.map { point ->
            Json.obj(
                "latitude" to Json.real(point.latitude),
                "longitude" to Json.real(point.longitude),
            )
        }
        val fields = linkedMapOf<String, JsonValue>(
            "name" to Json.str(name),
            "description" to Json.str(description),
            "zoneType" to Json.str(zoneType),
            "vertices" to Json.arr(ring),
        )
        if (status != null) fields["status"] = Json.str(status)
        return Json.write(Json.obj(fields))
    }

    /** The body for `POST /authority/zones/<id>/status`. */
    fun zoneStatusBody(status: String): String =
        Json.write(Json.obj("status" to Json.str(status)))

    /** The body for `POST /authority/sos/<id>/ack`. Only `note` is optional. */
    fun sosAckBody(note: String?): String {
        val trimmed = note?.trim()?.takeIf { it.isNotEmpty() }
        return Json.write(Json.obj("note" to Json.str(trimmed)))
    }

    // ---------------------------------------------------------------- authority console

    /**
     * `GET /authority/overview`.
     *
     * The counts are read field by field with a zero default rather than "all or nothing": a server
     * that adds a counter must not blank the whole dashboard, but a counter it did not send must read
     * as zero rather than as an invented number.
     */
    fun parseOverview(text: String): AuthorityOverview {
        val root = Json.parse(text)
        val counts = root.objectOrNull("counts")
        return AuthorityOverview(
            counts = AuthorityOverviewCounts(
                tourists = counts?.int("tourists") ?: 0,
                events = counts?.int("events") ?: 0,
                eventsLast24h = counts?.int("eventsLast24h") ?: 0,
                sosTotal = counts?.int("sosTotal") ?: 0,
                sosOpen = counts?.int("sosOpen") ?: 0,
                sosAcknowledged = counts?.int("sosAcknowledged") ?: 0,
                zoneAlerts = counts?.int("zoneAlerts") ?: 0,
                emergencyContacts = counts?.int("emergencyContacts") ?: 0,
                riskAssessments = counts?.int("riskAssessments") ?: 0,
                riskElevated = counts?.int("riskElevated") ?: 0,
                touristsWithElevatedRiskLast24h = counts?.int("touristsWithElevatedRiskLast24h") ?: 0,
            ),
            tourists = root.array("tourists").mapNotNull { it.toOverviewTouristOrNull() },
            serverTimeMillis = root.long("serverTimeMillis") ?: 0L,
        )
    }

    private fun JsonValue.toOverviewTouristOrNull(): AuthorityOverviewTourist? {
        val touristId = string("touristId")?.takeIf { it.isNotBlank() } ?: return null
        return AuthorityOverviewTourist(
            touristId = touristId,
            displayName = string("displayName") ?: touristId,
            latitude = double("latitude"),
            longitude = double("longitude"),
            accuracyMeters = double("accuracyMeters"),
            positionRecordedAtMillis = long("positionRecordedAtMillis"),
            lastSeenMillis = long("lastSeenMillis"),
            lastEventAtMillis = long("lastEventAtMillis"),
            openSosCount = int("openSosCount") ?: 0,
            emergencyContactCount = int("emergencyContactCount") ?: 0,
            simulated = boolean("simulated") ?: false,
            state = string("state") ?: "OK",
            latestRiskBand = string("latestRiskBand"),
            latestRiskProbability = double("latestRiskProbability"),
            latestRiskAtMillis = long("latestRiskAtMillis"),
        )
    }

    /**
     * `GET /authority/sos`.
     *
     * A row without an id is dropped: an incident the officer cannot acknowledge is not an incident
     * the app should list as if it could be acted on.
     */
    fun parseSos(text: String): List<AuthoritySosRow> =
        Json.parse(text).array("sos").mapNotNull { it.toSosOrNull() }

    /** The single incident `POST /authority/sos/<id>/ack` answers with. */
    fun parseSosOne(text: String): AuthoritySosRow? =
        Json.parse(text).objectOrNull("sos")?.toSosOrNull()

    private fun JsonValue.toSosOrNull(): AuthoritySosRow? {
        val sosId = long("sosId") ?: return null
        return AuthoritySosRow(
            sosId = sosId,
            serverEventId = string("serverEventId"),
            touristId = string("touristId") ?: "",
            displayName = string("displayName") ?: string("touristId") ?: "",
            latitude = double("latitude"),
            longitude = double("longitude"),
            accuracyMeters = double("accuracyMeters"),
            raisedAtMillis = long("raisedAtMillis") ?: 0L,
            receivedAtMillis = long("receivedAtMillis") ?: 0L,
            status = string("status") ?: "OPEN",
            simulated = boolean("simulated") ?: false,
            acknowledgedAtMillis = long("acknowledgedAtMillis"),
            acknowledgedBy = string("acknowledgedBy"),
            note = string("note"),
        )
    }

    /**
     * `GET /authority/risk`.
     *
     * Kept strictly apart from [parseSos]: a forecast is not an incident, and nothing here is ever
     * turned into an [AuthoritySosRow]. An unrecognised band is preserved verbatim rather than mapped
     * onto "low" — the app does not get to downgrade a forecast it does not understand.
     */
    fun parseRisk(text: String): List<AuthorityRiskRow> =
        Json.parse(text).array("risk").mapNotNull { it.toRiskOrNull() }

    private fun JsonValue.toRiskOrNull(): AuthorityRiskRow? {
        val touristId = string("touristId")?.takeIf { it.isNotBlank() } ?: return null
        val band = string("riskBand")?.takeIf { it.isNotBlank() } ?: return null
        return AuthorityRiskRow(
            touristId = touristId,
            riskBand = band,
            probability = double("probability"),
            horizonSeconds = double("horizonSeconds") ?: 0.0,
            modelVersion = string("modelVersion") ?: "",
            observedSamples = int("observedSamples") ?: 0,
            windowSamples = int("windowSamples") ?: 0,
            dataOk = boolean("dataOk") ?: false,
            dataReason = string("dataReason"),
            factors = array("factors").mapNotNull { factor ->
                val label = factor.string("label") ?: return@mapNotNull null
                AuthorityRiskFactor(label = label, detail = factor.string("detail"))
            },
            assessedAtMillis = long("assessedAtMillis") ?: 0L,
            receivedAtMillis = long("receivedAtMillis") ?: 0L,
            disclaimer = string("disclaimer"),
        )
    }

    /** `GET /authority/events`. A row without a kind is dropped — it cannot be labelled honestly. */
    fun parseEvents(text: String): List<AuthorityEventRow> =
        Json.parse(text).array("events").mapNotNull { entry ->
            val kind = entry.string("kind")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            AuthorityEventRow(
                serverEventId = entry.string("serverEventId"),
                touristId = entry.string("touristId") ?: "",
                kind = kind,
                origin = entry.string("origin"),
                simulated = entry.boolean("simulated") ?: false,
                latitude = entry.double("latitude"),
                longitude = entry.double("longitude"),
                zoneName = entry.string("zoneName"),
                zoneType = entry.string("zoneType"),
                eventType = entry.string("eventType"),
                deviceTimestampMillis = entry.long("deviceTimestampMillis"),
                receivedAtMillis = entry.long("receivedAtMillis") ?: 0L,
            )
        }

    private fun JsonValue.toContactOrNull(): EmergencyContact? {
        val name = string("name") ?: return null
        val phone = string("phone") ?: return null
        return EmergencyContact(
            name = name,
            phone = phone,
            relationship = string("relationship"),
            priority = int("priority") ?: 0,
        )
    }

    /** The human-readable part of an error body, for a `Failed`/`Rejected` reason. */
    fun errorMessage(text: String): String? =
        Json.parseOrNull(text)?.let { it.string("message") ?: it.string("error") }
            ?.takeIf { it.isNotBlank() }

    /** The machine-readable part, e.g. `unauthorized` or `bad_request`. */
    fun errorCode(text: String): String? =
        Json.parseOrNull(text)?.string("error")?.takeIf { it.isNotBlank() }
}
