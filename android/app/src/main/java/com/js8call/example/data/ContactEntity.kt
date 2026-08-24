package com.js8call.example.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A station heard on the air, updated on every decode from it.
 * Name, star and comment are user data and survive heard updates.
 */
@Entity(
    tableName = "contacts",
    indices = [Index(value = ["lastHeard"])]
)
data class ContactEntity(
    @PrimaryKey
    val callsign: String,

    /**
     * What the operator calls this station. Shown in place of the callsign
     * wherever a station is named, with the callsign kept underneath.
     */
    val name: String? = null,

    /** UTC timestamp in milliseconds of the newest decode from this station */
    val lastHeard: Long,

    /** SNR of the newest decode */
    val snr: Int? = null,

    /** Audio frequency offset in Hz of the newest decode */
    val offset: Float? = null,

    /** Maidenhead grid, kept from the newest message that carried one */
    val grid: String? = null,

    /** Station info text, kept from the newest INFO reply heard */
    val info: String? = null,

    /** True once the station has sent a message directed at our callsign */
    val heardUs: Boolean = false,

    /** User favorite flag */
    val starred: Boolean = false,

    /** User note */
    val comment: String? = null
)
