package com.touristsafety.app.data.database

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.touristsafety.app.data.repository.AlertRepository
import com.touristsafety.app.data.repository.GeoFenceRepository
import com.touristsafety.app.data.repository.LocationRepository
import com.touristsafety.app.data.repository.SaveZoneResult
import com.touristsafety.app.domain.model.AlertEventType
import com.touristsafety.app.domain.model.GeoFenceAlert
import com.touristsafety.app.domain.model.GeoReading
import com.touristsafety.app.domain.model.LocationSource
import com.touristsafety.app.domain.model.ZoneType
import com.touristsafety.app.testing.TestGeo
import com.touristsafety.app.testing.deleteDatabaseFile
import com.touristsafety.app.testing.fileDatabase
import com.touristsafety.app.testing.inMemoryDatabase
import com.touristsafety.app.testing.testReading
import com.touristsafety.app.testing.testZone
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Offline persistence, including the guarantee the brief cares most about: **closing and reopening
 * the app must not lose saved geo-fences or alert history.**
 *
 * One test deliberately uses a real *file* database rather than an in-memory one, because an
 * in-memory database is discarded on close and would prove nothing about durability.
 */
@RunWith(AndroidJUnit4::class)
class RoomPersistenceTest {

    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        database = inMemoryDatabase()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private val geoFences: GeoFenceRepository get() = GeoFenceRepository(database.geoFenceDao())
    private val locations: LocationRepository get() = LocationRepository(database.locationRecordDao())
    private val alerts: AlertRepository get() = AlertRepository(database.geoFenceAlertDao())

    // ------------------------------------------------------------ schema contract

    @Test
    fun `the schema is versioned and the migration path is non-destructive`() {
        assertEquals(3, AppDatabase.VERSION)
        assertTrue(
            "adding the offline outbox and the contact list must not lose the tourist's zones",
            DatabaseMigrations.ALL.isNotEmpty(),
        )
        // The registered path must be a contiguous chain from 1 to the current version: no step
        // skipped, nothing dangling, ending exactly on the version Room will open.
        var expectedStart = 1
        DatabaseMigrations.ALL.forEach { migration ->
            assertEquals("upgrades must run in order with no gaps", expectedStart, migration.startVersion)
            assertEquals(expectedStart + 1, migration.endVersion)
            expectedStart = migration.endVersion
        }
        assertEquals(
            "the chain must reach the current schema version",
            AppDatabase.VERSION,
            expectedStart,
        )
    }

    /**
     * Runs every registered migration against a real (in-memory) SQLite database and checks that a row
     * written before the upgrade is still readable afterwards, and that each new table appeared.
     *
     * The instrumented counterpart in `androidTest` uses Room's own `MigrationTestHelper`, but there is
     * no device or emulator in this environment, so it is compile-checked only. This is the executable
     * proof here that the upgrade is additive rather than destructive.
     */
    @Test
    fun `running the registered migrations keeps existing rows and creates the new tables`() {
        val db = openInMemorySqlite()
        try {
            // A stand-in for the v1 schema: one table holding one row, as the tourist left it.
            db.execSQL(
                "CREATE TABLE `geo_fences` " +
                    "(`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL)",
            )
            db.execSQL("INSERT INTO `geo_fences` (`name`) VALUES ('saved before the upgrade')")

            DatabaseMigrations.ALL.forEach { it.migrate(db) }

            db.query("SELECT COUNT(*) FROM `geo_fences`").use { cursor ->
                cursor.moveToFirst()
                assertEquals("a saved zone must survive every upgrade", 1, cursor.getInt(0))
            }
            db.query("SELECT COUNT(*) FROM `outbox_events`").use { cursor ->
                cursor.moveToFirst()
                assertEquals("the outbox starts empty", 0, cursor.getInt(0))
            }
            db.query("SELECT COUNT(*) FROM `emergency_contacts`").use { cursor ->
                cursor.moveToFirst()
                assertEquals("the contact list starts empty", 0, cursor.getInt(0))
            }
        } finally {
            db.close()
        }
    }

    /** A bare in-memory SQLite database, so a migration can be executed without Room in the way. */
    private fun openInMemorySqlite(): SupportSQLiteDatabase {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(null)
            .callback(
                object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                },
            )
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(configuration).writableDatabase
    }

    // ------------------------------------------------------------ durability

    @Test
    fun `zones and alert history survive closing and reopening the database`() = runBlocking {
        val name = "restart-survival-test.db"
        deleteDatabaseFile(name)

        val first = fileDatabase(name)
        val savedId: Long
        run {
            val repository = GeoFenceRepository(first.geoFenceDao())
            val result = repository.create(testZone(id = 0, type = ZoneType.DANGEROUS, name = "Cliff edge"))
            savedId = (result as SaveZoneResult.Saved).id
            AlertRepository(first.geoFenceAlertDao()).record(
                GeoFenceAlert(
                    zoneId = savedId,
                    zoneName = "Cliff edge",
                    zoneType = ZoneType.DANGEROUS,
                    eventType = AlertEventType.ENTRY,
                    point = TestGeo.squareCenter,
                    accuracyMeters = 5f,
                    timestampMillis = 42L,
                ),
            )
        }
        first.close()

        // Reopen: this is the moment a destructive migration would wipe everything.
        val second = fileDatabase(name)
        try {
            val zone = GeoFenceRepository(second.geoFenceDao()).getZone(savedId)
            assertNotNull("the saved zone must still exist", zone)
            assertEquals("Cliff edge", zone!!.name)
            assertEquals(ZoneType.DANGEROUS, zone.type)
            assertEquals(4, zone.vertexCount)

            val history = AlertRepository(second.geoFenceAlertDao()).observeHistory().first()
            assertEquals(1, history.size)
            assertEquals("Cliff edge", history.first().zoneName)
        } finally {
            second.close()
            deleteDatabaseFile(name)
        }
    }

    // ------------------------------------------------------------ zone CRUD

    @Test
    fun `vertex rings round trip through sqlite exactly`() = runBlocking {
        val vertices = TestGeo.squareVertices
        val result = geoFences.create(testZone(id = 0, vertices = vertices))
        val id = (result as SaveZoneResult.Saved).id

        val reloaded = geoFences.getZone(id)!!.vertices

        assertEquals(vertices, reloaded)
        // Order is what makes ray casting correct; assert it explicitly rather than trusting equals.
        assertEquals(vertices[0], reloaded[0])
        assertEquals(vertices[3], reloaded[3])
    }

    @Test
    fun `updating a zone keeps its id and creation time but moves the update time`() = runBlocking {
        var clock = 1_000L
        val repository = GeoFenceRepository(database.geoFenceDao()) { clock }

        val id = (repository.create(testZone(id = 0, name = "Original")) as SaveZoneResult.Saved).id
        clock = 5_000L
        repository.update(testZone(id = id, name = "Renamed").copy(vertices = TestGeo.squareVertices))

        val reloaded = repository.getZone(id)!!
        assertEquals(id, reloaded.id)
        assertEquals("Renamed", reloaded.name)
        assertEquals(1_000L, reloaded.createdAtMillis)
        assertEquals(5_000L, reloaded.updatedAtMillis)
    }

    @Test
    fun `an invalid polygon is rejected before it reaches sqlite`() = runBlocking {
        val tooFewVertices = geoFences.create(
            testZone(id = 0, vertices = TestGeo.squareVertices.take(2)),
        )
        assertTrue(tooFewVertices is SaveZoneResult.Rejected)

        val selfCrossing = geoFences.create(testZone(id = 0, vertices = TestGeo.bowtie()))
        assertTrue(selfCrossing is SaveZoneResult.Rejected)

        val zeroArea = geoFences.create(testZone(id = 0, vertices = TestGeo.degenerateLine()))
        assertTrue(zeroArea is SaveZoneResult.Rejected)

        val blankName = geoFences.create(testZone(id = 0, name = "   "))
        assertTrue(blankName is SaveZoneResult.Rejected)

        assertEquals("nothing invalid may be stored", 0, geoFences.getAllZones().size)
    }

    @Test
    fun `disabling a zone removes it from the enabled snapshot`() = runBlocking {
        val enabled = (geoFences.create(testZone(id = 0, name = "Active")) as SaveZoneResult.Saved).id
        val paused = (geoFences.create(testZone(id = 0, name = "Paused")) as SaveZoneResult.Saved).id

        geoFences.setEnabled(paused, false)

        val active = geoFences.getEnabledZones()
        assertEquals(1, active.size)
        assertEquals(enabled, active.single().id)
        assertTrue(active.single().enabled)
        assertEquals(2, geoFences.getAllZones().size)
        assertEquals(paused, geoFences.getZone(paused)!!.id)
    }

    @Test
    fun `deleting every zone keeps the alert history`() = runBlocking {
        val id = (geoFences.create(testZone(id = 0, name = "Beach")) as SaveZoneResult.Saved).id
        alerts.record(
            GeoFenceAlert(
                zoneId = id,
                zoneName = "Beach",
                zoneType = ZoneType.SAFE,
                eventType = AlertEventType.ENTRY,
                point = TestGeo.squareCenter,
                accuracyMeters = 4f,
                timestampMillis = 10L,
            ),
        )

        geoFences.deleteAll()

        assertEquals(0, geoFences.getAllZones().size)
        assertEquals(
            "history records what happened and must outlive the zone itself",
            1,
            alerts.observeHistory().first().size,
        )
        assertNull(geoFences.getZone(id))
    }

    // ------------------------------------------------------------ location history

    @Test
    fun `pruning keeps only the newest location rows`() = runBlocking {
        (1..5).forEach { index ->
            locations.record(testReading(TestGeo.squareCenter, timestampMillis = index * 1_000L))
        }
        assertEquals(5, locations.observeCount().first())

        locations.pruneToLatest(keep = 2)

        val remaining = locations.observeRecent(10).first()
        assertEquals(2, remaining.size)
        assertEquals(listOf(5_000L, 4_000L), remaining.map { it.timestampMillis })
    }

    @Test
    fun `clearing simulated data never touches real gps rows`() = runBlocking {
        locations.record(testReading(TestGeo.outsideSouth, timestampMillis = 1L))
        locations.record(
            GeoReading(
                point = TestGeo.squareCenter,
                accuracyMeters = 3f,
                timestampMillis = 2L,
                source = LocationSource.SIMULATION,
            ),
        )
        alerts.record(
            GeoFenceAlert(
                zoneId = 1L,
                zoneName = "Real",
                zoneType = ZoneType.RESTRICTED,
                eventType = AlertEventType.ENTRY,
                point = TestGeo.squareCenter,
                accuracyMeters = 5f,
                timestampMillis = 1L,
                source = LocationSource.GPS,
            ),
        )
        alerts.record(
            GeoFenceAlert(
                zoneId = 1L,
                zoneName = "Demo",
                zoneType = ZoneType.RESTRICTED,
                eventType = AlertEventType.ENTRY,
                point = TestGeo.squareCenter,
                accuracyMeters = 5f,
                timestampMillis = 2L,
                source = LocationSource.SIMULATION,
            ),
        )

        val removedLocations = locations.clearSimulated()
        val removedAlerts = alerts.clearSimulated()

        assertEquals(1, removedLocations)
        assertEquals(1, removedAlerts)

        val remainingLocations = locations.observeRecent(10).first()
        assertEquals(1, remainingLocations.size)
        assertEquals(LocationSource.GPS, remainingLocations.single().source)

        val remainingAlerts = alerts.observeHistory().first()
        assertEquals(1, remainingAlerts.size)
        assertEquals("Real", remainingAlerts.single().zoneName)
    }

    // ------------------------------------------------------------ alert bookkeeping

    @Test
    fun `acknowledging alerts is reflected in the unacknowledged count`() = runBlocking {
        (1..3).forEach { index ->
            alerts.record(
                GeoFenceAlert(
                    zoneId = 1L,
                    zoneName = "Zone",
                    zoneType = ZoneType.RESTRICTED,
                    eventType = AlertEventType.ENTRY,
                    point = TestGeo.squareCenter,
                    accuracyMeters = 5f,
                    timestampMillis = index.toLong(),
                ),
            )
        }
        assertEquals(3, alerts.observeUnacknowledgedCount().first())

        val first = alerts.observeHistory().first().first()
        alerts.acknowledge(first.id)
        assertEquals(2, alerts.observeUnacknowledgedCount().first())

        alerts.acknowledgeAll()
        assertEquals(0, alerts.observeUnacknowledgedCount().first())
    }

    @Test
    fun `the newest location row is readable and distinct from older ones`() = runBlocking {
        locations.record(testReading(TestGeo.outsideSouth, timestampMillis = 1_000L))
        locations.record(testReading(TestGeo.squareCenter, timestampMillis = 9_000L))

        val latest = locations.latest()
        assertNotNull(latest)
        assertEquals(9_000L, latest!!.timestampMillis)
        assertNotEquals(TestGeo.outsideSouth.latitude, latest.point.latitude, 1e-9)
    }
}
