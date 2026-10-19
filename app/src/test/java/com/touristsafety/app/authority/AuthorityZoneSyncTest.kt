package com.touristsafety.app.authority

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.touristsafety.app.data.database.AppDatabase
import com.touristsafety.app.data.repository.GeoFenceRepository
import com.touristsafety.app.data.repository.SaveZoneResult
import com.touristsafety.app.domain.model.ZoneSource
import com.touristsafety.app.domain.model.ZoneType
import com.touristsafety.app.testing.FakeAuthorityHttp
import com.touristsafety.app.testing.InMemoryAuthoritySessionStore
import com.touristsafety.app.testing.TestGeo
import com.touristsafety.app.testing.inMemoryDatabase
import com.touristsafety.app.testing.testZone
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Shared zone management, from the tourist's side of the wire.
 *
 * The claim this file exists to pin down is the one the whole feature rests on: **an officer's zone
 * reaches a tourist's phone, and the tourist is warned by it through the geofence engine that was
 * already there.** Before the zone-provenance columns existed, a zone could only be created on the
 * phone that drew it, so "the authority published this" was not expressible and every zone was
 * private. These tests check the mechanism end to end — fetch, mirror into `geo_fences`, and read back
 * out of the same repository the tracking pipeline calls on every fix.
 *
 * The other half is the negative space: a zone **this phone drew** must be invisible to the sync. A
 * mirror that could overwrite or delete a local zone would be a feature that silently destroys the
 * user's own work whenever the authority happened to publish something.
 */
@RunWith(AndroidJUnit4::class)
class AuthorityZoneSyncTest {

    private lateinit var database: AppDatabase
    private lateinit var geoFences: GeoFenceRepository

    /** Far enough in the future that a session is never "expired" mid-test. */
    private val future = System.currentTimeMillis() + 3_600_000L

    @Before
    fun setUp() {
        database = inMemoryDatabase()
        geoFences = GeoFenceRepository(database.geoFenceDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    // ------------------------------------------------------------ fixtures

    private fun zoneBody(
        zoneId: Int = 7,
        name: String = "Old quarry",
        description: String = "Steep sides and no fence.",
        zoneType: String = "RESTRICTED",
        revision: Int = 1,
        vertices: List<com.touristsafety.app.domain.model.GeoPoint> = TestGeo.squareVertices,
        updatedAtMillis: Long = 1_700_000_000_000L,
    ): String {
        val ring = vertices.joinToString(",") { point ->
            """{"latitude":${point.latitude},"longitude":${point.longitude}}"""
        }
        return """{"zoneId":$zoneId,"name":"$name","description":"$description",
            "zoneType":"$zoneType","status":"ACTIVE","revision":$revision,
            "vertexCount":${vertices.size},"updatedAtMillis":$updatedAtMillis,
            "vertices":[$ring]}""".trimIndent().replace("\n", "")
    }

    private fun zonesResponse(vararg bodies: String): String =
        """{"zones":[${bodies.joinToString(",")}],"serverTimeMillis":1730000000000}"""

    private fun touristLogin() = AuthorityLoginResult(
        token = "tourist-token",
        role = "tourist",
        subjectId = "T-1001",
        displayName = "Demo Tourist",
        expiresAtMillis = future,
        serverTimeMillis = 1_730_000_000_000L,
    )

    private class Harness(
        val sync: AuthorityZoneSync,
        val http: FakeAuthorityHttp,
        val store: InMemoryAuthoritySessionStore,
    )

    private fun harness(
        signedIn: Boolean = true,
        http: FakeAuthorityHttp = FakeAuthorityHttp(),
    ): Harness {
        val store = InMemoryAuthoritySessionStore(
            AuthoritySession(baseUrl = "192.168.1.20:8080"),
        )
        if (signedIn) {
            runBlocking { store.saveLogin("192.168.1.20:8080", touristLogin()) }
        }
        val client = AuthorityClient(baseUrl = { store.current().baseUrl }, http = http)
        return Harness(
            sync = AuthorityZoneSync(sessions = store, client = client, geoFences = geoFences),
            http = http,
            store = store,
        )
    }

    private fun publishedZones() = runBlocking {
        geoFences.getAllZones().filter { it.source == ZoneSource.AUTHORITY }
    }

    // ------------------------------------------------------------ the mechanism

    @Test
    fun `a published zone becomes a fence the tracking engine already reads`() = runBlocking {
        val http = FakeAuthorityHttp().apply {
            respondWith { AuthorityHttpResponseOk(zonesResponse(zoneBody())) }
        }
        val harness = harness(http = http)

        val outcome = harness.sync.sync()

        assertEquals(ZoneSyncOutcome.Applied::class.java, outcome.javaClass)
        assertEquals(1, (outcome as ZoneSyncOutcome.Applied).published)

        // The zone is read back through the *enabled* query — the exact call
        // `TrackingController` makes on every fix. If this list is empty, the tourist is not warned,
        // however green the rest of the feature looks.
        val enabled = geoFences.getEnabledZones()
        assertEquals(1, enabled.size)
        val zone = enabled.single()
        assertEquals("Old quarry", zone.name)
        assertEquals(ZoneType.RESTRICTED, zone.type)
        assertEquals(ZoneSource.AUTHORITY, zone.source)
        assertEquals(7, zone.authorityZoneId)
        assertEquals(1, zone.authorityRevision)
        assertTrue(zone.enabled)
        assertTrue(zone.hasEnoughVertices)
        assertEquals(TestGeo.squareVertices, zone.vertices)
    }

    @Test
    fun `the fetch uses the tourist token against the read-only zone route`() = runBlocking {
        val http = FakeAuthorityHttp().apply {
            respondWith { AuthorityHttpResponseOk(zonesResponse(zoneBody())) }
        }
        val harness = harness(http = http)

        harness.sync.sync()

        val request = http.requests.single()
        assertEquals("GET", request.method)
        assertTrue(
            "the tourist's phone must read the read route, never a management route: ${request.url}",
            request.url.endsWith("/api/v1/zones"),
        )
        assertFalse(
            "the read route and the write route are different paths",
            request.url.contains("/authority/zones"),
        )
        assertEquals("tourist-token", request.bearerToken)
    }

    @Test
    fun `a second sync of an unchanged zone writes nothing`() = runBlocking {
        val http = FakeAuthorityHttp().apply {
            respondWith { AuthorityHttpResponseOk(zonesResponse(zoneBody())) }
        }
        val harness = harness(http = http)

        harness.sync.sync()
        val firstId = publishedZones().single().id
        val second = harness.sync.sync()

        val applied = second as ZoneSyncOutcome.Applied
        assertTrue("an unchanged zone must not be rewritten", applied.result.isEmpty)
        assertEquals("and must keep its row, not be deleted and re-inserted", firstId, publishedZones().single().id)
    }

    @Test
    fun `a new revision replaces the polygon rather than sitting beside it`() = runBlocking {
        val http = FakeAuthorityHttp()
        val harness = harness(http = http)
        val moved = TestGeo.overlappingSquare(north = 0.0, east = 50.0)

        http.respondWith { AuthorityHttpResponseOk(zonesResponse(zoneBody(revision = 1))) }
        harness.sync.sync()

        http.respondWith {
            AuthorityHttpResponseOk(zonesResponse(zoneBody(revision = 2, vertices = moved)))
        }
        val second = harness.sync.sync()

        assertEquals(1, (second as ZoneSyncOutcome.Applied).result.updated)
        val zones = publishedZones()
        assertEquals("the same zone must not become two zones", 1, zones.size)
        assertEquals(2, zones.single().authorityRevision)
        assertEquals("the authority's new geometry must be in effect", moved, zones.single().vertices)
    }

    @Test
    fun `a withdrawn zone stops being evaluated`() = runBlocking {
        val http = FakeAuthorityHttp()
        val harness = harness(http = http)

        http.respondWith { AuthorityHttpResponseOk(zonesResponse(zoneBody())) }
        harness.sync.sync()
        assertEquals(1, geoFences.getEnabledZones().size)

        // The officer de-activated or archived it, so it is no longer in `GET /zones`.
        http.respondWith { AuthorityHttpResponseOk(zonesResponse()) }
        val second = harness.sync.sync()

        assertEquals(1, (second as ZoneSyncOutcome.Applied).result.withdrawn)
        assertTrue(
            "a boundary the authority no longer publishes must stop warning people",
            geoFences.getEnabledZones().isEmpty(),
        )
        assertTrue(publishedZones().isEmpty())
    }

    // ------------------------------------------------------------ a local zone is not the sync's business

    @Test
    fun `a zone drawn on this phone is never read, written or removed by the sync`() = runBlocking {
        val local = geoFences.create(
            testZone(id = 0, type = ZoneType.DANGEROUS, name = "Only mine"),
        ) as SaveZoneResult.Saved
        val before = geoFences.getZone(local.id)!!

        val http = FakeAuthorityHttp().apply {
            respondWith { AuthorityHttpResponseOk(zonesResponse(zoneBody())) }
        }
        val harness = harness(http = http)

        harness.sync.sync()

        // Both kinds of zone are visible to the engine, and only one of them came from the authority:
        // the tourist is warned by the published boundary *and* still has their own.
        val afterFirstSync = geoFences.getEnabledZones()
        assertEquals(2, afterFirstSync.size)
        assertEquals(
            setOf(ZoneSource.LOCAL, ZoneSource.AUTHORITY),
            afterFirstSync.map { it.source }.toSet(),
        )

        // Now the dangerous path: the authority publishes nothing at all. The mirror is withdrawn, and
        // the zone drawn on this phone is the thing that must survive it.
        http.respondWith { AuthorityHttpResponseOk(zonesResponse()) }
        harness.sync.sync()

        val after = geoFences.getZone(local.id)
        assertNotNull("the tourist's own zone must still exist", after)
        assertEquals("and be bit-for-bit unchanged", before, after)
        assertEquals(ZoneSource.LOCAL, after!!.source)
        assertNull(after.authorityZoneId)

        val enabled = geoFences.getEnabledZones()
        assertEquals("only the local zone is left", 1, enabled.size)
        assertEquals(ZoneSource.LOCAL, enabled.single().source)
        assertTrue(publishedZones().isEmpty())
    }

    @Test
    fun `a local zone that happens to have the authority's id is not adopted`() = runBlocking {
        // A local zone numbered 7 exists; the authority also publishes zone 7. Nothing may be conflated:
        // the local row must not be updated, and a separate mirrored row must be created instead.
        val local = geoFences.create(testZone(id = 0, name = "Local seven")) as SaveZoneResult.Saved
        val http = FakeAuthorityHttp().apply {
            respondWith { AuthorityHttpResponseOk(zonesResponse(zoneBody(zoneId = 7))) }
        }
        val harness = harness(http = http)

        harness.sync.sync()

        assertEquals("Local seven", geoFences.getZone(local.id)!!.name)
        assertEquals(ZoneSource.LOCAL, geoFences.getZone(local.id)!!.source)
        val mirrored = publishedZones()
        assertEquals(1, mirrored.size)
        assertEquals(7, mirrored.single().authorityZoneId)
        assertTrue("the mirror is its own row", mirrored.single().id != local.id)
    }

    /**
     * "Delete my zones" must mean the tourist's own zones.
     *
     * The tourist-facing list offers one bulk delete. If it removed the authority's mirrors too, the
     * button would look broken: the next sync would put them back, and a user who thought they had
     * cleared a restricted area would find it warning them again a few minutes later. It is the
     * *authority* that withdraws a published zone, and that path is
     * [com.touristsafety.app.data.repository.GeoFenceRepository.replaceAuthorityZones].
     */
    @Test
    fun `deleting the tourist's own zones leaves the authority's mirrors in place`() = runBlocking {
        geoFences.create(testZone(id = 0, name = "Mine A"))
        geoFences.create(testZone(id = 0, name = "Mine B"))
        val http = FakeAuthorityHttp().apply {
            respondWith { AuthorityHttpResponseOk(zonesResponse(zoneBody(), zoneBody(zoneId = 8, name = "Cliff"))) }
        }
        val harness = harness(http = http)
        harness.sync.sync()
        assertEquals(4, geoFences.getAllZones().size)

        val deleted = geoFences.deleteLocalZones()

        assertEquals("only the two local zones", 2, deleted)
        val remaining = geoFences.getAllZones()
        assertEquals(2, remaining.size)
        assertTrue(
            "the authority's zones must survive a local bulk delete",
            remaining.all { it.source == ZoneSource.AUTHORITY },
        )
        assertEquals(setOf("Cliff", "Old quarry"), remaining.map { it.name }.toSet())

        // And the mirror is still intact enough for the sync to be a no-op afterwards.
        val again = harness.sync.sync() as ZoneSyncOutcome.Applied
        assertTrue("nothing should need re-writing", again.result.isEmpty)
    }

    // ------------------------------------------------------------ failure is never a silent wipe
    @Test
    fun `a failed fetch leaves the mirror exactly as it was and says so`() = runBlocking {
        val http = FakeAuthorityHttp().apply {
            respondWith { AuthorityHttpResponseOk(zonesResponse(zoneBody())) }
        }
        val harness = harness(http = http)
        harness.sync.sync()
        val before = publishedZones()

        http.failure = java.io.IOException("Connection refused")
        val outcome = harness.sync.sync()

        assertTrue(outcome is ZoneSyncOutcome.Failed)
        assertEquals("a network blip must not withdraw a zone", before, publishedZones())
        assertEquals(1, geoFences.getEnabledZones().size)
        assertNotNull("and the failure must be reported", harness.sync.state.value.lastError)
        assertEquals(
            "the last success must still be remembered",
            true,
            harness.sync.state.value.hasEverSucceeded,
        )
    }

    @Test
    fun `a refused token reports the refusal without touching the stored zones`() = runBlocking {
        val http = FakeAuthorityHttp().apply {
            respondWith { AuthorityHttpResponseOk(zonesResponse(zoneBody())) }
        }
        val harness = harness(http = http)
        harness.sync.sync()
        val before = publishedZones()

        http.respondWith {
            AuthorityHttpResponse(
                status = 401,
                body = """{"error":"unauthorized","message":"Token expired."}""",
            )
        }
        val outcome = harness.sync.sync()

        assertTrue(outcome is ZoneSyncOutcome.Failed)
        assertTrue(
            "the refusal must be named, was ${(outcome as ZoneSyncOutcome.Failed).message}",
            outcome.message.contains("401"),
        )
        assertEquals(before, publishedZones())
    }

    @Test
    fun `signed out, the sync makes no request and keeps the zones already downloaded`() = runBlocking {
        val http = FakeAuthorityHttp().apply {
            respondWith { AuthorityHttpResponseOk(zonesResponse(zoneBody())) }
        }
        val harness = harness(signedIn = false, http = http)

        val outcome = harness.sync.sync()

        assertTrue(outcome is ZoneSyncOutcome.SignedOut)
        assertTrue("no session, no request", http.requests.isEmpty())
        assertTrue(publishedZones().isEmpty())
    }

    // ------------------------------------------------------------ a bad polygon is refused, not fenced

    @Test
    fun `a self-intersecting polygon from the authority is rejected rather than stored`() = runBlocking {
        val http = FakeAuthorityHttp().apply {
            respondWith {
                AuthorityHttpResponseOk(zonesResponse(zoneBody(vertices = TestGeo.bowtie())))
            }
        }
        val harness = harness(http = http)

        val outcome = harness.sync.sync() as ZoneSyncOutcome.Applied

        assertEquals(1, outcome.result.rejected)
        assertEquals(0, outcome.result.inserted)
        assertTrue(
            "a bow-tie ring must never become a fence the engine evaluates",
            geoFences.getAllZones().isEmpty(),
        )
    }

    @Test
    fun `a publication that removes a broken polygon also withdraws it`() = runBlocking {
        // The zone is good, then the authority edits it into a ring this build refuses. It must not
        // keep warning people with the stale good polygon, and it must not be kept as a fence either.
        val http = FakeAuthorityHttp()
        val harness = harness(http = http)

        http.respondWith { AuthorityHttpResponseOk(zonesResponse(zoneBody(revision = 1))) }
        harness.sync.sync()
        assertEquals(1, geoFences.getEnabledZones().size)

        http.respondWith {
            AuthorityHttpResponseOk(zonesResponse(zoneBody(revision = 2, vertices = TestGeo.bowtie())))
        }
        val second = harness.sync.sync() as ZoneSyncOutcome.Applied

        assertEquals(1, second.result.rejected)
        assertEquals(0, publishedZones().size)
        assertTrue(geoFences.getEnabledZones().isEmpty())
    }

    // ------------------------------------------------------------ state reporting

    @Test
    fun `the sync state reports what actually happened`() = runBlocking {
        val http = FakeAuthorityHttp().apply {
            respondWith { AuthorityHttpResponseOk(zonesResponse(zoneBody())) }
        }
        val harness = harness(http = http)

        val state = harness.sync.state.value
        assertNull(state.lastAttemptAtMillis)
        assertFalse(state.inFlight)

        harness.sync.sync()

        val after = harness.sync.state.value
        assertNotNull(after.lastAttemptAtMillis)
        assertNotNull(after.lastSuccessAtMillis)
        assertEquals(1, after.publishedCount)
        assertNull(after.lastError)
        assertFalse(after.inFlight)
        assertEquals(1, after.lastResult?.inserted)
    }

}

/**
 * Local alias so the responder lambdas read as an expression rather than a constructor call.
 *
 * A top-level function rather than a member: inside `FakeAuthorityHttp().apply { respondWith { … } }`
 * the implicit receiver is the fake, and a member helper would be resolved through a chain of receiver
 * scopes that is easy to get wrong.
 */
private fun AuthorityHttpResponseOk(body: String): AuthorityHttpResponse =
    AuthorityHttpResponse(status = 200, body = body)
