package com.touristsafety.app.ui.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.touristsafety.app.authority.AuthorityClient
import com.touristsafety.app.authority.AuthorityHttpRequest
import com.touristsafety.app.authority.AuthorityHttpResponse
import com.touristsafety.app.authority.AuthoritySession
import com.touristsafety.app.data.database.AppDatabase
import com.touristsafety.app.data.repository.OutboxRepository
import com.touristsafety.app.domain.model.EmergencyContact
import com.touristsafety.app.domain.model.OutboxEvent
import com.touristsafety.app.outbox.DeliveryResult
import com.touristsafety.app.outbox.OutboxSyncer
import com.touristsafety.app.outbox.SyncTransport
import com.touristsafety.app.testing.FakeAuthorityHttp
import com.touristsafety.app.testing.InMemoryAuthoritySessionStore
import com.touristsafety.app.testing.MainDispatcherRule
import com.touristsafety.app.testing.inMemoryDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Self-service registration through the ViewModel.
 *
 * The point of these tests is the *state machine*, not the wire format (that is `AuthorityApiTest`):
 * a bad form must be refused without any request leaving the phone, a 409 must read as "that number is
 * already registered" rather than a generic failure, and a success must produce an id to show — while
 * leaving nobody signed in.
 */
@RunWith(AndroidJUnit4::class)
class AuthorityViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        database = inMemoryDatabase()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun viewModel(
        http: FakeAuthorityHttp,
        baseUrl: String = "192.168.1.20:8080",
    ): Pair<AuthorityViewModel, InMemoryAuthoritySessionStore> {
        val store = InMemoryAuthoritySessionStore(AuthoritySession(baseUrl = baseUrl))
        val client = AuthorityClient(baseUrl = { store.current().baseUrl }, http = http)
        val repository = OutboxRepository(database.outboxEventDao())
        val syncer = OutboxSyncer(
            repository = repository,
            transport = object : SyncTransport {
                override val name: String = "registration-test"

                override suspend fun deliver(event: OutboxEvent): DeliveryResult =
                    DeliveryResult.Retry("the outbox is not involved in registration")
            },
        )
        return AuthorityViewModel(store, client, syncer, repository, "Pixel 7 (test)") to store
    }

    private fun registered(http: FakeAuthorityHttp) {
        http.respondWith {
            AuthorityHttpResponse(
                status = 201,
                body = """{"tourist":{"touristId":"TR-2026-00124","displayName":"Nadia Qureshi",
                    "phone":"+91 90000 11111","registeredAtMillis":1730000000000,
                    "registrationSource":"SELF","emergencyContactCount":0},
                    "note":"Registration complete.","serverTimeMillis":1730000000000}""",
            )
        }
    }

    private fun register(
        viewModel: AuthorityViewModel,
        name: String = "Nadia Qureshi",
        phone: String = "+91 90000 11111",
        pin: String = "4821",
        confirmation: String = "4821",
        contacts: List<EmergencyContact> = emptyList(),
    ) = viewModel.register(name, phone, pin, confirmation, contacts)

    @Test
    fun `a successful registration stores the id the server minted and does not sign anyone in`() {
        val http = FakeAuthorityHttp()
        registered(http)
        val (viewModel, store) = viewModel(http)

        register(viewModel)

        assertEquals("TR-2026-00124", viewModel.registration.value?.touristId)
        assertTrue(
            "the success message must contain the id the tourist has to write down",
            viewModel.message.value!!.contains("TR-2026-00124"),
        )
        // Registration creates an account; it must not also hand out a session.
        assertNull(runBlocking { store.current().token })
    }

    @Test
    fun `the request that reaches the wire carries no bearer token`() {
        val http = FakeAuthorityHttp()
        registered(http)
        val (viewModel, _) = viewModel(http)

        register(viewModel)

        assertNull(http.requests.single().bearerToken)
    }

    @Test
    fun `a mismatched pin is refused without any request leaving the phone`() {
        val http = FakeAuthorityHttp()
        val (viewModel, _) = viewModel(http)

        register(viewModel, pin = "4821", confirmation = "4822")

        assertEquals("The two PINs do not match.", viewModel.message.value)
        assertNull(viewModel.registration.value)
        assertTrue("validation must happen before the network call", http.requests.isEmpty())
    }

    @Test
    fun `an empty name is refused locally`() {
        val http = FakeAuthorityHttp()
        val (viewModel, _) = viewModel(http)

        register(viewModel, name = "   ")

        assertEquals("Enter your name.", viewModel.message.value)
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun `a duplicate phone number is reported as such rather than as a generic failure`() {
        val http = FakeAuthorityHttp()
        http.respondWith {
            AuthorityHttpResponse(
                status = 409,
                body = """{"error":"conflict","message":"An account is already registered with that phone number."}""",
            )
        }
        val (viewModel, _) = viewModel(http)

        register(viewModel)

        assertNull(viewModel.registration.value)
        assertTrue(
            "a duplicate should read as a duplicate, was ${viewModel.message.value}",
            viewModel.message.value!!.contains("already registered"),
        )
    }

    @Test
    fun `an unreachable authority is named in the message`() {
        val http = FakeAuthorityHttp()
        http.failure = java.io.IOException("Connection refused")
        val (viewModel, _) = viewModel(http)

        register(viewModel)

        assertNull(viewModel.registration.value)
        assertTrue(viewModel.message.value!!.contains("192.168.1.20:8080"))
    }

    @Test
    fun `a blank address is refused before a call is attempted`() {
        val http = FakeAuthorityHttp()
        val (viewModel, _) = viewModel(http, baseUrl = "   ")

        register(viewModel)

        assertTrue(viewModel.message.value!!.contains("address"))
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun `clearing the registration takes the success screen away`() {
        val http = FakeAuthorityHttp()
        registered(http)
        val (viewModel, _) = viewModel(http)

        register(viewModel)
        viewModel.clearRegistration()

        assertNull(viewModel.registration.value)
    }

    @Test
    fun `a later failure does not erase an id the tourist may still be copying`() {
        val http = FakeAuthorityHttp()
        registered(http)
        val (viewModel, _) = viewModel(http)
        register(viewModel)

        // A subsequent attempt fails (say the phone number is taken by now)...
        http.respondWith {
            AuthorityHttpResponse(status = 409, body = """{"error":"conflict","message":"Taken."}""")
        }
        register(viewModel)

        // ...and the id from the earlier success is still on screen.
        assertEquals("TR-2026-00124", viewModel.registration.value?.touristId)
    }

    @Test
    fun `contacts collected by the form are sent with the registration`() {
        val http = FakeAuthorityHttp()
        registered(http)
        val (viewModel, _) = viewModel(http)

        register(
            viewModel,
            contacts = listOf(EmergencyContact(name = "Asha", phone = "112", relationship = "Sister")),
        )

        val body = http.requests.single().body!!
        assertTrue("contacts must travel with the registration, body was $body", body.contains("Asha"))
        assertTrue(body.contains("4821"))
    }

    @Test
    fun `an http request to the register route uses POST`() {
        val http = FakeAuthorityHttp()
        registered(http)
        val (viewModel, _) = viewModel(http)

        register(viewModel)

        val request: AuthorityHttpRequest = http.requests.single()
        assertEquals("POST", request.method)
        assertTrue(request.url.endsWith("/api/v1/auth/register"))
    }
}
