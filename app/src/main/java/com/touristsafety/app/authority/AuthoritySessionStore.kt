package com.touristsafety.app.authority

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * Where the authority session lives.
 *
 * A separate DataStore file from the tuning preferences on purpose: signing out must not be able to
 * touch the GPS settings, and a corrupted session must not stop the app from starting the tracker.
 */
interface AuthoritySessionStore {

    val session: Flow<AuthoritySession>

    /** The current session, for callers that cannot suspend-collect (the sync transport). */
    suspend fun current(): AuthoritySession

    /** The current *officer* session, same reasoning as [current]. */
    suspend fun currentOfficer(): OfficerSession

    suspend fun setBaseUrl(baseUrl: String)

    /** Records a successful sign-in. [baseUrl] is stored with it so the token cannot outlive it. */
    suspend fun saveLogin(baseUrl: String, login: AuthorityLoginResult)

    /** Drops the token but keeps the configured base URL, so re-signing-in is one field. */
    suspend fun clearLogin()

    suspend fun clear()

    /**
     * The *officer* session, stored under its own keys.
     *
     * Deliberately not the same value as [session]: see [OfficerSession] for why signing in as an
     * officer must not make this phone behave like a signed-in tourist.
     */
    val officerSession: Flow<OfficerSession>

    suspend fun saveOfficerLogin(baseUrl: String, login: AuthorityLoginResult)

    suspend fun clearOfficerLogin()

    /**
     * Writes [defaultUrl] **only** when no address has ever been configured.
     *
     * This is what lets a build ship pointed at a known authority without overwriting an address a
     * user has already chosen. Returns true when it actually wrote, so the caller can log or test it.
     */
    suspend fun seedBaseUrlIfUnset(defaultUrl: String): Boolean
}

private val Context.authoritySessionDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "authority_session",
)

/**
 * The production session store, over the process-wide preferences DataStore.
 *
 * A factory rather than a defaulted constructor argument, so the store class itself carries no Android
 * dependency and a test can drive it against a throw-away file on the plain JVM — which is what makes
 * "the session survives a restart" and "the officer session is separate" assertable rather than
 * asserted-by-inspection.
 */
fun createAuthoritySessionStore(context: Context): AuthoritySessionStore =
    DataStoreAuthoritySessionStore(context.applicationContext.authoritySessionDataStore)

/**
 * The production store.
 *
 * Deliberately plain preferences rather than encrypted storage: the token is a revocable 30-day
 * bearer token for a LAN service, and pretending to protect it with a key that ships in the app
 * would be theatre. What actually limits the damage is the server-side session row, which an
 * operator can delete.
 */
class DataStoreAuthoritySessionStore(
    private val dataStore: DataStore<Preferences>,
) : AuthoritySessionStore {

    override val session: Flow<AuthoritySession> = dataStore.data
        .catch { failure ->
            if (failure is IOException) emit(emptyPreferences()) else throw failure
        }
        .map { preferences -> preferences.toSession() }

    override suspend fun current(): AuthoritySession = session.first()

    override suspend fun currentOfficer(): OfficerSession = officerSession.first()

    override suspend fun setBaseUrl(baseUrl: String) {
        dataStore.edit { it[KEY_BASE_URL] = AuthorityUrls.normalise(baseUrl) }
    }

    override suspend fun saveLogin(baseUrl: String, login: AuthorityLoginResult) {
        dataStore.edit { preferences ->
            preferences[KEY_BASE_URL] = AuthorityUrls.normalise(baseUrl)
            preferences[KEY_TOKEN] = login.token
            preferences[KEY_ROLE] = login.role
            preferences[KEY_SUBJECT_ID] = login.subjectId
            preferences[KEY_DISPLAY_NAME] = login.displayName
            preferences[KEY_EXPIRES_AT] = login.expiresAtMillis
            preferences[KEY_SIGNED_IN_AT] = login.serverTimeMillis
        }
    }

    override suspend fun clearLogin() {
        dataStore.edit { preferences ->
            preferences.remove(KEY_TOKEN)
            preferences.remove(KEY_ROLE)
            preferences.remove(KEY_SUBJECT_ID)
            preferences.remove(KEY_DISPLAY_NAME)
            preferences.remove(KEY_EXPIRES_AT)
            preferences.remove(KEY_SIGNED_IN_AT)
        }
    }

    override suspend fun clear() {
        dataStore.edit { it.clear() }
    }

    override val officerSession: Flow<OfficerSession> = dataStore.data
        .catch { failure ->
            if (failure is IOException) emit(emptyPreferences()) else throw failure
        }
        .map { preferences -> preferences.toOfficerSession() }

    override suspend fun saveOfficerLogin(baseUrl: String, login: AuthorityLoginResult) {
        dataStore.edit { preferences ->
            preferences[KEY_BASE_URL] = AuthorityUrls.normalise(baseUrl)
            preferences[KEY_OFFICER_TOKEN] = login.token
            preferences[KEY_OFFICER_SUBJECT_ID] = login.subjectId
            preferences[KEY_OFFICER_DISPLAY_NAME] = login.displayName
            preferences[KEY_OFFICER_EXPIRES_AT] = login.expiresAtMillis
        }
    }

    override suspend fun clearOfficerLogin() {
        dataStore.edit { preferences ->
            preferences.remove(KEY_OFFICER_TOKEN)
            preferences.remove(KEY_OFFICER_SUBJECT_ID)
            preferences.remove(KEY_OFFICER_DISPLAY_NAME)
            preferences.remove(KEY_OFFICER_EXPIRES_AT)
        }
    }

    override suspend fun seedBaseUrlIfUnset(defaultUrl: String): Boolean {
        val normalised = AuthorityUrls.normalise(defaultUrl)
        if (normalised.isEmpty()) return false
        // Read-then-write in one edit block so a concurrent save cannot be clobbered: `edit` holds the
        // store's mutex, and the preference is only touched when it is genuinely absent.
        var wrote = false
        dataStore.edit { preferences ->
            val existing = preferences[KEY_BASE_URL]
            if (existing.isNullOrBlank()) {
                preferences[KEY_BASE_URL] = normalised
                wrote = true
            }
        }
        return wrote
    }

    private fun Preferences.toSession(): AuthoritySession = AuthoritySession(
        baseUrl = this[KEY_BASE_URL].orEmpty(),
        token = this[KEY_TOKEN],
        role = this[KEY_ROLE],
        subjectId = this[KEY_SUBJECT_ID],
        displayName = this[KEY_DISPLAY_NAME],
        expiresAtMillis = this[KEY_EXPIRES_AT] ?: 0L,
        signedInAtMillis = this[KEY_SIGNED_IN_AT] ?: 0L,
    )

    private fun Preferences.toOfficerSession(): OfficerSession = OfficerSession(
        baseUrl = this[KEY_BASE_URL].orEmpty(),
        token = this[KEY_OFFICER_TOKEN],
        subjectId = this[KEY_OFFICER_SUBJECT_ID],
        displayName = this[KEY_OFFICER_DISPLAY_NAME],
        expiresAtMillis = this[KEY_OFFICER_EXPIRES_AT] ?: 0L,
    )

    private companion object {
        val KEY_BASE_URL = stringPreferencesKey("base_url")
        val KEY_TOKEN = stringPreferencesKey("token")
        val KEY_ROLE = stringPreferencesKey("role")
        val KEY_SUBJECT_ID = stringPreferencesKey("subject_id")
        val KEY_DISPLAY_NAME = stringPreferencesKey("display_name")
        val KEY_EXPIRES_AT = longPreferencesKey("expires_at_millis")
        val KEY_SIGNED_IN_AT = longPreferencesKey("signed_in_at_millis")

        // Officer keys. Separate names, so a tourist sign-out can never revoke an officer's session.
        val KEY_OFFICER_TOKEN = stringPreferencesKey("officer_token")
        val KEY_OFFICER_SUBJECT_ID = stringPreferencesKey("officer_subject_id")
        val KEY_OFFICER_DISPLAY_NAME = stringPreferencesKey("officer_display_name")
        val KEY_OFFICER_EXPIRES_AT = longPreferencesKey("officer_expires_at_millis")
    }
}
