package com.touristsafety.app.data.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the migration path is **non-destructive**: a zone saved by the old schema is still there
 * afterwards, each new table (`outbox_events`, then `emergency_contacts`) is created, the ownership
 * column added in v4 leaves already-queued events on disk, unassigned, and the zone-provenance columns
 * added in v5 leave a zone that a person drew marked `LOCAL` quite deliberately.
 *
 * This uses Room's own [MigrationTestHelper], so it also *validates* the hand-written migration SQL
 * against the exported schema (a wrong column type would fail here).
 *
 * NOTE: as with all instrumented sources in this project, there is no emulator/device in the current
 * environment, so this test is **compile-checked only** and has **not been executed**. Run it on a
 * real device with `:app:connectedDebugAndroidTest`. The Robolectric-backed
 * `RoomPersistenceTest` runs the same migrations on the JVM, so the additivity claim is still
 * exercised in an ordinary test run.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {

    private val databaseName = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate1To2CreatesOutboxAndKeepsZones() {
        helper.createDatabase(databaseName, 1).use { db ->
            db.execSQL(
                "INSERT INTO geo_fences " +
                    "(name, description, zone_type, vertices, vertex_count, enabled, created_at, updated_at) " +
                    "VALUES ('v1 zone', '', 'RESTRICTED', '1.0,1.0;1.0,2.0;2.0,2.0', 3, 1, 1, 1)",
            )
        }

        helper.runMigrationsAndValidate(
            databaseName,
            2,
            true,
            DatabaseMigrations.MIGRATION_1_2,
        ).use { db ->
            db.query("SELECT COUNT(*) FROM geo_fences").use { cursor ->
                cursor.moveToFirst()
                assertEquals("the saved zone must survive the upgrade", 1, cursor.getInt(0))
            }
            db.query("SELECT COUNT(*) FROM outbox_events").use { cursor ->
                cursor.moveToFirst()
                assertEquals("the outbox starts empty", 0, cursor.getInt(0))
            }
        }
    }

    @Test
    fun migrate2To3CreatesContactsAndKeepsZones() {
        helper.createDatabase(databaseName, 2).use { db ->
            db.execSQL(
                "INSERT INTO geo_fences " +
                    "(name, description, zone_type, vertices, vertex_count, enabled, created_at, updated_at) " +
                    "VALUES ('v2 zone', '', 'RESTRICTED', '1.0,1.0;1.0,2.0;2.0,2.0', 3, 1, 1, 1)",
            )
        }

        helper.runMigrationsAndValidate(
            databaseName,
            3,
            true,
            DatabaseMigrations.MIGRATION_2_3,
        ).use { db ->
            db.query("SELECT COUNT(*) FROM geo_fences").use { cursor ->
                cursor.moveToFirst()
                assertEquals("the saved zone must survive the upgrade", 1, cursor.getInt(0))
            }
            db.query("SELECT COUNT(*) FROM emergency_contacts").use { cursor ->
                cursor.moveToFirst()
                assertEquals("the contact list starts empty", 0, cursor.getInt(0))
            }
        }
    }

    /**
     * The ownership upgrade, on the real device path Room validates.
     *
     * `runMigrationsAndValidate` compares the migrated table against the exported `4.json`, which is
     * what proves the hand-written `ALTER TABLE` matches the schema Room expects. The inserted row
     * stands in for an SOS a tourist queued and never managed to upload before upgrading.
     */
    @Test
    fun migrate3To4AddsOwnerColumnAndKeepsQueuedEvents() {
        helper.createDatabase(databaseName, 3).use { db ->
            db.execSQL(
                "INSERT INTO outbox_events " +
                    "(dedupe_key, kind, payload, created_at, status, origin, attempt_count) " +
                    "VALUES ('sos:queued-before-upgrade', 'SOS', '{}', 7, 'PENDING', 'GPS', 1)",
            )
        }

        helper.runMigrationsAndValidate(
            databaseName,
            4,
            true,
            DatabaseMigrations.MIGRATION_3_4,
        ).use { db ->
            db.query(
                "SELECT dedupe_key, status, attempt_count, owner_subject_id FROM outbox_events",
            ).use { cursor ->
                assertTrue("the queued event must survive the upgrade", cursor.moveToFirst())
                assertEquals("sos:queued-before-upgrade", cursor.getString(0))
                assertEquals("it must still be waiting to upload", "PENDING", cursor.getString(1))
                assertEquals("its retry history must be intact", 1, cursor.getInt(2))
                assertTrue(
                    "an event that predates ownership must stay unassigned, not be adopted",
                    cursor.isNull(3),
                )
                assertEquals("the upgrade must not invent or drop rows", 1, cursor.count)
            }
        }
    }

    /** The whole chain in one hop, matching a device still on the original build. */
    @Test
    fun migrate1To5KeepsZonesAndAddsEverything() {
        helper.createDatabase(databaseName, 1).use { db ->
            db.execSQL(
                "INSERT INTO geo_fences " +
                    "(name, description, zone_type, vertices, vertex_count, enabled, created_at, updated_at) " +
                    "VALUES ('v1 zone', '', 'RESTRICTED', '1.0,1.0;1.0,2.0;2.0,2.0', 3, 1, 1, 1)",
            )
        }

        helper.runMigrationsAndValidate(
            databaseName,
            5,
            true,
            DatabaseMigrations.MIGRATION_1_2,
            DatabaseMigrations.MIGRATION_2_3,
            DatabaseMigrations.MIGRATION_3_4,
            DatabaseMigrations.MIGRATION_4_5,
        ).use { db ->
            db.query("SELECT COUNT(*) FROM geo_fences").use { cursor ->
                cursor.moveToFirst()
                assertEquals("the saved zone must survive every upgrade", 1, cursor.getInt(0))
            }
            db.query("SELECT COUNT(*) FROM outbox_events").use { cursor ->
                cursor.moveToFirst()
                assertEquals("the outbox starts empty", 0, cursor.getInt(0))
            }
            db.query("SELECT COUNT(*) FROM emergency_contacts").use { cursor ->
                cursor.moveToFirst()
                assertEquals("the contact list starts empty", 0, cursor.getInt(0))
            }
            // The zone drawn on the original build must have been read as a local zone, and nothing
            // may have been downloaded on its behalf.
            db.query("SELECT source, authority_zone_id FROM geo_fences").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("a pre-existing zone is a local zone", "LOCAL", cursor.getString(0))
                assertTrue("and has no authority id", cursor.isNull(1))
            }
        }
    }

    /**
     * The zone-provenance upgrade, on the real device path Room validates.
     *
     * `runMigrationsAndValidate` compares the migrated `geo_fences` against the exported `5.json`, so
     * this is what proves the three hand-written `ALTER TABLE` statements match the schema Room
     * expects — including `source`'s `NOT NULL DEFAULT 'LOCAL'` and the two nullable authority columns.
     *
     * The zone inserted before the upgrade stands in for one a tourist drew themselves. It must come
     * out of the upgrade still local: a backfill to `AUTHORITY` would hand the sync permission to
     * overwrite or delete it.
     */
    @Test
    fun migrate4To5AddsZoneProvenanceAndKeepsDrawnZonesLocal() {
        helper.createDatabase(databaseName, 4).use { db ->
            db.execSQL(
                "INSERT INTO geo_fences " +
                    "(name, description, zone_type, vertices, vertex_count, enabled, created_at, updated_at) " +
                    "VALUES ('drawn by hand', '', 'DANGEROUS', '1.0,1.0;1.0,2.0;2.0,2.0', 3, 1, 11, 22)",
            )
        }

        helper.runMigrationsAndValidate(
            databaseName,
            5,
            true,
            DatabaseMigrations.MIGRATION_4_5,
        ).use { db ->
            db.query(
                "SELECT name, zone_type, vertices, enabled, source, authority_zone_id, " +
                    "authority_revision FROM geo_fences",
            ).use { cursor ->
                assertTrue("the drawn zone must survive the upgrade", cursor.moveToFirst())
                assertEquals("drawn by hand", cursor.getString(0))
                assertEquals("its type must be untouched", "DANGEROUS", cursor.getString(1))
                assertEquals("its geometry must be untouched", "1.0,1.0;1.0,2.0;2.0,2.0", cursor.getString(2))
                assertEquals("it must still be enabled", 1, cursor.getInt(3))
                assertEquals(
                    "a zone that existed before shared zones is a local zone, not the authority's",
                    "LOCAL",
                    cursor.getString(4),
                )
                assertTrue("the upgrade must not invent a zone id", cursor.isNull(5))
                assertTrue("the upgrade must not invent a revision", cursor.isNull(6))
                assertEquals("the upgrade must not invent or drop rows", 1, cursor.count)
            }
        }
    }
}
