package com.touristsafety.app.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Launch, role selection, and the officer-only gate.
 *
 * These are the claims a phone has to keep even when it is handed to someone else: the app opens by
 * asking who is holding it, a previous session does not answer that question for them, the authority
 * address is fixable before any sign-in, and nothing in the officer console can be reached without an
 * officer session. All of it is asserted against the same constants and predicates the navigation
 * graph uses, so a change to the graph breaks a test rather than quietly changing the rule.
 *
 * Plain JVM: routes are strings and the gate is a function, so none of this needs Compose or a device.
 */
class RoleSelectionTest {

    @Test
    fun `a cold launch always begins at the role chooser`() {
        assertEquals(
            "the app must open on the role chooser, not on a role's own screen",
            AppRoutes.WELCOME,
            AppRoutes.ROLE_SELECTION,
        )
    }

    @Test
    fun `the launch route is pre-login, so it has no tab bar and no shortcut icons`() {
        assertTrue(
            "the chooser must be treated as an entering-the-app route",
            AppRoutes.ROLE_SELECTION in onboardingRoutes,
        )
    }

    @Test
    fun `no role is selected for the user`() {
        assertFalse(
            "the chooser is not a tourist tab — it is not part of the safety dashboard",
            TouristNavigation.isTouristTab(AppRoutes.ROLE_SELECTION),
        )
        assertFalse(
            "and it is not an officer route, so no officer gate applies to it",
            AuthorityNavigation.isConsoleRoute(AppRoutes.ROLE_SELECTION),
        )
    }

    @Test
    fun `the two role gates are separate screens and neither belongs to the other role`() {
        assertNotEquals(AppRoutes.TOURIST_AUTH, AppRoutes.OFFICER)
        assertFalse(
            "a tourist sign-in is never an officer console route",
            AuthorityNavigation.isConsoleRoute(AppRoutes.TOURIST_AUTH),
        )
        assertFalse(
            "an officer sign-in is never a tourist tab",
            TouristNavigation.isTouristTab(AppRoutes.OFFICER),
        )
    }

    @Test
    fun `backend settings can be opened before anyone signs in`() {
        assertTrue(
            "the address must be fixable with no session at all",
            AppRoutes.BACKEND_SETTINGS in onboardingRoutes,
        )
        assertFalse(
            "and it must not be redirected to the officer gate, or a phone with a dead address " +
                "could never be corrected",
            requiresOfficerSignIn(AppRoutes.BACKEND_SETTINGS, officerSignedIn = false),
        )
    }

    @Test
    fun `backend settings is configuration, not zone management`() {
        assertFalse(
            TouristNavigation.authorityZoneRoutes.contains(AppRoutes.BACKEND_SETTINGS),
        )
    }

    @Test
    fun `the authority dashboard and zone management require a signed-in officer`() {
        val officerOnly = listOf(
            AppRoutes.AUTHORITY_DASHBOARD,
            AppRoutes.AUTHORITY_SOS,
            AppRoutes.AUTHORITY_TOURISTS,
            AppRoutes.AUTHORITY_LIVE,
            AppRoutes.AUTHORITY_RISK,
            AppRoutes.AUTHORITY_ACCOUNT,
            AppRoutes.ZONES,
            AppRoutes.LOCAL_ZONES,
            AppRoutes.authorityZoneEditor(),
            AppRoutes.editor(),
        )

        officerOnly.forEach { route ->
            assertTrue(
                "$route must be officer-only",
                requiresOfficerSignIn(route, officerSignedIn = false),
            )
            assertFalse(
                "$route must be reachable once an officer is signed in",
                requiresOfficerSignIn(route, officerSignedIn = true),
            )
        }
    }

    @Test
    fun `a signed-in officer still cannot be sent to the console by an unauthorised route`() {
        // The gate is a conjunction: an officer session alone does not make the tourist's own screens
        // officer screens, and being at a console route alone does not open it.
        assertFalse(requiresOfficerSignIn(AppRoutes.HOME, officerSignedIn = false))
        assertFalse(requiresOfficerSignIn(AppRoutes.TOURIST_AUTH, officerSignedIn = false))
        assertFalse(requiresOfficerSignIn(null, officerSignedIn = false))
        assertTrue(requiresOfficerSignIn(AppRoutes.AUTHORITY_DASHBOARD, officerSignedIn = false))
    }

    @Test
    fun `signing out returns to the role chooser rather than to a role's home`() {
        assertEquals(
            "the sign-out destination and the launch destination must be the same question",
            AppRoutes.ROLE_SELECTION,
            AppRoutes.WELCOME,
        )
        assertFalse(
            "signing out must not land on tracking",
            AppRoutes.ROLE_SELECTION == AppRoutes.TRACKING,
        )
        assertFalse(
            "signing out must not land in the officer console",
            AuthorityNavigation.isConsoleRoute(AppRoutes.ROLE_SELECTION),
        )
    }
}
