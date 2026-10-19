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
        assertEquals(5, AppDatabase.VERSION)
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

    /**
     * The one thing the ownership upgrade must *not* do is decide for itself which tourist a queued
     * event belonged to: it does not know, and guessing would have the authority file the event
     * against the wrong person. So the column is added as `NULL` — meaning *unassigned* — and the rows
     * themselves are left exactly as they were.
     *
     * This runs the real [DatabaseMigrations.MIGRATION_3_4] against a real SQLite database, so it
     * exercises the actual `ALTER TABLE` rather than a description of it.
     */
    @Test
    fun `the ownership upgrade adds the owner column and leaves queued events unassigned`() {
        val db = openInMemorySqlite()
        try {
            // The v3 outbox, as it exists on a device that queued an SOS before this build.
            db.execSQL(
                "CREATE TABLE `outbox_events` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`dedupe_key` TEXT NOT NULL, " +
                    "`kind` TEXT NOT NULL, " +
                    "`payload` TEXT NOT NULL, " +
                    "`created_at` INTEGER NOT NULL, " +
                    "`attempt_count` INTEGER NOT NULL DEFAULT 0, " +
                    "`last_attempt_at` INTEGER, " +
                    "`status` TEXT NOT NULL, " +
                    "`origin` TEXT NOT NULL, " +
                    "`last_error` TEXT)",
            )
            db.execSQL(
                "INSERT INTO `outbox_events` " +
                    "(`dedupe_key`,`kind`,`payload`,`created_at`,`status`,`origin`,`attempt_count`) " +
                    "VALUES ('sos:queued-before-the-upgrade','SOS','{}',7,'PENDING','GPS',1)",
            )

            DatabaseMigrations.MIGRATION_3_4.migrate(db)

            db.query(
                "SELECT `dedupe_key`, `status`, `attempt_count`, `owner_subject_id` FROM `outbox_events`",
            ).use { cursor ->
                assertTrue("the queued event must survive the upgrade", cursor.moveToFirst())
                assertEquals("sos:queued-before-the-upgrade", cursor.getString(0))
                assertEquals("it must still be waiting to upload", "PENDING", cursor.getString(1))
                assertEquals("its retry history must be intact", 1, cursor.getInt(2))
                assertTrue(
                    "an event queued before ownership existed must stay unassigned, not be adopted",
                    cursor.isNull(3),
                )
                assertEquals("the upgrade must not invent or drop rows", 1, cursor.count)
            }

            db.query("PRAGMA table_info(`outbox_events`)").use { cursor ->
                val nameColumn = cursor.getColumnIndexOrThrow("name")
                val notNullColumn = cursor.getColumnIndexOrThrow("notnull")
                val defaultColumn = cursor.getColumnIndexOrThrow("dflt_value")
                var found = false
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameColumn) != "owner_subject_id") continue
                    found = true
                    assertEquals(
                        "unassigned is the absence of an owner",
                        0,
                        cursor.getInt(notNullColumn),
                    )
                    assertTrue(
                        "no default: a default would silently adopt every old row",
                        cursor.isNull(defaultColumn),
                    )
                }
                assertTrue("the upgrade must add owner_subject_id", found)
            }
        } finally {
            db.close()
        }
    }

    /**
     * The zone-provenance upgrade must not re-label a zone the tourist drew.
     *
     * Three columns are added to `geo_fences`, and each of the three has a "correct" value that is
     * *not* the app's guess: `source` is `LOCAL`, because a row that existed before this build was a
     * zone drawn on this phone, and the two authority columns are `NULL`, because this phone has never
     * downloaded anything. Marking such a row `AUTHORITY` would authorise the sync to overwrite or
     * delete it; giving it a sentinel id like `-1` would be a real value a comparison could match.
     *
     * This runs the real [DatabaseMigrations.MIGRATION_4_5] against a real SQLite database, and then
     * checks the column metadata as well as the data, so a "fix" that adds the columns with the wrong
     * nullability or the wrong default fails here.
     */
    @Test
    fun `the zone provenance upgrade keeps drawn zones local and downloads nothing`() {
        val db = openInMemorySqlite()
        try {
            // The v4 `geo_fences`, as it exists on a device that drew a zone before this build.
            db.execSQL(
                "CREATE TABLE `geo_fences` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`name` TEXT NOT NULL, " +
                    "`description` TEXT NOT NULL, " +
                    "`zone_type` TEXT NOT NULL, " +
                    "`vertices` TEXT NOT NULL, " +
                    "`vertex_count` INTEGER NOT NULL, " +
                    "`enabled` INTEGER NOT NULL DEFAULT 1, " +
                    "`created_at` INTEGER NOT NULL, " +
                    "`updated_at` INTEGER NOT NULL)",
            )
            db.execSQL(
                "INSERT INTO `geo_fences` " +
                    "(`name`,`description`,`zone_type`,`vertices`,`vertex_count`,`enabled`," +
                    "`created_at`,`updated_at`) " +
                    "VALUES ('Drawn by hand','','RESTRICTED','12.9,77.5;12.9,77.6;13.0,77.6',3,1,11,22)",
            )

            DatabaseMigrations.MIGRATION_4_5.migrate(db)

            db.query(
                "SELECT `name`, `vertices`, `enabled`, `source`, `authority_zone_id`, " +
                    "`authority_revision` FROM `geo_fences`",
            ).use { cursor ->
                assertTrue("the drawn zone must survive the upgrade", cursor.moveToFirst())
                assertEquals("Drawn by hand", cursor.getString(0))
                assertEquals(
                    "its geometry must be untouched",
                    "12.9,77.5;12.9,77.6;13.0,77.6",
                    cursor.getString(1),
                )
                assertEquals("it must still be enabled", 1, cursor.getInt(2))
                assertEquals(
                    "a zone that existed before shared zones is a local zone",
                    "LOCAL",
                    cursor.getString(3),
                )
                assertTrue(
                    "the upgrade must not invent a zone id",
                    cursor.isNull(4),
                )
                assertTrue(
                    "the upgrade must not invent a revision",
                    cursor.isNull(5),
                )
                assertEquals("the upgrade must not invent or drop rows", 1, cursor.count)
            }

            val columns = mutableMapOf<String, Pair<Int, String?>>()
            db.query("PRAGMA table_info(`geo_fences`)").use { cursor ->
                val nameColumn = cursor.getColumnIndexOrThrow("name")
                val notNullColumn = cursor.getColumnIndexOrThrow("notnull")
                val defaultColumn = cursor.getColumnIndexOrThrow("dflt_value")
                while (cursor.moveToNext()) {
                    columns[cursor.getString(nameColumn)] = cursor.getInt(notNullColumn) to
                        if (cursor.isNull(defaultColumn)) null else cursor.getString(defaultColumn)
                }
            }

            val source = columns["source"]
            assertNotNull("the source column must exist", source)
            assertEquals("source is never null", 1, source!!.first)
            assertEquals(
                "and defaults to LOCAL, which is not a guess: the row was local",
                "'LOCAL'",
                source.second,
            )

            val authorityId = columns["authority_zone_id"]
            assertNotNull("the authority id column must exist", authorityId)
            assertEquals("an authority id may be absent", 0, authorityId!!.first)
            assertNull("no sentinel default for the authority id", authorityId.second)

            val revision = columns["authority_revision"]
            assertNotNull("the revision column must exist", revision)
            assertEquals("a revision may be absent", 0, revision!!.first)
            assertNull("no sentinel default for the revision", revision.second)
        } finally {
            db.close()
        }
    }

    /** A bare in-memory SQLite database, so a migration can be executed without Room in the way. */
    private fun openInMemorySqlite(): SupportSQLiteDatabase {        val context = ApplicationProvider.getApplicationContext<Context>()
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
