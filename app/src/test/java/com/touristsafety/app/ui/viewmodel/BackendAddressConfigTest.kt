package com.touristsafety.app.ui.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.touristsafety.app.authority.AuthorityClient
import com.touristsafety.app.authority.AuthorityConnectionState
import com.touristsafety.app.authority.AuthorityHttpRequest
import com.touristsafety.app.authority.AuthorityHttpResponse
import com.touristsafety.app.authority.AuthoritySession
import com.touristsafety.app.data.database.AppDatabase
import com.touristsafety.app.domain.model.OutboxEvent
import com.touristsafety.app.outbox.DeliveryResult
import com.touristsafety.app.outbox.OutboxSyncer
import com.touristsafety.app.outbox.SyncTransport
import com.touristsafety.app.testing.FakeAuthorityHttp
import com.touristsafety.app.testing.InMemoryAuthoritySessionStore
import com.touristsafety.app.testing.MainDispatcherRule
import com.touristsafety.app.testing.inMemoryDatabase
import com.touristsafety.app.testing.ownedOutbox
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The configurable backend address, and the two sign-in flows that have to use it.
 *
 * The failure this file exists to prevent: a phone saved an address for one network, moved to
 * another, and could not be pointed at the new one — while "Test connection" quietly dialled the
 * **old** address and reported failure for an address the operator had not even typed. That made a
 * correct address look ignored.
 *
 * So there are three claims here, and they are checked against real URLs reaching a fake socket:
 *
 * 1. tourist sign-in, officer sign-in and every other call dial the **stored** address;
 * 2. changing the stored address changes the next call, with no cached copy anywhere;
 * 3. "Test connection" dials the address **on screen**, and never changes what is stored.
 */
@RunWith(AndroidJUnit4::class)
class BackendAddressConfigTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private lateinit var database: AppDatabase

    private val expiresAt = System.currentTimeMillis() + 3_600_000L

    private val touristLoginBody =
        """{"token":"tourist-token","role":"tourist","subjectId":"TR-2026-00001",
            "displayName":"Asha Menon","expiresAtMillis":$expiresAt,
            "serverTimeMillis":1730000000000}"""

    private val officerLoginBody =
        """{"token":"officer-token","role":"authority","subjectId":"A-01",
            "displayName":"Duty Officer","expiresAtMillis":$expiresAt,
            "serverTimeMillis":1730000000000}"""

    @Before
    fun setUp() {
        database = inMemoryDatabase()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun store(baseUrl: String): InMemoryAuthoritySessionStore =
        InMemoryAuthoritySessionStore(AuthoritySession(baseUrl = baseUrl))

    /** Exactly the production wiring: one client, reading the address from the store on every call. */
    private fun clientFor(
        store: InMemoryAuthoritySessionStore,
        http: FakeAuthorityHttp,
    ): AuthorityClient = AuthorityClient(baseUrl = { store.current().baseUrl }, http = http)

    private fun authorityViewModel(
        http: FakeAuthorityHttp,
        baseUrl: String,
    ): Pair<AuthorityViewModel, InMemoryAuthoritySessionStore> {
        val store = store(baseUrl)
        val repository = ownedOutbox(database.outboxEventDao())
        val syncer = OutboxSyncer(
            repository = repository,
            transport = object : SyncTransport {
                override val name: String = "backend-address-test"

                override suspend fun deliver(event: OutboxEvent): DeliveryResult =
                    DeliveryResult.Retry("the outbox is not part of this claim")
            },
        )
        return AuthorityViewModel(
            sessions = store,
            client = clientFor(store, http),
            syncer = syncer,
            outboxRepository = repository,
            deviceId = "Pixel 7 (test)",
        ) to store
    }

    private fun loginResponder(
        loginBody: String,
    ): (AuthorityHttpRequest) -> AuthorityHttpResponse = { request ->
            if (request.url.endsWith("/api/v1/auth/login")) {
                AuthorityHttpResponse(200, loginBody)
            } else if (request.url.endsWith("/api/v1/authority/tourists")) {
                AuthorityHttpResponse(200, """{"tourists":[],"serverTimeMillis":1730000000000}""")
            } else {
                // The tourist flow reads /api/v1/me after a sign-in; a 404 keeps this test about the
                // login URL without pretending a profile was served.
                AuthorityHttpResponse(404, """{"error":"not_found"}""")
            }
        }

    // ---------------------------------------------------- the two sign-in flows

    @Test
    fun `tourist sign-in dials the saved address`() {
        val http = FakeAuthorityHttp()
        http.respondWith(loginResponder(touristLoginBody))
        val (viewModel, store) = authorityViewModel(http, baseUrl = "10.20.30.40:9000")

        viewModel.signIn("TR-2026-00001", "4821")

        assertEquals("http://10.20.30.40:9000/api/v1/auth/login", http.requests.first().url)
        // The session is stored against the address it was minted by.
        assertEquals("http://10.20.30.40:9000", runBlocking { store.current().baseUrl })
    }

    @Test
    fun `officer sign-in dials the same saved address`() {
        val http = FakeAuthorityHttp()
        http.respondWith(loginResponder(officerLoginBody))
        val store = store("10.20.30.40:9000")

        OfficerViewModel(store, clientFor(store, http), "Pixel 7 (test)")
            .signIn("A-01", "officer-pass-test")

        assertEquals("http://10.20.30.40:9000/api/v1/auth/login", http.requests.first().url)
    }

    @Test
    fun `changing the saved address changes the address of the next call`() {
        val http = FakeAuthorityHttp()
        val store = store("192.168.1.100:8080")
        val client = clientFor(store, http)

        runBlocking { client.health() }
        runBlocking { store.setBaseUrl("172.16.5.5:9090") }
        runBlocking { client.health() }

        assertEquals(
            listOf(
                "http://192.168.1.100:8080/api/v1/health",
                "http://172.16.5.5:9090/api/v1/health",
            ),
            http.requests.map { it.url },
        )
    }

    @Test
    fun `saving an address stores the normalised form and re-checks nothing on its own`() {
        val http = FakeAuthorityHttp()
        val (viewModel, store) = authorityViewModel(http, baseUrl = "192.168.1.100:8080")

        viewModel.setAddress("10.1.2.3:8080/")

        assertEquals("http://10.1.2.3:8080", runBlocking { store.current().baseUrl })
        assertTrue(viewModel.message.value!!.contains("http://10.1.2.3:8080"))
        // Saving is a configuration change, not a claim about reachability.
        assertEquals(AuthorityConnectionState.UNKNOWN, viewModel.connection.value)
        assertTrue("saving must not dial anything", http.requests.isEmpty())
    }

    // ------------------------------------------------------- test connection

    @Test
    fun `test connection dials the address on screen, not the one already saved`() {
        val http = FakeAuthorityHttp()
        val (viewModel, store) = authorityViewModel(http, baseUrl = "192.168.43.27:8080")

        viewModel.testConnection("192.168.1.100:8080")

        // The regression: this used to be a request to 192.168.43.27:8080.
        assertEquals("http://192.168.1.100:8080/api/v1/health", http.requests.single().url)
        assertEquals("192.168.43.27:8080", runBlocking { store.current().baseUrl })

        val message = viewModel.message.value!!
        assertTrue("the message must name the tested address", message.contains("192.168.1.100:8080"))
        assertTrue(
            "an unsaved success must not be presented as already in effect",
            message.contains("Save address"),
        )
        // The indicator describes the stored address, which this test did not touch.
        assertEquals(AuthorityConnectionState.UNKNOWN, viewModel.connection.value)
    }

    @Test
    fun `test connection of the saved address reports it as connected`() {
        val http = FakeAuthorityHttp()
        val (viewModel, _) = authorityViewModel(http, baseUrl = "192.168.1.100:8080")

        viewModel.testConnection("192.168.1.100:8080")

        assertEquals(AuthorityConnectionState.CONNECTED, viewModel.connection.value)
        assertFalse(
            "an address that is already in use needs no instruction to save it",
            viewModel.message.value!!.contains("Save address"),
        )
    }

    @Test
    fun `test connection refuses an unusable address without dialling it`() {
        val http = FakeAuthorityHttp()
        val (viewModel, store) = authorityViewModel(http, baseUrl = "192.168.1.100:8080")

        viewModel.testConnection("192.168.1.100 8080")

        assertTrue("nothing may be dialled for text that is not an address", http.requests.isEmpty())
        assertTrue(viewModel.message.value!!.contains("spaces"))
        assertEquals("192.168.1.100:8080", runBlocking { store.current().baseUrl })
    }

    @Test
    fun `saving an unusable address is refused and the saved one is kept`() {
        val http = FakeAuthorityHttp()
        val (viewModel, store) = authorityViewModel(http, baseUrl = "192.168.1.100:8080")

        viewModel.setAddress("http://")

        assertEquals(
            "a refused address must leave the working one in place",
            "192.168.1.100:8080",
            runBlocking { store.current().baseUrl },
        )
        assertTrue(viewModel.message.value!!.contains("192.168.1.20:8080"))
    }

    // ------------------------------------------- failures must not close the app

    @Test
    fun `an unreachable authority is reported as offline with its reason`() {
        val http = FakeAuthorityHttp()
        http.failure = java.io.IOException("Connection refused")
        val (viewModel, store) = authorityViewModel(http, baseUrl = "192.168.1.100:8080")

        viewModel.testConnection("192.168.1.100:8080")

        assertEquals(AuthorityConnectionState.OFFLINE, viewModel.connection.value)
        assertTrue(viewModel.message.value!!.contains("Connection refused"))
        assertNull("a failed probe must not sign anyone in", runBlocking { store.current().token })
    }

    @Test
    fun `an exception that is not an IOException is reported, not thrown`() {
        // The platform can throw these out of a socket call: a SecurityException when a permission is
        // missing, an IllegalStateException from a URL with no host. Before the client caught them,
        // one of these reached the UI thread and closed the app while the address screen was open.
        val http = FakeAuthorityHttp()
        http.unexpectedFailure = IllegalStateException("No host")
        val (viewModel, store) = authorityViewModel(http, baseUrl = "192.168.1.100:8080")

        viewModel.testConnection("192.168.1.100:8080")

        assertEquals(AuthorityConnectionState.OFFLINE, viewModel.connection.value)
        assertTrue(
            "the message must name what went wrong so the failure is diagnosable",
            viewModel.message.value!!.contains("IllegalStateException"),
        )
        assertEquals("192.168.1.100:8080", runBlocking { store.current().baseUrl })
    }

    @Test
    fun `a refused security check becomes a message rather than a crash`() {
        val http = FakeAuthorityHttp()
        http.unexpectedFailure = SecurityException("Permission denied")
        val (viewModel, _) = authorityViewModel(http, baseUrl = "192.168.1.100:8080")

        viewModel.testConnection("192.168.1.100:8080")

        assertEquals(AuthorityConnectionState.OFFLINE, viewModel.connection.value)
        assertTrue(viewModel.message.value!!.contains("SecurityException"))
    }

    @Test
    fun `a failed sign-in says why and signs nobody in`() {
        val http = FakeAuthorityHttp()
        http.respondWith {
            AuthorityHttpResponse(401, """{"error":"unauthorized","message":"Unknown id or PIN."}""")
        }
        val (viewModel, store) = authorityViewModel(http, baseUrl = "192.168.1.100:8080")

        viewModel.signIn("TR-2026-00001", "0000")

        assertTrue(viewModel.message.value!!.contains("Unknown id or PIN"))
        assertNull("a refused sign-in must not leave a session behind", runBlocking { store.current().token })
        assertEquals("192.168.1.100:8080", runBlocking { store.current().baseUrl })
    }

    @Test
    fun `saving or testing an address never signs anyone in`() {
        val http = FakeAuthorityHttp()
        val (viewModel, store) = authorityViewModel(http, baseUrl = "")

        viewModel.setAddress("192.168.1.100:8080")
        viewModel.testConnection("192.168.1.100:8080")

        assertNull(runBlocking { store.current().token })
        assertNull(runBlocking { store.currentOfficer().token })
        assertTrue(
            "configuration must not be mistaken for authentication",
            viewModel.session.value.token == null,
        )
    }

    @Test
    fun `a save that cannot be written is reported, and does not close the app`() {
        // The one code path that could genuinely take the process down on this screen: a DataStore write
        // throws (a full disk, an unwritable file) and the exception escapes `viewModelScope.launch`,
        // which nothing catches. The operator must get a sentence, and the old address must stand.
        val http = FakeAuthorityHttp()
        val (viewModel, store) = authorityViewModel(http, baseUrl = "192.168.1.20:8080")
        store.writeFailure = java.io.IOException("No space left on device")

        viewModel.setAddress("10.0.0.7:9090")

        val message = viewModel.message.value
        assertNotNull("a failed save must say something rather than fail silently", message)
        val said = requireNotNull(message)
        assertTrue(
            "the operator must be told the save failed, got: $said",
            said.contains("Could not save the address"),
        )
        assertTrue(
            "and why, so it is actionable: $said",
            said.contains("No space left on device"),
        )
        assertEquals(
            "nothing was written, so the address already in force must be unchanged",
            "192.168.1.20:8080",
            runBlocking { store.current().baseUrl },
        )
    }

    @Test
    fun `a login whose session cannot be stored does not claim to be signed in`() {
        val http = FakeAuthorityHttp()
        http.respondWith(loginResponder(touristLoginBody))
        val (viewModel, store) = authorityViewModel(http, baseUrl = "192.168.1.100:8080")
        store.writeFailure = java.io.IOException("Read-only file system")

        viewModel.signIn("TR-2026-00001", "1357")

        val message = viewModel.message.value
        assertNotNull("a refused sign-in must say something", message)
        assertTrue(
            "a token that could not be stored is not a sign-in, got: $message",
            requireNotNull(message).contains("could not store the session"),
        )
        assertNull("and no session may be left behind", runBlocking { store.current().token })
    }

    @Test
    fun `a sign-out that cannot clear the session says so instead of pretending`() {
        val http = FakeAuthorityHttp()
        http.respondWith(loginResponder(touristLoginBody))
        val (viewModel, store) = authorityViewModel(http, baseUrl = "192.168.1.100:8080")
        viewModel.signIn("TR-2026-00001", "1357")

        store.writeFailure = java.io.IOException("Read-only file system")
        viewModel.signOut()

        val message = viewModel.message.value
        assertNotNull("a sign-out that failed must say something", message)
        assertTrue(
            "the operator must not be told they are signed out when they are not, got: $message",
            requireNotNull(message).contains("Could not clear this phone's session"),
        )
        assertTrue(
            "the session really is still there, which is why saying otherwise would be a lie",
            runBlocking { store.current().token } != null,
        )
    }
}
