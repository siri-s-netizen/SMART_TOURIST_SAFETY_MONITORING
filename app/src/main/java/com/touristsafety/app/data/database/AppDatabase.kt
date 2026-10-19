package com.touristsafety.app.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.touristsafety.app.data.database.dao.EmergencyContactDao
import com.touristsafety.app.data.database.dao.GeoFenceAlertDao
import com.touristsafety.app.data.database.dao.GeoFenceDao
import com.touristsafety.app.data.database.dao.LocationRecordDao
import com.touristsafety.app.data.database.dao.OutboxEventDao
import com.touristsafety.app.data.database.entity.EmergencyContactEntity
import com.touristsafety.app.data.database.entity.GeoFenceAlertEntity
import com.touristsafety.app.data.database.entity.GeoFenceEntity
import com.touristsafety.app.data.database.entity.LocationRecordEntity
import com.touristsafety.app.data.database.entity.OutboxEventEntity

/**
 * The whole offline store: zones, GPS history and alert history.
 *
 * `exportSchema = true` is essential rather than cosmetic — the exported JSON under `app/schemas`
 * is the contract that makes non-destructive migrations reviewable in code review.
 *
 * Note there is deliberately **no** `fallbackToDestructiveMigration()`. A destructive fallback
 * would silently delete every saved zone and the entire alert history on an upgrade, which is
 * exactly the failure mode the project brief forbids. If a schema changes, add a
 * [DatabaseMigrations] entry instead.
 */
@Database(
    entities = [
        GeoFenceEntity::class,
        LocationRecordEntity::class,
        GeoFenceAlertEntity::class,
        OutboxEventEntity::class,
        EmergencyContactEntity::class,
    ],
    version = AppDatabase.VERSION,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun geoFenceDao(): GeoFenceDao

    abstract fun locationRecordDao(): LocationRecordDao

    abstract fun geoFenceAlertDao(): GeoFenceAlertDao

    abstract fun outboxEventDao(): OutboxEventDao

    abstract fun emergencyContactDao(): EmergencyContactDao

    companion object {
        /**
         * Schema version. Version 5 added the zone-provenance columns to `geo_fences`; the migration
         * in [DatabaseMigrations.MIGRATION_4_5] is additive, so an upgrade keeps every row.
         */
        const val VERSION: Int = 5
        const val DATABASE_NAME: String = "tourist_safety.db"

        @Volatile
        private var instance: AppDatabase? = null

        /** Process-wide singleton so the foreground service and the UI share one connection. */
        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        /** Builds a standalone instance; used by the singleton and by tests. */
        fun build(context: Context, name: String = DATABASE_NAME): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, name)
                .addMigrations(*DatabaseMigrations.ALL)
                .build()
    }
}
