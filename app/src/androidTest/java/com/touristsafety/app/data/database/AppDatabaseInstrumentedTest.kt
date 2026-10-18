package com.touristsafety.app.data.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.touristsafety.app.data.repository.AlertRepository
import com.touristsafety.app.data.repository.GeoFenceRepository
import com.touristsafety.app.data.repository.LocationRepository
import com.touristsafety.app.data.repository.SaveZoneResult
import com.touristsafety.app.domain.model.GeoFenceAlert
import com.touristsafety.app.domain.model.GeoFenceZone
import com.touristsafety.app.domain.model.AlertEventType
import com.touristsafety.app.domain.model.GeoPoint
import com.touristsafety.app.domain.model.GeoReading
import com.touristsafety.app.domain.model.LocationSource
import com.touristsafety.app.domain.model.ZoneType
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
 * On-device persistence smoke test.
 *
 * This is the instrumented counterpart of the JVM `RoomPersistenceTest`. It runs against the real
 * Android SQLite implementation rather than Robolectric's shadow, so it is the check that the
 * shipped schema actually works on a handset.
 *
 * NOTE: this test has only been **compile-checked** in this environment — there is no emulator or
 * attached device available, so it has not been executed. Run it with `:app:connectedDebugAndroidTest`
 * on a real device.
 */
@RunWith(AndroidJUnit4::class)
class AppDatabaseInstrumentedTest {

    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun squareAround(center: GeoPoint, sideMeters: Double): List<GeoPoint> {
        val half = sideMeters / 2.0
        val deltaLat = half / GeoPoint.METERS_PER_DEGREE_LATITUDE
        val deltaLon = half / GeoPoint.METERS_PER_DEGREE_LATITUDE
        return listOf(
            GeoPoint(center.latitude - deltaLat, center.longitude - deltaLon),
            GeoPoint(center.latitude - deltaLat, center.longitude + deltaLon),
            GeoPoint(center.latitude + deltaLat, center.longitude + deltaLon),
            GeoPoint(center.latitude + deltaLat, center.longitude - deltaLon),
        )
    }

    @Test
    fun zonesAndAlertsPersistAndReload() = runBlocking {
        val geoFences = GeoFenceRepository(database.geoFenceDao())
        val alerts = AlertRepository(database.geoFenceAlertDao())

        val center = GeoPoint(48.8584, 2.2945)
        val result = geoFences.create(
            GeoFenceZone(
                name = "Instrumented zone",
                description = "",
                type = ZoneType.DANGEROUS,
                vertices = squareAround(center, 200.0),
                enabled = true,
            ),
        )
        assertTrue(result is SaveZoneResult.Saved)
        val id = (result as SaveZoneResult.Saved).id

        alerts.record(
            GeoFenceAlert(
                zoneId = id,
                zoneName = "Instrumented zone",
                zoneType = ZoneType.DANGEROUS,
                eventType = AlertEventType.ENTRY,
                point = center,
                accuracyMeters = 5f,
                timestampMillis = 1L,
            ),
        )

        val reloaded = geoFences.getZone(id)
        assertNotNull(reloaded)
        assertEquals(4, reloaded!!.vertexCount)
        assertEquals(1, alerts.observeHistory().first().size)
    }

    @Test
    fun clearingSimulatedLocationsKeepsRealOnes() = runBlocking {
        val locations = LocationRepository(database.locationRecordDao())

        locations.record(
            GeoReading(
                point = GeoPoint(48.8584, 2.2945),
                accuracyMeters = 4f,
                timestampMillis = 1L,
                source = LocationSource.GPS,
            ),
        )
        locations.record(
            GeoReading(
                point = GeoPoint(48.8584, 2.2945),
                accuracyMeters = 4f,
                timestampMillis = 2L,
                source = LocationSource.SIMULATION,
            ),
        )

        assertEquals(1, locations.clearSimulated())

        val remaining = locations.observeRecent(10).first()
        assertEquals(1, remaining.size)
        assertEquals(LocationSource.GPS, remaining.single().source)
    }
}
