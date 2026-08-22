package com.js8call.example.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One piece of who-hears-whom evidence mined from a decoded frame. Rows are
 * append-only and pruned by age: the network map and the path recommender
 * aggregate at query time, and keeping the raw observations is what lets them
 * weight by recency instead of trusting a link heard once, hours ago.
 *
 * Links are directed: [reporter] heard [heard], not the other way around.
 */
@Entity(
    tableName = "link_observations",
    indices = [Index("reporter"), Index("heard"), Index("observedAt")]
)
data class LinkObservationEntity(

    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** The station that heard. */
    val reporter: String,

    /** The station it heard. */
    val heard: String,

    /** Reported or measured SNR in dB. Null when the evidence has no number. */
    val snr: Int?,

    /** A [com.js8call.example.util.LinkEvidence.Source] name. */
    val source: String,

    /**
     * Dial frequency in Hz when the frame was decoded, so links stay scoped
     * to the band they were observed on. Null when no rig control is active
     * and the dial is unknown.
     */
    val dialFreqHz: Long?,

    val observedAt: Long
)
