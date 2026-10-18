package com.touristsafety.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.touristsafety.app.authority.AuthoritySessionStore
import com.touristsafety.app.data.repository.OutboxRepository
import com.touristsafety.app.watch.WatchController
import com.touristsafety.app.watch.WatchUiState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The simulated smartwatch screen.
 *
 * A very thin wrapper: the behaviour lives in [WatchController] (unit-tested) and the queue lives in
 * the outbox. The ViewModel only exposes read-only state plus the three user actions.
 */
class WatchViewModel(
    private val watchController: WatchController,
    outboxRepository: OutboxRepository,
    sessions: AuthoritySessionStore,
) : ViewModel() {

    val state: StateFlow<WatchUiState> = watchController.state

    /** How many events are still waiting to reach an authority — the offline queue, made visible. */
    val queuedCount: StateFlow<Int> = outboxRepository.observePendingCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), 0)

    /**
     * Where the queue is actually going, read from the persisted session rather than assumed.
     *
     * This is the screen where "the backend is not added" is complained about, so it has to state the
     * real answer: no address, an address but no sign-in, or signed in as whom.
     */
    val authorityAddress: StateFlow<String> = sessions.session
        .map { it.baseUrl }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), "")

    val signedInAs: StateFlow<String?> = sessions.session
        .map { session ->
            if (session.isSignedIn(System.currentTimeMillis())) session.subjectId else null
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), null)

    fun triggerSos() = watchController.triggerSos()

    fun acknowledgeSos() = watchController.acknowledgeSos()

    fun retrySync() = watchController.retrySync()

    private companion object {
        const val STOP_TIMEOUT = 5_000L
    }
}
