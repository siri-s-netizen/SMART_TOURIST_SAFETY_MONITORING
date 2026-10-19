package com.touristsafety.app.authority

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The real session store, over a real file.
 *
 * Everything else in this package drives `InMemoryAuthoritySessionStore`, which proves the ViewModels
 * behave. This proves the thing they depend on: that the address and the two sessions are actually
 * written to disk, that they come back after the store is torn down and rebuilt, and — the claim that
 * matters most for the two roles — that a tourist signing out cannot evict an officer and vice versa.
 *
 * No Android `Context` is involved: the store takes a `DataStore` directly, so this runs on the plain
 * JVM with no Robolectric and no shared singleton.
 */
class AuthoritySessionStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var file: File

    @Before
    fun setUp() {
        scope = newScope()
        file = File(folder.root, "authority_session.preferences_pb")
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun newScope(): CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private fun store(): AuthoritySessionStore =
        DataStoreAuthoritySessionStore(
            PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }),
        )

    /**
     * Ends the store the way a process death would, then opens a fresh one over the same file.
     *
     * The `join` is not decoration: DataStore refuses to open a second instance on a file that is
     * still active, and the handle is released when its write actor finishes — which is exactly what
     * joining the cancelled scope waits for. Without it this test would be racy.
     */
    private fun restart(): AuthoritySessionStore {
        val ending = scope
        ending.cancel()
        runBlocking { ending.coroutineContext[Job]!!.join() }
        scope = newScope()
        return store()
    }

    private fun login(
        token: String,
        role: String,
        subjectId: String,
    ) = AuthorityLoginResult(
        token = token,
        role = role,
        subjectId = subjectId,
        displayName = "Test Person",
        expiresAtMillis = 4_102_444_800_000L,
        serverTimeMillis = 1_730_000_000_000L,
    )

    @Test
    fun `the configured address survives the store being rebuilt`() {
        runBlocking { store().setBaseUrl("192.168.1.20:8080") }

        val afterRestart = restart()

        assertEquals(
            "the address must be read back from the file, not just held in memory",
            "http://192.168.1.20:8080",
            runBlocking { afterRestart.current().baseUrl },
        )
        assertTrue("the file must actually have been written", file.length() > 0)
    }

    @Test
    fun `a changed address survives a restart, so moving networks is a durable change`() {
        runBlocking { store().setBaseUrl("192.168.1.20:8080") }

        // The phone moves: a new network, a new address for the laptop.
        val afterMove = restart()
        runBlocking { afterMove.setBaseUrl("10.0.0.7:9090") }

        val afterSecondRestart = restart()

        assertEquals(
            "the second address, not the first, must come back after a restart",
            "http://10.0.0.7:9090",
            runBlocking { afterSecondRestart.current().baseUrl },
        )
    }

    @Test
    fun `seedBaseUrlIfUnset writes only when nothing has been configured`() {
        runBlocking {
            val store = store()

            assertTrue("a fresh store has no address", store.seedBaseUrlIfUnset("192.168.1.20:8080"))
            assertEquals("http://192.168.1.20:8080", store.current().baseUrl)

            // A build-time default must never overwrite an address the user has already chosen.
            assertFalse(store.seedBaseUrlIfUnset("10.0.2.2:8080"))
            assertEquals("http://192.168.1.20:8080", store.current().baseUrl)
        }
    }

    @Test
    fun `seedBaseUrlIfUnset ignores an empty default so an unconfigured build stays unconfigured`() {
        runBlocking {
            val store = store()

            assertFalse(store.seedBaseUrlIfUnset("   "))
            assertEquals("", store.current().baseUrl)
        }
    }

    @Test
    fun `a tourist session survives a restart with its token and role`() {
        runBlocking {
            store().saveLogin("192.168.1.20:8080", login("tok_t", AuthorityRoles.TOURIST, "T-1001"))
        }

        val restored = runBlocking { restart().current() }

        assertEquals("tok_t", restored.token)
        assertEquals(AuthorityRoles.TOURIST, restored.role)
        assertEquals("T-1001", restored.subjectId)
        assertTrue("a restored session must still be usable", restored.isSignedIn(1_730_000_000_001L))
    }

    @Test
    fun `the officer session is stored apart from the tourist session`() {
        runBlocking {
            val store = store()
            store.saveLogin("192.168.1.20:8080", login("tok_t", AuthorityRoles.TOURIST, "T-1001"))
            store.saveOfficerLogin(
                "192.168.1.20:8080",
                login("tok_a", AuthorityRoles.AUTHORITY, "A-01"),
            )

            assertEquals("tok_t", store.current().token)
            assertEquals("tok_a", store.currentOfficer().token)
        }
    }

    @Test
    fun `a tourist signing out cannot evict the officer`() {
        runBlocking {
            val store = store()
            store.saveLogin("192.168.1.20:8080", login("tok_t", AuthorityRoles.TOURIST, "T-1001"))
            store.saveOfficerLogin(
                "192.168.1.20:8080",
                login("tok_a", AuthorityRoles.AUTHORITY, "A-01"),
            )

            store.clearLogin()

            assertNull("the tourist is signed out", store.current().token)
            assertEquals("the officer is not", "tok_a", store.currentOfficer().token)
            assertEquals(
                "and the address is kept so re-signing-in is one field",
                "http://192.168.1.20:8080",
                store.current().baseUrl,
            )
        }
    }

    @Test
    fun `an officer signing out cannot evict the tourist`() {
        runBlocking {
            val store = store()
            store.saveLogin("192.168.1.20:8080", login("tok_t", AuthorityRoles.TOURIST, "T-1001"))
            store.saveOfficerLogin(
                "192.168.1.20:8080",
                login("tok_a", AuthorityRoles.AUTHORITY, "A-01"),
            )

            store.clearOfficerLogin()

            assertNull(store.currentOfficer().token)
            assertEquals("tok_t", store.current().token)
        }
    }

    @Test
    fun `an officer session survives a restart`() {
        runBlocking {
            store().saveOfficerLogin(
                "192.168.1.20:8080",
                login("tok_a", AuthorityRoles.AUTHORITY, "A-01"),
            )
        }

        val restored = runBlocking { restart().currentOfficer() }

        assertEquals("tok_a", restored.token)
        assertEquals("A-01", restored.subjectId)
        assertEquals("http://192.168.1.20:8080", restored.baseUrl)
    }
}
