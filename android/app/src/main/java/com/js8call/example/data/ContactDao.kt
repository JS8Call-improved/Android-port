package com.js8call.example.data

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Data Access Object for heard-station contacts.
 */
@Dao
interface ContactDao {

    @Query("SELECT * FROM contacts ORDER BY starred DESC, lastHeard DESC")
    fun getContacts(): LiveData<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE callsign = :callsign")
    suspend fun getContact(callsign: String): ContactEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(contact: ContactEntity): Long

    /** Refresh the heard fields, keeping star, comment, and heardUs. */
    @Query("""
        UPDATE contacts
        SET lastHeard = :timestamp,
            snr = :snr,
            offset = :offset,
            grid = COALESCE(:grid, grid),
            info = COALESCE(:info, info)
        WHERE callsign = :callsign
    """)
    suspend fun updateHeard(
        callsign: String,
        timestamp: Long,
        snr: Int?,
        offset: Float?,
        grid: String?,
        info: String?
    )

    @Query("UPDATE contacts SET heardUs = 1 WHERE callsign = :callsign")
    suspend fun markHeardUs(callsign: String)

    @Query("UPDATE contacts SET starred = :starred WHERE callsign = :callsign")
    suspend fun setStarred(callsign: String, starred: Boolean)

    @Query("UPDATE contacts SET comment = :comment WHERE callsign = :callsign")
    suspend fun setComment(callsign: String, comment: String?)

    @Query("DELETE FROM contacts WHERE callsign = :callsign")
    suspend fun deleteContact(callsign: String)
}
