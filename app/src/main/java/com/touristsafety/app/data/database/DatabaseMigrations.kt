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
 * Version 4 adds the `owner_subject_id` column to `outbox_events`, so a queued event remembers which
 * tourist raised it. It is an `ALTER TABLE ... ADD COLUMN`, so it is additive too: every existing row
 * survives, with an owner of `NULL` meaning "unassigned" (never "belongs to whoever is signed in
 * now").
 *
 * Version 5 adds the **zone provenance** columns (`source`, `authority_zone_id`, `authority_revision`)
 * to `geo_fences`, so a phone can hold zones the authority published as well as zones it drew itself.
 * Again three additive `ALTER TABLE` statements: every existing row survives, keeps its geometry and
 * is marked `LOCAL`, which is what it was.
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

    /**
     * Version 4 gives every queued outbox event an **owner**: the stable subject id of the tourist who
     * raised it (`owner_subject_id`).
     *
     * The SQL is a single additive `ALTER TABLE ... ADD COLUMN`, which is the safest upgrade SQLite
     * offers: existing rows are untouched (their new column is `NULL`), no table is rebuilt, no row is
     * copied and no row is deleted. A row that was already queued when the app is upgraded therefore
     * keeps its payload, its dedupe key, its status and its position in the queue; it simply has no
     * owner yet, and
     * [com.touristsafety.app.data.repository.OutboxRepository.pendingForDelivery] treats exactly that
     * as "unassigned, do not upload".
     *
     * The column is nullable with **no** default on purpose. A `DEFAULT ''` or a backfill to the
     * currently-signed-in tourist would be a silent adoption: it would assert an ownership the app
     * cannot prove, and the authority would then file those events against whoever the default named.
     *
     * The SQL must match what Room generates for
     * [com.touristsafety.app.data.database.entity.OutboxEventEntity]; it is checked against the
     * exported `app/schemas/com.touristsafety.app.data.database.AppDatabase/4.json`.
     */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `outbox_events` ADD COLUMN `owner_subject_id` TEXT")
        }
    }

    /**
     * Version 5 lets a phone hold zones it did **not** draw: `source`, `authority_zone_id` and
     * `authority_revision` on `geo_fences`.
     *
     * Three additive `ALTER TABLE ... ADD COLUMN` statements and nothing else. No table is rebuilt,
     * no row is copied, no row is deleted and no index is dropped, so every saved zone, GPS row,
     * alert, queued event and contact survives the upgrade untouched.
     *
     * `source` is `NOT NULL DEFAULT 'LOCAL'`, which is not a guess: a row that existed before this
     * version *was* a local zone, drawn on this phone. Migrating it to anything else would be
     * inventing a provenance, and
     * [com.touristsafety.app.domain.model.ZoneSource.AUTHORITY] in particular would authorise the
     * authority sync to overwrite or delete a zone a person created.
     *
     * `authority_zone_id` and `authority_revision` are nullable with **no** default, matching
     * [com.touristsafety.app.domain.model.GeoFenceZone.authorityZoneId] being null for a local zone. A
     * sentinel such as `-1` or `0` would be a real value that a buggy comparison could match against a
     * real authority id.
     *
     * The SQL must match what Room generates for
     * [com.touristsafety.app.data.database.entity.GeoFenceEntity]; it is checked against the exported
     * `app/schemas/com.touristsafety.app.data.database.AppDatabase/5.json`.
     */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `geo_fences` ADD COLUMN `source` TEXT NOT NULL DEFAULT 'LOCAL'",
            )
            db.execSQL("ALTER TABLE `geo_fences` ADD COLUMN `authority_zone_id` INTEGER")
            db.execSQL("ALTER TABLE `geo_fences` ADD COLUMN `authority_revision` INTEGER")
        }
    }

    /** Every migration, oldest first. Wired into [AppDatabase.build]. */
    val ALL: Array<Migration> =
        arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
}
