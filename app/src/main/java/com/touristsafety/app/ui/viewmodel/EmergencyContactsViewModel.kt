package com.touristsafety.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.touristsafety.app.data.repository.EmergencyContactRepository
import com.touristsafety.app.data.repository.OutboxRepository
import com.touristsafety.app.domain.model.EmergencyContact
import com.touristsafety.app.domain.model.OutboxEvent
import com.touristsafety.app.domain.model.OutboxKind
import com.touristsafety.app.domain.model.OutboxOrigin
import com.touristsafety.app.outbox.OutboxPayloads
import com.touristsafety.app.outbox.OutboxSyncer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The tourist's emergency contacts.
 *
 * Editing writes to the device immediately and re-uploads the *whole* list through the durable
 * outbox, because the authority's endpoint is a whole-list replace. Doing it that way means a contact
 * added with no signal is not lost and cannot half-arrive.
 */
class EmergencyContactsViewModel(
    private val repository: EmergencyContactRepository,
    private val outboxRepository: OutboxRepository,
    private val syncer: OutboxSyncer,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    val contacts: StateFlow<List<EmergencyContact>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    val pendingCount: StateFlow<Int> = outboxRepository.observePendingCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), 0)

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun addContact(name: String, phone: String, relationship: String) {
        viewModelScope.launch {
            val cleanName = name.trim()
            val cleanPhone = phone.trim()
            if (cleanName.isEmpty() || cleanPhone.isEmpty()) {
                _message.value = "A contact needs both a name and a phone number."
                return@launch
            }
            repository.add(
                EmergencyContact(
                    name = cleanName,
                    phone = cleanPhone,
                    relationship = relationship.trim().ifEmpty { null },
                ),
                atMillis = now(),
            )
            _message.value = "Added $cleanName. Tap \"Save & upload\" to send the list."
        }
    }

    fun deleteContact(id: Long) {
        viewModelScope.launch {
            val removed = repository.delete(id)
            _message.value = if (removed) {
                "Contact removed. Tap \"Save & upload\" to send the shortened list."
            } else {
                "That contact was already gone."
            }
        }
    }

    /**
     * Queues the current list and immediately tries to deliver it.
     *
     * The enqueue happens first and the flush second, in that order, so a failure to reach the
     * authority leaves a durable row rather than a lost edit.
     */
    fun saveAndUpload() {
        viewModelScope.launch {
            val current = repository.all()
            if (current.isEmpty()) {
                _message.value = "Add at least one contact before uploading."
                return@launch
            }
            _busy.value = true
            val at = now()
            val queued = outboxRepository.enqueue(
                OutboxEvent(
                    dedupeKey = OutboxPayloads.contactsDedupeKey(at),
                    kind = OutboxKind.CONTACTS,
                    payload = OutboxPayloads.contacts(current, at),
                    createdAtMillis = at,
                    origin = OutboxOrigin.GPS,
                ),
            )
            val report = syncer.flush()
            _busy.value = false
            _message.value = buildString {
                append(
                    if (queued) {
                        "Contact list queued (${current.size} contact(s))."
                    } else {
                        "This revision was already queued."
                    },
                )
                append(" ")
                append("${report.delivered} delivered, ${report.retried} still waiting for the authority.")
            }
        }
    }

    /** Uploads only, for when the list is already saved and the network has come back. */
    fun uploadNow() {
        viewModelScope.launch {
            _busy.value = true
            val report = syncer.flush()
            _busy.value = false
            _message.value = "Queue drained: ${report.delivered} delivered, " +
                "${report.retried} still queued, ${report.rejected} rejected."
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
