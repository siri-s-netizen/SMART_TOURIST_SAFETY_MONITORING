package com.touristsafety.app.data.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the migration path is **non-destructive**: a zone saved by the old schema is still there
 * afterwards, and each new table (`outbox_events`, then `emergency_contacts`) is created.
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

    /** The whole chain in one hop, matching what a device still on the original build will run. */
    @Test
    fun migrate1To3KeepsZonesAndAddsBothTables() {
        helper.createDatabase(databaseName, 1).use { db ->
            db.execSQL(
                "INSERT INTO geo_fences " +
                    "(name, description, zone_type, vertices, vertex_count, enabled, created_at, updated_at) " +
                    "VALUES ('v1 zone', '', 'RESTRICTED', '1.0,1.0;1.0,2.0;2.0,2.0', 3, 1, 1, 1)",
            )
        }

        helper.runMigrationsAndValidate(
            databaseName,
            3,
            true,
            DatabaseMigrations.MIGRATION_1_2,
            DatabaseMigrations.MIGRATION_2_3,
        ).use { db ->
            db.query("SELECT COUNT(*) FROM geo_fences").use { cursor ->
                cursor.moveToFirst()
                assertEquals("the saved zone must survive both upgrades", 1, cursor.getInt(0))
            }
            db.query("SELECT COUNT(*) FROM outbox_events").use { cursor ->
                cursor.moveToFirst()
                assertEquals("the outbox starts empty", 0, cursor.getInt(0))
            }
            db.query("SELECT COUNT(*) FROM emergency_contacts").use { cursor ->
                cursor.moveToFirst()
                assertEquals("the contact list starts empty", 0, cursor.getInt(0))
            }
        }
    }
}
