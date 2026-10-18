package com.touristsafety.app.motion

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Runs the movement watch: sensor windows in, [MovementState] out.
 *
 * Android-free apart from the injected [MotionSource] and the two forests, so the whole thing —
 * including the fall cooldown — is exercised by a JVM test with a scripted source, exactly like
 * [com.touristsafety.app.tracking.TrackingController]. The failure policy mirrors that controller
 * too: a bad window is reported and skipped, it never tears the watch down.
 */
class MovementController(
    private val motionSource: MotionSource,
    private val activityForest: DecisionForest?,
    private val fallForest: DecisionForest?,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * The trained future-risk model, when this build ships one.
     *
     * Optional on purpose: the activity/impact watch must keep working on a build without the
     * artefact, and the existing tests construct this controller without it. When it is `null` the
     * risk state reports [FutureRiskBand.UNAVAILABLE] rather than inventing a band.
     */
    futureRiskModel: FutureRiskModel? = null,
) {

    private val analyzer = MovementAnalyzer(activityForest, fallForest)

    private val futureRiskAnalyzer = FutureRiskAnalyzer(futureRiskModel)

    private val _state = MutableStateFlow(
        MovementState(
            modelAvailable = analyzer.modelAvailable,
            problem = if (analyzer.modelAvailable) null else MovementProblem.MODEL_UNAVAILABLE,
        ),
    )
    val state: StateFlow<MovementState> = _state.asStateFlow()

    /**
     * The future-event prediction, driven by the same windows as the fall watch.
     *
     * It is a **separate flow from [state]** on purpose: the risk card must be able to show "model
     * unavailable" or "too little data" while the activity/impact watch is perfectly healthy, and the
     * fall banner must never be fed by the risk model (a risk band never raises an SOS).
     */
    private val _futureRisk = MutableStateFlow(FutureRiskState.waiting(false))
    val futureRisk: StateFlow<FutureRiskState> = _futureRisk.asStateFlow()

    /** True when the trained future-risk artefact loaded, for the diagnostics panel. */
    val futureRiskModelAvailable: Boolean get() = futureRiskAnalyzer.modelAvailable

    /** Fires once per confirmed impact event, for the notification/banner path. */
    private val _fallAlerts = MutableSharedFlow<MovementState>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val fallAlerts: SharedFlow<MovementState> = _fallAlerts.asSharedFlow()

    private var windowJob: Job? = null

    /** Starts the watch. Safe to call repeatedly — the second call is a no-op, not a second listener. */
    fun start() {
        if (windowJob?.isActive == true) return

        if (!analyzer.modelAvailable) {
            _state.update { it.copy(problem = MovementProblem.MODEL_UNAVAILABLE, isMonitoring = false) }
            return
        }
        if (!motionSource.isAvailable) {
            _state.update { it.copy(problem = MovementProblem.SENSORS_UNAVAILABLE, isMonitoring = false) }
            return
        }

        _state.update { it.copy(isMonitoring = true, problem = null, lastErrorMessage = null) }

        // Report a missing risk model once, at start, instead of on every window.
        if (!futureRiskAnalyzer.modelAvailable) {
            _futureRisk.value = FutureRiskState.modelUnavailable(
                FutureRiskModels.lastFailure()
                    ?: "This build has no trained future-risk model asset",
                isMonitoring = true,
            )
        } else {
            _futureRisk.update { it.copy(isMonitoring = true) }
        }

        windowJob = scope.launch {
            try {
                motionSource.windows().collect { window -> handle(window) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                _state.update {
                    it.copy(
                        problem = MovementProblem.SENSOR_ERROR,
                        lastErrorMessage = error.localizedMessage ?: error::class.java.simpleName,
                    )
                }
            }
        }
    }

    /** Stops the watch and releases the sensors. */
    fun stop() {
        windowJob?.cancel()
        windowJob = null
        _state.update { it.copy(isMonitoring = false) }
        // The last prediction is kept, with its timestamp: the card can then say "last predicted
        // 40 s ago" instead of silently presenting a frozen band as current.
        _futureRisk.update { futureRiskAnalyzer.paused(it) }
    }

    fun isMonitoring(): Boolean = windowJob?.isActive == true

    /**
     * Analyses one window. Public so a test can drive the pipeline without any coroutine timing.
     *
     * A garbage reading from a misbehaving driver must not poison the state, so an implausible
     * window is counted and reported rather than fed to the model.
     */
    fun onWindow(window: MotionWindow) {
        val now = clock()

        if (MovementAnalyzer.hasImplausibleSample(window)) {
            _state.update {
                it.copy(
                    windowsEvaluated = it.windowsEvaluated + 1,
                    problem = MovementProblem.SENSOR_ERROR,
                    lastErrorMessage = "The accelerometer reported an implausible magnitude; window skipped.",
                )
            }
            _futureRisk.value = unavailableFor(window, now)
            return
        }

        val next = analyzer.analyze(window, _state.value, now)
        _state.value = next
        if (next.fallAlertRaised) {
            _fallAlerts.tryEmit(next)
        }

        // Same window, same subscription, a different model and a different question ("is an impact
        // coming?" rather than "did one just happen?"). Keeping it here means there is exactly one
        // sensor listener on the device.
        _futureRisk.value = futureRiskAnalyzer.analyze(
            window = window,
            nowMillis = now,
            isMonitoring = true,
            failureReason = FutureRiskModels.lastFailure(),
        )
    }

    /** The "we cannot answer" state for a window that failed the plausibility gate. */
    private fun unavailableFor(window: MotionWindow, nowMillis: Long): FutureRiskState {
        val reason = "Unusable accelerometer reading; the prediction was skipped for this window"
        return FutureRiskState(
            isMonitoring = true,
            modelAvailable = futureRiskAnalyzer.modelAvailable,
            band = FutureRiskBand.UNAVAILABLE,
            evaluatedAtMillis = nowMillis,
            dataQuality = FutureRiskDataQuality(
                samples = window.size,
                requiredSamples = FutureRiskFeatures.MIN_SAMPLES,
                ok = false,
                reason = reason,
            ),
            unavailableReason = reason,
        )
    }

    private fun handle(window: MotionWindow) {
        try {
            onWindow(window)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            _state.update {
                it.copy(
                    problem = MovementProblem.SENSOR_ERROR,
                    lastErrorMessage = error.localizedMessage ?: error::class.java.simpleName,
                )
            }
        }
    }

    /** Clears the session counters the user can see (fall count, last event). */
    fun resetSession() {
        _state.update {
            it.copy(
                fallEventsThisSession = 0,
                lastFallAtMillis = null,
                fallSuspected = false,
                fallAlertRaised = false,
            )
        }
    }

    /** Reports a problem discovered outside the pipeline, e.g. a sensor that disappeared. */
    fun reportProblem(problem: MovementProblem?, detail: String? = null) {
        _state.update { it.copy(problem = problem, lastErrorMessage = detail) }
    }
}
