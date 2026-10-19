package com.touristsafety.app.authority

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The authority console's wire contract, decided on the plain JVM.
 *
 * The JSON here is copied from real `GET /authority/overview`, `/authority/sos`, `/authority/risk` and
 * `/authority/events` responses — field for field, including the ones this app currently ignores — so
 * these tests fail if the server's shape changes in a way the app would misread.
 *
 * The two promises pinned down here are the console's honesty rules. A counter the server did not send
 * reads as zero rather than as an invented number, and a **forecast never becomes an incident**: the
 * risk parser has no path that produces an [AuthoritySosRow], and a row whose band is unrecognised keeps
 * the band's own text instead of being downgraded to something safer-sounding.
 */
class AuthorityConsoleApiTest {

    // ------------------------------------------------------------ overview

    private val overviewBody = """
        {
          "counts": {
            "tourists": 5, "events": 27, "eventsLast24h": 27,
            "sosTotal": 6, "sosOpen": 2, "sosAcknowledged": 4,
            "zoneAlerts": 1, "emergencyContacts": 2,
            "riskAssessments": 11, "riskElevated": 9,
            "touristsWithElevatedRiskLast24h": 3
          },
          "tourists": [
            {
              "touristId": "T-1001", "displayName": "Demo Tourist",
              "latitude": 12.9716, "longitude": 77.5946, "accuracyMeters": 5.0,
              "positionRecordedAtMillis": 1730000000000, "lastSeenMillis": 1730000001000,
              "lastEventAtMillis": 1730000002000, "openSosCount": 1,
              "emergencyContactCount": 1, "simulated": false, "state": "SOS",
              "latestRiskBand": "HIGH", "latestRiskProbability": 0.83,
              "latestRiskAtMillis": 1730000003000
            },
            {
              "touristId": "T-1002", "displayName": "Never Reported",
              "latitude": null, "longitude": null, "accuracyMeters": null,
              "positionRecordedAtMillis": null, "lastSeenMillis": null,
              "lastEventAtMillis": null, "openSosCount": 0,
              "emergencyContactCount": 0, "simulated": false, "state": "STALE",
              "latestRiskBand": null, "latestRiskProbability": null,
              "latestRiskAtMillis": null
            }
          ],
          "serverTimeMillis": 1730000010000
        }
    """.trimIndent()

    @Test
    fun `every overview counter is read as the server counted it`() {
        val overview = AuthorityApi.parseOverview(overviewBody)

        assertEquals(5, overview.counts.tourists)
        assertEquals(27, overview.counts.events)
        assertEquals(27, overview.counts.eventsLast24h)
        assertEquals(6, overview.counts.sosTotal)
        assertEquals(2, overview.counts.sosOpen)
        assertEquals(4, overview.counts.sosAcknowledged)
        assertEquals(1, overview.counts.zoneAlerts)
        assertEquals(2, overview.counts.emergencyContacts)
        assertEquals(11, overview.counts.riskAssessments)
        assertEquals(9, overview.counts.riskElevated)
        assertEquals(3, overview.counts.touristsWithElevatedRiskLast24h)
        assertEquals(1_730_000_010_000L, overview.serverTimeMillis)
    }

    @Test
    fun `a tourist who has never reported has no position rather than a point at zero`() {
        val overview = AuthorityApi.parseOverview(overviewBody)

        val reporting = overview.tourists.first()
        assertEquals("T-1001", reporting.touristId)
        assertTrue(reporting.hasPosition)
        assertEquals(12.9716, reporting.latitude!!, 1e-9)
        assertEquals(1, reporting.openSosCount)
        assertEquals("SOS", reporting.state)
        assertEquals("HIGH", reporting.latestRiskBand)
        assertTrue(!reporting.simulated)

        val silent = overview.tourists[1]
        assertEquals("T-1002", silent.touristId)
        assertTrue("(0, 0) would be an invention", !silent.hasPosition)
        assertNull(silent.positionRecordedAtMillis)
        assertNull(silent.lastSeenMillis)
        assertNull(silent.latestRiskBand)
    }

    @Test
    fun `a counter the server did not send reads as zero, not as a guess`() {
        val overview = AuthorityApi.parseOverview("""{"tourists":[],"serverTimeMillis":1}""")
        assertEquals(0, overview.counts.tourists)
        assertEquals(0, overview.counts.sosOpen)
        assertEquals(0, overview.counts.touristsWithElevatedRiskLast24h)
        assertTrue(overview.tourists.isEmpty())
    }

    @Test
    fun `an overview row without a tourist id is dropped rather than keyed on nothing`() {
        val body = """
            {"tourists":[{"displayName":"Anonymous","state":"OK"}],"serverTimeMillis":1}
        """.trimIndent()
        assertTrue(AuthorityApi.parseOverview(body).tourists.isEmpty())
    }

    // ------------------------------------------------------------ incidents

    private val sosBody = """
        {
          "sos": [
            {
              "sosId": 6, "serverEventId": "srv-6", "touristId": "T-1001",
              "displayName": "Demo Tourist", "latitude": 12.9716, "longitude": 77.5946,
              "accuracyMeters": 5.0, "raisedAtMillis": 1730000000000,
              "receivedAtMillis": 1730000000500, "status": "OPEN", "simulated": false,
              "acknowledgedAtMillis": null, "acknowledgedBy": null, "note": null
            },
            {
              "sosId": 5, "serverEventId": "srv-5", "touristId": "T-1001",
              "displayName": "Demo Tourist", "latitude": 12.9, "longitude": 77.5,
              "accuracyMeters": 8.0, "raisedAtMillis": 1729999000000,
              "receivedAtMillis": 1729999000100, "status": "ACKNOWLEDGED", "simulated": true,
              "acknowledgedAtMillis": 1729999005000, "acknowledgedBy": "A-01",
              "note": "Called the tourist."
            }
          ],
          "serverTimeMillis": 1730000010000
        }
    """.trimIndent()

    @Test
    fun `an incident keeps its status, its acknowledger and its simulation flag`() {
        val rows = AuthorityApi.parseSos(sosBody)

        assertEquals(2, rows.size)
        val open = rows.first()
        assertEquals(6L, open.sosId)
        assertTrue(open.isOpen)
        assertNull("an open incident has no acknowledger", open.acknowledgedBy)
        assertTrue(open.latitude != null)

        val acknowledged = rows[1]
        assertEquals("ACKNOWLEDGED", acknowledged.status)
        assertTrue(!acknowledged.isOpen)
        assertEquals("A-01", acknowledged.acknowledgedBy)
        assertEquals(1_729_999_005_000L, acknowledged.acknowledgedAtMillis)
        assertEquals("Called the tourist.", acknowledged.note)
        assertTrue("a simulated incident must stay marked as one", acknowledged.simulated)
    }

    @Test
    fun `an incident without an id is dropped, because it could not be acknowledged`() {
        val body = """
            {"sos":[{"touristId":"T-1001","status":"OPEN","raisedAtMillis":1}],"serverTimeMillis":1}
        """.trimIndent()
        assertTrue(AuthorityApi.parseSos(body).isEmpty())
    }

    @Test
    fun `the acknowledgement reply is read from its incident object`() {
        val body = """
            {"sos":{"sosId":6,"touristId":"T-1001","status":"ACKNOWLEDGED","raisedAtMillis":1,
            "receivedAtMillis":2,"acknowledgedAtMillis":3,"acknowledgedBy":"A-01",
            "note":"On our way.","latitude":12.97,"longitude":77.59,"simulated":false}}
        """.trimIndent()

        val row = AuthorityApi.parseSosOne(body)

        assertEquals(6L, row!!.sosId)
        assertEquals("ACKNOWLEDGED", row.status)
        assertEquals("A-01", row.acknowledgedBy)
        assertEquals("On our way.", row.note)
    }

    @Test
    fun `an acknowledgement body carries the note and nothing that could claim a name`() {
        val withNote = AuthorityApi.sosAckBody("  Dispatching a ranger.  ")
        assertTrue(withNote.contains("Dispatching a ranger."))
        assertTrue(!withNote.contains("acknowledgedBy"))
        assertTrue(!withNote.contains("A-01"))

        // A blank note is no note at all — not an empty string the server would store as one.
        assertEquals("{\"note\":null}", AuthorityApi.sosAckBody("   "))
        assertEquals("{\"note\":null}", AuthorityApi.sosAckBody(null))
    }

    // ------------------------------------------------------------ forecasts

    @Test
    fun `a forecast keeps its band, probability, horizon and factors`() {
        val body = """
            {
              "risk": [
                {
                  "kind": "PREDICTION", "touristId": "T-1001",
                  "assessedAtMillis": 1730000000000, "receivedAtMillis": 1730000000500,
                  "riskBand": "HIGH", "probability": 0.83, "horizonSeconds": 45.0,
                  "modelVersion": "fall-risk-1.2", "observedSamples": 64, "windowSamples": 80,
                  "dataOk": true, "dataReason": null,
                  "factors": [
                    {"label": "Vertical impact", "detail": "peak 3.1 g"},
                    {"label": "Post-impact stillness", "detail": "no step for 12 s"}
                  ],
                  "disclaimer": "A trained on-device model's forecast..."
                }
              ],
              "serverTimeMillis": 1730000010000
            }
        """.trimIndent()

        val rows = AuthorityApi.parseRisk(body)

        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals("T-1001", row.touristId)
        assertEquals("HIGH", row.riskBand)
        assertEquals(0.83, row.probability!!, 1e-9)
        assertEquals(45.0, row.horizonSeconds, 1e-9)
        assertEquals("fall-risk-1.2", row.modelVersion)
        assertEquals(64, row.observedSamples)
        assertEquals(80, row.windowSamples)
        assertTrue(row.dataOk)
        assertEquals(2, row.factors.size)
        assertEquals("Vertical impact", row.factors.first().label)
        assertEquals("peak 3.1 g", row.factors.first().detail)
        assertNull(row.dataReason)
    }

    @Test
    fun `a forecast the model could not make is not turned into a low risk`() {
        val body = """
            {"risk":[{"touristId":"T-1001","riskBand":"LOW","probability":null,
            "horizonSeconds":45.0,"modelVersion":"m","observedSamples":2,"windowSamples":80,
            "dataOk":false,"dataReason":"not enough samples","factors":[],
            "assessedAtMillis":1,"receivedAtMillis":2,"kind":"PREDICTION"}],"serverTimeMillis":3}
        """.trimIndent()

        val row = AuthorityApi.parseRisk(body).single()

        assertTrue(!row.dataOk)
        assertNull("a missing probability is not 0.0, which would read as 'no risk'", row.probability)
        assertEquals("not enough samples", row.dataReason)
    }

    @Test
    fun `an unrecognised risk band is preserved verbatim rather than mapped to low`() {
        val body = """
            {"risk":[{"touristId":"T-1001","riskBand":"SEVERE","probability":0.95,
            "horizonSeconds":30.0,"modelVersion":"m","observedSamples":9,"windowSamples":9,
            "dataOk":true,"factors":[],"assessedAtMillis":1,"receivedAtMillis":2}],
            "serverTimeMillis":3}
        """.trimIndent()

        assertEquals("SEVERE", AuthorityApi.parseRisk(body).single().riskBand)
    }

    @Test
    fun `a forecast row without a band is dropped rather than shown as unknown`() {
        val body = """{"risk":[{"touristId":"T-1001","probability":0.5}],"serverTimeMillis":1}"""
        assertTrue(AuthorityApi.parseRisk(body).isEmpty())
    }

    @Test
    fun `the risk parser cannot produce an incident`() {
        // Both parsers are given the same shape and each reads only its own key, so a forecast served
        // by mistake on the incident route is an empty list — never a phantom SOS.
        val body = """{"risk":[{"touristId":"T-1","riskBand":"HIGH","probability":0.9}],"serverTimeMillis":1}"""
        assertTrue(AuthorityApi.parseSos(body).isEmpty())
    }

    // ------------------------------------------------------------ event feed

    @Test
    fun `an event keeps its kind, zone and received time`() {
        val body = """
            {"events":[
              {"serverEventId":"e-1","touristId":"T-1001","dedupeKey":"k","kind":"ZONE_ALERT",
               "origin":"GPS","simulated":false,"latitude":12.97,"longitude":77.59,
               "accuracyMeters":5.0,"zoneId":7,"zoneName":"Old quarry","zoneType":"RESTRICTED",
               "eventType":"ENTRY","deviceTimestampMillis":1730000000000,"receivedAtMillis":1730000000100}
            ],"serverTimeMillis":1730000010000}
        """.trimIndent()

        val row = AuthorityApi.parseEvents(body).single()

        assertEquals("ZONE_ALERT", row.kind)
        assertEquals("T-1001", row.touristId)
        assertEquals("Old quarry", row.zoneName)
        assertEquals("RESTRICTED", row.zoneType)
        assertEquals("ENTRY", row.eventType)
        assertEquals(1_730_000_000_100L, row.receivedAtMillis)
        assertTrue(!row.simulated)
    }

    @Test
    fun `an event without a kind is dropped, because it cannot be labelled honestly`() {
        val body = """{"events":[{"touristId":"T-1","receivedAtMillis":1}],"serverTimeMillis":1}"""
        assertTrue(AuthorityApi.parseEvents(body).isEmpty())
    }

    // ------------------------------------------------------------ endpoints

    @Test
    fun `the console endpoints point at the authority routes`() {
        assertEquals("/api/v1/authority/overview", AuthorityEndpoints.AUTHORITY_OVERVIEW)
        assertEquals("/api/v1/authority/tourists", AuthorityEndpoints.AUTHORITY_TOURISTS)
        assertEquals("/api/v1/authority/sos", AuthorityEndpoints.AUTHORITY_SOS)
        assertEquals("/api/v1/authority/sos/6/ack", AuthorityEndpoints.authoritySosAckPath(6))
        assertEquals("/api/v1/authority/risk", AuthorityEndpoints.AUTHORITY_RISK)
        assertEquals("/api/v1/authority/events", AuthorityEndpoints.AUTHORITY_EVENTS)

        // Every write path is under /authority; the tourist's read route is a different path.
        assertTrue(AuthorityEndpoints.authoritySosAckPath(6) != AuthorityEndpoints.ZONES)
        assertTrue(AuthorityEndpoints.AUTHORITY_RISK.startsWith("/api/v1/authority/"))
        assertTrue(AuthorityEndpoints.AUTHORITY_EVENTS.startsWith("/api/v1/authority/"))
    }

    @Test
    fun `a malformed body is a parse error rather than a silently empty console`() {
        try {
            AuthorityApi.parseOverview("not json at all")
            throw AssertionError("a body that is not JSON must not read as an empty overview")
        } catch (expected: JsonParseException) {
            assertTrue(expected.message!!.isNotBlank())
        }
    }
}
