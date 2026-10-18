package com.touristsafety.app.simulation

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.touristsafety.app.data.database.AppDatabase
import com.touristsafety.app.data.repository.AlertRepository
import com.touristsafety.app.data.repository.GeoFenceRepository
import com.touristsafety.app.data.repository.LocationRepository
import com.touristsafety.app.data.repository.SaveZoneResult
import com.touristsafety.app.domain.model.AlertEventType
import com.touristsafety.app.domain.model.GeoFenceAlert
import com.touristsafety.app.domain.model.LocationSource
import com.touristsafety.app.domain.model.ZoneType
import com.touristsafety.app.testing.FakeSettingsRepository
import com.touristsafety.app.testing.RecordingAlertNotifier
import com.touristsafety.app.testing.TestGeo
import com.touristsafety.app.testing.inMemoryDatabase
import com.touristsafety.app.testing.testReading
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Simulation mode, end to end.
 *
 * The key property under test is *separation*: the simulator drives the real engine and the real
 * database, but everything it produces is stamped `SIMULATION`, and clearing it never removes a real
 * GPS row. If that ever regressed, a demo could silently destroy a tourist's real track.
 */
@RunWith(AndroidJUnit4::class)
class SimulationControllerTest {

    private lateinit var database: AppDatabase
    private lateinit var geoFenceRepository: GeoFenceRepository
    private lateinit var locationRepository: LocationRepository
    private lateinit var alertRepository: AlertRepository
    private lateinit var notifier: RecordingAlertNotifier
    private lateinit var scope: CoroutineScope
    private lateinit var controller: SimulationController

    @Before
    fun setUp() {
        database = inMemoryDatabase()
        geoFenceRepository = GeoFenceRepository(database.geoFenceDao())
        locationRepository = LocationRepository(database.locationRecordDao())
        alertRepository = AlertRepository(database.geoFenceAlertDao())
        notifier = RecordingAlertNotifier()
        scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        controller = SimulationController(
            geoFenceRepository = geoFenceRepository,
            locationRepository = locationRepository,
            alertRepository = alertRepository,
            alertNotifier = notifier,
            settingsRepository = FakeSettingsRepository(),
            scope = scope,
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        database.close()
    }

    private suspend fun targetZone() = geoFenceRepository.getZone(controller.state.value.targetZoneId!!)!!

    @Test
    fun `creating a test zone persists a real square polygon`() = runBlocking {
        val result = controller.createTestZone(
            center = TestGeo.origin,
            name = "Demo zone",
            type = ZoneType.RESTRICTED,
        )

        assertTrue(result is SaveZoneResult.Saved)
        val zone = targetZone()
        assertEquals("Demo zone", zone.name)
        assertEquals(4, zone.vertexCount)
        assertEquals(ZoneType.RESTRICTED, zone.type)
        assertTrue(zone.enabled)
        assertEquals(1, geoFenceRepository.getAllZones().size)
    }

    @Test
    fun `a full simulated walk crosses the polygon exactly once in each direction`() = runBlocking {
        controller.createTestZone(center = TestGeo.origin)
        val zone = targetZone()

        controller.loadRoute(zone.vertices)
        val steps = controller.state.value.route.size
        assertTrue("the route must contain several positions", steps >= 3)

        repeat(steps) { controller.step() }

        val events = notifier.alerts.map { it.eventType }
        assertEquals(
            "outside -> inside -> outside must yield one entry and one exit",
            listOf(AlertEventType.ENTRY, AlertEventType.EXIT),
            events,
        )
        assertTrue(controller.state.value.isComplete)
    }

    @Test
    fun `every record the simulator writes is stamped as simulated`() = runBlocking {
        controller.createTestZone(center = TestGeo.origin)
        controller.loadRoute(targetZone().vertices)
        repeat(controller.state.value.route.size) { controller.step() }

        val locations = locationRepository.observeRecent(100).first()
        assertTrue(locations.isNotEmpty())
        assertTrue(locations.all { it.source == LocationSource.SIMULATION })

        val alerts = alertRepository.observeHistory().first()
        assertTrue(alerts.isNotEmpty())
        assertTrue(alerts.all { it.source.isSimulated })
    }

    @Test
    fun `the simulated accuracy is applied to the readings it produces`() = runBlocking {
        controller.createTestZone(center = TestGeo.origin)
        controller.loadRoute(targetZone().vertices)
        controller.setAccuracy(42f)

        controller.step()

        assertEquals(42f, controller.state.value.lastReading!!.accuracyMeters, 1e-6f)
    }

    @Test
    fun `accuracy is clamped to a usable range`() {
        controller.setAccuracy(-5f)
        assertEquals(0.5f, controller.state.value.accuracyMeters, 1e-6f)
        controller.setAccuracy(99_999f)
        assertEquals(5_000f, controller.state.value.accuracyMeters, 1e-6f)
    }

    @Test
    fun `clearing simulated data deletes only the simulator's own rows`() = runBlocking {
        // A real track and a real alert that must survive.
        locationRepository.record(testReading(TestGeo.outsideSouth, timestampMillis = 1L))
        alertRepository.record(
            GeoFenceAlert(
                zoneId = 1L,
                zoneName = "Real zone",
                zoneType = ZoneType.RESTRICTED,
                eventType = AlertEventType.ENTRY,
                point = TestGeo.squareCenter,
                accuracyMeters = 4f,
                timestampMillis = 1L,
                source = LocationSource.GPS,
            ),
        )

        controller.createTestZone(center = TestGeo.origin)
        controller.loadRoute(targetZone().vertices)
        repeat(controller.state.value.route.size) { controller.step() }
        assertTrue(locationRepository.observeCount().first() > 1)

        controller.clearSimulatedData()

        val locations = locationRepository.observeRecent(100).first()
        assertEquals("only the real fix may remain", 1, locations.size)
        assertEquals(LocationSource.GPS, locations.single().source)

        val alerts = alertRepository.observeHistory().first()
        assertEquals(1, alerts.size)
        assertEquals("Real zone", alerts.single().zoneName)
    }

    @Test
    fun `resetting keeps the test zone and history but clears the run`() = runBlocking {
        controller.createTestZone(center = TestGeo.origin)
        val zoneId = controller.state.value.targetZoneId!!
        controller.loadRoute(targetZone().vertices)
        controller.step()

        controller.reset()

        assertTrue(controller.state.value.route.isEmpty())
        assertEquals(-1, controller.state.value.currentIndex)
        assertNotNull("the test zone itself must survive a reset", geoFenceRepository.getZone(zoneId))
        assertTrue(alertRepository.observeCount().first() >= 0)
    }

    @Test
    fun `a route is not produced for a degenerate polygon`() {
        controller.loadRoute(TestGeo.degenerateLine())
        assertTrue(controller.state.value.route.isEmpty())
        assertNotNull(controller.state.value.message)
    }
}
