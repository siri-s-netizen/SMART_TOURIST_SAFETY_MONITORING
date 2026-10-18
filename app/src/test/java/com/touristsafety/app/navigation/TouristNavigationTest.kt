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
}
