package com.touristsafety.app.data.repository

import androidx.room.withTransaction
import com.touristsafety.app.data.database.AppDatabase
import com.touristsafety.app.data.database.dao.EmergencyContactDao
import com.touristsafety.app.data.mapper.toDomain
import com.touristsafety.app.data.mapper.toEntity
import com.touristsafety.app.domain.model.EmergencyContact
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The device's own copy of the emergency-contact list.
 *
 * The authority stores its own copy, replaced wholesale on every upload. Keeping a local copy is what
 * lets the list be *edited* offline; the upload is just another queued event.
 */
class EmergencyContactRepository(
    private val database: AppDatabase,
    private val dao: EmergencyContactDao,
) {

    fun observeAll(): Flow<List<EmergencyContact>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    fun observeCount(): Flow<Int> = dao.observeCount()

    suspend fun all(): List<EmergencyContact> = dao.all().map { it.toDomain() }

    suspend fun add(contact: EmergencyContact, atMillis: Long): Long =
        dao.insert(contact.copy(id = 0L).toEntity(atMillis))

    /**
     * Replaces the whole list, in one transaction so a crash cannot leave the tourist with half a
     * contact list. Position in [contacts] *is* the priority, which matches the order the authority
     * works down.
     */
    suspend fun replaceAll(contacts: List<EmergencyContact>, atMillis: Long): Int {
        val rows = contacts.mapIndexed { index, contact ->
            contact.copy(id = 0L, priority = index).toEntity(atMillis)
        }
        database.withTransaction {
            dao.deleteAll()
            rows.forEach { dao.insert(it) }
        }
        return rows.size
    }

    suspend fun delete(id: Long): Boolean = dao.deleteById(id) > 0

    suspend fun clear(): Int = dao.deleteAll()
}
