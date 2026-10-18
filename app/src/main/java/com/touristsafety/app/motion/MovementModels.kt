package com.touristsafety.app.motion

/** Window geometry, shared by the sensor source and the UI so both describe the same thing. */
object MotionWindowDefaults {
    /** Samples per window — the length the model was trained on. */
    const val WINDOW_SAMPLES: Int = 128

    /** Sampling rate the model expects. */
    const val SAMPLE_RATE_HZ: Int = 50

    /** How far the window advances between evaluations: a 0.64 s step over a 2.56 s window. */
    const val HOP_SAMPLES: Int = 32

    /** Wall-clock length of one window, for display. */
    const val WINDOW_MILLIS: Long = WINDOW_SAMPLES * 1000L / SAMPLE_RATE_HZ
}

/**
 * The six activities the on-device model can report.
 *
 * These are the dataset's own labels — the app does not invent a "falling" class, because the model
 * was never trained on one.
 */
enum class ActivityKind(val modelLabel: String, val displayName: String) {
    WALKING("WALKING", "Walking"),
    WALKING_UPSTAIRS("WALKING_UPSTAIRS", "Walking upstairs"),
    WALKING_DOWNSTAIRS("WALKING_DOWNSTAIRS", "Walking downstairs"),
    SITTING("SITTING", "Sitting"),
    STANDING("STANDING", "Standing"),
    LAYING("LAYING", "Lying down"),
    ;

    /** Walking of any kind — the state in which a fall is actually possible. */
    val isAmbulating: Boolean
        get() = this == WALKING || this == WALKING_UPSTAIRS || this == WALKING_DOWNSTAIRS

    /** Lying down for a long time is worth noticing, but is not by itself a problem. */
    val isStationary: Boolean
        get() = this == SITTING || this == STANDING || this == LAYING

    companion object {
        fun fromModelLabel(label: String): ActivityKind? =
            entries.firstOrNull { it.modelLabel == label }
    }
}

/** Something the movement monitor cannot do, reported instead of guessed at. */
enum class MovementProblem(val displayName: String) {
    SENSORS_UNAVAILABLE("This device does not report both an accelerometer and a gyroscope, so movement cannot be analysed."),
    MODEL_UNAVAILABLE("The movement model could not be loaded from this build."),
    SENSOR_ERROR("The motion sensor reported an error."),
}

/**
 * The movement picture the UI draws, assembled from one window of sensor data at a time.
 *
 * [fallProbability] is a **model score for the impact signature the detector was trained on**
 * (synthesised impacts injected into real recordings — see `docs/model_card_movement.md`). It is not
 * a diagnosis, and the app never presents it as one. A fall is only ever *suspected*, and only when
 * the physics gate and the model agree.
 */
data class MovementState(
    val isMonitoring: Boolean = false,
    val modelAvailable: Boolean = false,
    val activity: ActivityKind? = null,
    val activityConfidence: Float = 0f,
    /** Model class distribution, in [DecisionForest.classes] order, for the explain-it-to-the-examiner view. */
    val activityProbabilities: Map<ActivityKind, Float> = emptyMap(),
    val fallProbability: Float = 0f,
    /** Largest acceleration magnitude in the window, in g. 1 g is the phone sitting still. */
    val peakImpactG: Float = 0f,
    /** Standard deviation of the acceleration magnitude: how much the device is moving. */
    val accelerationStdG: Float = 0f,
    /** Stillness *after* the peak, the physical half of the fall test. `null` when unmeasurable. */
    val stillnessAfterImpactG: Float? = null,
    val fallSuspected: Boolean = false,
    /**
     * True only for the window that actually raised an alert — i.e. qualified *and* outside the
     * cooldown. Overlapping windows mean [fallSuspected] stays true for several windows in a row,
     * and this flag is what keeps that from becoming several notifications.
     */
    val fallAlertRaised: Boolean = false,
    val fallEventsThisSession: Int = 0,
    val lastFallAtMillis: Long? = null,
    val windowsEvaluated: Int = 0,
    val updatedAtMillis: Long = 0L,
    val windowMillis: Long = MotionWindowDefaults.WINDOW_MILLIS,
    val problem: MovementProblem? = null,
    val lastErrorMessage: String? = null,
) {
    /** A short, plain-language summary for the dashboard card. */
    val summary: String
        get() = when {
            !modelAvailable -> "Movement analysis unavailable"
            !isMonitoring -> "Movement watch is off"
            activity == null -> "Waiting for the first movement window…"
            else -> activity.displayName
        }
}

/**
 * The fixed, published thresholds behind [MovementAnalyzer]. Every number here is a decision a
 * reader can argue with, which is the point — nothing is hidden inside a model.
 */
object FallPolicy {
    /**
     * A hard fall lands roughly 2–5 g at the waist (published free-fall/impact studies). Below this
     * the event is a stumble, a dropped phone or a bang against a rail, not a fall.
     */
    const val MIN_IMPACT_G: Float = 2.0f

    /** The model score above which the impact signature looks like the one it was trained on. */
    const val MIN_MODEL_SCORE: Float = 0.55f

    /** After a real fall the body stops: magnitude variance collapses. */
    const val MAX_STILLNESS_STD_G: Float = 0.12f

    /**
     * Windows overlap, so one fall is seen by several consecutive windows. This cooldown makes sure
     * the tourist sees one alert, not five.
     */
    const val COOLDOWN_MILLIS: Long = 15_000L

    /**
     * If the peak lands in the last quarter of the window there is not enough signal left to judge
     * stillness, so the stillness gate is skipped rather than failing the fall.
     */
    const val MIN_TAIL_FRACTION: Float = 0.25f
}
