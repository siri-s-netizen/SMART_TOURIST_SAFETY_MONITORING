package com.touristsafety.app.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Non-destructive upgrade path for the local store.
 *
 * The database started at version 1. Version 2 adds the offline **outbox** table
 * (`outbox_events`), which is purely additive: no existing column changes, so every saved zone,
 * GPS row and alert survives the upgrade. The SQL below must match the schema Room generates for
 * `OutboxEventEntity` exactly — that is verified by comparing against the exported
 * `app/schemas/com.touristsafety.app.data.database.AppDatabase/2.json`.
 *
 * Version 3 adds the **emergency contacts** table, also purely additive, so the same guarantee holds
 * at every step of the chain.
 *
 * ## How to add a migration
 *
 * 1. Bump `AppDatabase.VERSION` and change the entities.
 * 2. Add an entry here, keeping the existing ones, and append it to [ALL].
 * 3. Run `./gradlew :app:assembleDebug` so the next `N.json` schema is exported, and copy the
 *    `createSql` from it into the migration.
 * 4. Prove it with the instrumented migration test in
 *    `app/src/androidTest/.../DatabaseMigrationTest.kt`.
 *
 * Because [AppDatabase.build] never registers a destructive fallback, forgetting step 2 fails loudly
 * in development (`A migration from 1 to 2 was required`) instead of quietly wiping the tourist's
 * zones in the field.
 */
object DatabaseMigrations {

    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `outbox_events` (" +
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
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_outbox_events_dedupe_key` " +
                    "ON `outbox_events` (`dedupe_key`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_outbox_events_status` " +
                    "ON `outbox_events` (`status`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_outbox_events_created_at` " +
                    "ON `outbox_events` (`created_at`)",
            )
        }
    }

    /**
     * Version 3 adds the **emergency contacts** table. Also purely additive: one new table, no column
     * touched, so every zone, GPS row, alert and queued event survives.
     *
     * The SQL must match what Room generates for
     * [com.touristsafety.app.data.database.entity.EmergencyContactEntity]; it is checked against the
     * exported `app/schemas/com.touristsafety.app.data.database.AppDatabase/3.json`.
     */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `emergency_contacts` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`name` TEXT NOT NULL, " +
                    "`phone` TEXT NOT NULL, " +
                    "`relationship` TEXT, " +
                    "`priority` INTEGER NOT NULL, " +
                    "`updated_at` INTEGER NOT NULL)",
            )
        }
    }

    /** Every migration, oldest first. Wired into [AppDatabase.build]. */
    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
}
