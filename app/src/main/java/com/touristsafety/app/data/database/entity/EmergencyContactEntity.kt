package com.touristsafety.app.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The tourist's emergency contacts, stored on the device.
 *
 * These are kept locally *and* uploaded, rather than only uploaded, so the list survives being edited
 * in a valley with no signal: the edit is written here immediately and the upload is queued like any
 * other outbound event.
 *
 * ## Why there is no index on `priority`
 *
 * The table holds at most a handful of rows (the backend refuses more than 20), and the read is
 * `ORDER BY priority, name`. Adding an index would mean a second statement in the migration for no
 * measurable benefit, and every extra statement in a migration is another thing that can disagree
 * with the exported schema.
 */
@Entity(tableName = "emergency_contacts")
data class EmergencyContactEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "phone")
    val phone: String,

    @ColumnInfo(name = "relationship")
    val relationship: String? = null,

    /** The order the authority should work down, not a severity. */
    @ColumnInfo(name = "priority")
    val priority: Int = 0,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = 0L,
)
