package com.touristsafety.app.ui.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.touristsafety.app.authority.AuthorityClient
import com.touristsafety.app.authority.AuthorityHttpRequest
import com.touristsafety.app.authority.AuthorityHttpResponse
import com.touristsafety.app.authority.AuthoritySession
import com.touristsafety.app.testing.FakeAuthorityHttp
import com.touristsafety.app.testing.InMemoryAuthoritySessionStore
import com.touristsafety.app.testing.MainDispatcherRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The officer's console, driven through the ViewModel.
 *
 * The claims under test are the ones that make the role real rather than cosmetic: the officer token
 * is stored apart from the tourist session (so an officer signing in here does not make the phone
 * start behaving like a tracked tourist), an officer sign-out does not evict a tourist, and a 403 on
 * the register drops the session instead of leaving a console that can only ever fail.
 */
@RunWith(AndroidJUnit4::class)
class OfficerViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val future = System.currentTimeMillis() + 3_600_000L

    private val registerBody = """
        {"tourists":[
          {"touristId":"TR-2026-00001","displayName":"Asha Menon","phone":"+91 90000 22222",
           "createdAtMillis":1730000000000,"registrationSource":"SELF",
           "registeredAtMillis":1730000000000,"registrationStatus":"REGISTERED",
           "trackingStatus":"AWAITING_FIRST_REPORT","lastSeenMillis":null,"hasPosition":false,
           "latestPosition":null,"emergencyContactCount":2,"openSosCount":0,
           "latestRiskBand":null,"latestRiskAtMillis":null,"state":"STALE"}
        ],"serverTimeMillis":1730000000000}
    """.trimIndent()

    private fun loginBody(): String =
        """{"token":"officer-token","role":"authority","subjectId":"A-01",
            "displayName":"Duty Officer","expiresAtMillis":$future,
            "serverTimeMillis":1730000000000}"""

    /** A client whose two routes can be made to fail independently. */
    private fun http(
        loginStatus: Int = 200,
        registerStatus: Int = 200,
        registerPayload: String = registerBody,
    ): FakeAuthorityHttp {
        val http = FakeAuthorityHttp()
        http.respondWith { request: AuthorityHttpRequest ->
            when {
                request.url.endsWith("/api/v1/auth/login") -> if (loginStatus == 200) {
                    AuthorityHttpResponse(200, loginBody())
                } else {
                    AuthorityHttpResponse(
                        loginStatus,
                        """{"error":"unauthorized","message":"Unknown id or wrong password."}""",
                    )
                }

                request.url.endsWith("/api/v1/authority/tourists") -> if (registerStatus == 200) {
                    AuthorityHttpResponse(200, registerPayload)
                } else {
                    AuthorityHttpResponse(
                        registerStatus,
                        """{"error":"forbidden","message":"Authority role required."}""",
                    )
                }

                else -> AuthorityHttpResponse(404, """{"error":"not_found"}""")
            }
        }
        return http
    }

    private fun viewModel(
        http: FakeAuthorityHttp,
        baseUrl: String = "192.168.1.20:8080",
    ): Pair<OfficerViewModel, InMemoryAuthoritySessionStore> {
        val store = InMemoryAuthoritySessionStore(AuthoritySession(baseUrl = baseUrl))
        val client = AuthorityClient(baseUrl = { store.current().baseUrl }, http = http)
        return OfficerViewModel(store, client, "Pixel 7 (test)") to store
    }

    @Test
    fun `a successful officer sign-in stores the session and reads the register`() {
        val (viewModel, store) = viewModel(http())

        viewModel.signIn("A-01", "officer-pass-test")

        assertEquals("A-01", runBlocking { store.currentOfficer().subjectId })
        assertEquals("officer-token", runBlocking { store.currentOfficer().token })
        assertEquals(1, viewModel.tourists.value.size)
        assertEquals("TR-2026-00001", viewModel.tourists.value.single().touristId)
        assertTrue(viewModel.registerLoaded.value)
    }

    @Test
    fun `an officer sign-in does not sign a tourist in on the same phone`() {
        val (viewModel, store) = viewModel(http())

        viewModel.signIn("A-01", "officer-pass-test")

        // The tourist half of the store is untouched, so the outbox and the position reporter stay
        // exactly as they were: this handset is not now a tracked tourist.
        val tourist = runBlocking { store.current() }
        assertNull(tourist.token)
        assertNull(tourist.subjectId)
    }

    @Test
    fun `a rejected officer sign-in stores nothing and says only that the pair was refused`() {
        val (viewModel, store) = viewModel(http(loginStatus = 401))

        viewModel.signIn("A-01", "wrong")

        assertEquals("That officer id and password were not accepted.", viewModel.message.value)
        assertNull(runBlocking { store.currentOfficer().token })
        assertTrue(viewModel.tourists.value.isEmpty())
    }

    @Test
    fun `an unreachable authority is named rather than reported as bad credentials`() {
        val http = http()
        http.failure = java.io.IOException("Connection refused")
        val (viewModel, store) = viewModel(http)

        viewModel.signIn("A-01", "officer-pass-test")

        assertNull(runBlocking { store.currentOfficer().token })
        assertTrue(
            "the message must name the address, was ${viewModel.message.value}",
            viewModel.message.value!!.contains("192.168.1.20:8080"),
        )
    }

    @Test
    fun `a blank address is refused before any call is attempted`() {
        val http = http()
        val (viewModel, _) = viewModel(http, baseUrl = "   ")

        viewModel.signIn("A-01", "officer-pass-test")

        assertTrue(viewModel.message.value!!.contains("address"))
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun `blank credentials are refused before any call is attempted`() {
        val http = http()
        val (viewModel, _) = viewModel(http)

        viewModel.signIn("  ", "")

        assertEquals("Enter your officer id and password.", viewModel.message.value)
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun `the register request carries the officer token as a bearer token`() {
        val http = http()
        val (viewModel, _) = viewModel(http)

        viewModel.signIn("A-01", "officer-pass-test")

        val registerCall = http.requests.last()
        assertEquals("GET", registerCall.method)
        assertTrue(registerCall.url.endsWith("/api/v1/authority/tourists"))
        assertEquals("officer-token", registerCall.bearerToken)
    }

    @Test
    fun `a forbidden register drops the session instead of leaving a console that cannot work`() {
        val http = http(registerStatus = 403)
        val (viewModel, store) = viewModel(http)
        runBlocking { store.saveOfficerLogin("192.168.1.20:8080", officerLogin()) }

        viewModel.refresh()

        assertNull(runBlocking { store.currentOfficer().token })
        assertTrue(viewModel.tourists.value.isEmpty())
        assertTrue(
            "a refusal must be reported as a refusal, was ${viewModel.message.value}",
            viewModel.message.value!!.contains("403"),
        )
    }

    @Test
    fun `signing out revokes the token and clears only the officer session`() {
        val http = http()
        val (viewModel, store) = viewModel(http)
        runBlocking {
            store.saveLogin("192.168.1.20:8080", touristLogin())
        }
        viewModel.signIn("A-01", "officer-pass-test")

        viewModel.signOut()

        assertNull(runBlocking { store.currentOfficer().token })
        assertTrue(viewModel.tourists.value.isEmpty())
        assertEquals(
            "the officer's sign-out must not touch the tourist session",
            "tourist-token",
            runBlocking { store.current().token },
        )
        assertTrue(http.requests.any { it.url.endsWith("/api/v1/auth/logout") })
    }

    @Test
    fun `refreshing without a session asks for a sign-in rather than calling the server`() {
        val http = http()
        val (viewModel, _) = viewModel(http)

        viewModel.refresh()

        assertEquals("Sign in first.", viewModel.message.value)
        assertTrue(http.requests.isEmpty())
    }

    private fun officerLogin() = com.touristsafety.app.authority.AuthorityLoginResult(
        token = "officer-token",
        role = "authority",
        subjectId = "A-01",
        displayName = "Duty Officer",
        expiresAtMillis = future,
        serverTimeMillis = 1730000000000L,
    )

    private fun touristLogin() = com.touristsafety.app.authority.AuthorityLoginResult(
        token = "tourist-token",
        role = "tourist",
        subjectId = "T-1001",
        displayName = "Demo Tourist",
        expiresAtMillis = future,
        serverTimeMillis = 1730000000000L,
    )
}
