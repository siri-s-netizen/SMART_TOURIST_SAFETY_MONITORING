package com.touristsafety.app

import android.content.Context
import com.touristsafety.app.authority.AuthorityClient
import com.touristsafety.app.authority.AuthoritySessionStore
import com.touristsafety.app.authority.AuthoritySyncTransport
import com.touristsafety.app.authority.AuthorityZoneSync
import com.touristsafety.app.authority.HttpUrlConnectionAuthorityHttp
import com.touristsafety.app.authority.SelectingSyncTransport
import com.touristsafety.app.authority.createAuthoritySessionStore
import com.touristsafety.app.data.database.AppDatabase
import com.touristsafety.app.data.repository.AlertRepository
import com.touristsafety.app.data.repository.EmergencyContactRepository
import com.touristsafety.app.data.repository.GeoFenceRepository
import com.touristsafety.app.data.repository.LocationRepository
import com.touristsafety.app.data.repository.OutboxRepository
import com.touristsafety.app.domain.model.OutboxEvent
import com.touristsafety.app.domain.model.OutboxKind
import com.touristsafety.app.domain.model.OutboxOrigin
import com.touristsafety.app.location.FusedLocationSource
import com.touristsafety.app.location.LocationSource
import com.touristsafety.app.motion.FutureRiskModels
import com.touristsafety.app.motion.FutureRiskReporter
import com.touristsafety.app.motion.MotionSource
import com.touristsafety.app.motion.MovementController
import com.touristsafety.app.motion.MovementModels
import com.touristsafety.app.motion.SensorMotionSource
import com.touristsafety.app.notifications.AndroidAlertNotifier
import com.touristsafety.app.notifications.AlertNotifier
import com.touristsafety.app.notifications.NotificationHelper
import com.touristsafety.app.outbox.OutboxPayloads
import com.touristsafety.app.outbox.OutboxSyncer
import com.touristsafety.app.outbox.SyncReport
import com.touristsafety.app.outbox.SyncTransport
import com.touristsafety.app.settings.AppSettings
import com.touristsafety.app.settings.DataStoreSettingsRepository
import com.touristsafety.app.settings.SettingsRepository
import com.touristsafety.app.simulation.SimulationController
import com.touristsafety.app.tracking.AndroidBackgroundTrackingControl
import com.touristsafety.app.tracking.BackgroundTrackingControl
import com.touristsafety.app.tracking.PositionReporter
import com.touristsafety.app.tracking.TrackingController
import com.touristsafety.app.watch.AndroidWatchHaptics
import com.touristsafety.app.watch.WatchController
import com.touristsafety.app.watch.WatchHaptics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Hand-rolled dependency container.
 *
 * There is deliberately no DI framework here: the graph is small, fully known at compile time, and
 * keeping it explicit means the whole app can be reasoned about (and the tracking pipeline tested)
 * without generated code. Everything is `lazy` so nothing heavier than a `Context` reference is
 * created until it is actually needed.
 *
 * The container lives on the [android.app.Application], which is what lets the foreground service
 * reach the *same* [TrackingController] instance the UI is observing — a second controller would
 * mean a second GPS subscription, which is exactly the bug this design prevents.
 */
class AppContainer(private val appContext: Context) {

    /**
     * Application-lifetime scope for the long-running pipelines. `SupervisorJob` so one failing
     * child (say, a notification that throws) cannot cancel the GPS collection alongside it.
     */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: AppDatabase by lazy { AppDatabase.get(appContext) }

    val geoFenceRepository: GeoFenceRepository by lazy { GeoFenceRepository(database.geoFenceDao()) }

    val locationRepository: LocationRepository by lazy { LocationRepository(database.locationRecordDao()) }

    val alertRepository: AlertRepository by lazy { AlertRepository(database.geoFenceAlertDao()) }

    val settingsRepository: SettingsRepository by lazy { DataStoreSettingsRepository(appContext) }

    val notificationHelper: NotificationHelper by lazy { NotificationHelper(appContext) }

    val alertNotifier: AlertNotifier by lazy { AndroidAlertNotifier(notificationHelper) }

    val locationSource: LocationSource by lazy { FusedLocationSource(appContext) }

    val backgroundTrackingControl: BackgroundTrackingControl by lazy {
        AndroidBackgroundTrackingControl(appContext)
    }

    val trackingController: TrackingController by lazy {
        TrackingController(
            locationProvider = locationSource,
            geoFenceRepository = geoFenceRepository,
            locationRepository = locationRepository,
            alertRepository = alertRepository,
            alertNotifier = alertNotifier,
            settingsRepository = settingsRepository,
            scope = appScope,
        )
    }

    val simulationController: SimulationController by lazy {
        SimulationController(
            geoFenceRepository = geoFenceRepository,
            locationRepository = locationRepository,
            alertRepository = alertRepository,
            alertNotifier = alertNotifier,
            settingsRepository = settingsRepository,
            scope = appScope,
        )
    }

    // ---------------------------------------------------------------- movement / fall watch

    /**
     * Real accelerometer + gyroscope sampling. No runtime permission is involved, which is why the
     * movement watch never shows a dialog.
     */
    val motionSource: MotionSource by lazy { SensorMotionSource(appContext) }

    /**
     * Runs the on-device activity/fall models over the sensor stream.
     *
     * Both forests are loaded from `assets/` and parsed once. If either file is missing the controller
     * reports [com.touristsafety.app.motion.MovementProblem.MODEL_UNAVAILABLE] instead of throwing,
     * so a build without the trained model still runs and still says so.
     *
     * The **future-risk** forest rides the same controller and therefore the same sensor
     * subscription: there is exactly one accelerometer listener on the device, and the risk model
     * reads the last second of the very window the fall model just analysed.
     */
    private val movementControllerLazy: Lazy<MovementController> = lazy {
        MovementController(
            motionSource = motionSource,
            activityForest = MovementModels.activity(appContext.assets),
            fallForest = MovementModels.fall(appContext.assets),
            scope = appScope,
            futureRiskModel = FutureRiskModels.load(appContext.assets),
        )
    }

    val movementController: MovementController by movementControllerLazy

    /**
     * Stops the movement watch as part of signing the tourist out.
     *
     * Deliberately **not** a plain [movementController] reference from the dashboard: constructing the
     * controller parses the trained forests (about 2.3 MB of JSON), and the Home screen must not pay
     * that cost merely to be able to sign out. Touching it only when it has already been created is
     * also the only case in which there is anything to stop — a watch that was never started holds no
     * sensor subscription and no listener to release.
     */
    fun stopMovementWatchIfStarted() {
        if (movementControllerLazy.isInitialized()) {
            movementController.stop()
        }
    }

    // ---------------------------------------------------------------- offline outbox + watch

    /**
     * The offline queue, bound to the currently signed-in tourist.
     *
     * The owner is resolved from the session store on *every* enqueue and every delivery, so an
     * account switch takes effect immediately with nothing to rebuild: an event queued by Tourist A is
     * stamped with A's id and is never uploaded while B holds the token.
     *
     * That id is the authority's own stable subject id — the value it writes into `sessions.subject_id`
     * and the one it files an uploaded event against (`backend/tsm/api.py`). It is deliberately not the
     * display name and not the base URL. It is absent whenever nobody is signed in, which is what
     * marks a new event *unassigned* instead of misattributed.
     */
    val outboxRepository: OutboxRepository by lazy {
        OutboxRepository(
            dao = database.outboxEventDao(),
            currentOwner = { authoritySessionStore.current().subjectId },
        )
    }

    val emergencyContactRepository: EmergencyContactRepository by lazy {
        EmergencyContactRepository(database, database.emergencyContactDao())
    }

    // ---------------------------------------------------------------- authority backend

    /**
     * Where the authority's address and this phone's session live. A separate DataStore from the
     * tuning preferences so signing out can never disturb the tracking settings.
     */
    val authoritySessionStore: AuthoritySessionStore by lazy {
        createAuthoritySessionStore(appContext)
    }

    /**
     * The HTTP client. The base URL is read from the session store on *every* call, so entering an
     * address in the sign-in screen takes effect on the next delivery with no restart.
     */
    val authorityClient: AuthorityClient by lazy {
        AuthorityClient(
            baseUrl = { authoritySessionStore.current().baseUrl },
            http = HttpUrlConnectionAuthorityHttp(),
        )
    }

    private val authoritySyncTransport: SyncTransport by lazy {
        AuthoritySyncTransport(sessions = authoritySessionStore, client = authorityClient)
    }

    /**
     * The transport the outbox actually uses.
     *
     * It is a selector rather than a plain [AuthoritySyncTransport] so the honest
     * [NoBackendTransport] behaviour is preserved exactly when no address is configured: every event
     * stays queued and says why. The moment an operator enters an address, that same flush starts
     * delivering — no rebuild, no restart.
     */
    val syncTransport: SyncTransport by lazy {
        SelectingSyncTransport(
            configured = authoritySyncTransport,
            isConfigured = { authoritySessionStore.current().isConfigured },
        )
    }

    val outboxSyncer: OutboxSyncer by lazy { OutboxSyncer(outboxRepository, syncTransport) }

    /**
     * Queues routine position reports for the authority, subject to the user's reporting interval.
     *
     * It reads the interval policy from the live settings on every fix, and it only queues at all
     * once an authority address is configured — with nowhere to send them, a queue of position
     * reports would grow for no reason. Real GPS only: the reporter rejects simulated readings.
     */
    val positionReporter: PositionReporter by lazy {
        PositionReporter(
            outboxRepository = outboxRepository,
            policy = { latestSettings.positionReportPolicy },
            isConfigured = { authoritySessionStore.current().isConfigured },
        )
    }

    /**
     * Shares elevated **trained-model** predictions with the authority.
     *
     * Same outbox, same authentication and the same offline queue as an SOS or a position report, so
     * a prediction made in a valley is delivered when the phone next has signal. What travels is a
     * summary — band, calibrated probability, model version, contributing factors — never a
     * continuous accelerometer record, and never anything that could be read as an incident.
     */
    val futureRiskReporter: FutureRiskReporter by lazy {
        FutureRiskReporter(
            outboxRepository = outboxRepository,
            isConfigured = { authoritySessionStore.current().isConfigured },
        )
    }

    /**
     * Keeps this phone's copy of the authority's **published zones** current.
     *
     * The other half of shared zone management: an officer draws a boundary in the console, the
     * authority stores it, and this fetches it into the same `geo_fences` table the existing geofence
     * engine already reads. That is why the tracking pipeline needed no change — a published zone
     * arrives as an ordinary row, marked `AUTHORITY`, that the engine evaluates exactly like a local
     * one.
     *
     * It reads `GET /zones` with the *tourist* token and never calls a management route, and the write
     * it performs cannot touch a local zone: the repository's read and delete are both scoped to
     * `source = 'AUTHORITY'`.
     */
    val authorityZoneSync: AuthorityZoneSync by lazy {
        AuthorityZoneSync(
            sessions = authoritySessionStore,
            client = authorityClient,
            geoFences = geoFenceRepository,
        )
    }

    /** Drains the queue now; what the "Upload now" button and the tests call. */
    suspend fun flushOutbox(): SyncReport = outboxSyncer.flush()

    /**
     * A label for the session row, so an operator can see *which* phone signed in.
     *
     * The last six characters of the device id are enough to tell two handsets apart without
     * recording a full hardware identifier on the authority's disk.
     */
    val deviceLabel: String by lazy {
        val model = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim()
        val androidId = runCatching {
            android.provider.Settings.Secure.getString(
                appContext.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID,
            )
        }.getOrNull()
        val suffix = androidId?.takeLast(6)?.takeIf { it.length == 6 }
        if (suffix == null) model.ifBlank { "android" } else "$model ($suffix)"
    }

    val watchHaptics: WatchHaptics by lazy { AndroidWatchHaptics(appContext) }

    val watchController: WatchController by lazy {
        WatchController(
            scope = appScope,
            haptics = watchHaptics,
            outboxRepository = outboxRepository,
            syncer = outboxSyncer,
        )
    }

    /**
     * Latest settings, cached so [settingsSnapshot] can be called from the service's non-suspend
     * code path. `@Volatile` because it is written on [appScope] and read on the main thread.
     */
    @Volatile
    private var latestSettings: AppSettings = AppSettings()

    fun settingsSnapshot(): AppSettings = latestSettings

    /** Idempotent: safe to call from both the Application and, defensively, the service. */
    fun start() {
        notificationHelper.ensureChannels()
        trackingController.observeSettings()
        simulationController.observeSettings()
        appScope.launch {
            settingsRepository.settings.collect { latestSettings = it }
        }

        // Point a fresh install at the authority this build was configured for, once. An address the
        // user has already chosen is never overwritten, and a build with no default simply leaves the
        // field empty for them to fill in — either way nobody is left staring at a dead button
        // wondering why.
        appScope.launch {
            authoritySessionStore.seedBaseUrlIfUnset(BuildConfig.DEFAULT_AUTHORITY_URL)
        }

        // Drive the simulated watch from the *real* tracking snapshot (no second data source).
        appScope.launch {
            trackingController.snapshot.collect { snapshot ->
                watchController.onTrackingSnapshot(snapshot)
                // Queue a throttled position report for the authority, if one is due. The reporter
                // ignores simulated readings and is a no-op until an authority address is configured.
                snapshot.lastReading?.let { reading -> positionReporter.onReading(reading) }
            }
        }

        // Queue every zone crossing for later delivery, and mirror it on the watch face.
        appScope.launch {
            trackingController.instantAlerts.collect { alert ->
                outboxRepository.enqueue(
                    OutboxEvent(
                        dedupeKey = OutboxPayloads.zoneAlertDedupeKey(alert),
                        kind = OutboxKind.ZONE_ALERT,
                        payload = OutboxPayloads.zoneAlert(alert),
                        createdAtMillis = alert.timestampMillis,
                        origin = if (alert.source.isSimulated) {
                            OutboxOrigin.SIMULATION
                        } else {
                            OutboxOrigin.GPS
                        },
                    ),
                )
                watchController.onAlert(alert)
            }
        }

        // Retry loop. With no backend configured every event stays PENDING here; a production build
        // would additionally trigger a flush from a ConnectivityManager network callback.
        appScope.launch {
            while (isActive) {
                outboxSyncer.flush()
                delay(SYNC_RETRY_INTERVAL_MILLIS)
            }
        }

        // Keep the authority's published zones current: once on sign-in, then periodically while that
        // session lasts. Signed out, it does nothing at all and leaves the zones already downloaded in
        // place, because a tourist who signs out has not withdrawn from a restricted area.
        authorityZoneSync.start(appScope)
    }

    private companion object {
        const val SYNC_RETRY_INTERVAL_MILLIS: Long = 30_000L
    }
}
