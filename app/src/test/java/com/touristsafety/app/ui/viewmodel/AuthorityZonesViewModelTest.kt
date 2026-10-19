package com.touristsafety.app.ui.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.touristsafety.app.authority.AuthorityClient
import com.touristsafety.app.authority.AuthorityHttpRequest
import com.touristsafety.app.authority.AuthorityHttpResponse
import com.touristsafety.app.authority.AuthorityLoginResult
import com.touristsafety.app.authority.AuthoritySession
import com.touristsafety.app.authority.ZoneStatus
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
 * The officer's zone management, driven through its ViewModel.
 *
 * The claim under test is the one the brief is emphatic about: zone management is **shared**, so it
 * lives in the authority, not on the officer's handset. Every method here must therefore produce an
 * HTTP request against `/authority/zones`, and the list the screen shows must come from the server's
 * reply — including the revision, which is the field that makes a change to an already-published
 * boundary visible to every phone holding the old polygon.
 *
 * The negative half matters just as much: a refused write must leave the list as the server last
 * described it, never optimistically updated.
 */
@RunWith(AndroidJUnit4::class)
class AuthorityZonesViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val future = System.currentTimeMillis() + 3_600_000L

    private fun zoneJson(
        zoneId: Int,
        name: String = "Old quarry",
        status: String = "ACTIVE",
        revision: Int = 1,
    ): String {
        val ring = TestGeo.squareVertices.joinToString(",") { point ->
            """{"latitude":${point.latitude},"longitude":${point.longitude}}"""
        }
        return """{"zoneId":$zoneId,"name":"$name","description":"Steep sides.",
            "zoneType":"RESTRICTED","status":"$status","revision":$revision,
            "vertexCount":${TestGeo.squareVertices.size},"updatedAtMillis":1700000000000,
            "vertices":[$ring]}""".trimIndent().replace("\n", "")
    }

    private fun officerLogin() = AuthorityLoginResult(
        token = "officer-token",
        role = "authority",
        subjectId = "A-01",
        displayName = "Duty Officer",
        expiresAtMillis = future,
        serverTimeMillis = 1_730_000_000_000L,
    )

    private fun viewModel(
        http: FakeAuthorityHttp,
        signedIn: Boolean = true,
    ): Pair<AuthorityZonesViewModel, InMemoryAuthoritySessionStore> {
        val store = InMemoryAuthoritySessionStore(
            AuthoritySession(baseUrl = "192.168.1.20:8080"),
        )
        if (signedIn) {
            runBlocking { store.saveOfficerLogin("192.168.1.20:8080", officerLogin()) }
        }
        val client = AuthorityClient(baseUrl = { store.current().baseUrl }, http = http)
        return AuthorityZonesViewModel(store, client) to store
    }

    @Test
    fun `refresh lists the zones the authority holds, with the authority's revisions`() {
        val http = FakeAuthorityHttp().apply {
            respondWith {
                AuthorityHttpResponse(
                    200,
                    """{"zones":[${zoneJson(7, revision = 3)},${zoneJson(8, name = "Cliff", status = "INACTIVE", revision = 5)}],"serverTimeMillis":1}""",
                )
            }
        }
        val (viewModel, _) = viewModel(http)

        viewModel.refresh()

        assertEquals(2, viewModel.zones.value.size)
        assertEquals(3, viewModel.zones.value.first { it.zoneId == 7 }.revision)
        assertEquals(5, viewModel.zones.value.first { it.zoneId == 8 }.revision)
        assertEquals(ZoneStatus.INACTIVE, viewModel.zones.value.first { it.zoneId == 8 }.status)
        assertTrue(viewModel.loaded.value)
        assertTrue(http.requests.single().url.endsWith("/api/v1/authority/zones"))
        assertEquals("officer-token", http.requests.single().bearerToken)
    }

    @Test
    fun `activating a zone is a status write, and the list takes the server's revision`() {
        val http = FakeAuthorityHttp().apply {
            respondWith { AuthorityHttpResponse(200, """{"zones":[${zoneJson(8, status = "INACTIVE")}],"serverTimeMillis":1}""") }
        }
        val (viewModel, _) = viewModel(http)
        viewModel.refresh()

        http.respondWith {
            AuthorityHttpResponse(200, """{"zone":${zoneJson(8, status = "ACTIVE", revision = 2)}}""")
        }
        viewModel.setStatus(zoneId = 8, status = ZoneStatus.ACTIVE)

        val write = http.requests.last()
        assertEquals("POST", write.method)
        assertTrue(write.url.endsWith("/api/v1/authority/zones/8/status"))
        assertTrue(write.body!!.contains("\"status\":\"ACTIVE\""))
        // The revision shown is the server's, not a locally incremented guess.
        assertEquals(2, viewModel.zones.value.single().revision)
        assertEquals(ZoneStatus.ACTIVE, viewModel.zones.value.single().status)
    }

    @Test
    fun `archiving is a soft delete that keeps the zone in the list`() {
        val http = FakeAuthorityHttp().apply {
            respondWith { AuthorityHttpResponse(200, """{"zones":[${zoneJson(7)}],"serverTimeMillis":1}""") }
        }
        val (viewModel, _) = viewModel(http)
        viewModel.refresh()

        http.respondWith {
            AuthorityHttpResponse(200, """{"zone":${zoneJson(7, status = "ARCHIVED", revision = 4)}}""")
        }
        viewModel.archive(zoneId = 7)

        val write = http.requests.last()
        assertEquals("DELETE", write.method)
        assertTrue(write.url.endsWith("/api/v1/authority/zones/7"))
        assertEquals("the record is kept, not deleted", 1, viewModel.zones.value.size)
        assertEquals(ZoneStatus.ARCHIVED, viewModel.zones.value.single().status)
        assertEquals(4, viewModel.zones.value.single().revision)
        assertTrue(
            "archiving must not quietly drop the row: ${viewModel.message.value}",
            !viewModel.zones.value.single().isActive,
        )
    }

    @Test
    fun `a refused write leaves the list as the server last described it`() {
        val http = FakeAuthorityHttp().apply {
            respondWith { AuthorityHttpResponse(200, """{"zones":[${zoneJson(7)}],"serverTimeMillis":1}""") }
        }
        val (viewModel, _) = viewModel(http)
        viewModel.refresh()
        val before = viewModel.zones.value

        http.respondWith {
            AuthorityHttpResponse(500, """{"error":"server_error","message":"boom"}""")
        }
        viewModel.archive(zoneId = 7)

        assertEquals("the zone must not be shown as archived when the write failed", before, viewModel.zones.value)
        assertTrue(viewModel.message.value!!.contains("500"))
    }

    @Test
    fun `a refused token drops the officer session rather than leaving a dead list`() {
        val http = FakeAuthorityHttp().apply {
            respondWith {
                AuthorityHttpResponse(403, """{"error":"forbidden","message":"Authority role required."}""")
            }
        }
        val (viewModel, store) = viewModel(http)

        viewModel.refresh()

        assertNull(runBlocking { store.currentOfficer().token })
        assertTrue(viewModel.zones.value.isEmpty())
        assertTrue(
            "a refusal must be reported as a refusal, was ${viewModel.message.value}",
            viewModel.message.value!!.contains("refused"),
        )
    }

    @Test
    fun `refreshing without a session asks for a sign-in and calls nothing`() {
        val http = FakeAuthorityHttp()
        val (viewModel, _) = viewModel(http, signedIn = false)

        viewModel.refresh()

        assertEquals("Sign in first.", viewModel.message.value)
        assertTrue(http.requests.isEmpty())
    }
}
