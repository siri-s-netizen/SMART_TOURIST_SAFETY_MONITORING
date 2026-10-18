package com.touristsafety.app.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * Persisted user preferences.
 *
 * An interface rather than a concrete class so the tracking pipeline can be unit-tested without a
 * DataStore (and therefore without Robolectric) by substituting an in-memory implementation.
 */
interface SettingsRepository {
    val settings: Flow<AppSettings>

    /** Applies an arbitrary change; used by the Settings screen's individual controls. */
    suspend fun update(transform: (AppSettings) -> AppSettings)

    suspend fun replace(settings: AppSettings)

    suspend fun setBackgroundTrackingEnabled(enabled: Boolean)

    suspend fun setNotificationsEnabled(enabled: Boolean)

    suspend fun setSimulationModeEnabled(enabled: Boolean)
}

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "tourist_safety_settings",
)

/** DataStore-backed implementation. Survives process death and app restarts. */
class DataStoreSettingsRepository(private val context: Context) : SettingsRepository {

    private object Keys {
        val updateIntervalMillis = longPreferencesKey("update_interval_millis")
        val minUpdateDistanceMeters = floatPreferencesKey("min_update_distance_meters")
        val accuracyPriority = stringPreferencesKey("accuracy_priority")
        val backgroundTracking = booleanPreferencesKey("background_tracking_enabled")
        val notifications = booleanPreferencesKey("notifications_enabled")
        val simulationMode = booleanPreferencesKey("simulation_mode_enabled")
        val entryTolerance = floatPreferencesKey("entry_tolerance_meters")
        val exitTolerance = floatPreferencesKey("exit_tolerance_meters")
        val maxUsableAccuracy = floatPreferencesKey("max_usable_accuracy_meters")
        val retainedRecords = intPreferencesKey("retained_location_records")
        val positionReporting = booleanPreferencesKey("position_reporting_enabled")
        val positionReportInterval = longPreferencesKey("position_report_interval_millis")
    }

    override val settings: Flow<AppSettings> = context.settingsDataStore.data
        // A corrupt preferences file must not crash the safety-critical path; fall back to defaults.
        .catch { throwable ->
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map { preferences ->
            val defaults = AppSettings()
            AppSettings(
                updateIntervalMillis = preferences[Keys.updateIntervalMillis]
                    ?: defaults.updateIntervalMillis,
                minUpdateDistanceMeters = preferences[Keys.minUpdateDistanceMeters]
                    ?: defaults.minUpdateDistanceMeters,
                accuracyPriority = LocationAccuracyPriority.fromStorage(
                    preferences[Keys.accuracyPriority],
                ),
                backgroundTrackingEnabled = preferences[Keys.backgroundTracking]
                    ?: defaults.backgroundTrackingEnabled,
                notificationsEnabled = preferences[Keys.notifications] ?: defaults.notificationsEnabled,
                simulationModeEnabled = preferences[Keys.simulationMode] ?: defaults.simulationModeEnabled,
                entryToleranceMeters = preferences[Keys.entryTolerance]?.toDouble()
                    ?: defaults.entryToleranceMeters,
                exitToleranceMeters = preferences[Keys.exitTolerance]?.toDouble()
                    ?: defaults.exitToleranceMeters,
                maxUsableAccuracyMeters = preferences[Keys.maxUsableAccuracy]?.toDouble()
                    ?: defaults.maxUsableAccuracyMeters,
                retainedLocationRecords = preferences[Keys.retainedRecords]
                    ?: defaults.retainedLocationRecords,
                positionReportingEnabled = preferences[Keys.positionReporting]
                    ?: defaults.positionReportingEnabled,
                positionReportIntervalMillis = preferences[Keys.positionReportInterval]
                    ?: defaults.positionReportIntervalMillis,
            )
        }

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.settingsDataStore.edit { preferences ->
            val current = preferences.toAppSettings()
            val next = transform(current)
            preferences.writeAll(next)
        }
    }

    override suspend fun replace(settings: AppSettings) {
        context.settingsDataStore.edit { preferences -> preferences.writeAll(settings) }
    }

    override suspend fun setBackgroundTrackingEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.backgroundTracking] = enabled }
    }

    override suspend fun setNotificationsEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.notifications] = enabled }
    }

    override suspend fun setSimulationModeEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.simulationMode] = enabled }
    }

    private fun Preferences.toAppSettings(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            updateIntervalMillis = this[Keys.updateIntervalMillis] ?: defaults.updateIntervalMillis,
            minUpdateDistanceMeters = this[Keys.minUpdateDistanceMeters]
                ?: defaults.minUpdateDistanceMeters,
            accuracyPriority = LocationAccuracyPriority.fromStorage(this[Keys.accuracyPriority]),
            backgroundTrackingEnabled = this[Keys.backgroundTracking] ?: defaults.backgroundTrackingEnabled,
            notificationsEnabled = this[Keys.notifications] ?: defaults.notificationsEnabled,
            simulationModeEnabled = this[Keys.simulationMode] ?: defaults.simulationModeEnabled,
            entryToleranceMeters = this[Keys.entryTolerance]?.toDouble() ?: defaults.entryToleranceMeters,
            exitToleranceMeters = this[Keys.exitTolerance]?.toDouble() ?: defaults.exitToleranceMeters,
            maxUsableAccuracyMeters = this[Keys.maxUsableAccuracy]?.toDouble()
                ?: defaults.maxUsableAccuracyMeters,
            retainedLocationRecords = this[Keys.retainedRecords] ?: defaults.retainedLocationRecords,
            positionReportingEnabled = this[Keys.positionReporting] ?: defaults.positionReportingEnabled,
            positionReportIntervalMillis = this[Keys.positionReportInterval]
                ?: defaults.positionReportIntervalMillis,
        )
    }

    private fun androidx.datastore.preferences.core.MutablePreferences.writeAll(settings: AppSettings) {
        this[Keys.updateIntervalMillis] = settings.updateIntervalMillis
        this[Keys.minUpdateDistanceMeters] = settings.minUpdateDistanceMeters
        this[Keys.accuracyPriority] = settings.accuracyPriority.storageKey
        this[Keys.backgroundTracking] = settings.backgroundTrackingEnabled
        this[Keys.notifications] = settings.notificationsEnabled
        this[Keys.simulationMode] = settings.simulationModeEnabled
        this[Keys.entryTolerance] = settings.entryToleranceMeters.toFloat()
        this[Keys.exitTolerance] = settings.exitToleranceMeters.toFloat()
        this[Keys.maxUsableAccuracy] = settings.maxUsableAccuracyMeters.toFloat()
        this[Keys.retainedRecords] = settings.retainedLocationRecords
        this[Keys.positionReporting] = settings.positionReportingEnabled
        this[Keys.positionReportInterval] = settings.positionReportIntervalMillis
    }
}
