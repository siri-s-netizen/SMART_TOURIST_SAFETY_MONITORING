package com.touristsafety.app.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tourist navigation must not contain a zone-management surface.
 *
 * This is the regression that matters after moving zone management to the authority: someone
 * re-adding a "Create" tab, or putting `zones` back in the bottom bar, would fail here rather than
 * shipping. The server-side 403 is the real boundary (see `backend/tests/test_zones.py`); this test
 * guards the *usability* half — a tourist who never sees the control never wonders about it.
 */
class TouristNavigationTest {

    @Test
    fun `the tourist tabs are home, tracking, alerts and settings`() {
        assertEquals(
            listOf(
                AppRoutes.HOME,
                AppRoutes.TRACKING,
                AppRoutes.ALERTS,
                AppRoutes.SETTINGS,
            ),
            TouristNavigation.tabRoutes,
        )
    }

    @Test
    fun `no tourist tab is a zone-management route`() {
        assertEquals(emptyList<String>(), TouristNavigation.tabRoutes.intersect(
            TouristNavigation.authorityZoneRoutes.toSet(),
        ).toList())
    }

    @Test
    fun `zones and the editor are authority-only and are not tourist tabs`() {
        assertFalse(TouristNavigation.isTouristTab(AppRoutes.ZONES))
        assertFalse(TouristNavigation.isTouristTab("editor"))
        assertFalse(TouristNavigation.isTouristTab(AppRoutes.editor()))
        assertTrue(TouristNavigation.authorityZoneRoutes.contains(AppRoutes.ZONES))
        assertTrue(TouristNavigation.authorityZoneRoutes.any { AppRoutes.editor().startsWith(it) })
    }

    @Test
    fun `the editor route defaults to creating a new zone`() {
        assertEquals("editor?zoneId=-1", AppRoutes.editor())
        assertEquals("editor?zoneId=7", AppRoutes.editor(7L))
    }

    /**
     * The officer console is a second, separate navigation graph — and a tourist must not be able to
     * reach any of it from the bottom bar.
     *
     * The server is the real boundary (`require_auth("authority")` on every console route, see
     * `backend/tests/`); this guards the usability half, the same way the zone tests above do. The
     * console's own bottom bar is asserted separately, because a screen that is unreachable from the
     * tourist bar and unreachable from the officer bar too would be dead code rather than a boundary.
     */
    @Test
    fun `no authority console route is a tourist tab`() {
        assertEquals(
            emptyList<String>(),
            TouristNavigation.tabRoutes.intersect(AuthorityNavigation.consoleRoutes.toSet()).toList(),
        )
        AuthorityNavigation.consoleRoutes.forEach { route ->
            assertFalse("'$route' is officer-only", TouristNavigation.isTouristTab(route))
        }
    }

    @Test
    fun `the officer bottom bar covers five destinations and every console section is reachable`() {
        assertEquals(
            listOf(
                AppRoutes.AUTHORITY_DASHBOARD,
                AppRoutes.AUTHORITY_SOS,
                AppRoutes.ZONES,
                AppRoutes.AUTHORITY_TOURISTS,
                AppRoutes.AUTHORITY_LIVE,
            ),
            AuthorityNavigation.tabRoutes,
        )
        // Five items, because a Material navigation bar stops being readable past that on a phone.
        assertEquals(5, AuthorityNavigation.tabRoutes.size)

        // Every section of the console is either a tab or a secondary route — nothing is unreachable.
        val all = AuthorityNavigation.consoleRoutes.toSet()
        assertTrue(all.containsAll(AuthorityNavigation.tabRoutes))
        listOf(
            AppRoutes.AUTHORITY_RISK,
            AppRoutes.AUTHORITY_ACCOUNT,
            AppRoutes.LOCAL_ZONES,
        ).forEach { route -> assertTrue("'$route' must be reachable", all.contains(route)) }
        assertTrue(
            "the authority zone editor must be reachable",
            AuthorityNavigation.isConsoleRoute(AppRoutes.authorityZoneEditor()),
        )
        assertTrue(
            "and so must the local editor, which is kept rather than deleted",
            AuthorityNavigation.isConsoleRoute(AppRoutes.editor()),
        )
    }

    @Test
    fun `console-route detection matches the parameterised editors but not a tourist route`() {
        assertTrue(AuthorityNavigation.isConsoleRoute(AppRoutes.AUTHORITY_DASHBOARD))
        assertTrue(AuthorityNavigation.isConsoleRoute(AppRoutes.authorityZoneEditor(7)))
        assertTrue(AuthorityNavigation.isConsoleRoute(AppRoutes.editor(7L)))
        assertFalse(AuthorityNavigation.isConsoleRoute(AppRoutes.HOME))
        assertFalse(AuthorityNavigation.isConsoleRoute(AppRoutes.WELCOME))
        assertFalse(AuthorityNavigation.isConsoleRoute(null))
        // A prefix match must not be a substring match: `editor` must not capture `editorial`.
        assertFalse(AuthorityNavigation.isConsoleRoute("editorial"))
    }
}
