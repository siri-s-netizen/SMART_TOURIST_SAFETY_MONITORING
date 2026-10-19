package com.touristsafety.app.ui.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.touristsafety.app.authority.AuthorityClient
import com.touristsafety.app.authority.AuthorityHttpRequest
import com.touristsafety.app.authority.AuthorityHttpResponse
import com.touristsafety.app.authority.AuthorityLoginResult
import com.touristsafety.app.authority.AuthoritySession
import com.touristsafety.app.authority.ZoneStatus
import com.touristsafety.app.domain.model.ZoneType
import com.touristsafety.app.testing.FakeAuthorityHttp
import com.touristsafety.app.testing.InMemoryAuthoritySessionStore
import com.touristsafety.app.testing.MainDispatcherRule
import com.touristsafety.app.testing.TestGeo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drawing a restricted area and publishing it **to the authority**.
 *
 * The editor is the one place in the tourist app that could plausibly have been written as "save it
 * locally and hope", so what is pinned down here is that it cannot: a save produces a `POST` or a
 * `PUT` against `/authority/zones`, the shape the backend validates is exactly what goes on the wire,
 * and nothing reports success until the server has answered.
 *
 * The local-only editor still exists, untouched, on its own route — this class is about the shared one.
 */
@RunWith(AndroidJUnit4::class)
class AuthorityZoneEditorViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val future = System.currentTimeMillis() + 3_600_000L

    private val publishedZone = """
        {"zoneId":7,"name":"Old quarry","description":"Steep sides.","zoneType":"RESTRICTED",
        "status":"ACTIVE","revision":3,"vertexCount":4,"updatedAtMillis":1700000000000,
        "vertices":[{"latitude":12.9716,"longitude":77.5946},{"latitude":12.9734,"longitude":77.5946},
                    {"latitude":12.9734,"longitude":77.5974},{"latitude":12.9716,"longitude":77.5974}]}
    """.trimIndent().replace("\n", "")

    private fun officerLogin() = AuthorityLoginResult(
        token = "officer-token",
        role = "authority",
        subjectId = "A-01",
        displayName = "Duty Officer",
        expiresAtMillis = future,
        serverTimeMillis = 1_730_000_000_000L,
    )

    private fun http(zoneStatus: Int = 200, zoneBody: String = publishedZone): FakeAuthorityHttp {
        val http = FakeAuthorityHttp()
        http.respondWith { request: AuthorityHttpRequest ->
            when {
                request.method == "POST" || request.method == "PUT" ->
                    if (zoneStatus == 200) {
                        AuthorityHttpResponse(200, """{"zone":$zoneBody}""")
                    } else {
                        AuthorityHttpResponse(
                            zoneStatus,
                            """{"error":"server_error","message":"boom"}""",
                        )
                    }

                request.url.contains("/authority/zones/") ->
                    AuthorityHttpResponse(200, """{"zone":$zoneBody,"history":[]}""")

                request.url.endsWith("/api/v1/authority/zones") ->
                    AuthorityHttpResponse(200, """{"zones":[$publishedZone],"serverTimeMillis":1}""")

                else -> AuthorityHttpResponse(404, """{"error":"not_found"}""")
            }
        }
        return http
    }

    private fun viewModel(
        http: FakeAuthorityHttp,
        editingZoneId: Int? = null,
        signedIn: Boolean = true,
    ): Pair<AuthorityZoneEditorViewModel, InMemoryAuthoritySessionStore> {
        val store = InMemoryAuthoritySessionStore(
            AuthoritySession(baseUrl = "192.168.1.20:8080"),
        )
        if (signedIn) {
            runBlocking { store.saveOfficerLogin("192.168.1.20:8080", officerLogin()) }
        }
        val client = AuthorityClient(baseUrl = { store.current().baseUrl }, http = http)
        return AuthorityZoneEditorViewModel(store, client, editingZoneId) to store
    }

    private fun AuthorityZoneEditorViewModel.drawSquare() {
        TestGeo.squareVertices.forEach { vertex -> addVertex(vertex) }
    }

    /**
     * The requests that could change the authority's data.
     *
     * Construction also performs a read — the edited zone, and the other zones for map context — so
     * "nothing was published" has to mean "no POST and no PUT", not "no request at all". Filtering by
     * method says that precisely instead of counting.
     */
    private fun FakeAuthorityHttp.writes(): List<AuthorityHttpRequest> =
        requests.filter { it.method == "POST" || it.method == "PUT" }

    // ------------------------------------------------------------ create

    @Test
    fun `publishing a new zone posts the polygon the backend validates`() {
        val http = http()
        val (viewModel, _) = viewModel(http)

        viewModel.setName("Old quarry")
        viewModel.setDescription("Steep sides and no fence.")
        viewModel.setType(ZoneType.DANGEROUS)
        viewModel.drawSquare()
        viewModel.closeRing()
        viewModel.save()

        val post = http.writes().single()
        assertEquals("POST", post.method)
        assertTrue(post.url.endsWith("/api/v1/authority/zones"))
        assertEquals("officer-token", post.bearerToken)
        assertTrue(post.body!!.contains("\"name\":\"Old quarry\""))
        assertTrue(post.body!!.contains("\"zoneType\":\"DANGEROUS\""))
        assertTrue("named latitude, not a GeoJSON pair", post.body!!.contains("\"latitude\""))
        assertTrue(post.body!!.contains("\"longitude\""))
        assertTrue("the polygon travels with the zone", post.body!!.contains("\"vertices\""))
        assertTrue(
            "a published zone is on by default",
            post.body!!.contains("\"status\":\"${ZoneStatus.ACTIVE}\""),
        )

        // Success is only reported from the server's own reply.
        assertEquals(7, viewModel.savedZone.value!!.zoneId)
        assertEquals(3, viewModel.revision.value)
    }

    @Test
    fun `a zone with too few corners is refused before anything is sent`() {
        val http = http()
        val (viewModel, _) = viewModel(http)

        viewModel.setName("Half drawn")
        viewModel.addVertex(TestGeo.origin)
        viewModel.addVertex(TestGeo.at(TestGeo.origin, 50.0, 0.0))
        viewModel.save()

        assertTrue(http.writes().isEmpty())
        assertNull("nothing was published", viewModel.savedZone.value)
        assertTrue(
            "the refusal must say what is wrong, was ${viewModel.message.value}",
            viewModel.message.value!!.contains("vertex", ignoreCase = true),
        )
    }

    @Test
    fun `a zone with no name is refused before anything is sent`() {
        val http = http()
        val (viewModel, _) = viewModel(http)

        viewModel.drawSquare()
        viewModel.save()

        assertTrue(http.writes().isEmpty())
        assertTrue(viewModel.message.value!!.contains("name"))

        viewModel.setName("Named now")
        viewModel.save()
        assertEquals(1, http.writes().size)
    }

    @Test
    fun `a corner placed on top of the previous one is refused, not dropped silently`() {
        val http = http()
        val (viewModel, _) = viewModel(http)

        viewModel.setName("Duplicate")
        viewModel.addVertex(TestGeo.origin)
        val before = viewModel.draft.value.vertexCount
        viewModel.addVertex(TestGeo.origin.copy(latitude = TestGeo.origin.latitude + 1e-9))

        assertEquals("a duplicate corner must not be added", before, viewModel.draft.value.vertexCount)
        assertTrue(viewModel.message.value!!.contains("on top of"))
        assertTrue(http.writes().isEmpty())
    }

    // ------------------------------------------------------------ edit

    @Test
    fun `editing re-reads the zone from the authority before allowing a change`() {
        val http = http()
        val (viewModel, _) = viewModel(http, editingZoneId = 7)

        // The draft starts from the server's polygon, not from a list row held in memory.
        assertEquals("Old quarry", viewModel.draft.value.name)
        assertEquals(ZoneType.RESTRICTED, viewModel.draft.value.type)
        assertEquals(4, viewModel.draft.value.vertexCount)
        assertEquals("editing an existing zone, not creating one", false, viewModel.draft.value.isNew)
        assertEquals(3, viewModel.revision.value)

        val read = http.requests.first { it.method == "GET" && it.url.contains("/authority/zones/7") }
        assertEquals("GET", read.method)
        assertTrue(read.url.endsWith("/api/v1/authority/zones/7"))
    }

    @Test
    fun `publishing an edit puts the whole zone, so the revision changes meaningfully`() {
        val http = http()
        val (viewModel, _) = viewModel(http, editingZoneId = 7)

        viewModel.setName("Old quarry (extended)")
        viewModel.save()

        val put = http.requests.last()
        assertEquals("PUT", put.method)
        assertTrue(put.url.endsWith("/api/v1/authority/zones/7"))
        assertTrue(put.body!!.contains("Old quarry (extended)"))
        // A full replace, geometry included — a partial write would make "what changed" ambiguous.
        assertTrue(put.body!!.contains("\"vertices\""))
        assertTrue(put.body!!.contains("\"status\""))
    }

    @Test
    fun `a refused publish is reported and never shown as saved`() {
        val http = http(zoneStatus = 500)
        val (viewModel, _) = viewModel(http)

        viewModel.setName("Old quarry")
        viewModel.drawSquare()
        viewModel.save()

        assertNull("a failed write must not look like a successful one", viewModel.savedZone.value)
        assertTrue(viewModel.message.value!!.contains("500"))
    }

    @Test
    fun `an unreachable authority says the zone was not saved`() {
        val http = http()
        http.failure = java.io.IOException("Connection refused")
        val (viewModel, _) = viewModel(http)

        viewModel.setName("Old quarry")
        viewModel.drawSquare()
        viewModel.save()

        assertNull(viewModel.savedZone.value)
        assertTrue(
            "the message must be unambiguous that nothing was published, was ${viewModel.message.value}",
            viewModel.message.value!!.contains("NOT saved"),
        )
    }

    @Test
    fun `publishing without an officer session is refused before anything is sent`() {
        val http = http()
        val (viewModel, _) = viewModel(http, signedIn = false)

        viewModel.setName("Old quarry")
        viewModel.drawSquare()
        viewModel.save()

        assertTrue(http.requests.isEmpty())
        assertNull(viewModel.savedZone.value)
        assertTrue(viewModel.message.value!!.contains("officer"))
    }

    // ------------------------------------------------------------ context

    @Test
    fun `the other published zones are loaded for context but the edited one is excluded`() {
        val http = http()
        val (viewModel, _) = viewModel(http, editingZoneId = 7)

        // The edited zone is already drawn as the draft; drawing it again as background context would
        // double-render the same boundary.
        assertTrue(viewModel.contextZones.value.isEmpty())
    }
}
